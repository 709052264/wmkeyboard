# Moving hot code to Rust, with NEON where it pays

This is the working plan for moving parts of WM Keyboard's Kotlin into
`native/wm-engine` and adding ARM NEON kernels. It records why each piece moves,
the order, and the rules every step follows. Sessions doing the work update the
status lines below as they land.

## Why, and why not everything

The July prediction perf pass found that typing latency is mostly rendering
(Skia, RenderThread, recomposition), and that the compute-heavy parts were
either already native (Harper, the AI and voice runtimes, ML Kit) or fixable in
Kotlin. So code moves to Rust for two reasons only:

1. **One engine for Android and WMK Desktop.** `native/wm-engine` already holds
   every transliteration engine and the CJK lattice for both. Prediction is the
   largest piece the desktop still lacks.
2. **Data-parallel loops**, where NEON gives several times the throughput and
   ART's JIT does not vectorise: dot products over embeddings, per-pixel image
   work, palette search, per-sample audio work.

Branchy orchestration code gains little and stays in Kotlin.

## Phase 0: measure

A JVM microbenchmark per candidate below, plus on-device timing on the
CPH2481 with the odex loaded (memory perf-pass-2026-10-16 has the Perfetto
recipe and the trap). Output: milliseconds per call, calls per keystroke or per
action, share of frame time. A candidate under about 1 ms per action, or 0.3 ms
per keystroke, stays in Kotlin unless Phase 2 moves it for desktop sharing.

Status: host JVM measured 2026-10-21; phone not measured yet (the CPH2481's
wireless-debugging pairing had lapsed, and the device pass was skipped).

