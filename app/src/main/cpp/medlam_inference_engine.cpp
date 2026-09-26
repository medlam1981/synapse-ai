#include "medlam_inference_engine.h"

#include <android/log.h>
#include <sched.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/resource.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <time.h>

#define TAG "MedlamEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

namespace medlam {

#if defined(__ANDROID__)
#ifndef pthread_setaffinity_np
static inline int pthread_setaffinity_np(pthread_t thread, size_t cpu_size, const cpu_set_t *cpu_set) {
    pid_t tid = pthread_gettid_np(thread);
    return sched_setaffinity(tid, cpu_size, cpu_set);
}
#endif
#endif

// Pins calling thread to performance cores dynamically using Linux EAS cpu_capacity / frequency
void pin_to_big_cores() {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int total_cores = sysconf(_SC_NPROCESSORS_CONF);
    if (total_cores <= 0) total_cores = 8;

    int max_capacity = 0;
    std::vector<int> capacities(total_cores, 0);
    for (int i = 0; i < total_cores; ++i) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", i);
        FILE* f = fopen(path, "r");
        if (f) {
            int cap = 0;
            if (fscanf(f, "%d", &cap) == 1 && cap > 0) {
                capacities[i] = cap;
                if (cap > max_capacity) max_capacity = cap;
            }
            fclose(f);
        }
    }

    int perf_core_count = 0;
    if (max_capacity > 0) {
        int cap_threshold = (int)(max_capacity * 0.70);
        for (int i = 0; i < total_cores; ++i) {
            if (capacities[i] >= cap_threshold) {
                CPU_SET(i, &cpuset);
                perf_core_count++;
            }
        }
    }

    if (perf_core_count == 0) {
        std::vector<long> freqs(total_cores, 0);
        long max_freq = 0;
        for (int i = 0; i < total_cores; ++i) {
            char path[128];
            snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
            FILE* f = fopen(path, "r");
            if (!f) {
                snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", i);
                f = fopen(path, "r");
            }
            if (f) {
                long freq = 0;
                if (fscanf(f, "%ld", &freq) == 1 && freq > 0) {
                    freqs[i] = freq;
                    if (freq > max_freq) max_freq = freq;
                }
                fclose(f);
            }
        }
        if (max_freq > 0) {
            long freq_threshold = (long)(max_freq * 0.90);
            for (int i = 0; i < total_cores; ++i) {
                if (freqs[i] >= freq_threshold) {
                    CPU_SET(i, &cpuset);
                    perf_core_count++;
                }
            }
        }
    }

    if (perf_core_count == 0) {
        if (total_cores <= 4) {
            for (int i = 0; i < total_cores; ++i) { CPU_SET(i, &cpuset); perf_core_count++; }
        } else {
            int start_core = (total_cores >= 8) ? 4 : (total_cores / 2);
            for (int i = start_core; i < total_cores; ++i) { CPU_SET(i, &cpuset); perf_core_count++; }
        }
    }

    pthread_t current_thread = pthread_self();
    int rc = pthread_setaffinity_np(current_thread, sizeof(cpu_set_t), &cpuset);
    if (rc == 0) {
        setpriority(PRIO_PROCESS, 0, -10);
        LOGI("[ThreadAffinity] Pinned thread %d to %d performance Big cores", pthread_gettid_np(current_thread), perf_core_count);
    } else {
        LOGW("[ThreadAffinity] pthread_setaffinity_np failed: %d (errno: %d, %s)", rc, errno, strerror(errno));
    }
}

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

