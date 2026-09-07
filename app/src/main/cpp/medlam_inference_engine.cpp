#include "medlam_inference_engine.h"

#include <android/log.h>
#include <sched.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <dirent.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <fstream>
#include <sstream>

#define TAG "MedlamEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

namespace medlam {

// Helper: Add token to batch
static inline void batch_add_token(
    struct llama_batch& batch,
    llama_token id,
    llama_pos pos,
    const std::vector<llama_seq_id>& seq_ids,
    bool logits
) {
    batch.token[batch.n_tokens] = id;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = static_cast<int32_t>(seq_ids.size());
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits[batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

MedlamInferenceEngine& MedlamInferenceEngine::instance() {
    static MedlamInferenceEngine s_instance;
    return s_instance;
}

MedlamInferenceEngine::MedlamInferenceEngine() {
    LOGI("MedlamInferenceEngine instance created");
}

MedlamInferenceEngine::~MedlamInferenceEngine() {
    free();
}

bool MedlamInferenceEngine::is_initialized() const {
    return m_initialized.load();
}

bool MedlamInferenceEngine::is_generating() const {
    return m_is_generating.load();
}

void MedlamInferenceEngine::stop() {
    m_abort_requested.store(true);
    LOGI("Inference abort requested");
}

void MedlamInferenceEngine::setup_thread_affinity() {
    m_perf_cores.clear();

    // Scan sysfs to identify big/performance cores (highest max frequency)
    unsigned long max_freq_all = 0;
    std::vector<std::pair<int, unsigned long>> core_freqs;

    for (int cpu = 0; cpu < 16; ++cpu) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "r");
        if (f) {
            unsigned long freq = 0;
            if (fscanf(f, "%lu", &freq) == 1) {
                core_freqs.emplace_back(cpu, freq);
                if (freq > max_freq_all) {
                    max_freq_all = freq;
                }
            }
            fclose(f);
        }
    }

    // Cores within 15% of max frequency are considered Performance/Prime cores
    unsigned long threshold = (max_freq_all * 85) / 100;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int perf_count = 0;

    for (const auto& entry : core_freqs) {
        if (entry.second >= threshold) {
            m_perf_cores.push_back(entry.first);
            CPU_SET(entry.first, &cpuset);
            perf_count++;
        }
    }

    if (perf_count > 0 && m_config.enable_performance_affinity) {
        int rc = sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
        if (rc == 0) {
            LOGI("Thread affinity bound to %d performance cores (Cluster max freq: %lu kHz)", perf_count, max_freq_all);
        } else {
            LOGW("Failed to set thread affinity: %d", rc);
        }
    } else {
        LOGI("Hardware affinity: using default scheduler (%zu total cores detected)", core_freqs.size());
    }
}

void MedlamInferenceEngine::check_thermal_status() {
    // Check thermal zone temperatures to mitigate aggressive kernel throttling
    int max_temp_c = 0;
    for (int z = 0; z < 10; ++z) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/class/thermal/thermal_zone%d/temp", z);
        FILE* f = fopen(path, "r");
        if (f) {
            int t = 0;
            if (fscanf(f, "%d", &t) == 1) {
                int temp_c = (t > 1000) ? (t / 1000) : t;
                if (temp_c > max_temp_c) {
                    max_temp_c = temp_c;
                }
            }
            fclose(f);
        }
    }

    if (max_temp_c >= 70) {
        LOGW("Thermal threshold reached (%d°C). Inserting 3ms micro-cooldown.", max_temp_c);
        usleep(3000); // 3ms micro-pause to lower SoC junction temperature
    }
}

