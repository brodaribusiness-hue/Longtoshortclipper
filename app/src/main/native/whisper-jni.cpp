/**
 * JNI bridge between the Android app (Kotlin) and whisper.cpp v1.6.2.
 * Provides: model load/free + full offline transcription with token-level
 * timestamps (word timing) and per-token confidence.
 *
 * All processing is local: audio samples in -> transcript JSON out.
 */
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include "whisper.h"

namespace {

std::string toStdString(JNIEnv *env, jstring jstr) {
    if (jstr == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

void appendJsonEscaped(std::string &out, const char *text) {
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
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
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
Java_com_shortsclipper_ai_WhisperNative_initModel(JNIEnv *env, jobject thiz, jstring modelPath) {
    std::string path = toStdString(env, modelPath);
    if (path.empty()) return 0;

    struct whisper_context_params cparams = whisper_context_default_params();
    // CPU inference for maximum device compatibility in V1.
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_shortsclipper_ai_WhisperNative_freeModel(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(handle);
    whisper_free(ctx);
}

/**
 * Transcribes 16 kHz mono float PCM and returns a JSON transcript with
 * segment- and token-level timestamps, or nullptr on failure.
 *
 * JSON: {"language":"en","segments":[
 *   {"s":<ms>,"e":<ms>,"text":"...","tokens":[{"x":"..","s":<ms>,"e":<ms>,"p":0.93}]}
 * ]}
 */
JNIEXPORT jstring JNICALL
Java_com_shortsclipper_ai_WhisperNative_transcribe(JNIEnv *env, jobject thiz,
                                                   jlong handle,
                                                   jfloatArray samples,
                                                   jint threads,
                                                   jstring language) {
    if (handle == 0 || samples == nullptr) return nullptr;
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(handle);

    jsize n = env->GetArrayLength(samples);
    if (n <= 0) return nullptr;
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return nullptr;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    // Word-level timestamps (DTW based, built into whisper.cpp >= 1.5).
    params.token_timestamps = true;
    params.split_on_word = true;
    params.max_len = 0;
    params.no_context = true;

    std::string lang;
    if (language != nullptr) {
        lang = toStdString(env, language);
        if (!lang.empty()) {
            params.language = lang.c_str();
        }
    }

    int rc = whisper_full(ctx, params, data, static_cast<int>(n));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) {
        return nullptr;
    }

    std::string json = "{\"language\":\"";
    int langId = whisper_full_lang_id(ctx);
    const char *langStr = whisper_lang_str(langId);
    json += (langStr != nullptr ? langStr : "unknown");
    json += "\",\"segments\":[";

    int nSegments = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSegments; ++i) {
        int64_t s0 = whisper_full_get_segment_t0(ctx, i); // centiseconds
        int64_t s1 = whisper_full_get_segment_t1(ctx, i);
        if (i > 0) json += ",";
        json += "{\"s\":";
        json += std::to_string(s0 * 10);
        json += ",\"e\":";
        json += std::to_string(s1 * 10);
        json += ",\"text\":\"";
        appendJsonEscaped(json, whisper_full_get_segment_text(ctx, i));
        json += "\",\"tokens\":[";

        int nTokens = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < nTokens; ++j) {
            whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
            int64_t t0 = td.t0; // centiseconds when token_timestamps = true
            int64_t t1 = td.t1;
            if (j > 0) json += ",";
            json += "{\"x\":\"";
            appendJsonEscaped(json, whisper_token_to_str(ctx, td.id));
            json += "\",\"s\":";
            json += std::to_string(t0 * 10);
            json += ",\"e\":";
            json += std::to_string(t1 * 10);
            json += ",\"p\":";
            char pbuf[16];
            snprintf(pbuf, sizeof(pbuf), "%.3f", td.p);
            json += pbuf;
            json += "}";
        }
        json += "]}";
    }
    json += "]}";

    return env->NewStringUTF(json.c_str());
}

} // extern "C"
