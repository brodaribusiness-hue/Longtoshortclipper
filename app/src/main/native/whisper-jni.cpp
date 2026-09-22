/**
 * JNI bridge between the Android app and whisper.cpp v1.6.2.
 * All processing is local. Handles are registered and reference-counted while
 * JNI calls are in flight so cancel/free cannot race into use-after-free.
 */
#include <jni.h>
#include <atomic>
#include <condition_variable>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <unordered_set>
#include "whisper.h"

namespace {

struct NativeModel {
    whisper_context *context = nullptr;
    std::atomic_bool cancelRequested{false};
    std::mutex transcribeMutex;

    // lifecycleMutex is intentionally separate from transcribeMutex: cancel
    // only sets an atomic flag while free waits for an in-flight JNI user.
    std::mutex lifecycleMutex;
    std::condition_variable lifecycleChanged;
    bool closing = false;
    int inFlightCalls = 0;
};

std::mutex registeredHandlesMutex;
std::unordered_set<NativeModel *> registeredHandles;

std::string toStdString(JNIEnv *env, jstring jstr) {
    if (jstr == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

bool shouldAbort(void *userData) {
    auto *model = static_cast<NativeModel *>(userData);
    return model == nullptr || model->cancelRequested.load(std::memory_order_relaxed);
}

// Acquiring under the registry lock prevents nativeFreeModel from deleting a
// handle between the raw jlong conversion and the lifecycle increment.
NativeModel *acquireModel(jlong handle) {
    if (handle == 0) return nullptr;
    auto *model = reinterpret_cast<NativeModel *>(handle);
    std::lock_guard<std::mutex> handlesLock(registeredHandlesMutex);
    if (registeredHandles.find(model) == registeredHandles.end()) return nullptr;
    std::lock_guard<std::mutex> lifecycleLock(model->lifecycleMutex);
    if (model->closing || model->context == nullptr) return nullptr;
    ++model->inFlightCalls;
    return model;
}

void releaseModel(NativeModel *model) {
    if (model == nullptr) return;
    std::lock_guard<std::mutex> lifecycleLock(model->lifecycleMutex);
    --model->inFlightCalls;
    if (model->inFlightCalls == 0) model->lifecycleChanged.notify_all();
}

void appendJsonEscaped(std::string &out, const char *text) {
    if (text == nullptr) return;
    for (const char *p = text; *p != '\0'; ++p) {
        unsigned char c = static_cast<unsigned char>(*p);
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_shortsclipper_ai_WhisperNative_nativeInitModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring modelPath) {
    // Imported files are untrusted. Keep every C++ allocation/loader failure
    // on this side of the JNI boundary so Kotlin receives a normal 0 handle.
    whisper_context *context = nullptr;
    NativeModel *model = nullptr;
    try {
        const std::string path = toStdString(env, modelPath);
        if (path.empty()) return 0;

        whisper_context_params cparams = whisper_context_default_params();
        cparams.use_gpu = false;
        context = whisper_init_from_file_with_params(path.c_str(), cparams);
        if (context == nullptr) return 0;

        model = new (std::nothrow) NativeModel();
        if (model == nullptr) {
            whisper_free(context);
            return 0;
        }
        model->context = context;
        {
            std::lock_guard<std::mutex> handlesLock(registeredHandlesMutex);
            registeredHandles.insert(model);
        }
        return reinterpret_cast<jlong>(model);
    } catch (...) {
        if (model != nullptr) {
            if (model->context != nullptr) whisper_free(model->context);
            delete model;
        } else if (context != nullptr) {
            whisper_free(context);
        }
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_shortsclipper_ai_WhisperNative_nativeFreeModel(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return;
    auto *model = reinterpret_cast<NativeModel *>(handle);
    {
        std::lock_guard<std::mutex> handlesLock(registeredHandlesMutex);
        auto found = registeredHandles.find(model);
        if (found == registeredHandles.end()) return;
        registeredHandles.erase(found);
        std::lock_guard<std::mutex> lifecycleLock(model->lifecycleMutex);
        model->closing = true;
        model->cancelRequested.store(true, std::memory_order_relaxed);
    }

    {
        std::unique_lock<std::mutex> lifecycleLock(model->lifecycleMutex);
        model->lifecycleChanged.wait(lifecycleLock, [model] { return model->inFlightCalls == 0; });
    }
    // No in-flight caller can now use context, and no new caller can acquire
    // after registry removal.
    std::lock_guard<std::mutex> transcribeLock(model->transcribeMutex);
    if (model->context != nullptr) {
        whisper_free(model->context);
        model->context = nullptr;
    }
    delete model;
}

JNIEXPORT void JNICALL
Java_com_shortsclipper_ai_WhisperNative_nativeCancel(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return;
    auto *model = reinterpret_cast<NativeModel *>(handle);
    // The registry lock guarantees the pointer is live while the atomic write
    // happens; cancellation never waits for a long whisper_full invocation.
    std::lock_guard<std::mutex> handlesLock(registeredHandlesMutex);
    if (registeredHandles.find(model) != registeredHandles.end()) {
        model->cancelRequested.store(true, std::memory_order_relaxed);
    }
}

/**
 * Transcribes 16 kHz mono float PCM and returns segment/token timestamp JSON,
 * or null on cancellation/failure.
 */
JNIEXPORT jstring JNICALL
Java_com_shortsclipper_ai_WhisperNative_nativeTranscribe(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle,
        jfloatArray samples,
        jint threads,
        jstring language) {
    if (samples == nullptr) return nullptr;
    NativeModel *model = acquireModel(handle);
    if (model == nullptr) return nullptr;

    struct ModelReleaseGuard {
        NativeModel *model;
        ~ModelReleaseGuard() { releaseModel(model); }
    } releaseGuard{model};

    // Keep Java array ownership exception-safe. In particular, string/JSON
    // allocation can fail after Whisper returns, and no C++ exception may cross
    // a JNI boundary or leave an array pinned.
    jfloat *data = nullptr;
    const auto releaseSamples = [&]() {
        if (data != nullptr) {
            env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
            data = nullptr;
        }
    };

    try {
        const jsize sampleCount = env->GetArrayLength(samples);
        if (sampleCount <= 0) return nullptr;
        data = env->GetFloatArrayElements(samples, nullptr);
        if (data == nullptr) return nullptr;

        std::lock_guard<std::mutex> transcribeLock(model->transcribeMutex);
        // Do not clear cancelRequested here. The Kotlin engine uses each handle for
        // one analysis run; clearing it would lose a cancellation that arrived in
        // the tiny interval just before this method acquired the mutex.
        if (model->cancelRequested.load(std::memory_order_relaxed) || model->context == nullptr) {
            releaseSamples();
            return nullptr;
        }

        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = threads > 0 ? threads : 4;
        params.print_progress = false;
        params.print_special = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.token_timestamps = true;
        params.split_on_word = true;
        params.max_len = 0;
        params.no_context = true;
        params.abort_callback = shouldAbort;
        params.abort_callback_user_data = model;

        const std::string requestedLanguage = toStdString(env, language);
        if (!requestedLanguage.empty() && requestedLanguage != "auto") {
            params.language = requestedLanguage.c_str();
            params.detect_language = false;
        }

        const int result = whisper_full(model->context, params, data, static_cast<int>(sampleCount));
        releaseSamples();
        if (result != 0 || model->cancelRequested.load(std::memory_order_relaxed)) return nullptr;

        std::string json = "{\"language\":\"";
        const int languageId = whisper_full_lang_id(model->context);
        const char *languageName = whisper_lang_str(languageId);
        appendJsonEscaped(json, languageName != nullptr ? languageName : "unknown");
        json += "\",\"segments\":[";

        const int segmentCount = whisper_full_n_segments(model->context);
        for (int i = 0; i < segmentCount; ++i) {
            const int64_t startCs = whisper_full_get_segment_t0(model->context, i);
            const int64_t endCs = whisper_full_get_segment_t1(model->context, i);
            if (i > 0) json += ",";
            json += "{\"s\":";
            json += std::to_string(startCs * 10);
            json += ",\"e\":";
            json += std::to_string(endCs * 10);
            json += ",\"text\":\"";
            appendJsonEscaped(json, whisper_full_get_segment_text(model->context, i));
            json += "\",\"tokens\":[";

            const int tokenCount = whisper_full_n_tokens(model->context, i);
            for (int j = 0; j < tokenCount; ++j) {
                const whisper_token_data token = whisper_full_get_token_data(model->context, i, j);
                if (j > 0) json += ",";
                json += "{\"x\":\"";
                appendJsonEscaped(json, whisper_token_to_str(model->context, token.id));
                json += "\",\"s\":";
                json += std::to_string(static_cast<int64_t>(token.t0) * 10);
                json += ",\"e\":";
                json += std::to_string(static_cast<int64_t>(token.t1) * 10);
                json += ",\"p\":";
                char probability[24];
                std::snprintf(probability, sizeof(probability), "%.4f", token.p);
                json += probability;
                json += "}";
            }
            json += "]}";
        }
        json += "]}";
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        releaseSamples();
        return nullptr;
    }
}

} // extern "C"
