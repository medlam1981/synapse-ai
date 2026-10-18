#include "medlam_inference_engine.h"

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <sched.h>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>
#include <fstream>
#include <sstream>
#include <vector>
#include <cstdint>
#include <string>
#include <memory>
#include <atomic>
#include <dlfcn.h>

#define TAG "MedlamJNI"
#define LOGI(...) do { } while (0)
#define LOGE(...) do { } while (0)

static std::atomic<bool> g_is_aborted{false};

static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

#include <vulkan/vulkan.h>

static bool is_vulkan_compute_supported_hardware() {
    static int cached_result = -1;
    if (cached_result != -1) {
        return cached_result == 1;
    }

    void* h_vk = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!h_vk) {
        cached_result = 0;
        return false;
    }

    typedef VkResult (*pfn_vkCreateInstance)(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);
    typedef void (*pfn_vkDestroyInstance)(VkInstance, const VkAllocationCallbacks*);
    typedef VkResult (*pfn_vkEnumeratePhysicalDevices)(VkInstance, uint32_t*, VkPhysicalDevice*);
    typedef void (*pfn_vkGetPhysicalDeviceFeatures2)(VkPhysicalDevice, VkPhysicalDeviceFeatures2*);

    pfn_vkCreateInstance fn_create = (pfn_vkCreateInstance) dlsym(h_vk, "vkCreateInstance");
    pfn_vkDestroyInstance fn_destroy = (pfn_vkDestroyInstance) dlsym(h_vk, "vkDestroyInstance");
    pfn_vkEnumeratePhysicalDevices fn_enum = (pfn_vkEnumeratePhysicalDevices) dlsym(h_vk, "vkEnumeratePhysicalDevices");
    pfn_vkGetPhysicalDeviceFeatures2 fn_feat2 = (pfn_vkGetPhysicalDeviceFeatures2) dlsym(h_vk, "vkGetPhysicalDeviceFeatures2");

    if (!fn_create || !fn_destroy || !fn_enum) {
        dlclose(h_vk);
        cached_result = 0;
        return false;
    }

    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "SynapseAI";
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    if (fn_create(&createInfo, nullptr, &instance) != VK_SUCCESS || !instance) {
        dlclose(h_vk);
        cached_result = 0;
        return false;
    }

    uint32_t dev_count = 0;
    fn_enum(instance, &dev_count, nullptr);
    if (dev_count == 0) {
        fn_destroy(instance, nullptr);
        dlclose(h_vk);
        cached_result = 0;
        return false;
    }

    std::vector<VkPhysicalDevice> devices(dev_count);
    fn_enum(instance, &dev_count, devices.data());

    bool supported = false;
    if (fn_feat2) {
        for (uint32_t i = 0; i < dev_count; ++i) {
            VkPhysicalDevice16BitStorageFeatures bit16 = {};
            bit16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
            VkPhysicalDeviceFeatures2 feat2 = {};
            feat2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            feat2.pNext = &bit16;
            fn_feat2(devices[i], &feat2);

            if (bit16.storageBuffer16BitAccess) {
                supported = true;
                break;
            }
        }
    }

    fn_destroy(instance, nullptr);
    dlclose(h_vk);
    cached_result = supported ? 1 : 0;
    LOGI("Hardware Vulkan Compute Evaluation: 16-bit storage supported = %s", supported ? "YES (GPU Offloading Ready)" : "NO (CPU NEON Fallback)");
    return supported;
}

typedef void* ggml_backend_reg_t;
typedef void (*fn_ggml_backend_register)(ggml_backend_reg_t);
typedef ggml_backend_reg_t (*fn_ggml_backend_vk_reg)();

