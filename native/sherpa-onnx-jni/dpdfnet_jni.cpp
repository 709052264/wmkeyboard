// JNI surface for sherpa-onnx's streaming speech denoiser over a DPDFNet
// model (Ceva's DeepFilterNet2 with dual-path RNN blocks, Apache-2.0): the
// Strong level of Reduce background noise. Kotlin side:
// com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative, driven by
// com.wasimaster.wmkeyboard.core.voice.StrongDenoise.
//
// The model takes everything it judges noise out, and a speech recogniser
// reads that as damage: measured on noisy LibriSpeech through Whisper, the
// bare output was barely better than no denoising at all. So a little of the
// microphone is mixed back under it, `keep` times the input (an attenuation
// limit, the trick DeepFilterNet's own tools offer). The denoiser's output
// trails its input by `delay` samples (STFT overlap plus the model's
// lookahead, 640 for the 16 kHz exports), so the input is delayed by that
// much before it is mixed in.

#include <jni.h>

#include <cstring>
#include <deque>
#include <vector>

#include "sherpa-onnx/c-api/c-api.h"

namespace {

struct Denoiser {
    const SherpaOnnxOnlineSpeechDenoiser* sd = nullptr;
    // Input samples not yet mixed back, oldest first; once the first `delay`
    // output samples are past, the front one lines up with the next output.
    std::deque<float> history;
    int32_t delay = 0;
    float keep = 0.0f;
    // Output samples dropped so far, up to `delay`.
    int32_t primed = 0;
};

Denoiser* denoiser(jlong h) { return reinterpret_cast<Denoiser*>(h); }

// Appends `audio` to `dst`, with the delayed input mixed back in. The first
// `delay` output samples of a stream come out before any input has reached
// the output, so they are dropped: the clip keeps its own timeline, and the
// silent tail [nativeDenoiseFinish] feeds brings its last words out instead.
void mix(Denoiser* d, const SherpaOnnxDenoisedAudio* audio, std::vector<float>& dst) {
    if (!audio) return;
    for (int32_t i = 0; i < audio->n; ++i) {
        if (d->primed < d->delay) {
            ++d->primed;
            continue;
        }
        float dry = 0.0f;
        if (!d->history.empty()) {
            dry = d->history.front();
            d->history.pop_front();
        }
        float v = audio->samples[i] + d->keep * dry;
        dst.push_back(v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v));
    }
    SherpaOnnxDestroyDenoisedAudio(audio);
}

jfloatArray floats(JNIEnv* env, const std::vector<float>& v) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (result && !v.empty()) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(v.size()), v.data());
    return result;
}

}  // namespace

extern "C" {

// A streaming denoiser for one 16 kHz model, 0 when it will not load. The
// caller checks the file's SHA-256 first: sherpa-onnx exits the process on a
// model whose metadata it does not recognise.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeDenoiser(
    JNIEnv* env, jclass, jstring model, jint threads, jint delay, jfloat keep) {
    const char* path = model ? env->GetStringUTFChars(model, nullptr) : nullptr;
    if (!path) return 0;
    SherpaOnnxOnlineSpeechDenoiserConfig config;
    std::memset(&config, 0, sizeof(config));
    config.model.dpdfnet.model = path;
    config.model.num_threads = threads;
    config.model.provider = "cpu";
    const SherpaOnnxOnlineSpeechDenoiser* sd = SherpaOnnxCreateOnlineSpeechDenoiser(&config);
    env->ReleaseStringUTFChars(model, path);
    if (!sd) return 0;
    if (SherpaOnnxOnlineSpeechDenoiserGetSampleRate(sd) != 16000) {
        SherpaOnnxDestroyOnlineSpeechDenoiser(sd);
        return 0;
    }
    auto* d = new Denoiser();
    d->sd = sd;
    d->delay = delay > 0 ? delay : 0;
    d->keep = keep > 0.0f ? keep : 0.0f;
    return reinterpret_cast<jlong>(d);
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeDenoiserFree(JNIEnv*, jclass, jlong h) {
    Denoiser* d = denoiser(h);
    if (!d) return;
    SherpaOnnxDestroyOnlineSpeechDenoiser(d->sd);
    delete d;
}

// n samples (16 kHz, [-1, 1]) in; whatever the denoiser has ready out, which
// can be nothing yet. Output trails input by the model's delay.
JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeDenoise(
    JNIEnv* env, jclass, jlong h, jfloatArray samples, jint n) {
    Denoiser* d = denoiser(h);
    std::vector<float> out;
    if (!d || n <= 0) return floats(env, out);
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return floats(env, out);
    d->history.insert(d->history.end(), data, data + n);
    const SherpaOnnxDenoisedAudio* audio = SherpaOnnxOnlineSpeechDenoiserRun(d->sd, data, n, 16000);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    out.reserve(static_cast<size_t>(n));
    mix(d, audio, out);
    return floats(env, out);
}

// The clip is over: feeds `delay` samples of silence so the clip's last
// `delay` samples come out, flushes, and leaves the denoiser ready for a new
// clip. Over the whole clip, output and input are the same length.
JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeDenoiseFinish(JNIEnv* env, jclass, jlong h) {
    Denoiser* d = denoiser(h);
    std::vector<float> out;
    if (!d) return floats(env, out);
    if (d->delay > 0) {
        const std::vector<float> tail(static_cast<size_t>(d->delay), 0.0f);
        d->history.insert(d->history.end(), tail.begin(), tail.end());
        mix(d, SherpaOnnxOnlineSpeechDenoiserRun(d->sd, tail.data(), d->delay, 16000), out);
    }
    mix(d, SherpaOnnxOnlineSpeechDenoiserFlush(d->sd), out);
    // Flush resets the model; the mix starts over with it.
    d->history.clear();
    d->primed = 0;
    return floats(env, out);
}

}  // extern "C"
