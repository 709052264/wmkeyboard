// JNI surface of libwmxlit for XlitNative in :core:intelligence. Handles are
// longs (0 on failure); see README.md for the contract.
#include <jni.h>

#include <android/log.h>

#include <cstring>
#include <string>
#include <vector>

#include "xlit.h"

namespace {

const char* const kTag = "WMKeyboard.Xlit";

std::string utf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_xlit_XlitNative_nativeOpen(JNIEnv* env, jclass, jstring encoder,
                                                               jstring decoder, jint threads) {
    std::string error;
    auto model = wmxlit::Model::open(utf8(env, encoder), utf8(env, decoder), threads, &error);
    if (!model) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "open failed: %s", error.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(model.release());
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_xlit_XlitNative_nativeClose(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<wmxlit::Model*>(handle);
}

// Packed result: for each reading, best first, its token count n, the bits
// of its float score, then its n target ids. Null when the run failed.
JNIEXPORT jintArray JNICALL
Java_com_wasimaster_wmkeyboard_core_xlit_XlitNative_nativeTransliterate(JNIEnv* env, jclass, jlong handle,
                                                                        jintArray src, jbooleanArray allowed,
                                                                        jint beam, jint maxLen) {
    auto* model = reinterpret_cast<wmxlit::Model*>(handle);
    if (!model || !src || !allowed) return nullptr;
    const jsize srcLen = env->GetArrayLength(src);
    std::vector<jint> srcInts(srcLen);
    env->GetIntArrayRegion(src, 0, srcLen, srcInts.data());
    std::vector<int64_t> ids(srcInts.begin(), srcInts.end());
    const jsize vocab = env->GetArrayLength(allowed);
    std::vector<jboolean> flags(vocab);
    env->GetBooleanArrayRegion(allowed, 0, vocab, flags.data());
    std::vector<uint8_t> mask(flags.begin(), flags.end());

    std::vector<wmxlit::Hypothesis> found;
    try {
        found = model->transliterate(ids, mask, beam, maxLen);
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "run failed: %s", e.what());
        return nullptr;
    }
    std::vector<jint> packed;
    for (const auto& h : found) {
        packed.push_back(static_cast<jint>(h.tokens.size()));
        jint bits;
        std::memcpy(&bits, &h.score, sizeof bits);
        packed.push_back(bits);
        packed.insert(packed.end(), h.tokens.begin(), h.tokens.end());
    }
    jintArray out = env->NewIntArray(static_cast<jsize>(packed.size()));
    if (out) env->SetIntArrayRegion(out, 0, static_cast<jsize>(packed.size()), packed.data());
    return out;
}

}  // extern "C"