bool MedlamInferenceEngine::load_target_model(const EngineConfig& config) {
    LOGI("Loading Target Model (mmap=%d, gpu_layers=%d): %s", config.use_mmap, config.gpu_layers, config.model_path.c_str());

    // 1. Zero-Copy mmap & GGUF Model Parameters
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = config.gpu_layers;
    mparams.use_mmap = config.use_mmap;
    mparams.use_mlock = false; // Never lock all RAM on Android to avoid OOM killer
    mparams.check_tensors = false; // Fast instant zero-copy loading

    m_target_model = llama_model_load_from_file(config.model_path.c_str(), mparams);
    if (!m_target_model) {
        LOGE("Failed to load Target Model from: %s", config.model_path.c_str());
        return false;
    }

    m_target_vocab = llama_model_get_vocab(m_target_model);
    if (!m_target_vocab) {
        LOGE("Failed to obtain vocabulary for Target Model");
        return false;
    }

    // 2. Context Parameters & Memory Bounds
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(config.context_size);
    cparams.n_batch = static_cast<uint32_t>(config.batch_size);
    cparams.n_ubatch = static_cast<uint32_t>(config.ubatch_size);
    cparams.n_threads = config.thread_count;
    cparams.n_threads_batch = std::max(config.thread_count, 4);
    cparams.flash_attn = true;

    m_target_ctx = llama_init_from_model(m_target_model, cparams);
    if (!m_target_ctx) {
        LOGE("Failed to create context for Target Model");
        return false;
    }

    // 3. Sampler Chain
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    m_target_sampler = llama_sampler_chain_init(sparams);
    if (config.temperature > 0.0f) {
        llama_sampler_chain_add(m_target_sampler, llama_sampler_init_temp(config.temperature));
    }
    llama_sampler_chain_add(m_target_sampler, llama_sampler_init_greedy());

    char desc[256];
    llama_model_desc(m_target_model, desc, sizeof(desc));
    uint64_t params_count = llama_model_n_params(m_target_model);
    uint64_t model_size = llama_model_size(m_target_model);
    LOGI("Target Model loaded: %s | Params: %.2fB | Size: %.1f MB",
         desc, params_count / 1e9, model_size / (1024.0 * 1024.0));

    return true;
}

