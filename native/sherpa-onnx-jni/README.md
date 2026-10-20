# sherpa-onnx-jni: Offline Sherpa on ONNX Runtime

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runs Offline Sherpa's
streaming Zipformer transducers, Silero VAD and the English online
punctuation model, and Offline Omnilingual's model (Meta's Omnilingual ASR
300M CTC) on its offline recognizer, with Silero cutting the audio into
phrases. Kotlin entry points: `SherpaNative`, `SherpaEngine` and
`OmnilingualEngine` in
`core/voice/src/main/java/com/wasimaster/wmkeyboard/core/voice/sherpa/`.

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
downloader as the ExecuTorch engines.

## ABIs

| ABI | Size |
|---|---|
| arm64-v8a | about 2.2 MB |
| armeabi-v7a | about 1.4 MB |
| x86_64 | about 2.3 MB |

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

None of it is thread-safe; Kotlin calls everything on one thread.