static bool register_vulkan_backend_internal() {
    if (!is_vulkan_compute_supported_hardware()) {
        LOGI("ℹ️ [Vulkan GPU] Vulkan GPU lacks required 16-bit compute storage. Skipping Vulkan registration to keep engine on ultra-fast CPU.");
        return false;
    }
    fn_ggml_backend_register fn_reg = (fn_ggml_backend_register) dlsym(RTLD_DEFAULT, "ggml_backend_register");
    fn_ggml_backend_vk_reg fn_vk = (fn_ggml_backend_vk_reg) dlsym(RTLD_DEFAULT, "ggml_backend_vk_reg");
    if (!fn_vk) {
        void* h_vk = dlopen("libggml-vulkan.so", RTLD_NOW | RTLD_GLOBAL);
        if (h_vk) {
            fn_vk = (fn_ggml_backend_vk_reg) dlsym(h_vk, "ggml_backend_vk_reg");
        }
    }
    if (fn_reg && fn_vk) {
        ggml_backend_reg_t reg = fn_vk();
        if (reg) {
            fn_reg(reg);
            LOGI("⚡ [Vulkan GPU] Successfully registered ggml-vulkan backend into GGML runtime!");
            return true;
        }
    }
    LOGI("ℹ️ [Vulkan GPU] Vulkan backend not active or not present");
    return false;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    LOGI("JNI_OnLoad: Configuring OpenMP passive wait policy and thread limit to prevent spinlock contention");
    setenv("OMP_WAIT_POLICY", "PASSIVE", 1);
    setenv("KMP_BLOCKTIME", "0", 1);
    setenv("OMP_NUM_THREADS", "4", 1);

    typedef void (*kmp_set_blocktime_t)(int);
    kmp_set_blocktime_t fn_kmp = (kmp_set_blocktime_t) dlsym(RTLD_DEFAULT, "kmp_set_blocktime");
    if (fn_kmp) {
        fn_kmp(0);
        LOGI("OpenMP: kmp_set_blocktime(0) initialized successfully");
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaServerJNI_registerVulkanBackend(JNIEnv* /* env */, jclass /* clazz */) {
    return register_vulkan_backend_internal() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaServerJNI_isVulkanSupported(JNIEnv* /* env */, jclass /* clazz */) {
    return is_vulkan_compute_supported_hardware() ? JNI_TRUE : JNI_FALSE;
}

void pin_to_big_cores() {
    medlam::pin_to_big_cores();
}

/**
 * Native: init_engine(model_path, draft_model_path, thread_count)
 */
JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaNativeEngine_init_1engine(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jstring draft_model_path,
    jint thread_count
) {
    medlam::EngineConfig config;
    config.model_path = jstring_to_string(env, model_path);
    config.draft_model_path = jstring_to_string(env, draft_model_path);
    config.thread_count = (thread_count >= 1 && thread_count <= 32) ? thread_count : 4;
    config.use_mmap = true;
    config.context_size = 4096; // Standard 4096 context window
    config.batch_size = 512;
    config.ubatch_size = 256;
    config.speculative_k = 3;
    config.draft_use_mmap = true; // Zero forced RAM default
    config.draft_use_mlock = false;

    LOGI("init_engine called: model=%s, draft=%s, threads=%d, ctx=%d, mmap=%d",
         config.model_path.c_str(), config.draft_model_path.c_str(), config.thread_count, config.context_size, config.use_mmap);

    // Pin calling thread to Kryo Gold BIG cores right before initializing llama context
    medlam::pin_to_big_cores();
    bool ok = medlam::MedlamInferenceEngine::instance().init(config);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// CamelCase alias for init_engine with extended controls
JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaNativeEngine_initEngine(
    JNIEnv* env,
    jclass clazz,
    jstring model_path,
    jstring draft_model_path,
    jint thread_count,
    jint gpu_layers,
    jint context_size,
    jboolean use_mmap
) {
    medlam::EngineConfig config;
    config.model_path = jstring_to_string(env, model_path);
    config.draft_model_path = jstring_to_string(env, draft_model_path);
    config.thread_count = 4; // Restrict strictly to 4 performance threads
    config.gpu_layers = gpu_layers;
    config.context_size = (context_size > 0 && context_size <= 8192) ? context_size : 4096;
    config.batch_size = 512;
    config.ubatch_size = 256;
    config.use_mmap = (use_mmap == JNI_TRUE);
    config.speculative_k = 3;
    config.draft_use_mmap = true;
    config.draft_use_mlock = false; // Zero forced RAM: mmap-first by default

    // Pin calling thread to Kryo Gold BIG cores right before initializing llama context
    medlam::pin_to_big_cores();
    bool ok = medlam::MedlamInferenceEngine::instance().init(config);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Native: generate_stream(prompt, callback_handler)
 * callback_handler must implement:
 *   boolean onToken(String piece)
 *   void onComplete(int tokensGenerated, double tokensPerSecond)
 *   void onError(String message)
 */
JNIEXPORT jint JNICALL
Java_com_synapse_localai_LlamaNativeEngine_generate_1stream(
    JNIEnv* env,
    jclass /* clazz */,
    jstring prompt,
    jobject callback_handler
) {
    if (!prompt || !callback_handler) {
        LOGE("generate_stream invalid arguments");
        return -1;
    }

    g_is_aborted.store(false, std::memory_order_release);
    std::string prompt_str = jstring_to_string(env, prompt);

    jclass cb_class = env->GetObjectClass(callback_handler);
    jmethodID on_token_mid = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)Z");
    jmethodID on_complete_mid = env->GetMethodID(cb_class, "onComplete", "(ID)V");
    jmethodID on_error_mid = env->GetMethodID(cb_class, "onError", "(Ljava/lang/String;)V");

    if (!on_token_mid) {
        LOGE("Callback missing boolean onToken(String) method");
        return -1;
    }

    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);

    jobject global_cb = env->NewGlobalRef(callback_handler);

    auto token_fn = [jvm, global_cb, on_token_mid](const std::string& piece) -> bool {
        if (g_is_aborted.load(std::memory_order_acquire)) {
            return false;
        }

        thread_local static JNIEnv* tls_env = nullptr;
        if (!tls_env) {
            int get_env_res = jvm->GetEnv(reinterpret_cast<void**>(&tls_env), JNI_VERSION_1_6);
            if (get_env_res == JNI_EDETACHED) {
                if (jvm->AttachCurrentThread(&tls_env, nullptr) != JNI_OK) {
                    tls_env = nullptr;
                    return false;
                }
            }
        }

        if (!tls_env) return false;

        jstring jpiece = tls_env->NewStringUTF(piece.c_str());
        jboolean cont = tls_env->CallBooleanMethod(global_cb, on_token_mid, jpiece);
        tls_env->DeleteLocalRef(jpiece);

        return (cont == JNI_TRUE);
    };

    auto complete_fn = [jvm, global_cb, on_complete_mid](int tokens, double tps) {
        if (!on_complete_mid) return;
        JNIEnv* current_env = nullptr;
        bool attached = false;
        int get_env_res = jvm->GetEnv(reinterpret_cast<void**>(&current_env), JNI_VERSION_1_6);

        if (get_env_res == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&current_env, nullptr) != JNI_OK) {
                return;
            }
            attached = true;
        }

        current_env->CallVoidMethod(global_cb, on_complete_mid, static_cast<jint>(tokens), static_cast<jdouble>(tps));

        if (attached) {
            jvm->DetachCurrentThread();
        }
    };

    auto error_fn = [jvm, global_cb, on_error_mid](const std::string& err) {
        if (!on_error_mid) return;
        JNIEnv* current_env = nullptr;
        bool attached = false;
        int get_env_res = jvm->GetEnv(reinterpret_cast<void**>(&current_env), JNI_VERSION_1_6);

        if (get_env_res == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&current_env, nullptr) != JNI_OK) {
                return;
            }
            attached = true;
        }

        jstring jerr = current_env->NewStringUTF(err.c_str());
        current_env->CallVoidMethod(global_cb, on_error_mid, jerr);
        current_env->DeleteLocalRef(jerr);

        if (attached) {
            jvm->DetachCurrentThread();
        }
    };

    // Pin calling thread to Kryo Gold BIG cores right before starting main inference loop
    medlam::pin_to_big_cores();

    int tokens_generated = medlam::MedlamInferenceEngine::instance().generate_stream(
        prompt_str,
        token_fn,
        complete_fn,
        error_fn
    );

    env->DeleteGlobalRef(global_cb);
    return static_cast<jint>(tokens_generated);
}