bool MedlamInferenceEngine::load_draft_model(const EngineConfig& config) {
    if (config.draft_model_path.empty()) {
        LOGI("No draft model configured. Speculative decoding disabled.");
        return true;
    }

    LOGI("Loading Speculative Draft Model (mmap=%d, mlock=%d): %s", config.draft_use_mmap, !config.draft_use_mmap, config.draft_model_path.c_str());

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // Draft model runs lightning-fast on CPU
    mparams.use_mmap = config.draft_use_mmap;
    mparams.use_mlock = !config.draft_use_mmap; // Locked in RAM for zero-latency lookups
    mparams.check_tensors = false;

    m_draft_model = llama_model_load_from_file(config.draft_model_path.c_str(), mparams);
    if (!m_draft_model) {
        LOGW("Failed to load Draft Model from %s. Falling back to single-model mode.", config.draft_model_path.c_str());
        return true; // Graceful degradation
    }

    m_draft_vocab = llama_model_get_vocab(m_draft_model);

    llama_context_params cparams = llama_context_default_params();
    // Bounded context for draft model to save memory footprint
    cparams.n_ctx = static_cast<uint32_t>(std::min(config.context_size, 2048));
    cparams.n_batch = static_cast<uint32_t>(config.batch_size);
    cparams.n_ubatch = static_cast<uint32_t>(config.ubatch_size);
    cparams.n_threads = config.thread_count;
    cparams.n_threads_batch = config.thread_count;
    cparams.flash_attn = true;

    m_draft_ctx = llama_init_from_model(m_draft_model, cparams);
    if (!m_draft_ctx) {
        LOGW("Failed to create context for Draft Model. Falling back.");
        llama_model_free(m_draft_model);
        m_draft_model = nullptr;
        return true;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    m_draft_sampler = llama_sampler_chain_init(sparams);
    if (config.temperature > 0.0f) {
        llama_sampler_chain_add(m_draft_sampler, llama_sampler_init_temp(config.temperature));
    }
    llama_sampler_chain_add(m_draft_sampler, llama_sampler_init_greedy());

    char desc[256];
    llama_model_desc(m_draft_model, desc, sizeof(desc));
    uint64_t params_count = llama_model_n_params(m_draft_model);
    LOGI("Speculative Draft Model active: %s (%.2fB params)", desc, params_count / 1e9);

    return true;
}

bool MedlamInferenceEngine::init(const EngineConfig& config) {
    std::lock_guard<std::mutex> lock(m_engine_mutex);

    if (m_initialized.load()) {
        free();
    }

    m_config = config;
    setup_thread_affinity();

    if (!load_target_model(config)) {
        free();
        return false;
    }

    load_draft_model(config);

    m_initialized.store(true);
    m_abort_requested.store(false);
    LOGI("MedlamInferenceEngine initialized successfully");
    return true;
}

std::string MedlamInferenceEngine::token_to_string(const struct llama_vocab* vocab, llama_token token) {
    char piece_buf[256];
    int32_t n = llama_token_to_piece(vocab, token, piece_buf, sizeof(piece_buf), 0, false);
    if (n < 0) {
        std::vector<char> dyn_buf(-n + 1);
        n = llama_token_to_piece(vocab, token, dyn_buf.data(), dyn_buf.size(), 0, false);
        return (n > 0) ? std::string(dyn_buf.data(), n) : "";
    }
    return std::string(piece_buf, n);
}

int MedlamInferenceEngine::generate_stream(
    const std::string& prompt,
    TokenCallback on_token,
    CompletionCallback on_complete,
    ErrorCallback on_error
) {
    std::lock_guard<std::mutex> lock(m_engine_mutex);

    if (!m_initialized.load() || !m_target_model || !m_target_ctx) {
        if (on_error) on_error("Engine not initialized");
        return -1;
    }

    m_is_generating.store(true);
    m_abort_requested.store(false);

    // 1. Tokenize prompt using target vocabulary
    std::vector<llama_token> prompt_tokens(prompt.length() + 64);
    int32_t n_tokens = llama_tokenize(
        m_target_vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.length()),
        prompt_tokens.data(),
        static_cast<int32_t>(prompt_tokens.size()),
        true,  // add_special (BOS)
        true   // parse_special
    );

    if (n_tokens < 0) {
        prompt_tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            m_target_vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.length()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            true,
            true
        );
    }
    prompt_tokens.resize(std::max(0, n_tokens));

    if (prompt_tokens.empty()) {
        if (on_error) on_error("Prompt tokenization produced 0 tokens");
        m_is_generating.store(false);
        return 0;
    }

    LOGI("Starting generation for %zu prompt tokens...", prompt_tokens.size());

    int result = 0;
    if (m_draft_model && m_draft_ctx) {
        result = run_speculative_decoding(prompt_tokens, on_token, on_complete, on_error);
    } else {
        result = run_standard_decoding(prompt_tokens, on_token, on_complete, on_error);
    }

    m_is_generating.store(false);
    return result;
}

