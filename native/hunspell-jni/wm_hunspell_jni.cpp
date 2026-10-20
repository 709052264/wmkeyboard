// JNI surface for Hunspell: open an .aff/.dic pair, ask whether a word is
// spelt right, ask for spellings of one that is not. Kotlin side:
// com.wasimaster.wmkeyboard.core.prediction.HunspellNative.
//
// Words cross the boundary as byte arrays already in the dictionary's own
// encoding (its SET line, read back with nativeEncoding), never as Java
// strings: an ISO 8859 or KOI8 dictionary compares bytes, and JNI's modified
// UTF-8 would hand it the wrong ones. Kotlin encodes and decodes.
//
// A Hunspell object keeps scratch state between calls (the affix manager
// remembers the affixes of the last match), so it is not safe on two threads
// at once. Kotlin holds one lock per handle around every call.

#include <jni.h>

#include <new>
#include <string>
#include <vector>

#include "hunspell.hxx"

namespace {

Hunspell* from(jlong handle) { return reinterpret_cast<Hunspell*>(handle); }

std::string bytes(JNIEnv* env, jbyteArray array) {
    const jsize n = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte*>(&out[0]));
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_HunspellNative_nativeOpen(
        JNIEnv* env, jobject, jstring aff, jstring dic) {
    const char* affPath = env->GetStringUTFChars(aff, nullptr);
    const char* dicPath = env->GetStringUTFChars(dic, nullptr);
    Hunspell* h = nullptr;
    try {
        h = new Hunspell(affPath, dicPath);
    } catch (...) {
        h = nullptr;
    }
    env->ReleaseStringUTFChars(aff, affPath);
    env->ReleaseStringUTFChars(dic, dicPath);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_HunspellNative_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete from(handle);
}

JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_HunspellNative_nativeEncoding(JNIEnv* env, jobject, jlong handle) {
    // An encoding name is ASCII, so modified UTF-8 is safe here.
    return env->NewStringUTF(from(handle)->get_dict_encoding().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_HunspellNative_nativeSpell(
        JNIEnv* env, jobject, jlong handle, jbyteArray word) {
    try {
        return from(handle)->spell(bytes(env, word)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_prediction_HunspellNative_nativeSuggest(
        JNIEnv* env, jobject, jlong handle, jbyteArray word, jint limit) {
    std::vector<std::string> found;
    try {
        found = from(handle)->suggest(bytes(env, word));
    } catch (...) {
        found.clear();
    }
    if (limit >= 0 && found.size() > static_cast<size_t>(limit)) found.resize(static_cast<size_t>(limit));
    jclass byteArray = env->FindClass("[B");
    if (byteArray == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(found.size()), byteArray, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < found.size(); ++i) {
        const std::string& s = found[i];
        jbyteArray item = env->NewByteArray(static_cast<jsize>(s.size()));
        if (item == nullptr) return nullptr;
        env->SetByteArrayRegion(item, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
        env->SetObjectArrayElement(out, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return out;
}

}  // extern "C"
