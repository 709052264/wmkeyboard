# llamacpp-jni: GGUF models on llama.cpp

[llama.cpp](https://github.com/ggml-org/llama.cpp) is the second on-device AI
runtime, beside LiteRT-LM. LiteRT-LM runs only `.litertlm` and `.task`
bundles; llama.cpp runs any GGUF, so a user can download one of the curated
GGUF rows or import whatever model they like. `LocalLlmEngine` routes a GGUF
here and everything else to LiteRT-LM, which stays the default. Kotlin entry
point: `com.wasimaster.wmkeyboard.core.localllm.LlamaNative`, wrapped by
`LlamaModel`/`LlamaSession` (plain Kotlin) and `LlamaCppRuntime` (the
`LlmRuntime` implementation, Android) in the same package.

The built libraries are committed under `core/intelligence/src/llama/jniLibs/`,
a directory of their own so the Play split carries nothing else. GitHub builds
leave them out of the APK and pin them (gate `llama`), Play builds ship them in
the on-demand `:feature:llama` split, and F-Droid deletes the committed copies
and builds them with this script through `native/build-fdroid-libs.sh`. The
script maps the build machine's paths out of the library and fixes llama.cpp's
build number and commit, so two builds with the same NDK come out the same.
Rebuild after you change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/llamacpp-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches llama.cpp at the release
`pin.sh` names unless `LLAMA_SRC` points at a checkout of it. The committed
libraries were built with NDK r27 (`27.3.13750724`); F-Droid's recipe names
r29, which builds different bytes. Four configurations, a few minutes each.

## Provenance

llama.cpp release `b11753`, commit `d8d92973c8c21bb4dd5d592f0fec26fe2cd1e654`,
unmodified: `libllama`, ggml's CPU backend and `common/` (for the chat
templates and their Jinja interpreter) built as static libraries and linked
into one shared library with `llamacpp_jni.cpp` and libc++ static. llama.cpp
and ggml are MIT (`LICENSE` is upstream's); the chat code includes
nlohmann/json (MIT). Both are in `app/src/main/assets/licenses/mit-llama-cpp.txt`.
No HTTP, OpenSSL, OpenMP, KleidiAI or GPU backend is built.

## One ggml or two

libwmllama carries its own ggml rather than sharing one `libggml.so` with
libwmwhispercpp. The two upstreams sync ggml at different times (whisper.cpp
v1.9.5 and llama.cpp b11753 do not hold the same revision), so a shared
library would pin both projects to whichever revision they last agreed on and
make every upgrade of one wait for the other. They also come through
different gates (`speech` and `llama`), so a shared library would need a
third gate both depend on. The cost is ggml's CPU code twice, roughly a
megabyte, on a phone that has both. With `-fvisibility=hidden` and a version
script exporting only the JNI entry points, the two copies never see each
other's symbols.

## ABIs

| ABI | Library | Instruction set |
|---|---|---|
| arm64-v8a | `libwmllama.so` | ARMv8.0, every 64-bit phone |
| arm64-v8a | `libwmllama_v82.so` | ARMv8.2 with dot product and fp16 |
| armeabi-v7a | `libwmllama.so` | ARMv7 with NEON; small models only (32-bit address space) |
| x86_64 | `libwmllama.so` | SSE4.2, as the Android ABI guarantees |

`LlamaNative` loads the ARMv8.2 build only when every core's `Features` line
in `/proc/cpuinfo` lists `asimddp`, `asimdhp` and `fphp`, the same rule as
whisper.cpp's. Quantized matrix maths runs several times faster with them.

## Contract with Kotlin

Handles are native pointers as `Long`; 0 means failure.

- `nativeModelOpen(path, useMmap)`, `nativeModelClose(model)`. Opening reads
  the GGUF's chat template too (ChatML when it has none).
- `nativeModelInfo(model) -> LongArray`: params, bytes, trained context,
  layers, heads, KV heads, embedding width, sliding window, has-template,
  vocabulary. `LlamaModelInfo` names them and estimates the KV cache per token.
- `nativeSessionOpen(model, contextTokens, threads, batch)`,
  `nativeSessionClose(session)`: one `llama_context` plus a sampler chain.
- `nativeSetSampler(session, temperature, topK, topP, minP, seed)`:
  temperature 0 is greedy.
- `nativePromptChat(session, roles, contents, enableThinking) -> Int`: renders
  the messages with the model's template (llama.cpp's Jinja interpreter,
  falling back to its built-in formats) and loads them, ending on the
  assistant's turn. Template text is tokenized with special tokens; message
  text without, so a message can never open a turn of its own. The tokens the
  cache already holds as a prefix are kept, and only the rest is decoded.
  Returns the prompt's length, or -1 too long, -2 aborted, -3 decode failed,
  -4 template failed.
