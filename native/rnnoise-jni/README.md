# rnnoise-jni: noise reduction in front of the offline engines

[RNNoise](https://github.com/xiph/rnnoise) takes steady background noise out
of the microphone's audio before an on-device dictation engine hears it
(Settings › Voice typing › Reduce background noise). Kotlin entry point:
`com.wasimaster.wmkeyboard.core.voice.VoiceDenoise`, used by `WhisperRecorder`.

RNNoise works on 10 ms frames at 48 kHz; the keyboard records at 16 kHz.
`denoise_jni.c` raises each 160-sample block to 480 samples with a polyphase
windowed-sinc interpolator (48 taps, Blackman window, 7.2 kHz cut-off), runs
RNNoise, and brings the result back down through the same low-pass filter.
The output trails the input by under 10 ms. Speech models never look above
8 kHz, so nothing they use is lost.

The built libraries (about 130 KB each) are committed under
`core/voice/src/main/jniLibs/`, beside whisper.cpp's, and ride the same
`speech` gate (`:feature:speech` on Play). Rebuild after you change anything
here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/rnnoise-jni/build.sh
```

It needs only the NDK and fetches RNNoise at the pinned commit unless
`RNNOISE_SRC` points at a checkout.

## Provenance

RNNoise `v0.1.1`, commit `6cbfd53eb348a8d394e0757b4025c6ded34eb2b6`,
unmodified, BSD-3-Clause, with its original compiled-in model
(`rnn_data.c`). The 0.2 release's larger models are 5.7 MB (little) and
14.7 MB compiled, against about 90 KB here.

Checked on the host with a modulated 220 Hz + 660 Hz tone plus white noise
at -31 dBFS: the noise-only stretch fell from 0.029 to 0.0002 RMS, the tone kept
94% of its level.

## Contract with Kotlin

- `nativeCreate() -> Long`: a denoiser, 0 on failure.
- `nativeProcess(handle, samples, count) -> FloatArray`: every whole block
  the samples complete, denoised; up to 159 samples wait for the next call.
- `nativeFree(handle)`.

Not thread-safe; the recorder's reader thread owns it.
