/*
 * JNI bridge for core/voice's OpusEncoder: dictation audio compressed to Ogg
 * Opus before it is uploaded to a transcription server.
 *
 * Two shapes, both a few crossings a clip: nativeEncode takes a whole clip in
 * one call; nativeOpen / nativeWrite / nativeFinish / nativeFree take it in
 * chunks as it is recorded. The file comes back whole from either.
 */
#include <jni.h>
#include <stdint.h>

#include "ogg_opus.h"

#define CHUNK 4096 /* samples copied out of the Java array at a time */

static int write_array(JNIEnv *env, wm_ogg_opus *w, jfloatArray samples, jint count) {
    if (!samples || count < 0 || count > (*env)->GetArrayLength(env, samples)) return -1;
    float buffer[CHUNK];
    for (jint at = 0; at < count; at += CHUNK) {
        jint n = count - at < CHUNK ? count - at : CHUNK;
        (*env)->GetFloatArrayRegion(env, samples, at, n, buffer);
        if (wm_ogg_opus_write(w, buffer, (size_t)n)) return -1;
    }
    return 0;
}

static jbyteArray finish_array(JNIEnv *env, wm_ogg_opus *w) {
    const unsigned char *bytes = NULL;
    size_t len = 0;
    if (wm_ogg_opus_finish(w, &bytes, &len) || len > INT32_MAX) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)bytes);
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_OpusEncoder_nativeOpen(JNIEnv *env, jclass cls, jint rate, jint bitrate) {
    (void)env;
    (void)cls;
    return (jlong)(intptr_t)wm_ogg_opus_open(rate, bitrate);
}

JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_OpusEncoder_nativeWrite(JNIEnv *env, jclass cls, jlong handle,
                                                                  jfloatArray samples, jint count) {
    (void)cls;
    wm_ogg_opus *w = (wm_ogg_opus *)(intptr_t)handle;
    return w && write_array(env, w, samples, count) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_OpusEncoder_nativeFinish(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    wm_ogg_opus *w = (wm_ogg_opus *)(intptr_t)handle;
    return w ? finish_array(env, w) : NULL;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_OpusEncoder_nativeFree(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    wm_ogg_opus_free((wm_ogg_opus *)(intptr_t)handle);
}

JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_OpusEncoder_nativeEncode(JNIEnv *env, jclass cls, jfloatArray samples,
                                                                   jint count, jint rate, jint bitrate) {
    (void)cls;
    wm_ogg_opus *w = wm_ogg_opus_open(rate, bitrate);
    if (!w) return NULL;
    jbyteArray out = write_array(env, w, samples, count) == 0 ? finish_array(env, w) : NULL;
    wm_ogg_opus_free(w);
    return out;
}
