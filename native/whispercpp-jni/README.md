# whispercpp-jni: Offline Whisper on whisper.cpp

[whisper.cpp](https://github.com/ggml-org/whisper.cpp) runs Offline Whisper's
ggml models (#440), beside the LiteRT graphs it already had. Kotlin entry
point: `com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCpp` (in
`core/voice/src/main`), driven from `WhisperCppPlan` and the IME service.

Where a LiteRT graph has Hugging Face's whole `generate()` traced inside it,
whisper.cpp runs the decoder loop itself, so this bridge can offer what
Whisper takes: a language by name (any of the 99), detection kept to the
languages being dictated in, an initial prompt, beam search, text while it
decodes, stopping part way, and clips past 30 seconds.

The built libraries are committed under `core/voice/src/main/jniLibs/`, so the
app builds without an NDK, and both editions, full and lite, carry them.
F-Droid builds lite and `scandelete`s that directory, so its build has no
such engine. Rebuild after you change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/whispercpp-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches whisper.cpp at the pinned
commit unless `WHISPER_SRC` points at a checkout of it. A full rebuild is
four configurations and takes several minutes each.

## Provenance

whisper.cpp `v1.9.5`, commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`,
unmodified, built with the CPU backend only (no OpenMP, no GPU). whisper.cpp
and ggml are MIT; `LICENSE` is upstream's.

The models are not bundled. The keyboard downloads them on request from the
`ggerganov/whisper.cpp` Hugging Face repo at revision
`5359861c739e955e79d9a303bcbc70fb988958b1`, and checks each against the
SHA-256 in `WhisperCatalog` (the LFS oid Hugging Face reports for it). The
weights are OpenAI's Whisper (MIT), quantized by the whisper.cpp project.

## ABIs

| ABI | Library | Instruction set |
|---|---|---|
| arm64-v8a | `libwmwhispercpp.so` | ARMv8.0, every 64-bit phone |
| arm64-v8a | `libwmwhispercpp_v82.so` | ARMv8.2 with dot product and fp16 |
| armeabi-v7a | `libwmwhispercpp.so` | ARMv7 with NEON |
| x86_64 | `libwmwhispercpp.so` | SSE4.2, as the Android ABI guarantees |

`WhisperCpp` loads the ARMv8.2 build only when every core's `Features` line in
`/proc/cpuinfo` lists `asimddp`, `asimdhp` and `fphp`; big.LITTLE parts can mix
generations, and one core without them would die on an illegal instruction.
Quantized matrix maths, which is most of the work, runs several times faster
with the dot-product instructions.

## Contract with Kotlin

- `nativeOpen(path) -> Long`: a handle, or 0 when the model will not load.
- `nativeClose(handle)`.
- `nativeTranscribe(handle, pcm, languages, translate, prompt, beamSize, threads) -> ByteArray?`:
  16 kHz mono float PCM, any length. `languages` are Whisper codes: none detects
  among all, one is forced, several are detected among those only (one extra
  encoder pass). Returns `"<code>\n<text>"` as UTF-8, or null when
  `nativeAbort` stopped it. Throws `IllegalStateException` on failure.
- `nativePartial(handle) -> ByteArray`: what is decoded so far, from any
  thread, empty between transcriptions.
- `nativeAbort(handle)`: stops the running transcription at its next step.

Text crosses as UTF-8 bytes, never `NewStringUTF`: Whisper's byte-level tokens
can split a character, and JNI's modified UTF-8 has no room for that or for a
four-byte emoji. Decoding is one pass (no temperature fallback), with
non-speech tokens suppressed and a token budget of about 20 a second of audio,
which is what stops a decoder that has fallen into repeating itself.
