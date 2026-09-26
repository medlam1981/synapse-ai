#ifndef LLAMA_H
#define LLAMA_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opaque handles
struct llama_model;
struct llama_context;
struct llama_vocab;
struct llama_sampler;

typedef int32_t llama_pos;
typedef int32_t llama_token;
typedef int32_t llama_seq_id;

enum llama_split_mode {
    LLAMA_SPLIT_MODE_NONE  = 0,
    LLAMA_SPLIT_MODE_LAYER = 1,
    LLAMA_SPLIT_MODE_ROW   = 2,
};

// Model parameters
struct llama_model_params {
    int32_t n_gpu_layers;
    int32_t split_mode;
    int32_t main_gpu;
    const float * tensor_split;
    void * progress_callback;
    void * progress_callback_user_data;
    void * kv_overrides;
    bool vocab_only;
    bool use_mmap;
    bool use_mlock;
    bool check_tensors;
};

// Context parameters (136 bytes binary layout)
struct llama_context_params {
    uint32_t n_ctx;
    uint32_t n_batch;
    uint32_t n_ubatch;
    uint32_t n_seq_max;
    int32_t  n_threads;
    int32_t  n_threads_batch;

    int32_t  rope_scaling_type;
    int32_t  pooling_type;
    int32_t  attention_type;

    float    rope_freq_base;
    float    rope_freq_scale;
    float    yarn_ext_factor;
    float    yarn_attn_factor;
    float    yarn_beta_fast;
    float    yarn_beta_slow;
    uint32_t yarn_orig_ctx;
    float    defrag_thold;

    void *   cb_eval;
    void *   cb_eval_user_data;

    int32_t  type_k;
    int32_t  type_v;

    bool     logits_all;
    bool     embeddings;
    bool     offload_kqv;
    bool     flash_attn;
    bool     no_perf;

    void *   abort_callback;
    void *   abort_callback_data;
    uint8_t  _reserved[16];
};

// Evaluation batch
struct llama_batch {
    int32_t         n_tokens;
    llama_token  *  token;
    float        *  embd;
    llama_pos    *  pos;
    int32_t      *  n_seq_id;
    llama_seq_id ** seq_id;
    int8_t       *  logits;
};

// Sampler chain params
struct llama_sampler_chain_params {
    bool no_perf;
};

// Model initialization & management
struct llama_model_params llama_model_default_params(void);
struct llama_model *      llama_model_load_from_file(const char * path_model, struct llama_model_params params);
void                      llama_model_free(struct llama_model * model);
void                      llama_free_model(struct llama_model * model);

// Context initialization & management
struct llama_context_params llama_context_default_params(void);
struct llama_context *      llama_init_from_model(struct llama_model * model, struct llama_context_params params);
struct llama_context *      llama_new_context_with_model(struct llama_model * model, struct llama_context_params params);
void                        llama_free(struct llama_context * ctx);

// Vocabulary & Tokenization
const struct llama_vocab * llama_model_get_vocab(const struct llama_model * model);
int32_t                    llama_tokenize(const struct llama_vocab * vocab, const char * text, int32_t text_len, llama_token * tokens, int32_t n_tokens_max, bool add_special, bool parse_special);
int32_t                    llama_token_to_piece(const struct llama_vocab * vocab, llama_token token, char * buf, int32_t length, int32_t lstrip, bool special);
bool                       llama_vocab_is_eog(const struct llama_vocab * vocab, llama_token token);
bool                       llama_token_is_eog(const struct llama_model * model, llama_token token);
llama_token                llama_vocab_eos(const struct llama_vocab * vocab);
llama_token                llama_vocab_eot(const struct llama_vocab * vocab);
bool                       llama_vocab_is_control(const struct llama_vocab * vocab, llama_token token);
int32_t                    llama_vocab_n_tokens(const struct llama_vocab * vocab);
void                       llama_set_n_threads(struct llama_context * ctx, int32_t n_threads, int32_t n_threads_batch);
int32_t                    llama_n_threads(struct llama_context * ctx);

// Batch & Decoding
struct llama_batch llama_batch_init(int32_t n_tokens, int32_t embd, int32_t n_seq_max);
void               llama_batch_free(struct llama_batch batch);
int32_t            llama_decode(struct llama_context * ctx, struct llama_batch batch);
float *            llama_get_logits(struct llama_context * ctx);
float *            llama_get_logits_ith(struct llama_context * ctx, int32_t i);

// Sampler API
struct llama_sampler_chain_params llama_sampler_chain_default_params(void);
struct llama_sampler *            llama_sampler_chain_init(struct llama_sampler_chain_params params);
void                              llama_sampler_chain_add(struct llama_sampler * chain, struct llama_sampler * smpl);
struct llama_sampler *            llama_sampler_init_greedy(void);
struct llama_sampler *            llama_sampler_init_temp(float temp);
llama_token                       llama_sampler_sample(struct llama_sampler * smpl, struct llama_context * ctx, int32_t idx);
void                              llama_sampler_accept(struct llama_sampler * smpl, llama_token token);
void                              llama_sampler_free(struct llama_sampler * smpl);

// KV Cache / Memory control
void llama_memory_clear(struct llama_context * ctx, bool data);
bool llama_memory_seq_rm(struct llama_context * ctx, llama_seq_id seq_id, llama_pos p0, llama_pos p1);

// Telemetry & Diagnostics
int32_t      llama_model_desc(const struct llama_model * model, char * buf, size_t buf_size);
uint64_t     llama_model_size(const struct llama_model * model);
uint64_t     llama_model_n_params(const struct llama_model * model);
const char * llama_print_system_info(void);
int64_t      llama_time_us(void);

#ifdef __cplusplus
}
#endif

#endif // LLAMA_H