**Harness:** `native/bench/phase0/` (`Phase0Bench.kt`, `run.sh`), outside
Gradle and never shipped. `run.sh host` compiles it with the Kotlin compiler
from the Gradle cache against a checkout's built `core/*` release jars and runs
it on the JVM; `run.sh device` dexes the same classes and runs them under ART
on the phone with `app_process`, so the phone column will time identical code.
Each case warms for at least a second (meant to give ART's JIT time on the phone), then times up to 400
calls. Real classes are called wherever they take plain arrays. Where the loop
sits behind `android.graphics` or a private function (NsfwFilter's CHW loop,
ImageGenEngine.toBitmap, PhotoEditRenderer.sharpenInPlace, WhisperRecorder's
chunk loop, GlideBeam.buildCosts), its body is copied verbatim onto arrays.
Inputs: random unit vectors; 512² sticker frames shaded to fill the palette; a
12 MP photo with a 25-line detector map and PP-OCRv5's 40×18,385 recogniser
output; 200 synthetic QWERTY swipes (mean 139 points, 4 ms apart) on the
bundled 17,306-word `en.txt`, where GlideBeam's top-1 is 92%. No real-swipe set
is checked in, so the FUTO test split was not used. The method splits are JFR
execution samples taken during the same runs.

**Host numbers** (M-series Mac, JDK 21, the quieter of two runs; the first run
shared the machine with another session's sweeps and read about 1.6x slower):

| Candidate | Median / p95 per call | How often | NEON-shaped share |
|---|---|---|---|
| `VectorIndex.score`, 1k × 768 | 0.74 / 0.79 ms | per meaning search, after a 300 ms pause; per AI memory recall | dots 88%; the rest is a `Hit` per item and a full sort |
| `VectorIndex.score`, 5k × 768 | 3.8 / 3.9 ms | same | dots 86% |
| `VectorIndex.score`, 20k × 768 (photos) | 17.0 / 18.0 ms | same | dots 80% |
| `GifEncoder.addFrame`, 512² | 4.4 / 4.6 ms | per frame, 30-60 per animated sticker into a GIF-only field | LZW 43% (serial hash), median cut 38%, index lookup 6%, bounds 5% |
| `GifEncoder`, whole 30-frame GIF | 135 / 141 ms | per such sticker sent | as above |
| `PaddleOcrPipeline.read` minus inference, 12 MP, 25 lines | 42 / 44 ms | per OCR read | `detectorInput`'s box average 39%, `ctcDecode` argmax 36%, `strip` bilinear 17% |
| `detectorInput`, 12 MP to 640² | 14.4 / 14.9 ms | per OCR read | all of it |
| `ctcDecode`, 40 × 18,385 | 0.22 / 0.24 ms | per 320 px piece, 25-75 per page | all of it |
| Cutout `normalise` + `alphaOf`, 320² | 0.25 + 0.16 ms | per cutout, beside a LiteRT segmentation | all of it |
| NsfwFilter CHW loop, 224² | 0.05 ms | per generated picture | all of it |
| ImageGen sampler step, 4×64×64 | 0.012 ms | per UNet step | all of it |
| ImageGen `toBitmap`, 512² | 0.61 ms | per picture and preview, beside seconds of UNet | all of it |
| PhotoEdit sharpen, 2048 × 1536 | 67 / 68 ms | per adjust change in the editor preview while sharpen > 0 (`base()` reruns) | all of it |
| PhotoEdit sharpen, 4000 × 3000 | 257 / 261 ms | per photo sent with sharpen > 0 | all of it |
| `GlideBeam.decode` | 2.2 / 6.7 ms | per swipe, at finger-up | `advance` (banded min-plus DP, per trie node) ~50%, `buildRemain` ~16%, `anchorLead` ~12% |
| `GlideBeam`, 6 live previews | 7.6 / 19.7 ms | per swipe, while the finger is down | same |
| `buildCosts` loop, 48 samples × 26 keys | 0.001 ms | per decode | the "distance loop": nothing to win |
| `CtcGlideBeam.decode` | 2.3 / 3.7 ms | per swipe, FUTO engine only | hash maps and trie walk |
| `DuetBeam.decode` | 1.1 / 5.8 ms | per two-thumb word | `advance` 89% |
| PCM16 to float + RMS, 1,600 samples | 0.001 ms | per 100 ms audio chunk | all of it |
| `WhisperMel.compute`, 5 s | 5.5 ms | per utterance, LiteRT Whisper only, beside seconds of inference | FFT |
| `WavEncoder.encode`, 5 s | 0.06 ms | per utterance, server engines only | all of it |

None of these runs per keystroke. Every "port" verdict in Phase 1 clears the
1 ms bar on the host already, and every "skip" is under 0.1 ms or under 1% of
the action it belongs to, so a phone several times slower than the Mac does
not flip a verdict. The phone column is still owed before a kernel's commit
claims a speed-up: `native/bench/phase0/run.sh device` once adb is paired.

Fixes these runs found in Kotlin, unmeasured and out of scope here:
`GlideBeam.anchorLead` takes a square root on every `advance` call when its
value depends only on the key, so it could be one table per anchor filled in
`buildCosts`. `VectorIndex.score` sorts every hit when its callers keep a few.
`GifEncoder.Palette.of` builds its used-bucket list through a boxed
`(0 until BUCKETS).filter` on every frame.

## Shared infrastructure

Built first in Phase 2 (step 0), used by every Phase 1 kernel and every
Phase 2 port. Status: done.

**Kernels** live in `wm-engine/src/kernels/`, one file per kernel family
(`dot.rs` is the first: `dot_f32`, `dot_rows_f32`, `dot_i8`). Each kernel has:

- `scalar::name`, the reference: plain Rust, the definition of the result.
  x86_64 and armv7 run it.
- `neon::name` (and `dotprod::name`, `fp16::name` for the optional armv8.2
  extensions): `#[target_feature(enable = ...)]` over stable
  `core::arch::aarch64` intrinsics. `dotprod` and `fp16` are stable in the
  pinned 1.98.1.
- `name`, the dispatcher: asks `kernels::active()` and falls back to the
  reference.
- Tests comparing every SIMD version with the reference on seeded inputs
  (`kernels::testing::Rng`) over `testing::LENGTHS`, which covers every
  tail length, within a tolerance the file states. Integer kernels are exact.

`kernels::detected()` probes once with `is_aarch64_feature_detected!`.
`kernels::set_force_scalar` pins every kernel to its reference.

**Parity helper**: `wm-engine/src/java_heap.rs` is a binary heap that pops
in exactly `java.util.PriorityQueue`'s order, ties included (a
transcription of OpenJDK's sift steps, checked against orders recorded on a
JVM). The Kotlin walks order their frontiers by score alone, so equal scores
come out in heap order; a Rust port that must match them line for line uses
this instead of `std::collections::BinaryHeap`.

**JNI** (`wm-engine-jni`), one module per Kotlin class:

- `guard.rs`: `guarded` (every entry point's body runs in it: a panic is a
  `RuntimeException` in Kotlin), `read_units`/`give_units` (strings as
  UTF-16, lone surrogates intact, no modified-UTF-8 round trip),
  `with_critical` (a primitive array pinned for a closure that makes no JNI
  call), `give_ints`, `give_all_units`, `throw_state`.
- `handles.rs`: `Registry<T>`, Rust objects behind `long` handles that are
  never reused. Each call clones the `Arc`, so closing a handle while
  another thread is mid-call cannot free what it reads; a closed handle
  throws `IllegalStateException`.
- `kernels.rs`: `NativeEngine.nativeCpuFeatures`, `setForceScalar`,
  `nativeDotRows` (one query against a row-major matrix in one crossing:
  the shape `VectorIndex.score` needs).

**Kotlin**: `NativeEngine` (`core/language`, `core.engine`) loads the
library once for every bridge (`EngineNative` now goes through it) and
holds the switch. `NativeEngine.useNative` is what a migrated piece checks;
`forceKotlin` turns every Rust path off and pins the kernels to scalar. It
is set by the "Native prediction engine" setting (`suggestionStrip.nativePrediction`,
Typing > Automatic corrections, default on; the IME pushes it on every
settings change) and defaults from `WM_NATIVE=kotlin` (or
`-Dwmkeyboard.native=kotlin` for a JVM started by hand), which is how the
parity tests and evals run the Kotlin side.

**Host builds**: every test task that can reach the library depends on
`:core:language:buildHostEngine` (`build-host.sh`), now including
`:core:prediction`'s. CI's unit-test job runs those tasks with the runner's
cargo and caches the build (`Swatinem/rust-cache`), so `unitTests` and the
glide and prediction evals load the real Rust code.

**Adding a kernel**: a file under `kernels/` in the shape above, an entry
point in `wm-engine-jni/src/kernels.rs` that pins its arrays with
`with_critical`, a `NativeEngine` method that checks `useNative` (or
`requireLoaded` when it has no Kotlin fallback), then rebuild the `.so`s
with `build.sh` and report the size change.

## Phase 1: NEON kernels

Verdicts from Phase 0, host Kotlin cost (see the table there). In priority order.

| Kernel | Kotlin cost (host) | How often | Verdict | Expected NEON gain |
|---|---|---|---|---|
| Photo editor sharpen (`PhotoEditRenderer.sharpenInPlace`) | 67 ms at the 2048 px preview, 257 ms at 12 MP | per adjust change in the editor while sharpen is on; per photo sent | **port** | 10x or more: a 3×3 integer stencil on 16 u8 lanes, no gathers. A RuntimeShader would also do it on API 33+ |
| Embedding dot products (`VectorIndex.score`) | 0.74 / 3.8 / 17 ms at 1k / 5k / 20k vectors, 80-88% in the dots | per meaning search, per AI memory recall, per sticker in a tagging pass | **port** | 4-8x on the dots with `vfmaq_f32`; at 20k the f32 scan is 61 MB and memory-bound, so int8 + `sdot` (also 4x less memory) is what reaches the top of that. `dot_rows_f32` and `dot_i8` already exist. Keep the top-k in Kotlin, or select instead of sorting |
| OCR pre/post-processing (`PaddleOcrPipeline`) | 42 ms per page outside inference | per OCR read | **port** `detectorInput`'s box average and `ctcDecode`'s argmax (75% of it); `strip` stays | 4-8x on those two: u8 pairwise sums and a 4-lane max with index. At 5x, roughly 42 to 17 ms per page |
| GIF encoding (`GifEncoder`) | 4.4 ms a frame, 135 ms per 30-frame GIF | per animated sticker sent into a GIF-only field | **port, last**, as mostly scalar Rust | small from NEON: 43% is LZW's serial hash chain and 38% median cut. There is no nearest-colour search or dithering to vectorise (the palette is a lookup table). Rust's gain is bounds checks and allocation, maybe 2x. Try the Kotlin boxing fix first |
| Glide shape scoring (`GlideBeam`, `DuetBeam`, `CtcGlideBeam`) | 2.2 / 6.7 ms per decode, 7.6 / 19.7 ms per stroke of previews; the distance loop is 1 µs | per swipe | **skip as a Phase 1 kernel**; Phase 2 step 4 ports the decoders | the time is in `advance`, a banded min-plus DP called per trie node. A JNI call per node would cost more than it saves, so vectorise its band (4-lane min/select, perhaps 2x on a decode) inside the Rust decoder |
| Cutout, NSFW filter, sticker generator glue | 0.42, 0.05 and 0.012-0.61 ms | per cutout, picture or UNet step, each beside a network that takes hundreds of ms to seconds | **skip** | under 1% of the action |
| Audio glue (PCM16 to float, energy, `WhisperMel`, `WavEncoder`) | 1 µs per 100 ms chunk; 5.5 ms mel and 0.06 ms WAV per utterance | per chunk; per utterance | **skip** | under 1% of capture or inference. No Kotlin resampler exists: capture is at 16 kHz already |

None is "port only for desktop": the glide decoders are the shared piece, and
Phase 2 already moves them.

Status: verdicts in from host numbers; the phone column is owed (see Phase 0).
Kernel sessions may start in the order above.

## Phase 2: prediction core in Rust

Leaves first; Kotlin keeps the orchestration until the end.

1. `.wmdict` and `.wmng` readers, memory-mapped in Rust (the July "phase 3").
2. `FuzzyBeamSearch`, edit candidates and `KeyProximity`, `NgramReranker`, `FrequencyCodec`.
3. `UserLexicon` and the learning stores.
4. The glide decoders: `GlideBeam`, `DuetBeam`, `CtcGlideBeam`'s decode.
5. `SuggestionEngine` last, or never: its orchestration can stay Kotlin.

Each step keeps the Kotlin implementation until a parity test shows identical
output on the real dictionaries and on the glide eval sets, then deletes it.

### Step 1: the readers (done)

`MappedTrie`'s word-level queries (`frequencyOf`, `contains`, `complete`,
`entries`) and the whole of `MappedNgramPack` run in Rust: `wmdict::Dict`
and `wmng::Pack` over a `memmap2` mapping in `wm-engine-jni/src/dict.rs`,
behind `PredictionNative` handles that `NativeHandles` closes once the
Kotlin object is unreachable. The Kotlin reader code for those queries is
deleted. `MappedTrie` still maps the file in Kotlin too, for the node-level
`TrieWalker` methods the fuzzy walk, glide and the coverage checks read
node by node; those go when the walks move (steps 2 and 4).