// CamelCase alias for generate_stream
JNIEXPORT jint JNICALL
Java_com_synapse_localai_LlamaNativeEngine_generateStream(
    JNIEnv* env,
    jclass clazz,
    jstring prompt,
    jobject callback_handler
) {
    return Java_com_synapse_localai_LlamaNativeEngine_generate_1stream(env, clazz, prompt, callback_handler);
}

/**
 * Native: stop_generation()
 */
JNIEXPORT void JNICALL
Java_com_synapse_localai_LlamaNativeEngine_stop_1generation(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    g_is_aborted.store(true, std::memory_order_release);
    medlam::MedlamInferenceEngine::instance().stop();
}

JNIEXPORT void JNICALL
Java_com_synapse_localai_LlamaNativeEngine_stopGeneration(
    JNIEnv* env,
    jclass clazz
) {
    Java_com_synapse_localai_LlamaNativeEngine_stop_1generation(env, clazz);
}

/**
 * Native instant cancellation interface:
 * Immediately sets atomic abort flag and breaks inference loops.
 */
JNIEXPORT void JNICALL
Java_com_synapse_localai_LlamaNativeEngine_abort(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    LOGI("LlamaNativeEngine: Native abort signaled");
    g_is_aborted.store(true, std::memory_order_release);
    medlam::MedlamInferenceEngine::instance().stop();
}

JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaNativeEngine_isAborted(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    return g_is_aborted.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_synapse_localai_LlamaNativeEngine_resetAbort(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    g_is_aborted.store(false, std::memory_order_release);
}

/**
 * Native: free_engine()
 */
JNIEXPORT void JNICALL
Java_com_synapse_localai_LlamaNativeEngine_free_1engine(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    medlam::MedlamInferenceEngine::instance().free();
}

JNIEXPORT void JNICALL
Java_com_synapse_localai_LlamaNativeEngine_freeEngine(
    JNIEnv* env,
    jclass clazz
) {
    Java_com_synapse_localai_LlamaNativeEngine_free_1engine(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_synapse_localai_LlamaNativeEngine_clearKvCache(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    medlam::MedlamInferenceEngine::instance().clear_kv_cache();
}

/**
 * Native: get_model_info()
 */
JNIEXPORT jstring JNICALL
Java_com_synapse_localai_LlamaNativeEngine_getModelInfo(
    JNIEnv* env,
    jclass /* clazz */
) {
    std::string info = medlam::MedlamInferenceEngine::instance().get_model_info();
    return env->NewStringUTF(info.c_str());
}

/**
 * Native: is_initialized()
 */
JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaNativeEngine_isInitialized(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    return medlam::MedlamInferenceEngine::instance().is_initialized() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Native: bindToPerformanceCores()
 * Binds calling thread to Kryo Gold performance cores (Cores 4-7, mask 0xF0)
 */
JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaNativeEngine_bindToPerformanceCores(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    medlam::pin_to_big_cores();
    return medlam::MedlamInferenceEngine::instance().setup_thread_affinity() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaNativeEngine_pinToBigCores(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    medlam::pin_to_big_cores();
    return JNI_TRUE;
}

#include <dirent.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

struct llama_chat_message {
    const char * role;
    const char * content;
};

extern "C" int32_t llama_chat_apply_template(
    const char * tmpl,
    const struct llama_chat_message * chat,
    size_t n_msg,
    bool add_ass,
    char * buf,
    int32_t length
);

static bool is_system_thread(const char* comm) {
    if (!comm || comm[0] == '\0') return false;
    return (strstr(comm, "Signal") != nullptr ||
            strstr(comm, "Jit") != nullptr ||
            strstr(comm, "Heap") != nullptr ||
            strstr(comm, "Reference") != nullptr ||
            strstr(comm, "Finalizer") != nullptr ||
            strstr(comm, "binder") != nullptr ||
            strstr(comm, "Profile") != nullptr ||
            strstr(comm, "Render") != nullptr ||
            strstr(comm, "hwui") != nullptr ||
            strstr(comm, "perfetto") != nullptr);
}

static cpu_set_t get_dynamic_performance_cpuset() {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int total_cores = sysconf(_SC_NPROCESSORS_CONF);
    if (total_cores <= 0) total_cores = 8;

    std::vector<int> capacities(total_cores, 0);
    int max_capacity = 0;

    // 1. Linux Kernel EAS (Energy Aware Scheduling) cpu_capacity: 1024 = Big / Prime cores
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
        // Cores with capacity >= 70% of max_capacity are Big / Super / Prime cores
        int cap_threshold = (int)(max_capacity * 0.70);
        for (int i = 0; i < total_cores; ++i) {
            if (capacities[i] >= cap_threshold) {
                CPU_SET(i, &cpuset);
                perf_core_count++;
            }
        }
    }

    // 2. Fallback: Query max CPU frequencies
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

    // 3. Fallback: Upper-half cores for big.LITTLE or all cores
    if (perf_core_count == 0) {
        if (total_cores <= 4) {
            for (int i = 0; i < total_cores; ++i) { CPU_SET(i, &cpuset); perf_core_count++; }
        } else {
            int start_core = (total_cores >= 8) ? 4 : (total_cores / 2);
            for (int i = start_core; i < total_cores; ++i) { CPU_SET(i, &cpuset); perf_core_count++; }
        }
    }

    LOGI("Dynamic EAS CPU Topology: Identified %d Performance/Prime Big cores out of %d total cores",
         perf_core_count, total_cores);
    return cpuset;
}

static bool bind_process_threads_to_perf_cores() {
    DIR* dir = opendir("/proc/self/task");
    if (!dir) {
        LOGE("Failed to open /proc/self/task for thread affinity binding");
        return false;
    }

    cpu_set_t cpuset = get_dynamic_performance_cpuset();

    pid_t main_pid = getpid();
    struct dirent* entry;
    int success_count = 0;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        pid_t tid = atoi(entry->d_name);
        if (tid > 0) {
            char comm_path[64];
            snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm", tid);
            FILE* fcomm = fopen(comm_path, "r");
            char comm[32] = {0};
            if (fcomm) {
                if (fgets(comm, sizeof(comm), fcomm)) {
                    size_t len = strlen(comm);
                    if (len > 0 && comm[len-1] == '\n') comm[len-1] = '\0';
                }
                fclose(fcomm);
            }

            // Exclude background system runtime daemons; bind all compute & app threads to Big cores
            if (!is_system_thread(comm)) {
                if (sched_setaffinity(tid, sizeof(cpuset), &cpuset) == 0) {
                    success_count++;
                    setpriority(PRIO_PROCESS, tid, -10);
                }
            }
        }
    }
    closedir(dir);
    LOGI("Hardware Pinning: Bound %d compute/worker threads strictly to performance Big cores", success_count);
    return success_count > 0;
}

JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaServerJNI_bindInferenceThreadsToPerformanceCores(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    medlam::pin_to_big_cores();
    return bind_process_threads_to_perf_cores() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_synapse_localai_LlamaNativeEngine_bindAllThreadsToPerformanceCores(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    medlam::pin_to_big_cores();
    return bind_process_threads_to_perf_cores() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_synapse_localai_LlamaNativeEngine_applyChatTemplateNative(
    JNIEnv* env,
    jclass /* clazz */,
    jstring j_tmpl,
    jobjectArray j_roles,
    jobjectArray j_contents,
    jboolean add_assistant_prompt
) {
    if (!j_roles || !j_contents) return env->NewStringUTF("");

    jsize n_msg = env->GetArrayLength(j_roles);
    if (n_msg == 0 || n_msg != env->GetArrayLength(j_contents)) {
        return env->NewStringUTF("");
    }

    std::string tmpl_str = j_tmpl ? jstring_to_string(env, j_tmpl) : "";
    const char* tmpl_cstr = tmpl_str.empty() ? nullptr : tmpl_str.c_str();

    std::vector<std::string> roles_storage(n_msg);
    std::vector<std::string> contents_storage(n_msg);
    std::vector<llama_chat_message> chat_msgs(n_msg);

    for (jsize i = 0; i < n_msg; ++i) {
        jstring r = (jstring) env->GetObjectArrayElement(j_roles, i);
        jstring c = (jstring) env->GetObjectArrayElement(j_contents, i);
        roles_storage[i] = jstring_to_string(env, r);
        contents_storage[i] = jstring_to_string(env, c);
        chat_msgs[i].role = roles_storage[i].c_str();
        chat_msgs[i].content = contents_storage[i].c_str();
        if (r) env->DeleteLocalRef(r);
        if (c) env->DeleteLocalRef(c);
    }

    // Allocate buffer for formatted template output
    int32_t buf_len = 32768;
    std::vector<char> buf(buf_len);
    int32_t res = llama_chat_apply_template(
        tmpl_cstr,
        chat_msgs.data(),
        static_cast<size_t>(n_msg),
        add_assistant_prompt == JNI_TRUE,
        buf.data(),
        buf_len
    );

    if (res > buf_len) {
        buf_len = res + 256;
        buf.resize(buf_len);
        res = llama_chat_apply_template(
            tmpl_cstr,
            chat_msgs.data(),
            static_cast<size_t>(n_msg),
            add_assistant_prompt == JNI_TRUE,
            buf.data(),
            buf_len
        );
    }

    if (res < 0) {
        LOGE("llama_chat_apply_template failed with code %d", res);
        return env->NewStringUTF("");
    }

    return env->NewStringUTF(buf.data());
}

// C++ Float to PCM_16BIT Conversion (Critical Vocoder Normalization)
JNIEXPORT jbyteArray JNICALL
Java_com_synapse_localai_audio_AudioNormalizerJNI_convertFloatToPcm16(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray j_float_array
) {
    if (!j_float_array) return nullptr;
    jsize num_samples = env->GetArrayLength(j_float_array);
    if (num_samples <= 0) return nullptr;

    jfloat* onnx_audio_output = env->GetFloatArrayElements(j_float_array, nullptr);
    if (!onnx_audio_output) return nullptr;

    std::vector<int16_t> pcm_buffer(num_samples);
    for (int i = 0; i < num_samples; i++) {
        float sample = onnx_audio_output[i];
        
        // 1. Hard clip to prevent overflow/crackle
        if (sample > 1.0f) sample = 1.0f;
        if (sample < -1.0f) sample = -1.0f;
        
        // 2. Scale to 16-bit PCM integer range
        pcm_buffer[i] = static_cast<int16_t>(sample * 32767.0f);
    }

    jbyteArray j_byte_array = env->NewByteArray(num_samples * 2);
    if (j_byte_array) {
        env->SetByteArrayRegion(j_byte_array, 0, num_samples * 2, reinterpret_cast<const jbyte*>(pcm_buffer.data()));
    }

    env->ReleaseFloatArrayElements(j_float_array, onnx_audio_output, JNI_ABORT);
    return j_byte_array;
}

JNIEXPORT jshortArray JNICALL
Java_com_synapse_localai_audio_AudioNormalizerJNI_convertFloatToPcmShorts(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray j_float_array
) {
    if (!j_float_array) return nullptr;
    jsize num_samples = env->GetArrayLength(j_float_array);
    if (num_samples <= 0) return nullptr;

    jfloat* onnx_audio_output = env->GetFloatArrayElements(j_float_array, nullptr);
    if (!onnx_audio_output) return nullptr;

    std::vector<int16_t> pcm_buffer(num_samples);
    for (int i = 0; i < num_samples; i++) {
        float sample = onnx_audio_output[i];
        if (sample > 1.0f) sample = 1.0f;
        if (sample < -1.0f) sample = -1.0f;
        pcm_buffer[i] = static_cast<int16_t>(sample * 32767.0f);
    }

    jshortArray j_short_array = env->NewShortArray(num_samples);
    if (j_short_array) {
        env->SetShortArrayRegion(j_short_array, 0, num_samples, reinterpret_cast<const jshort*>(pcm_buffer.data()));
    }

    env->ReleaseFloatArrayElements(j_float_array, onnx_audio_output, JNI_ABORT);
    return j_short_array;
}

// ── PageCacheAdvisor: Kernel Page Management & Zero-Forced-RAM Execution ──────────

JNIEXPORT jlong JNICALL
Java_com_synapse_localai_PageCacheAdvisor_nativeGetPageSize(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    long sz = sysconf(_SC_PAGESIZE);
    return static_cast<jlong>(sz > 0 ? sz : 4096);
}

JNIEXPORT jlong JNICALL
Java_com_synapse_localai_PageCacheAdvisor_nativeAdviseModelMemory(
    JNIEnv* env,
    jclass /* clazz */,
    jstring file_path_pattern,
    jint advice_code
) {
    std::string pattern = "";
    if (file_path_pattern) {
        const char* chars = env->GetStringUTFChars(file_path_pattern, nullptr);
        if (chars) {
            pattern = chars;
            env->ReleaseStringUTFChars(file_path_pattern, chars);
        }
    }

    int advice = MADV_NORMAL;
    switch (advice_code) {
        case 1: advice = MADV_SEQUENTIAL; break; // Safeguard: Sequential access instead of kernel-blocking WILLNEED
        case 2: advice = MADV_DONTNEED; break;
        case 3: advice = MADV_SEQUENTIAL; break;
        default: advice = MADV_NORMAL; break;
    }

    uintptr_t page_size = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    if (page_size == 0) page_size = 4096;

    std::ifstream maps_file("/proc/self/maps");
    if (!maps_file.is_open()) {
        LOGE("PageCacheAdvisor: Could not open /proc/self/maps");
        return 0;
    }

    std::string line;
    uint64_t total_advised_bytes = 0;

    while (std::getline(maps_file, line)) {
        if (line.empty()) continue;

        bool matches = false;
        if (!pattern.empty()) {
            if (line.find(pattern) != std::string::npos) {
                matches = true;
            }
        } else {
            if (line.find(".gguf") != std::string::npos ||
                line.find(".onnx") != std::string::npos ||
                line.find(".tflite") != std::string::npos ||
                line.find(".bin") != std::string::npos) {
                matches = true;
            }
        }

        if (!matches) continue;

        size_t dash_pos = line.find('-');
        if (dash_pos == std::string::npos) continue;

        size_t space_pos = line.find(' ', dash_pos);
        if (space_pos == std::string::npos) continue;

        std::string start_str = line.substr(0, dash_pos);
        std::string end_str = line.substr(dash_pos + 1, space_pos - dash_pos - 1);

        uintptr_t start_addr = 0;
        uintptr_t end_addr = 0;
        try {
            start_addr = std::stoull(start_str, nullptr, 16);
            end_addr = std::stoull(end_str, nullptr, 16);
        } catch (...) {
            continue;
        }

        if (end_addr <= start_addr) continue;

        // FUSE / Storage mitigation: strictly align to system page boundaries (4KB or 16KB)
        uintptr_t aligned_start = start_addr & ~(page_size - 1);
        uintptr_t aligned_end = (end_addr + page_size - 1) & ~(page_size - 1);
        size_t length = aligned_end - aligned_start;

        int res = madvise(reinterpret_cast<void*>(aligned_start), length, advice);
        if (res == 0) {
            total_advised_bytes += length;
        }
    }

    LOGI("PageCacheAdvisor: Applied advice=%d to %llu MB of mapped pages (pattern='%s')",
         advice, static_cast<unsigned long long>(total_advised_bytes / (1024 * 1024)), pattern.c_str());

    return static_cast<jlong>(total_advised_bytes);
}

JNIEXPORT jlong JNICALL
Java_com_synapse_localai_PageCacheAdvisor_nativeGetResidentMemoryMb(
    JNIEnv* env,
    jclass /* clazz */,
    jstring file_path_pattern
) {
    std::string pattern = "";
    if (file_path_pattern) {
        const char* chars = env->GetStringUTFChars(file_path_pattern, nullptr);
        if (chars) {
            pattern = chars;
            env->ReleaseStringUTFChars(file_path_pattern, chars);
        }
    }

    uintptr_t page_size = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    if (page_size == 0) page_size = 4096;

    std::ifstream maps_file("/proc/self/maps");
    if (!maps_file.is_open()) return 0;

    std::string line;
    uint64_t resident_pages = 0;

    while (std::getline(maps_file, line)) {
        if (line.empty()) continue;

        bool matches = false;
        if (!pattern.empty()) {
            if (line.find(pattern) != std::string::npos) matches = true;
        } else {
            if (line.find(".gguf") != std::string::npos ||
                line.find(".onnx") != std::string::npos) {
                matches = true;
            }
        }
        if (!matches) continue;

        size_t dash_pos = line.find('-');
        if (dash_pos == std::string::npos) continue;
        size_t space_pos = line.find(' ', dash_pos);
        if (space_pos == std::string::npos) continue;

        uintptr_t start_addr = 0;
        uintptr_t end_addr = 0;
        try {
            start_addr = std::stoull(line.substr(0, dash_pos), nullptr, 16);
            end_addr = std::stoull(line.substr(dash_pos + 1, space_pos - dash_pos - 1), nullptr, 16);
        } catch (...) {
            continue;
        }

        if (end_addr <= start_addr) continue;

        uintptr_t aligned_start = start_addr & ~(page_size - 1);
        uintptr_t aligned_end = (end_addr + page_size - 1) & ~(page_size - 1);
        size_t length = aligned_end - aligned_start;
        size_t num_pages = length / page_size;

        std::vector<unsigned char> mincore_vec(num_pages);
        if (mincore(reinterpret_cast<void*>(aligned_start), length, mincore_vec.data()) == 0) {
            for (size_t i = 0; i < num_pages; ++i) {
                if (mincore_vec[i] & 1) {
                    resident_pages++;
                }
            }
        }
    }

    uint64_t resident_bytes = resident_pages * page_size;
    return static_cast<jlong>(resident_bytes / (1024 * 1024));
}

} // extern "C"

