// JNI surface for libvoikko: open a VFST dictionary folder, ask whether a
// word is spelt right, ask for spellings of one that is not, and hyphenate.
// Kotlin side: com.wasimaster.wmkeyboard.core.prediction.VoikkoNative.
//
// This file is offered under the same three licences as the libvoikko
// sources it is linked with (LICENSE.CORE): the Mozilla Public License 1.1,
// the GNU General Public License 2 or later, or the GNU Lesser General Public
// License 2.1 or later, at your option.
//
// Words cross the boundary as UTF-8 byte arrays, never as Java strings: JNI's
// modified UTF-8 differs from the real thing for characters outside the BMP
// and for NUL, and libvoikko's C-string entry points read real UTF-8. Kotlin
// encodes and decodes. A hyphenation pattern is ASCII, so it is a string.
//
// A Voikko handle keeps a speller cache and suggestion state between calls,
// so it is not safe on two threads at once. Kotlin holds one lock per handle
// around every call.

#include <jni.h>

#include <cstring>
#include <string>

#include "voikko.h"

namespace {

VoikkoHandle* from(jlong handle) { return reinterpret_cast<VoikkoHandle*>(handle); }

std::string bytes(JNIEnv* env, jbyteArray array) {
    const jsize n = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte*>(&out[0]));
    return out;
}

jbyteArray toBytes(JNIEnv* env, const char* s) {
    const jsize n = static_cast<jsize>(std::strlen(s));
    jbyteArray out = env->NewByteArray(n);
    if (out != nullptr && n > 0) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(s));
    return out;
}

}  // namespace

extern "C" {

// A handle over the dictionaries under dictPath (dictPath/5/mor-<variant>/),
// for language tag `language` ("fi", or "fi-x-<variant>" for a variant other
// than standard). 0 when none matches or it does not load.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_VoikkoNative_nativeOpen(
        JNIEnv* env, jobject, jstring language, jstring dictPath) {
    const char* tag = env->GetStringUTFChars(language, nullptr);
    const char* path = env->GetStringUTFChars(dictPath, nullptr);
    const char* error = nullptr;
    VoikkoHandle* h = nullptr;
    try {
        h = voikkoInit(&error, tag, path);
    } catch (...) {
        h = nullptr;
    }
    env->ReleaseStringUTFChars(language, tag);
    env->ReleaseStringUTFChars(dictPath, path);
    if (h != nullptr) {
        // Hyphenate as a typesetter would: no lone vowel left at either end.
        voikkoSetBooleanOption(h, VOIKKO_OPT_NO_UGLY_HYPHENATION, 1);
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_VoikkoNative_nativeClose(JNIEnv*, jobject, jlong handle) {
    if (handle != 0) voikkoTerminate(from(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_VoikkoNative_nativeSpell(
        JNIEnv* env, jobject, jlong handle, jbyteArray word) {
    try {
        return voikkoSpellCstr(from(handle), bytes(env, word).c_str()) == VOIKKO_SPELL_OK ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_VoikkoNative_nativeSuggest(
        JNIEnv* env, jobject, jlong handle, jbyteArray word, jint limit) {
    char** found = nullptr;
    try {
        found = voikkoSuggestCstr(from(handle), bytes(env, word).c_str());
    } catch (...) {
        found = nullptr;
    }
    jsize count = 0;
    if (found != nullptr) {
        while (found[count] != nullptr) ++count;
    }
    if (limit >= 0 && count > limit) count = limit;
    jclass byteArray = env->FindClass("[B");
    jobjectArray out = byteArray == nullptr ? nullptr : env->NewObjectArray(count, byteArray, nullptr);
    for (jsize i = 0; out != nullptr && i < count; ++i) {
        jbyteArray item = toBytes(env, found[i]);
        if (item == nullptr) {
            out = nullptr;
            break;
        }
        env->SetObjectArrayElement(out, i, item);
        env->DeleteLocalRef(item);
    }
    if (found != nullptr) voikkoFreeCstrArray(found);
    return out;
}

// One character per character (code point) of the word: ' ' no break, '-' a
// break before it, '=' a break that replaces it with the hyphen. Null on error.
JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_VoikkoNative_nativeHyphenate(
        JNIEnv* env, jobject, jlong handle, jbyteArray word) {
    char* pattern = nullptr;
    try {
        pattern = voikkoHyphenateCstr(from(handle), bytes(env, word).c_str());
    } catch (...) {
        pattern = nullptr;
    }
    if (pattern == nullptr) return nullptr;
    jstring out = env->NewStringUTF(pattern);
    voikkoFreeCstr(pattern);
    return out;
}

}  // extern "C"
