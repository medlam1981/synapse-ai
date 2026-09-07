#ifndef MEDLAM_INFERENCE_ENGINE_H
#define MEDLAM_INFERENCE_ENGINE_H

#include "llama.h"

#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <functional>
#include <chrono>

namespace medlam {

// Token emission callback; returning false signals immediate cancellation
using TokenCallback = std::function<bool(const std::string& token_piece)>;
using CompletionCallback = std::function<void(int tokens_generated, double tokens_per_second)>;
using ErrorCallback = std::function<void(const std::string& error_message)>;

struct EngineConfig {
    std::string model_path;
    std::string draft_model_path;
    int thread_count = 4;
    int gpu_layers = 0;
    int context_size = 4096;
    int batch_size = 256;
    int ubatch_size = 128;
    int speculative_k = 4;      // Lookahead draft sequence length (K=4 for 3x-4x speedup)
    float temperature = 0.7f;
    bool use_mmap = true;
    bool draft_use_mmap = true;
    bool enable_performance_affinity = true;
};

class MedlamInferenceEngine {
public:
    static MedlamInferenceEngine& instance();

    MedlamInferenceEngine();
    ~MedlamInferenceEngine();

    // Prevent copy/move
    MedlamInferenceEngine(const MedlamInferenceEngine&) = delete;
    MedlamInferenceEngine& operator=(const MedlamInferenceEngine&) = delete;

    /**
     * Initializes the target model and optional draft model with zero-copy mmap.
     */
    bool init(const EngineConfig& config);

    /**
     * Executes speculative decoding (or standard generation if no draft model).
     * Streams generated tokens via callback. Blocks until complete or aborted.
     */
    int generate_stream(
        const std::string& prompt,
        TokenCallback on_token,
        CompletionCallback on_complete,
        ErrorCallback on_error
    );

    /**
     * Requests immediate cancellation of the active generation loop.
     */
    void stop();

    /**
     * Releases all native memory, contexts, models, and samplers.
     */
    void free();

    bool is_initialized() const;
    bool is_generating() const;
    std::string get_model_info() const;

private:
    // Core internal methods
    bool load_target_model(const EngineConfig& config);
    bool load_draft_model(const EngineConfig& config);
    void setup_thread_affinity();
    void check_thermal_status();

    // Speculative decoding execution loop
    int run_speculative_decoding(
        const std::vector<llama_token>& prompt_tokens,
        TokenCallback& on_token,
        CompletionCallback& on_complete,
        ErrorCallback& on_error
    );

    // Standard autoregressive single-model fallback loop
    int run_standard_decoding(
        const std::vector<llama_token>& prompt_tokens,
        TokenCallback& on_token,
        CompletionCallback& on_complete,
        ErrorCallback& on_error
    );

    std::string token_to_string(const struct llama_vocab* vocab, llama_token token);

private:
    mutable std::mutex m_engine_mutex;

    // Target Model
    llama_model*   m_target_model = nullptr;
    llama_context* m_target_ctx   = nullptr;
    const llama_vocab* m_target_vocab = nullptr;
    llama_sampler* m_target_sampler = nullptr;

    // Draft Model (Speculative Decoding)
    llama_model*   m_draft_model = nullptr;
    llama_context* m_draft_ctx   = nullptr;
    const llama_vocab* m_draft_vocab = nullptr;
    llama_sampler* m_draft_sampler = nullptr;

    EngineConfig m_config;
    std::atomic<bool> m_initialized{false};
    std::atomic<bool> m_is_generating{false};
    std::atomic<bool> m_abort_requested{false};

    std::vector<int> m_perf_cores;
};

} // namespace medlam

#endif // MEDLAM_INFERENCE_ENGINE_H
