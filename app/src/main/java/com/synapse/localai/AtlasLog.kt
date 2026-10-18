package com.synapse.localai

object AtlasLog {
    @JvmStatic fun d(tag: String, message: String): Int = 0
    @JvmStatic fun d(tag: String, message: String, error: Throwable): Int = 0
    @JvmStatic fun i(tag: String, message: String): Int = 0
    @JvmStatic fun i(tag: String, message: String, error: Throwable): Int = 0
    @JvmStatic fun w(tag: String, message: String): Int = 0
    @JvmStatic fun w(tag: String, message: String, error: Throwable): Int = 0
    @JvmStatic fun e(tag: String, message: String): Int = 0
    @JvmStatic fun e(tag: String, message: String, error: Throwable): Int = 0
}