- Completion order: `TrieCompleter` pops a `PriorityQueue` ordered by
  frequency alone, so equal frequencies come out in heap order. `Dict::complete`
  now uses `java_heap::JavaHeap` with the same pushes in the same order, so
  ties match too, and WMK Desktop gets the phone's order.
- N-gram strings are looked up as Kotlin's `toByteArray(UTF_8)` encodes them
  (a lone surrogate as `?`), through the new `*_bytes` methods.
- Parity: `NativeReaderParityTest` (`:app`). Against `PackedTrie`/`TrieCompleter`
  and against the n-gram counts the packs were built from, on the bundled
  en and bn lists, a 296k-word synthetic list with non-BMP, lone-surrogate
  and Hangul words, and (with `PARITY_DOWNLOADS`) the data repo's en, ko and
  th lists at the Large cap and the en and bn n-gram packs. Before the
  deletion it also ran the Kotlin readers on the same files. Every query
  agreed: 1.29M completions, every word's frequency, every entry in order,
  every vocabulary id, count and follower list.
- Size: libwm_engine_jni arm64-v8a 1,114,720 -> 1,177,680 B (+62,960),
  armeabi-v7a 784,728 -> 831,264 (+46,536), x86_64 1,245,760 -> 1,311,984
  (+66,224).
