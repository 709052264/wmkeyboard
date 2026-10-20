// JNI surface for RNNoise: noise suppression in front of the on-device
// dictation engines. Kotlin side: com.wasimaster.wmkeyboard.core.voice.VoiceDenoise.
//
// RNNoise works on 10 ms frames at 48 kHz; the keyboard records at 16 kHz.
// Each 160-sample block is raised to 480 samples by a polyphase windowed-sinc
// interpolator, denoised, and brought back down through the same low-pass
// filter. Speech recognisers never look above 8 kHz, so nothing is lost
// on the way.

#include <jni.h>

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "rnnoise.h"

#define RATE_IN 16000
#define FACTOR 3
#define BLOCK 160                 /* 10 ms at 16 kHz */
#define FRAME (BLOCK * FACTOR)    /* 480, RNNoise's frame */
#define TAPS 48                   /* a multiple of FACTOR: 16 taps per phase */
#define PHASE_TAPS (TAPS / FACTOR)

typedef struct {
    DenoiseState *st;
    float h[TAPS];
    /* Last PHASE_TAPS input samples, oldest first, for the interpolator. */
    float up_hist[PHASE_TAPS];
    /* Last TAPS 48 kHz samples, oldest first, for the decimator. */
    float down_hist[TAPS];
    /* 16 kHz samples waiting for a whole block. */
    float pending[BLOCK];
    int npending;
} Denoiser;

/* A low-pass at 7.2 kHz for the 48 kHz rate, Blackman-windowed sinc, unit DC gain. */
static void design(float *h) {
    const double fc = 7200.0 / (RATE_IN * FACTOR);
    double sum = 0;
    for (int i = 0; i < TAPS; i++) {
        const double m = i - (TAPS - 1) / 2.0;
        const double x = 2 * M_PI * fc * m;
        const double sinc = m == 0 ? 2 * fc : sin(x) / (M_PI * m);
        const double w = 0.42 - 0.5 * cos(2 * M_PI * i / (TAPS - 1)) + 0.08 * cos(4 * M_PI * i / (TAPS - 1));
        h[i] = (float) (sinc * w);
        sum += h[i];
    }
    for (int i = 0; i < TAPS; i++) h[i] = (float) (h[i] / sum);
}

/* One block: 160 samples in [-1, 1] to 160 denoised samples. */
static void run_block(Denoiser *d, const float *in, float *out) {
    float up[FRAME];
    float clean[FRAME];
    for (int m = 0; m < BLOCK; m++) {
        memmove(d->up_hist, d->up_hist + 1, sizeof(float) * (PHASE_TAPS - 1));
        d->up_hist[PHASE_TAPS - 1] = in[m];
        for (int p = 0; p < FACTOR; p++) {
            float acc = 0;
            /* y[3m + p] = 3 * sum_j h[3j + p] * x[m - j] */
            for (int j = 0; j < PHASE_TAPS; j++) acc += d->h[FACTOR * j + p] * d->up_hist[PHASE_TAPS - 1 - j];
            /* RNNoise wants 16-bit sample values. */
            up[m * FACTOR + p] = acc * FACTOR * 32768.0f;
        }
    }
    rnnoise_process_frame(d->st, clean, up);
    for (int m = 0; m < BLOCK; m++) {
        memmove(d->down_hist, d->down_hist + FACTOR, sizeof(float) * (TAPS - FACTOR));
        for (int p = 0; p < FACTOR; p++) d->down_hist[TAPS - FACTOR + p] = clean[m * FACTOR + p];
        float acc = 0;
        for (int k = 0; k < TAPS; k++) acc += d->h[k] * d->down_hist[TAPS - 1 - k];
        float v = acc / 32768.0f;
        out[m] = v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v);
    }
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_VoiceDenoise_nativeCreate(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    Denoiser *d = calloc(1, sizeof(Denoiser));
    if (!d) return 0;
    d->st = rnnoise_create(NULL);
    if (!d->st) {
        free(d);
        return 0;
    }
    design(d->h);
    return (jlong) (intptr_t) d;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_VoiceDenoise_nativeFree(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    Denoiser *d = (Denoiser *) (intptr_t) handle;
    if (!d) return;
    rnnoise_destroy(d->st);
    free(d);
}

/*
 * [n] samples of [samples] in; every whole block they complete out, denoised.
 * Up to 159 samples wait inside for the next call.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_VoiceDenoise_nativeProcess(
    JNIEnv *env, jclass cls, jlong handle, jfloatArray samples, jint n) {
    (void) cls;
    Denoiser *d = (Denoiser *) (intptr_t) handle;
    if (!d || n < 0) return (*env)->NewFloatArray(env, 0);
    const int total = d->npending + n;
    const int blocks = total / BLOCK;
    jfloatArray result = (*env)->NewFloatArray(env, blocks * BLOCK);
    if (!result) return NULL;
    jfloat *in = n > 0 ? (*env)->GetFloatArrayElements(env, samples, NULL) : NULL;
    if (n > 0 && !in) return result;
    float *out = blocks > 0 ? malloc(sizeof(float) * blocks * BLOCK) : NULL;
    int used = 0;
    for (int b = 0; b < blocks; b++) {
        const int take = BLOCK - d->npending;
        memcpy(d->pending + d->npending, in + used, sizeof(float) * take);
        used += take;
        d->npending = 0;
        if (out) run_block(d, d->pending, out + b * BLOCK);
    }
    if (n > used) memcpy(d->pending + d->npending, in + used, sizeof(float) * (n - used));
    d->npending += n - used;
    if (in) (*env)->ReleaseFloatArrayElements(env, samples, in, JNI_ABORT);
    if (out) {
        (*env)->SetFloatArrayRegion(env, result, 0, blocks * BLOCK, out);
        free(out);
    }
    return result;
}