- `nativePromptRaw(session, text, addSpecial)`: the same for raw text.
- `nativeNext(session) -> ByteArray?`: samples and decodes one token. UTF-8 of
  whole characters only (empty while one is incomplete); null once the answer
  is over, for the reason `nativeStopReason` gives: 0 end of turn, 1 window
  full, 2 aborted, 3 error.
- `nativeAbort(session)`: any thread. Stops a prompt load at its next batch
  (llama.cpp's abort callback) and an answer at its next token.
- `nativeScore(session, tokens) -> FloatArray?`: each token's log-probability
  given the ones before it, in nats; element 0 is NaN. Batches of 32 with
  every position's logits kept. Replaces the cache's contents.
- `nativeTokenize`, `nativeTokenPiece`, `nativeContextSize`,
  `nativeTokensUsed`, `nativeLastReused`, `nativeSystemInfo`.

Text crosses as UTF-8 bytes, never `NewStringUTF`: a token can end inside a
character, and JNI's modified UTF-8 has no room for four-byte characters.
Logging goes to logcat (tag `WMLlama`), warnings and errors only.

## Checking it on a desktop

`host-test.sh` builds the same bridge for macOS or Linux (CPU only, like the
phones) and prints the library's path. The Kotlin above `LlamaNative` that has
no Android in it (`LlamaNative.kt`, `LlamaSession.kt`, `GgufFile.kt`) compiles
with Android Studio's bundled `kotlinc` beside two small stubs (a
`FeatureModules` whose `loadLibrary` calls `System.load`, and `ModelFormat`),
and runs on its JBR with `-Dwm.libdir=<dir of libwmllama.dylib>`.

Measured that way on 2026-10-20 on an Apple M2 (4 performance cores), 4
threads, greedy, a 390-token prompt, the ARMv8.2 build of the bridge. The Mac
was busy with other builds (load average 30 to 55), so these are the best of
three runs, and noisy:

| Model | Prompt (prefill) | Answer (generation) |
|---|---|---|
| Qwen 2.5 0.5B Instruct Q4_K_M | 173 tokens/s | 89 tokens/s |
| Gemma 3 270M IT Q8_0 | 1,508 tokens/s | 158 tokens/s |

The same runs checked: the template renders (ChatML for Qwen, Gemma's own
for Gemma, sliding-window cache included); the second turn of a chat reused
59 of 82 prompt tokens and decoded only the rest; an emoji survived the
UTF-8 streaming; `<|im_start|>system` inside a user message stayed 17 text
tokens; an abort from another thread ended the answer within one token; and
scoring put "tomorow" 11 nats below "tomorrow" under Qwen.

Library sizes (stripped): arm64 6.4 MB each, armeabi-v7a 4.4 MB, x86_64
6.9 MB. About 2.6 MB of that is llama.cpp's code for the model architectures
it knows, and roughly 1.5 MB the chat templates' Jinja interpreter and
template analysis, built at `-Os`. llama itself at `-Os` would save another
12% but looked slower on a small model in these noisy runs, so it stays at
`-O3`.

Nothing here has run on a phone yet.
