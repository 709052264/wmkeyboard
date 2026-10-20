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

Status: not started.

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

| Kernel | Today | Expected gain |
|---|---|---|
| Embedding dot products | `VectorIndex.score`, scalar Kotlin over every vector | 8-16x with `vfmaq_f32`; int8 storage + `sdot` (armv8.2) also cuts index memory 4x |
| GIF encoding | `GifEncoder`, `AnimatedWebpToGif` | palette nearest-colour search and dithering |
| Image pre/post-processing | `PaddleOcrPipeline`, `NsfwFilter`, cutout, sticker generator, `PhotoEditRenderer` | resize, normalise, alpha mask, colour conversion |
| Glide shape scoring | inner distance loops of `GlideBeam`, `DuetBeam`, `CtcGlideBeam` | four trace points per instruction; only if Phase 0 shows it matters |
| Audio glue | PCM16 to float, resampling, energy in front of every voice engine | small per call, runs on every chunk |

Status: waiting on Phase 0.

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