- Latency (`MappedTrieLatencyBench`, `PredictionLatencyBench`, two runs each,
  load average 3.5-4.3 with other sessions running): `frequencyOf` p50 0.75
  -> 0.42-0.54 us, `complete(3, 10)` p50 3.6-8.3 -> 5.1-5.3 us (p99 14-35 ->
  10-11 us), `contains` 0.21-0.29 -> 0.38 us; a whole keystroke
  (suggest + autocorrect) p50 0.404-0.457 -> 0.409-0.427 ms, unchanged.
  `pack.bigramCount` is about 1 us against Kotlin's sub-microsecond, because
  the Kotlin reader's head cache is gone; it moves inside Rust with
  `NgramReranker` in step 2.
- Evals: the `core.prediction.eval` family run before (041a6921a) and after
  gives byte-identical `metrics.json` and `context_miss.json` and the same
  report lines: top1 0.8195, top3 0.9300, MRR 0.8741, autocorrect precision
  0.9853, recall 0.7015, false corrections 0.

Status: step 1 done; steps 2-5 not started.

## Rules for every step

- **Home:** kernels and ported code go in `wm-engine` / `wm-engine-jni`. That
  library is in the APK, loads at startup and in direct boot, and never moves
  to an on-demand gate. Typing cannot wait for a download.
