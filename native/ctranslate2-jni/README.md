# ctranslate2-jni: NLLB-200 translation on the device

OpenNMT's [CTranslate2](https://github.com/OpenNMT/CTranslate2) (MIT) with
Google's [SentencePiece](https://github.com/google/sentencepiece), built for
Android with a small JNI bridge into `libwmct2.so`. It runs Meta's NLLB-200
model (distilled 600M, int8) as the translate tool's third on-device engine,
for the pairs ML Kit and Bergamot have no model for. Kotlin entry point:
`com.wasimaster.wmkeyboard.core.translate.Ct2Native` (in
`core/intelligence/src/main`), driven by `NllbTranslator`.

The built libraries are committed under `core/intelligence/src/ct2/jniLibs/`,
a directory of their own so that the Play split `:feature:ct2` carries
nothing else. GitHub builds pin them for download (native gate `ct2`),
F-Droid deletes the committed copies and builds them with this script (through
`native/build-fdroid-libs.sh`), and Play ships them in the split. Rebuild
after you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/ctranslate2-jni/build.sh
```

It needs git, CMake and Ninja on `PATH`, and fetches the sources at their
pinned commits unless `CT2_SRC` and `SPM_SRC` point at checkouts of them
(`SPM_SRC` with abseil already cloned into `third_party/abseil-cpp`).
`WMKB_ABIS` narrows the build to some of the three ABIs.

## Provenance

| Source | Pin | Licence |
|---|---|---|
| OpenNMT/CTranslate2 | `v4.8.3`, `8d91aa08225004755360daa43deb62b35b1ed65d` | MIT |
| its submodules `ruy` (+ `cpuinfo`), `spdlog` (bundles {fmt}), `cpu_features` | the commits that tag records | Apache-2.0 (+ BSD-2), MIT, Apache-2.0 |
| google/sentencepiece | `v0.2.2`, `e0cce7d37b065b5140349dbe12c6bcf6192fdd78` (bundles protobuf-lite, darts-clone) | Apache-2.0 (BSD-3, BSD-2) |
| abseil/abseil-cpp | `20260526.0`, `5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a`, the tag SentencePiece's CMake fetches | Apache-2.0 |

One edit is made to the checkout, in `build.sh`: bionic has no
`pthread_setaffinity_np`, so `thread_pool.cc`'s affinity code takes the
branch other systems take (it refuses when asked; the bridge never asks).
SentencePiece's CMake would clone abseil at configure time through a form of
FetchContent that ignores every "disconnected" switch, so `build.sh` clones
the same tag first and `CMakeLists.txt` adds it itself.

The notices of everything linked in are in
`app/src/main/assets/licenses/ct2-third-party.txt`, assembled from the
pinned sources' licence files.

## The model

Not bundled, not in the library. `NllbTranslator.FILES` pins the four files
of `JustFrederik/nllb-200-distilled-600M-ct2-int8` at revision
`302d78f00e6fdb50a1064059df7c392b735e9d05` (a CTranslate2 conversion of
`facebook/nllb-200-distilled-600M` with int8 weights) by size and SHA-256:
`model.bin` (622,595,991 bytes), `config.json`, `shared_vocabulary.txt` and
`sentencepiece.bpe.model`, 630,016,302 bytes in all. The weights are Meta's,
under CC BY-NC 4.0 (`cc-by-nc-4.0.txt` beside the notices); the phone fetches
them from Hugging Face only after the user accepts that licence
(`NativeConsent.NLLB`), so this project never redistributes them.

## Build choices

| Choice | Why |
|---|---|
| ruy for the int8 and the float GEMM, no BLAS, no OpenMP | `backend.cc` picks ruy for both when nothing else is compiled in, and ruy has its own thread pool. ruy chooses its dot-product kernels at run time on the phones that have them |
| CPU dispatch on | x86_64 gets CTranslate2's AVX, AVX2 and AVX-512 kernels, picked at run time through cpu_features |
| API 24 | nothing in either library needs more |
| armeabi-v7a: `stub.c`, no engine | 600 MB of weights held in memory is more than a 32-bit process can count on. Play wants every split to cover the base's three ABIs, so 32-bit ARM gets a library whose `JNI_OnLoad` fails; `Ct2Native.supported` wants a 64-bit process anyway |
| `c++_static`, `--gc-sections`, `--icf=all`, `--exclude-libs,ALL`, a version script | one self-contained library exporting only `JNI_OnLoad` and the three bridge functions; it needs only `liblog`, `libdl`, `libm` and `libc` |
| `-z max-page-size=16384` | 16 KB page devices |
| `-ffile-prefix-map` for both source trees, the build directory and the NDK | the same bytes from any machine with NDK r29, which F-Droid's reproducible build relies on |

Size, stripped, NDK r29: arm64-v8a 2,715,256 bytes, x86_64 3,063,888, the
armeabi-v7a stub 2,772.

## Contract with Kotlin

Text crosses as UTF-8 `byte[]`: JNI's modified UTF-8 would split an emoji
into two surrogates. Language tags are FLORES-200 codes (`ben_Beng`) as
strings. No handle may be used from two threads at once. Every C++ exception
becomes a `RuntimeException`, so a bad file costs a translation, not the
keyboard.

- `nativeLoad(modelDir, threads) -> Long`: reads the model into memory
  (int8 weights, float32 activations) and the SentencePiece model; `nativeFree(handle)`.
- `nativeTranslate(handle, byte[][], sourceTag, targetTag, beam) -> byte[][]`:
  one sentence each, in order, decoded together as one batch. The source is
  `[sourceTag] + pieces + "</s>"` and decoding starts from `targetTag`, as
  Hugging Face's NLLB tokenizer does; the result drops that first token.
  Decoding stops at twice the longest source plus ten tokens, at most 256.

## Tested

Not on a phone. The same CMake project built for macOS arm64 (Apple clang,
`-std=c++17` for abseil's check, JNI headers from a JDK) and loaded into a
JVM through these exact JNI names translated a fixed set of 40 sentences
(five English sentences into Bengali, Hindi, Arabic, Swahili and French, and
three of each back into English) with the pinned model, giving the same text
as CTranslate2's own Python wheel.

Measured on an M2 (8 cores) while other builds kept its load average between
25 and 50, so wall times are noise; instruction counts and memory are not:

| | |
|---|---|
| Load | 2.6 to 4.1 s |
| Memory, model loaded | 720 MB peak footprint |
| Memory, while translating | 1.25 GB peak footprint: ruy packs each weight matrix per call into a buffer it keeps, the 256,206 × 1024 output projection included |
| Work per output token, one thread | about 790 M instructions; about 140 ms of CPU on the moments the machine was quiet (a 15-token sentence in about 2 s, English out in about 1 s) |
| With ruy's dot-product kernels forced on | 17 % fewer instructions, the same cycles. macOS reports no dot-product to ruy, so the desktop run took the older NEON kernel; phones from the Cortex-A55/A75 on get the dot-product one |
| Five sentences in one call | 3.7 s against about 2 s for one alone, both in the machine's quiet minutes: about the cost of two |

A profile shows ruy's int8 kernel and its packing of the weights taking the
time in about equal parts: every decoding step repacks about 430 M int8
weights (the decoder's and the output projection's). So a phone's time per
token is bound by memory traffic as much as by its cores. From these numbers
a recent phone's big cores should take roughly a second for a short
sentence and an older one several; that is an estimate, not a measurement.
`Ct2Native.MIN_TOTAL_RAM` (5.5 GB, a 6 GB phone) follows from the 1.25 GB.

Beside Bergamot, on the pairs both have (the same desktop, Bergamot's own
build of `native/bergamot-jni` with Mozilla's pinned models, load average
about 240): Bergamot took 20 to 108 ms a sentence where NLLB took seconds,
thirty to a hundred times faster. Into English the two read the same
(Bengali, Hindi, Arabic and French each gave identical or equally good
sentences). Out of English Bergamot was as good or better: NLLB's Arabic
turned "this keyboard" into "these keys" and put "cancelled" in the active
voice, both of which Bergamot got right, and its Hindi "I will be late" came
out as "I will delay". NLLB's Bengali was the more natural of the two in one
sentence. Swahili, which Bergamot lacks, came out of NLLB readable with one
wrong word ("cancelled" as "rejected"). Hence the order in the translate
list: Bergamot where it has the pair, NLLB for the rest.

Two builds of all three ABIs from fresh clones in different directories
came out byte for byte the same (NDK r29, CMake 4.x, macOS host).

Not yet tried: a vocabulary restriction for the output layer (CTranslate2's
`use_vmap`, keyed on source tokens, so it would need a target-script map
built differently), which would cut the largest matrix and its packing
buffer.