int MedlamInferenceEngine::run_speculative_decoding(
    const std::vector<llama_token>& prompt_tokens,
    TokenCallback& on_token,
    CompletionCallback& on_complete,
    ErrorCallback& on_error
) {
    const auto start_time = std::chrono::high_resolution_clock::now();

    // 1. Clear KV caches
    llama_memory_clear(m_target_ctx, true);
    llama_memory_clear(m_draft_ctx, true);

    const int n_prompt = static_cast<int>(prompt_tokens.size());
    const int K = std::max(2, std::min(m_config.speculative_k, 8));

    // 2. Prefill prompt in both Target and Draft models
    struct llama_batch p_batch = llama_batch_init(std::max(n_prompt, 256), 0, 1);

    for (int i = 0; i < n_prompt; ++i) {
        batch_add_token(p_batch, prompt_tokens[i], i, {0}, (i == n_prompt - 1));
    }

    if (llama_decode(m_target_ctx, p_batch) != 0) {
        llama_batch_free(p_batch);
        if (on_error) on_error("Target prefill decode failed");
        return -1;
    }

    if (llama_decode(m_draft_ctx, p_batch) != 0) {
        llama_batch_free(p_batch);
        if (on_error) on_error("Draft prefill decode failed");
        return -1;
    }

    llama_batch_free(p_batch);

    int n_past = n_prompt;
    int total_tokens = 0;
    int accepted_spec_tokens = 0;
    bool is_eog = false;

    std::vector<llama_token> draft_tokens(K);

    // 3. Speculative Generation Loop
    while (!is_eog && !m_abort_requested.load()) {
        check_thermal_status();

        // Step A: Draft model generates K speculative tokens autoregressively
        struct llama_batch d_batch = llama_batch_init(1, 0, 1);

        for (int j = 0; j < K; ++j) {
            llama_token t_draft = llama_sampler_sample(m_draft_sampler, m_draft_ctx, -1);
            llama_sampler_accept(m_draft_sampler, t_draft);
            draft_tokens[j] = t_draft;

            d_batch.n_tokens = 0;
            batch_add_token(d_batch, t_draft, n_past + j, {0}, true);
            if (llama_decode(m_draft_ctx, d_batch) != 0) {
                break;
            }
        }
        llama_batch_free(d_batch);

        // Step B: Target Model Verifies all K draft tokens in a SINGLE forward pass!
        struct llama_batch v_batch = llama_batch_init(K, 0, 1);
        for (int j = 0; j < K; ++j) {
            batch_add_token(v_batch, draft_tokens[j], n_past + j, {0}, true);
        }

        if (llama_decode(m_target_ctx, v_batch) != 0) {
            llama_batch_free(v_batch);
            if (on_error) on_error("Target batch verification failed");
            return -1;
        }
        llama_batch_free(v_batch);

        // Step C: Verify Acceptance
        int n_accepted = 0;
        for (int j = 0; j < K; ++j) {
            llama_token t_target = llama_sampler_sample(m_target_sampler, m_target_ctx, j);
            llama_sampler_accept(m_target_sampler, t_target);

            if (t_target == draft_tokens[j]) {
                // Speculative match!
                n_accepted++;
                accepted_spec_tokens++;
                total_tokens++;

                std::string piece = token_to_string(m_target_vocab, t_target);
                if (on_token && !on_token(piece)) {
                    m_abort_requested.store(true);
                    break;
                }

                if (llama_vocab_is_eog(m_target_vocab, t_target)) {
                    is_eog = true;
                    break;
                }
            } else {
                // Mismatch at index j: Target token is ground truth
                total_tokens++;
                std::string piece = token_to_string(m_target_vocab, t_target);
                if (on_token && !on_token(piece)) {
                    m_abort_requested.store(true);
                    break;
                }

                if (llama_vocab_is_eog(m_target_vocab, t_target)) {
                    is_eog = true;
                }

                // Roll back target context to accepted index + 1
                llama_memory_seq_rm(m_target_ctx, 0, n_past + j + 1, -1);

                // Roll back draft context to index j and synchronize with target's true token
                llama_memory_seq_rm(m_draft_ctx, 0, n_past + j, -1);
                struct llama_batch sync_batch = llama_batch_init(1, 0, 1);
                batch_add_token(sync_batch, t_target, n_past + j, {0}, true);
                llama_decode(m_draft_ctx, sync_batch);
                llama_batch_free(sync_batch);

                n_past += j + 1;
                break;
            }
        }

        // If all K tokens were accepted, sample bonus token from target at index K-1
        if (n_accepted == K && !is_eog && !m_abort_requested.load()) {
            llama_token t_bonus = llama_sampler_sample(m_target_sampler, m_target_ctx, K - 1);
            llama_sampler_accept(m_target_sampler, t_bonus);
            total_tokens++;

            std::string piece = token_to_string(m_target_vocab, t_bonus);
            if (on_token && !on_token(piece)) {
                m_abort_requested.store(true);
                break;
            }

            if (llama_vocab_is_eog(m_target_vocab, t_bonus)) {
                is_eog = true;
            }

            // Sync draft context with bonus token
            struct llama_batch sync_batch = llama_batch_init(1, 0, 1);
            batch_add_token(sync_batch, t_bonus, n_past + K, {0}, true);
            llama_decode(m_draft_ctx, sync_batch);
            llama_batch_free(sync_batch);

            n_past += K + 1;
        }
    }

    const auto end_time = std::chrono::high_resolution_clock::now();
    const double elapsed_sec = std::chrono::duration<double>(end_time - start_time).count();
    const double tok_per_sec = (elapsed_sec > 0.0) ? (total_tokens / elapsed_sec) : 0.0;

    LOGI("Speculative decoding complete: %d tokens generated in %.2fs (%.2f tok/s) [Accepted Draft: %d]",
         total_tokens, elapsed_sec, tok_per_sec, accepted_spec_tokens);

    if (on_complete) {
        on_complete(total_tokens, tok_per_sec);
    }

    return total_tokens;
}

