package com.shortsclipper.ai

/**
 * JNI bindings to the bundled whisper.cpp build (src/main/native).
 * Loading is deferred behind an explicit availability check so an ABI/load
 * failure becomes a controlled transcription error rather than an app crash.
 */
object WhisperNative {

    private val nativeLoadError: Throwable? = runCatching {
        System.loadLibrary("whisper_jni")
    }.exceptionOrNull()

    val isAvailable: Boolean get() = nativeLoadError == null

    val unavailableReason: String?
        get() = nativeLoadError?.let { "Native transcription is unavailable on this device: ${it.message ?: it.javaClass.simpleName}" }

    private fun requireAvailable() {
        if (!isAvailable) throw IllegalStateException(unavailableReason ?: "Native transcription is unavailable")
    }

    /** Loads a validated GGML whisper model from disk. Returns native handle or 0 on failure. */
    fun initModel(modelPath: String): Long {
        requireAvailable()
        return nativeInitModel(modelPath)
    }

    fun freeModel(handle: Long) {
        if (handle != 0L && isAvailable) nativeFreeModel(handle)
    }

    /** Requests cooperative cancellation of the active whisper_full call for [handle]. */
    fun cancel(handle: Long) {
        if (handle != 0L && isAvailable) nativeCancel(handle)
    }

    /**
     * Transcribes 16 kHz mono float PCM.
     * Returns JSON {"language":..,"segments":[{"s":ms,"e":ms,"text":..,"tokens":[{x,s,e,p}]}]}
     * or null on native failure/cancellation.
     */
    fun transcribe(handle: Long, samples: FloatArray, threads: Int, language: String?): String? {
        requireAvailable()
        return nativeTranscribe(handle, samples, threads, language)
    }

    private external fun nativeInitModel(modelPath: String): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int, language: String?): String?
}
