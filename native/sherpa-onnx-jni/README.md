# sherpa-onnx-jni: Offline Sherpa on ONNX Runtime

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runs Offline Sherpa's
streaming Zipformer transducers, Silero VAD and the English online
punctuation model. Kotlin entry points: `SherpaNative`, `SherpaEngine` and
`OmnilingualEngine` in
`core/voice/src/main/java/com/wasimaster/wmkeyboard/core/voice/sherpa/`.

Offline Omnilingual's model (Meta's Omnilingual ASR 300M CTC) runs on ONNX
Runtime's C++ API directly (`omnilingual_jni.cpp`), with sherpa-onnx's Silero
cutting the audio into phrases. sherpa-onnx's offline recognizer decodes the
same model, but the model has no language conditioning and spells in
whichever script it thinks it hears (macOS's Hindi voice came back in Urdu
letters), and the recognizer offers no way to steer that. The bridge follows
sherpa-onnx's preprocessing (the raw waveform, normalised to zero mean and
unit variance) and does the greedy CTC pick itself, over the tokens
`OmnilingualScriptMask` flags for the dictation's scripts.

The same library reads text aloud with Meta's MMS-TTS voices
(`vits_jni.cpp`, driven by `core.voice.speech.MmsSpeech`). That part is not
sherpa-onnx: it runs the VITS network through ONNX Runtime's C++ API. An MMS
voice reads characters, so its whole text frontend (lower-casing, one id per
character, the blank between them) is a few lines of Kotlin, and building
sherpa-onnx's TTS would link espeak-ng, which is GPL-3.0, for phonemes these
voices never use.

