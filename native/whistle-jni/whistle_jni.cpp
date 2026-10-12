// JNI surface for Cactus Whistle: load the model once, transcribe a clip.
// Kotlin side: com.wasimaster.wmkeyboard.core.voice.whistle.WhistleEngine.
// Written by @Guillermode20 for PR #548; trimmed to the two calls
// the keyboard makes.

#include <jni.h>

#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "needle.h"

namespace {
// needle_* is process-global and not thread-safe; Kotlin also serialises,
// this is the belt to that braces.
std::mutex api_mutex;
// needle_load may keep pointers into the archive rather than copying it, so
// the bytes live as long as the process does.
std::vector<unsigned char> model_bytes;
constexpr int kMaxSamples = 16000 * 30;
constexpr int kOutputCapacity = 256 * 1024;
}  // namespace

// Null on success, else the reason.
extern "C" JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whistle_WhistleEngine_nativeLoad(
    JNIEnv* env, jclass, jbyteArray bytes) {
    if (!bytes || env->GetArrayLength(bytes) == 0) return env->NewStringUTF("Model file is empty");
    const jsize size = env->GetArrayLength(bytes);
    std::vector<unsigned char> model(static_cast<size_t>(size));
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(model.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::lock_guard<std::mutex> lock(api_mutex);
    model_bytes = std::move(model);
    if (needle_load(model_bytes.data(), static_cast<unsigned long long>(model_bytes.size())) < 0) {
        const char* error = needle_last_error();
        return env->NewStringUTF(error ? error : "needle_load failed");
    }
    return nullptr;
}

// The engine's JSON ({"text":...,"language":...}), or null with a pending
// exception. A failure inside the engine comes back as {"error":"..."}.
extern "C" JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whistle_WhistleEngine_nativeTranscribe(
    JNIEnv* env, jclass, jfloatArray audio, jstring language, jstring keywords) {
    if (!audio) return env->NewStringUTF("{\"error\":\"no audio\"}");
    const jsize count = env->GetArrayLength(audio);
    if (count <= 0 || count > kMaxSamples) return env->NewStringUTF("{\"error\":\"clip length out of range\"}");
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(audio, 0, count, pcm.data());
    if (env->ExceptionCheck()) return nullptr;
    const char* lang = language ? env->GetStringUTFChars(language, nullptr) : nullptr;
    const char* bias = keywords ? env->GetStringUTFChars(keywords, nullptr) : nullptr;
    std::vector<char> output(kOutputCapacity, 0);
    int result;
    const char* error = nullptr;
    {
        std::lock_guard<std::mutex> lock(api_mutex);
        result = needle_transcribe(pcm.data(), count, lang, bias, 0, output.data(),
                                   static_cast<int>(output.size()));
        if (result < 0) error = needle_last_error();
    }
    if (bias) env->ReleaseStringUTFChars(keywords, bias);
    if (lang) env->ReleaseStringUTFChars(language, lang);
    if (result < 0) {
        // The engine's own text is not JSON-escaped; Kotlin only shows it.
        std::string json = "{\"error\":\"";
        for (const char* c = error ? error : "needle_transcribe failed"; *c; ++c) {
            if (*c == '"' || *c == '\\') json += '\\';
            if (static_cast<unsigned char>(*c) >= 0x20) json += *c;
        }
        json += "\"}";
        return env->NewStringUTF(json.c_str());
    }
    output.back() = 0;
    return env->NewStringUTF(output.data());
}