bool MedlamInferenceEngine::setup_thread_affinity() {
    m_perf_cores.clear();

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int perf_count = 0;

    // 1. Scan sysfs to identify big/performance cores if permitted
    unsigned long max_freq_all = 0;
    std::vector<std::pair<int, unsigned long>> core_freqs;

    for (int cpu = 0; cpu < 16; ++cpu) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "r");
        if (!f) {
            snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", cpu);
            f = fopen(path, "r");
        }
        if (f) {
            unsigned long freq = 0;
            if (fscanf(f, "%lu", &freq) == 1 && freq > 0) {
                core_freqs.emplace_back(cpu, freq);
                if (freq > max_freq_all) {
                    max_freq_all = freq;
                }
            }
            fclose(f);
        }
    }

    if (!core_freqs.empty() && max_freq_all > 0) {
        unsigned long threshold = (max_freq_all * 85) / 100;
        for (const auto& entry : core_freqs) {
            if (entry.second >= threshold) {
                m_perf_cores.push_back(entry.first);
                CPU_SET(entry.first, &cpuset);
                perf_count++;
            }
        }
    }

    // 2. Explicit Hardware Pinning for ARM big.LITTLE (Snapdragon 665 / sm6125)
    // On Android 16 (SDK 36), SELinux blocks unprivileged access to sysfs cpufreq.
    // We MUST explicitly pin inference threads to the Big cluster (Cores 4, 5, 6, 7).
    if (perf_count == 0) {
        int total_cores = sysconf(_SC_NPROCESSORS_CONF);
        if (total_cores <= 0) total_cores = 8;
        LOGI("SELinux blocked sysfs frequency inspection. Enforcing big.LITTLE performance cluster affinity for %d-core architecture.", total_cores);

        if (total_cores >= 8) {
            // Snapdragon 665 / 660 / 680 / 720G / 730 / 855 / 865: Cores 4, 5, 6, 7 are Kryo Gold performance cores
            for (int cpu = 4; cpu <= 7; ++cpu) {
                m_perf_cores.push_back(cpu);
                CPU_SET(cpu, &cpuset);
                perf_count++;
            }
        } else {
            int start_core = std::max(0, total_cores / 2);
            for (int cpu = start_core; cpu < total_cores; ++cpu) {
                m_perf_cores.push_back(cpu);
                CPU_SET(cpu, &cpuset);
                perf_count++;
            }
        }
    }

    if (perf_count > 0) {
        int rc = sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
        if (rc == 0) {
            LOGI("Thread affinity successfully pinned to %d performance cores (0xF0 mask: Cores 4, 5, 6, 7)", perf_count);
            return true;
        } else {
            LOGW("sched_setaffinity returned code: %d (errno: %d, %s)", rc, errno, strerror(errno));
            return false;
        }
    }
    return false;
}

void MedlamInferenceEngine::pin_to_big_cores() {
    medlam::pin_to_big_cores();
}

int MedlamInferenceEngine::read_soc_temperature() {
    int max_temp = 0;
    DIR* dir = opendir("/sys/class/thermal");
    if (dir) {
        struct dirent* entry;
        while ((entry = readdir(dir)) != nullptr) {
            if (strncmp(entry->d_name, "thermal_zone", 12) == 0) {
                char temp_path[256];
                char type_path[256];
                snprintf(temp_path, sizeof(temp_path), "/sys/class/thermal/%s/temp", entry->d_name);
                snprintf(type_path, sizeof(type_path), "/sys/class/thermal/%s/type", entry->d_name);

                char type_buf[64] = {0};
                FILE* f_type = fopen(type_path, "r");
                if (f_type) {
                    if (fgets(type_buf, sizeof(type_buf), f_type)) {
                        for (int i = 0; type_buf[i]; ++i) type_buf[i] = static_cast<char>(tolower(type_buf[i]));
                    }
                    fclose(f_type);
                }

                FILE* f_temp = fopen(temp_path, "r");
                if (f_temp) {
                    int t = 0;
                    if (fscanf(f_temp, "%d", &t) == 1) {
                        int norm = (t > 1000) ? (t / 1000) : t;
                        if (norm >= 20 && norm <= 115) {
                            if (strstr(type_buf, "cpu") || strstr(type_buf, "tsens") || strstr(type_buf, "cluster")) {
                                max_temp = std::max(max_temp, norm);
                            } else if (norm > max_temp && norm != 37) {
                                max_temp = norm;
                            }
                        }
                    }
                    fclose(f_temp);
                }
            }
        }
        closedir(dir);
    }
    if (max_temp == 0 || max_temp == 37) {
        FILE* f0 = fopen("/sys/class/thermal/thermal_zone0/temp", "r");
        if (f0) {
            int t = 0;
            if (fscanf(f0, "%d", &t) == 1) {
                int norm = (t > 1000) ? (t / 1000) : t;
                if (norm >= 20 && norm <= 115) max_temp = norm;
            }
            fclose(f0);
        }
    }
    return max_temp;
}