Reduce background noise at Strong runs here too (`dpdfnet_jni.cpp`, driven by
`core.voice.StrongDenoise`): sherpa-onnx's streaming speech denoiser over Ceva's
DPDFNet baseline model (DeepFilterNet2 with dual-path RNN blocks, Apache-2.0,
16 kHz), with 12 dB of the microphone mixed back under the cleaned audio. See
[Choosing the Strong model](#choosing-the-strong-model) below for why that model
and that mix.

The bridge is a thin JNI layer over sherpa-onnx's C API, which is linked in
statically with only speech recognition switched on (no TTS, no diarization,
no audio I/O, no servers). ONNX Runtime is not linked in. `libwmsherpa.so`
names `libonnxruntime.so` as a dependency and calls one symbol of it,
`OrtGetApiBase`; everything else goes through the table that function
returns. At build time the linker sees a stub (`ort_stub.c`, built per ABI
with the soname `libonnxruntime.so`) and the runtime's headers, cloned at the
pinned tag. At run time `SherpaNative` loads Microsoft's own
`libonnxruntime.so` first and this library second, and the dynamic linker
binds the dependency by soname to the copy already loaded. So:

- no ONNX Runtime binary is in the repository or the build, and F-Droid can
  build this library from source;
- the runtime on the phone is Microsoft's release from Maven Central
  (`com.microsoft.onnxruntime:onnxruntime-android`, MIT), either packaged by the
  `wmkeyboard.onnxruntime` convention or, with native delivery, read entry by
  entry out of that AAR on the phone;
- the stub's symbol is unversioned, so the reference binds to whichever
  `VERS_<version>` the loaded runtime exports. The headers' API version must
  not be newer than the runtime's: keep `ORT_TAG` in `build.sh` and
  `onnxruntime` in `gradle/libs.versions.toml` on the same release.

The built libraries are committed under `core/voice/src/onnx/jniLibs/`, apart
from `src/main/jniLibs` because they ride their own delivery gate, `onnx`
(`:feature:onnx` on Play). Rebuild after you change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/sherpa-onnx-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches sherpa-onnx and the ONNX
Runtime headers at their pinned commits unless `SHERPA_SRC` / `ORT_SRC` point
at checkouts. sherpa-onnx's own CMake fetches its dependencies. One ABI takes
about four minutes.

## Provenance

- sherpa-onnx `v1.13.8`, commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`,
  unmodified, Apache-2.0.
- ONNX Runtime headers `v1.30.0`, commit
  `f2c39fe2f838cf35ce7da92824f5a5e3ee6e88a7`, MIT.

The models are not bundled. `EtCatalog` in `:core:voice` pins each one to a
Hugging Face revision and every file to its SHA-256, the same store and
downloader as the ExecuTorch engines. The denoiser's model is sherpa-onnx's own
conversion (`speech-enhancement-models` release, `dpdfnet_baseline.onnx`,
8,791,035 bytes), pinned by size and SHA-256 in `StrongDenoise`.

## ABIs

| ABI | Size |
|---|---|
| arm64-v8a | about 2.3 MB |
| armeabi-v7a | about 1.5 MB |
| x86_64 | about 2.5 MB |

All three are 16 KB aligned (`-z max-page-size=16384`), as is Microsoft's
runtime from 1.30.0.

## Contract with Kotlin

Handles are `Long`s, 0 on failure; text crosses as UTF-8 byte arrays.

- `nativeRecognizer(encoder, decoder, joiner, tokens, threads, trailingSilence)`:
  a streaming transducer, greedy search, 16 kHz, 80 mel bins. Its own
  endpoint fires after `trailingSilence` seconds of quiet following words;
  the no-words rule is pushed out of reach.
- `nativeStream`, `nativeFeed` (decodes every frame the samples complete),
  `nativeText`, `nativeEndpoint`, `nativeFinish` (a short silent tail, input
  finished, decode the rest), `nativeStreamFree`.
- `nativeVad(model, threshold, minSilence, minSpeech)`, `nativeVadFeed`
  (returns whether speech is going on; feeds Silero whole 512-sample windows
  and drops finished segments), `nativeVadReset`, `nativeVadFree`.
- `nativePunctuation(model, vocab)`, `nativePunctuate`, `nativePunctuationFree`.
- `nativeVits(model, threads)`, `nativeVitsMeta(handle, key)` (a custom
  metadata entry such as `sample_rate` or `add_blank`, empty when absent),
  `nativeVitsRun(handle, ids, noiseScale, lengthScale, noiseScaleW)` (one
  sentence's samples, null on failure; inputs matched by name, a `sid` input
  fed 0), `nativeVitsFree`.

- `nativeDenoiser(model, threads, delay, keep)`: a streaming DPDFNet denoiser,
  0 on failure (or a model whose rate is not 16 kHz). `nativeDenoise(handle,
  samples, count)` returns what is ready, `keep` times the input `delay`
  samples back mixed under it; the first `delay` output samples of a clip are
  dropped. `nativeDenoiseFinish` feeds `delay` samples of silence, flushes and
  resets, so over a clip the output is exactly as long as the input.
  `nativeDenoiserFree`. sherpa-onnx exits the process on a model whose
  metadata it does not recognise, so Kotlin hands it only a file whose SHA-256
  it has checked.

`mms_tts_catalog.py` writes the voice catalog, `core/voice/src/main/assets/speech/mms_tts.tsv`:
every voice of one pinned revision of the ONNX conversion, with sizes and SHA-256.

None of it is thread-safe; Kotlin calls the recognizer on one thread, the
voice on another (`MmsSpeech.dispatcher`) and a denoiser on the capture thread
of the clip using it; they share no handle.

## Choosing the Strong model

Measured on a Mac (2026-10-20) before any of it was built: 25 LibriSpeech
clips, each mixed with street and cafeteria noise from DEMAND (channel 1) and
a synthetic desk fan at 0, 5 and 10 dB SNR, plus the clean clips, 250 clips
and 34 minutes in all. Every denoiser was fed 100 ms chunks, as
`WhisperRecorder` does. Word error rate, all 250 clips:

| Denoiser | Whisper Tiny (en) | Sherpa zipformer-en-20M |
|---|---|---|
| none | 27.2% | 33.1% |
| RNNoise v0.1.1 (Light) | 32.8% | 40.0% |
| RNNoise, 12 dB of input kept | 31.0% | 35.7% |
| GTCRN (sherpa-onnx) | 32.6% | 37.9% |
| DPDFNet baseline | 26.4% | 32.4% |
| DPDFNet baseline, 12 dB kept (Strong) | 24.1% | 31.1% |
| DPDFNet2 | 25.9% | 30.7% |
| DPDFNet2, 12 dB kept | 23.7% | |
| DeepFilterNet3 (48 kHz, its own CLI) | 30.3% | |
| DeepFilterNet3, 12 dB kept | 24.7% | |

Blank cells are runs that did not finish. On clean clips Strong moved Whisper
from 8.2% to 9.0%. CPU per 10 ms of
audio, one thread, on a heavily loaded M2: RNNoise 0.25 ms, GTCRN 1.07,
DPDFNet baseline 1.20, DeepFilterNet3 1.23, DPDFNet2 2.62. DPDFNet2 is within
noise of the baseline on accuracy at twice the work, and DeepFilterNet3 needs
48 kHz audio and its own STFT and feature code (or the Rust runtime) on top,
so the baseline is the one shipped. Peak RSS of the Python process running it
was under 90 MB.
