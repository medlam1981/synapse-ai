#include "medlam_inference_engine.h"

#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>

#define TAG "MedlamJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

extern "C" {

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
    config.thread_count = (thread_count > 0) ? thread_count : 4;
    config.use_mmap = true;
    config.draft_use_mmap = true;
    config.context_size = 4096;
    config.speculative_k = 4;

    LOGI("init_engine called: model=%s, draft=%s, threads=%d",
         config.model_path.c_str(), config.draft_model_path.c_str(), config.thread_count);

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
    config.thread_count = (thread_count > 0) ? thread_count : 4;
    config.gpu_layers = gpu_layers;
    config.context_size = (context_size > 0) ? context_size : 4096;
    config.use_mmap = (use_mmap == JNI_TRUE);
    config.draft_use_mmap = true;
    config.speculative_k = 4;

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
        JNIEnv* current_env = nullptr;
        bool attached = false;
        int get_env_res = jvm->GetEnv(reinterpret_cast<void**>(&current_env), JNI_VERSION_1_6);

        if (get_env_res == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(reinterpret_cast<void**>(&current_env), nullptr) != JNI_OK) {
                return false;
            }
            attached = true;
        }

        jstring jpiece = current_env->NewStringUTF(piece.c_str());
        jboolean cont = current_env->CallBooleanMethod(global_cb, on_token_mid, jpiece);
        current_env->DeleteLocalRef(jpiece);

        if (attached) {
            jvm->DetachCurrentThread();
        }
        return (cont == JNI_TRUE);
    };

    auto complete_fn = [jvm, global_cb, on_complete_mid](int tokens, double tps) {
        if (!on_complete_mid) return;
        JNIEnv* current_env = nullptr;
        bool attached = false;
        int get_env_res = jvm->GetEnv(reinterpret_cast<void**>(&current_env), JNI_VERSION_1_6);

        if (get_env_res == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(reinterpret_cast<void**>(&current_env), nullptr) != JNI_OK) {
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
            if (jvm->AttachCurrentThread(reinterpret_cast<void**>(&current_env), nullptr) != JNI_OK) {
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

} // extern "C"