void MedlamInferenceEngine::check_thermal_status() {
    int temp = read_soc_temperature();
    if (temp <= 0) return;

    if (temp > 65 && m_current_threads > 2) {
        LOGW("Dynamic Thermal Guard: SoC temp is %d°C (> 65°C). Throttling worker threads from %d to 2 to eliminate thermal throttling.",
             temp, m_current_threads);
        m_current_threads = 2;
        if (m_target_ctx) {
            llama_set_n_threads(m_target_ctx, 2, 2);
        }
        if (m_draft_ctx) {
            llama_set_n_threads(m_draft_ctx, 2, 2);
        }
    } else if (temp < 58 && m_current_threads < 4) {
        LOGI("Dynamic Thermal Guard: SoC temp cooled down to %d°C (< 58°C). Restoring worker threads from %d to 4.",
             temp, m_current_threads);
        m_current_threads = 4;
        if (m_target_ctx) {
            llama_set_n_threads(m_target_ctx, 4, 4);
        }
        if (m_draft_ctx) {
            llama_set_n_threads(m_draft_ctx, 4, 4);
        }
    }
}

bool MedlamInferenceEngine::is_eog_or_special_stop(const struct llama_vocab* vocab, llama_token token, const std::string& piece) {
    if (vocab) {
        if (llama_vocab_is_eog(vocab, token)) return true;
        llama_token eos = llama_vocab_eos(vocab);
        if (token == eos && eos != -1) return true;
        llama_token eot = llama_vocab_eot(vocab);
        if (token == eot && eot != -1) return true;
    }
    if (piece.empty()) return false;

    if (piece == "<end_of_turn>" || piece == "<start_of_turn>" || piece == "<eos>" || piece == "<|end_of_turn|>" ||
        piece == "<|im_end|>" || piece == "<|im_start|>" || piece == "<|eot_id|>" || piece == "<|start_header_id|>" ||
        piece == "<|end_of_text|>") {
        return true;
    }
    if (piece.find("<end_of_turn>") != std::string::npos ||
        piece.find("<start_of_turn>") != std::string::npos ||
        piece.find("<|end_of_turn|>") != std::string::npos ||
        piece.find("<|im_end|>") != std::string::npos ||
        piece.find("<|eot_id|>") != std::string::npos) {
        return true;
    }
    return false;
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

    // 2. Context Parameters & Memory Bounds (Optimized for 32K context)
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>((config.context_size > 0 && config.context_size <= 8192) ? config.context_size : 4096);
    cparams.type_k = 2; // GGML_TYPE_Q4_0 Quantized Key cache
    cparams.type_v = 2; // GGML_TYPE_Q4_0 Quantized Value cache
    cparams.flash_attn = true;
    cparams.n_batch = static_cast<uint32_t>(config.batch_size > 0 ? config.batch_size : 512);
    cparams.n_ubatch = static_cast<uint32_t>(config.ubatch_size > 0 ? config.ubatch_size : 256);
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    if (cparams.n_ctx > 8192) {
        cparams.rope_freq_scale = config.rope_freq_scale > 0.0f ? config.rope_freq_scale : 0.25f;
        cparams.yarn_orig_ctx = config.yarn_orig_ctx > 0 ? static_cast<uint32_t>(config.yarn_orig_ctx) : 8192;
        LOGI("Positional RoPE scaling applied: rope_freq_scale=%.2f, yarn_orig_ctx=%u", cparams.rope_freq_scale, cparams.yarn_orig_ctx);
    }

    // Pin calling thread to Kryo Gold BIG cores right before initializing the llama context
    pin_to_big_cores();

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

    LOGI("Loading Speculative Draft Model (mmap=%d, mlock=%d): %s",
         config.draft_use_mmap, config.draft_use_mlock, config.draft_model_path.c_str());

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // Draft model runs lightning-fast on CPU
    mparams.use_mmap = config.draft_use_mmap;
    mparams.use_mlock = config.draft_use_mlock; // Draft locked in physical RAM for sub-millisecond lookups
    mparams.check_tensors = false;

    m_draft_model = llama_model_load_from_file(config.draft_model_path.c_str(), mparams);
    if (!m_draft_model) {
        LOGW("Failed to load Draft Model from %s. Falling back to single-model mode.", config.draft_model_path.c_str());
        return true; // Graceful degradation
    }

    m_draft_vocab = llama_model_get_vocab(m_draft_model);

    llama_context_params cparams = llama_context_default_params();
    // Bounded context for draft model to save memory footprint
    cparams.n_ctx = static_cast<uint32_t>(std::min(config.context_size, 4096));
    cparams.type_k = 2; // GGML_TYPE_Q4_0 Quantized Key cache
    cparams.type_v = 2; // GGML_TYPE_Q4_0 Quantized Value cache
    cparams.n_batch = 512;
    cparams.n_ubatch = 256;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    cparams.flash_attn = true;

    // Pin calling thread to Kryo Gold BIG cores right before initializing draft llama context
    pin_to_big_cores();

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

    // Kernel Read-Ahead Optimization (Root Execution):
    // Accelerates mmap page faults for Base Model by expanding block device read-ahead buffer to 4096 KB
    system("su -c \"echo 4096 > /sys/block/mmcblk0/queue/read_ahead_kb\" 2>/dev/null || su -c \"echo 4096 > /sys/block/sda/queue/read_ahead_kb\" 2>/dev/null");

    if (!load_target_model(config)) {
        free();
        return false;
    }

    if (!config.draft_model_path.empty()) {
        load_draft_model(config);
    }

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
    pin_to_big_cores();
    setup_thread_affinity();

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
    // Pin thread to BIG performance cores (4, 5, 6, 7) right before starting speculative inference loop
    pin_to_big_cores();

    const auto start_time = std::chrono::high_resolution_clock::now();

    const int n_prompt = static_cast<int>(prompt_tokens.size());
    const int K = std::min(std::max(m_config.speculative_k, 3), 5); // Up to 5 draft tokens per target forward pass

    // Incremental KV-Cache: Re-use cached tokens from previous conversation turns
    int n_past = 0;
    while (n_past < (int)m_prev_tokens.size() &&
           n_past < n_prompt &&
           m_prev_tokens[n_past] == prompt_tokens[n_past]) {
        n_past++;
    }

    if (n_past < (int)m_prev_tokens.size()) {
        llama_memory_seq_rm(m_target_ctx, 0, n_past, -1);
        llama_memory_seq_rm(m_draft_ctx, 0, n_past, -1);
    }

    LOGI("[KV-CACHE] Speculative KV Cache: Reusing %d cached tokens, prefilling %d new tokens (total prompt: %d)",
         n_past, n_prompt - n_past, n_prompt);

    // Thermal-Aware Chunked Prefill in both Target and Draft models for new tokens only
    const int chunk_size = (m_config.ubatch_size > 0) ? m_config.ubatch_size : 256;
    struct llama_batch p_batch = llama_batch_init(chunk_size, 0, 1);

    for (int i = n_past; i < n_prompt; i += chunk_size) {
        p_batch.n_tokens = 0;
        int n_eval = std::min(chunk_size, n_prompt - i);
        for (int j = 0; j < n_eval; ++j) {
            int token_idx = i + j;
            bool need_logits = (token_idx == n_prompt - 1);
            batch_add_token(p_batch, prompt_tokens[token_idx], token_idx, {0}, need_logits);
        }

        if (llama_decode(m_target_ctx, p_batch) != 0) {
            llama_batch_free(p_batch);
            if (on_error) on_error("Target prefill chunk decode failed");
            return -1;
        }

        if (llama_decode(m_draft_ctx, p_batch) != 0) {
            llama_batch_free(p_batch);
            if (on_error) on_error("Draft prefill chunk decode failed");
            return -1;
        }
    }
    llama_batch_free(p_batch);

    n_past = n_prompt;
    int total_tokens = 0;
    int accepted_spec_tokens = 0;
    bool is_eog = false;

    std::vector<llama_token> generated_tokens = prompt_tokens;
    std::vector<llama_token> draft_tokens(K);

    // 3. Speculative Generation Loop
    while (!is_eog && !m_abort_requested.load()) {
        check_thermal_status();

        // Rolling buffer / Context shift if approaching context window limit
        if (n_past + K >= m_config.context_size - 1) {
            const int shift_size = std::min(512, n_past / 4);
            LOGI("Speculative context approaching limit (%d tokens). Shifting KV cache by %d tokens...", n_past, shift_size);
            llama_memory_seq_rm(m_target_ctx, 0, 32, 32 + shift_size);
            llama_memory_seq_rm(m_draft_ctx, 0, 32, 32 + shift_size);
            n_past -= shift_size;
        }

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

            std::string piece = token_to_string(m_target_vocab, t_target);
            if (is_eog_or_special_stop(m_target_vocab, t_target, piece)) {
                is_eog = true;
                break;
            }

            if (t_target == draft_tokens[j]) {
                // Speculative match!
                n_accepted++;
                accepted_spec_tokens++;
                total_tokens++;
                generated_tokens.push_back(t_target);

                if (on_token && !on_token(piece)) {
                    m_abort_requested.store(true);
                    break;
                }
            } else {
                // Mismatch at index j: Target token is ground truth
                total_tokens++;
                generated_tokens.push_back(t_target);

                if (on_token && !on_token(piece)) {
                    m_abort_requested.store(true);
                    break;
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

            std::string piece = token_to_string(m_target_vocab, t_bonus);
            if (is_eog_or_special_stop(m_target_vocab, t_bonus, piece)) {
                is_eog = true;
                break;
            }

            total_tokens++;
            generated_tokens.push_back(t_bonus);

            if (on_token && !on_token(piece)) {
                m_abort_requested.store(true);
                break;
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

    m_prev_tokens = std::move(generated_tokens);

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
    // Pin thread to BIG performance cores (4, 5, 6, 7) right before starting the main inference loop
    pin_to_big_cores();

    const auto start_time = std::chrono::high_resolution_clock::now();

    const int n_prompt = static_cast<int>(prompt_tokens.size());

    // 1. Incremental KV-Cache: Calculate longest common prefix with previous conversation turn KV cache
    int n_past = 0;
    while (n_past < (int)m_prev_tokens.size() &&
           n_past < n_prompt &&
           m_prev_tokens[n_past] == prompt_tokens[n_past]) {
        n_past++;
    }

    if (n_past < (int)m_prev_tokens.size()) {
        // Discard any invalidated tokens from previous generation past n_past
        llama_memory_seq_rm(m_target_ctx, 0, n_past, -1);
    }

    LOGI("[KV-CACHE] Incremental KV Cache: Reusing %d cached tokens. Prefilling %d new tokens (total prompt: %d)",
         n_past, n_prompt - n_past, n_prompt);

    // 2. Prefill only newly appended tokens
    const int chunk_size = (m_config.ubatch_size > 0) ? m_config.ubatch_size : 256;
    struct llama_batch batch = llama_batch_init(chunk_size, 0, 1);

    const auto prefill_start = std::chrono::high_resolution_clock::now();
    for (int i = n_past; i < n_prompt; i += chunk_size) {
        batch.n_tokens = 0;
        int n_eval = std::min(chunk_size, n_prompt - i);
        for (int j = 0; j < n_eval; ++j) {
            int token_idx = i + j;
            bool need_logits = (token_idx == n_prompt - 1);
            batch_add_token(batch, prompt_tokens[token_idx], token_idx, {0}, need_logits);
        }

        if (llama_decode(m_target_ctx, batch) != 0) {
            llama_batch_free(batch);
            if (on_error) on_error("Prompt prefill failed in chunked decode");
            return -1;
        }
    }
    llama_batch_free(batch);

    const auto prefill_end = std::chrono::high_resolution_clock::now();
    const double prefill_ms = std::chrono::duration<double, std::milli>(prefill_end - prefill_start).count();
    const double prefill_tps = (prefill_ms > 0.0) ? ((n_prompt - n_past) / (prefill_ms / 1000.0)) : 0.0;
    LOGI("[BENCHMARK] Prompt Eval (Prefill): %d new tokens in %.2f ms (%.2f tok/s) [reused %d tokens]",
         n_prompt - n_past, prefill_ms, prefill_tps, n_past);

    n_past = n_prompt;
    int total_tokens = 0;
    bool is_eog = false;

    std::vector<llama_token> generated_tokens = prompt_tokens;

    struct llama_batch step_batch = llama_batch_init(1, 0, 1);
    const auto gen_start = std::chrono::high_resolution_clock::now();

    while (!is_eog && !m_abort_requested.load()) {
        if (total_tokens % 16 == 0) {
            check_thermal_status();
        }

        // Rolling buffer / Context shift if approaching context window limit
        if (n_past >= m_config.context_size - 1) {
            const int shift_size = std::min(512, n_past / 4);
            LOGI("Context approaching limit (%d tokens). Shifting KV cache by %d tokens...", n_past, shift_size);
            llama_memory_seq_rm(m_target_ctx, 0, 32, 32 + shift_size);
            n_past -= shift_size;
        }

        llama_token token = llama_sampler_sample(m_target_sampler, m_target_ctx, -1);
        llama_sampler_accept(m_target_sampler, token);

        std::string piece = token_to_string(m_target_vocab, token);

        // Strict EOS and Special Delimiter Halting (Eliminates <end_of_turn> leak and idling CPU)
        if (is_eog_or_special_stop(m_target_vocab, token, piece)) {
            LOGI("[EOS HALT] Turn delimiter / EOS token encountered (token=%d, piece=\"%s\"). Halting generation immediately.",
                 token, piece.c_str());
            is_eog = true;
            break;
        }

        total_tokens++;
        generated_tokens.push_back(token);

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

    // Save history for incremental KV-cache reuse in subsequent conversational turns
    m_prev_tokens = std::move(generated_tokens);

    const auto end_time = std::chrono::high_resolution_clock::now();
    const double gen_sec = std::chrono::duration<double>(end_time - gen_start).count();
    const double tok_per_sec = (gen_sec > 0.0) ? (total_tokens / gen_sec) : 0.0;

    LOGI("[BENCHMARK] Token Generation: %d tokens in %.2fs (%.2f tok/s) | Prefill: %.2f ms (%.2f tok/s)",
         total_tokens, gen_sec, tok_per_sec, prefill_ms, prefill_tps);

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

void MedlamInferenceEngine::clear_kv_cache() {
    std::lock_guard<std::mutex> lock(m_engine_mutex);
    if (m_target_ctx) {
        llama_memory_clear(m_target_ctx, true);
    }
    if (m_draft_ctx) {
        llama_memory_clear(m_draft_ctx, true);
    }
    m_prev_tokens.clear();
    LOGI("[KV-CACHE] KV cache and sequence history explicitly cleared");
}

void MedlamInferenceEngine::free() {
    std::lock_guard<std::mutex> lock(m_engine_mutex);

    m_initialized.store(false);
    m_is_generating.store(false);

    m_prev_tokens.clear();
    m_current_threads = 4;

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