int MedlamInferenceEngine::run_standard_decoding(
    const std::vector<llama_token>& prompt_tokens,
    TokenCallback& on_token,
    CompletionCallback& on_complete,
    ErrorCallback& on_error
) {
    const auto start_time = std::chrono::high_resolution_clock::now();

    llama_memory_clear(m_target_ctx, true);
    const int n_prompt = static_cast<int>(prompt_tokens.size());

    struct llama_batch batch = llama_batch_init(std::max(n_prompt, 256), 0, 1);

    for (int i = 0; i < n_prompt; ++i) {
        batch_add_token(batch, prompt_tokens[i], i, {0}, (i == n_prompt - 1));
    }

    if (llama_decode(m_target_ctx, batch) != 0) {
        llama_batch_free(batch);
        if (on_error) on_error("Prompt prefill failed");
        return -1;
    }
    llama_batch_free(batch);

    int n_past = n_prompt;
    int total_tokens = 0;
    bool is_eog = false;

    struct llama_batch step_batch = llama_batch_init(1, 0, 1);

    while (!is_eog && !m_abort_requested.load()) {
        if (total_tokens % 32 == 0) {
            check_thermal_status();
        }

        llama_token token = llama_sampler_sample(m_target_sampler, m_target_ctx, -1);
        llama_sampler_accept(m_target_sampler, token);

        if (llama_vocab_is_eog(m_target_vocab, token)) {
            is_eog = true;
            break;
        }

        total_tokens++;
        std::string piece = token_to_string(m_target_vocab, token);
        if (on_token && !on_token(piece)) {
            break;
        }

        step_batch.n_tokens = 0;
        batch_add_token(step_batch, token, n_past, {0}, true);
        if (llama_decode(m_target_ctx, step_batch) != 0) {
            if (on_error) on_error("Autoregressive decode step failed");
            break;
        }
        n_past++;
    }

    llama_batch_free(step_batch);

    const auto end_time = std::chrono::high_resolution_clock::now();
    const double elapsed_sec = std::chrono::duration<double>(end_time - start_time).count();
    const double tok_per_sec = (elapsed_sec > 0.0) ? (total_tokens / elapsed_sec) : 0.0;

    LOGI("Standard decoding complete: %d tokens in %.2fs (%.2f tok/s)", total_tokens, elapsed_sec, tok_per_sec);

    if (on_complete) {
        on_complete(total_tokens, tok_per_sec);
    }

    return total_tokens;
}

std::string MedlamInferenceEngine::get_model_info() const {
    if (!m_target_model) return "No model loaded";
    char desc[256];
    llama_model_desc(m_target_model, desc, sizeof(desc));
    return std::string(desc);
}

void MedlamInferenceEngine::free() {
    std::lock_guard<std::mutex> lock(m_engine_mutex);

    m_initialized.store(false);
    m_is_generating.store(false);

    if (m_target_sampler) {
        llama_sampler_free(m_target_sampler);
        m_target_sampler = nullptr;
    }
    if (m_target_ctx) {
        llama_free(m_target_ctx);
        m_target_ctx = nullptr;
    }
    if (m_target_model) {
        llama_model_free(m_target_model);
        m_target_model = nullptr;
    }
    m_target_vocab = nullptr;

    if (m_draft_sampler) {
        llama_sampler_free(m_draft_sampler);
        m_draft_sampler = nullptr;
    }
    if (m_draft_ctx) {
        llama_free(m_draft_ctx);
        m_draft_ctx = nullptr;
    }
    if (m_draft_model) {
        llama_model_free(m_draft_model);
        m_draft_model = nullptr;
    }
    m_draft_vocab = nullptr;

    LOGI("MedlamInferenceEngine resources freed");
}

} // namespace medlam
