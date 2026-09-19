package com.shortsclipper.ai

/**
 * JNI bindings to the bundled whisper.cpp build (src/main/native).
 * All speech-to-text runs locally; nothing leaves the device.
 */
object WhisperNative {

    init {
        System.loadLibrary("whisper_jni")
    }

    /** Loads a ggml whisper model from disk. Returns native handle or 0 on failure. */
    external fun initModel(modelPath: String): Long

    external fun freeModel(handle: Long)

    /**
     * Transcribes 16 kHz mono float PCM.
     * Returns JSON {"language":..,"segments":[{"s":ms,"e":ms,"text":..,"tokens":[{x,s,e,p}]}]}
     * or null on failure.
     */
    external fun transcribe(handle: Long, samples: FloatArray, threads: Int, language: String?): String?
}