- **NEON:** stable `core::arch::aarch64` intrinsics, not nightly `std::simd`.
  Runtime detection (`is_aarch64_feature_detected!`) for `dotprod` and `fp16`.
  armv7's NEON intrinsics are unstable in Rust, so armv7 runs the scalar
  reference (built with the target's `+neon`, which LLVM may vectorise);
  x86_64 and anything else get the scalar path too.
  Every kernel keeps a scalar reference implementation and a test comparing
  the two within a stated tolerance.
- **JNI:** batch work per call, never one crossing per element. Primitive
  arrays through `GetPrimitiveArrayCritical`, or direct ByteBuffers. Mapped
  data stays on the Rust side behind handles. Every entry point runs inside
  `catch_unwind`, so a panic becomes a Java exception, not a dead keyboard.
- **Tests and evals:** a host build of `wm-engine-jni` (macOS and Linux,
  `build-host.sh`) is built before JVM tests and in CI, so the `:app` suite and
  the glide evals exercise the Rust code, not a stub.
- **Builds:** reproducible as today (F-Droid builds `wm-engine` from source),
  16 KB aligned, only JNI symbols exported, size change reported per commit.
- **Not moving:** Compose UI, `WMKeyboardService` orchestration, settings, the
  AI and voice runtimes (already native), anything I/O or network bound.

## Risks

- JNI overhead on small calls can eat the gain; Phase 0 and batching catch it.
- Native crashes are harder to read: `catch_unwind`, symbols kept for the
  retrace bot, and a setting that forces the scalar Kotlin path during the
  migration.
- Two implementations during migration: parity tests gate every deletion.
- ART already vectorises a few simple loops: measure before porting.
