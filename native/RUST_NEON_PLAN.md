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
`(0 until BUCKETS).filter` on every frame (fixed before the Rust port, see
"GIF encoder" under Phase 1).

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
holds the switch. While a piece had both implementations it checked
`NativeEngine.useNative`, and `forceKotlin` turned every Rust path off and
pinned the kernels to scalar; a "Native prediction engine" setting drove it.
Once every Kotlin copy had moved to the test sources (step 4) the setting
reached nothing and was removed before it shipped. `forceKotlin` still pins
the kernels, and defaults from `WM_NATIVE=kotlin` (or
`-Dwmkeyboard.native=kotlin` for a JVM started by hand); the parity tests
now run the Kotlin oracles directly.

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
| Photo editor sharpen (`PhotoEditRenderer.sharpenInPlace`) | 67 ms at the 2048 px preview, 257 ms at 12 MP | per adjust change in the editor while sharpen is on; per photo sent | **done** (`kernels/sharpen.rs`) | measured 18x at 2048 px and 11x at 12 MP in the renderer's bands (host); see the status line below |
| Embedding dot products (`VectorIndex.score`) | 0.74 / 3.8 / 17 ms at 1k / 5k / 20k vectors, 80-88% in the dots | per meaning search, per AI memory recall, per sticker in a tagging pass | **done** (f32; int8 measured and not adopted, see below) | measured 7-8x per search: 0.094 / 0.48 / 2.0 ms on the host |
| OCR pre/post-processing (`PaddleOcrPipeline`) | 42 ms per page outside inference | per OCR read | **port** `detectorInput`'s box average and `ctcDecode`'s argmax (75% of it); `strip` stays. **Done** (host), see below | 4-8x on those two: u8 pairwise sums and a 4-lane max with index. At 5x, roughly 42 to 17 ms per page. Measured on the host: box average 15.0 to 2.8 ms (5.3x), argmax 0.23 to 0.060 ms per piece (3.8x), the whole `read` 43-45 to 21-22 ms |
| GIF encoding (`GifEncoder`) | 4.4 ms a frame, 135 ms per 30-frame GIF | per animated sticker sent into a GIF-only field | **port, last**, as mostly scalar Rust. **Done** (host), see below | small from NEON: 43% is LZW's serial hash chain and 38% median cut. There is no nearest-colour search or dithering to vectorise (the palette is a lookup table). Rust's gain is bounds checks and allocation, maybe 2x. Try the Kotlin boxing fix first. Measured on the host: the Kotlin fix 4.36 to 3.65 ms a frame, then Rust 1.59 ms (2.7x over Phase 0), 130.5 to 47.7 ms a GIF |
| Glide shape scoring (`GlideBeam`, `DuetBeam`, `CtcGlideBeam`) | 2.2 / 6.7 ms per decode, 7.6 / 19.7 ms per stroke of previews; the distance loop is 1 µs | per swipe | **skip as a Phase 1 kernel**; Phase 2 step 4 ports the decoders | the time is in `advance`, a banded min-plus DP called per trie node. A JNI call per node would cost more than it saves, so vectorise its band (4-lane min/select, perhaps 2x on a decode) inside the Rust decoder |
| Cutout, NSFW filter, sticker generator glue | 0.42, 0.05 and 0.012-0.61 ms | per cutout, picture or UNet step, each beside a network that takes hundreds of ms to seconds | **skip** | under 1% of the action |
| Audio glue (PCM16 to float, energy, `WhisperMel`, `WavEncoder`) | 1 µs per 100 ms chunk; 5.5 ms mel and 0.06 ms WAV per utterance | per chunk; per utterance | **skip** | under 1% of capture or inference. No Kotlin resampler exists: capture is at 16 kHz already |

None is "port only for desktop": the glide decoders are the shared piece, and
Phase 2 already moves them.

Status: verdicts in from host numbers; the phone column is owed (see Phase 0).
Kernel sessions may start in the order above. Embedding dot products: done
(f32), host numbers only. OCR kernels: done, host only. GIF encoder: done,
host only, the last item: **Phase 1 is done** on host numbers; the phone
column is still owed for every kernel.

**Sharpen: done 2026-10-21, host only.** `kernels::sharpen::sharpen_argb`,
`NativeEngine.sharpen` (false without the library or with `useNative` off,
and `PhotoEditRenderer.sharpenRows` runs instead), one call per 256-row band
of `sharpenInPlace`. The Kotlin channel maths is `f32`, not integer, so the
kernel is too: `ld4` splits sixteen pixels into channel registers, the
neighbour sum is exact in `u16`, and the multiply, add and round run on four
`f32` lanes in Kotlin's order with no fused multiply-add. `fcvtas` rounds
half away from zero, which is `Math.round` for everything not clamped to 0.
The output is bit for bit Kotlin's: Rust tests cover every tail width,
1 to 5 rows and every centre value against every neighbour sum at each
slider step, and the harness compares the JVM loop with the native call on
both photo sizes at all 20 steps. Same-run host numbers (the machine was
shared, load 10 to 12 on 8 cores, so the Kotlin side read 1.2 to 1.4x slower
than Phase 0's):

| Case | Kotlin | Native, one call | Native, 256-row bands |
|---|---|---|---|
| 2048 × 1536 | 96 ms | 4.4 ms (22x) | 5.3 ms (18x) |
| 4000 × 3000 | 307 ms | 19.5 ms (16x) | 27.7 ms (11x) |

The bands column copies each band into a fresh array, as `getPixels` does,
and is the shape the renderer runs; the bands stay so a 12 MP picture is never
held as one 48 MB `IntArray`. Phone column owed (`run.sh device` now pushes
the arm64 `.so` and names it with `WM_ENGINE_LIB`). Library growth over
main's build with the glide decoders: arm64 +4,400 bytes, armv7 +2,160,
x86_64 +3,304. armv7 and x86_64 run the Rust
scalar reference, unmeasured.

A RuntimeShader on API 33+ was not built. It does not fit `base()`, which
draws on a software canvas so the vignette, grain and later layers can share
it; on that canvas Skia would interpret the shader per pixel on the CPU, and
the GPU route means uploading the picture, rendering through a
`HardwareRenderer` and reading 48 MB back at 12 MP before the next software
stage. It also cannot match Kotlin: AGSL sees premultiplied colours as
normalised floats (half precision allowed) and Skia rounds them back its own
way, so API 33+ and older devices would sharpen differently. It cannot be
timed on the host either (no Android graphics there), and the phone is not
paired. So NEON runs on every API level. What is left on a phone is
`getPixels`/`setPixels`, which unpremultiply and premultiply every pixel, a
cost the host harness cannot see and the phone run should time; locking the bitmap's own pixels
(`AndroidBitmap_lockPixels`) would remove both copies but changes results
for translucent pixels, so measure it on the phone first.

### Embedding dot products (done, f32)

`VectorIndex` keeps its vectors in one row-major `FloatArray` and scores
them with `NativeEngine.dotRows` in one crossing; the Kotlin loop stays as
the fallback when `useNative` is off. Callers now ask
`search(query, tuning, accept)`, which returns exactly what
`MeaningRank.select(score(...))` did, hits and order: the median comes from
a quickselect and only the hits over the bar are ranked, at most the
tuning's limit, so no `Hit` per item and no full sort. `VectorIndexParityTest`
holds the kernel to the Kotlin loop (same hits and order, scores within
`dot.rs`'s 1e-5; neighbours closer than that may trade places) and `search`
to `select`. The `.so` did not change: `dot_rows_f32` and `nativeDotRows`
came with the shared infrastructure.

Host numbers from the Phase 0 harness (`only=vector`, same machine and
load for both columns). "Before" is `score` alone; the old callers then
also ran `select`, whose median sorted a boxed copy of every score.

| Vectors × 768 | Before (Kotlin `score`) | After, Kotlin fallback (`search`) | After, NEON (`search`) | Heap before / after (read from file) |
|---|---|---|---|---|
| 1k | 0.69 ms | 0.63 ms | 0.094 ms | 3 / 4 MB (GC noise at this size) |
| 5k | 3.70 ms | 3.18 ms | 0.48 ms | 16 / 17 MB |
| 20k | 16.9 ms | 12.8 ms | 2.03 ms | 64.5 / 65.5 MB |

An index built a batch at a time grows by an eighth, so it holds at most
an eighth more than it needs (71.8 MB at 20k); `read` sizes it exactly. The
file format did not change.

**int8, measured and not adopted.** The test was the brief's: adopt only if
the top hits stay essentially identical. Inputs were real EmbeddingGemma 2
outputs: 4,200 documents (docs-site paragraphs, and the app's strings in
English, Bengali and French as short clip-like texts) and 380 queries
(headings and 2-4 word fragments), embedded with the models release's text
encoder through Python LiteRT, plus the 1,716 shipped emoji vectors. Each
variant ran through a float32 copy of `MeaningRank.select` with the
shipped tunings and was compared with f32:

| Storage | Top hit changed (text / emoji / sticker tags) | Kept-set Jaccard | Largest score error |
|---|---|---|---|
| int8 per vector, int8 query (`sdot`) | 2.1% / 2.6% / 4.3% | 0.93-0.97 | 0.0050 |
| int8 after centring and a block-Hadamard rotation | 0.5% / 0.3% / 1.1% | 0.984-0.995 | 0.0014 |
| fp16 | 0% / 0% / 0.1% | 0.997-0.998 | 0.00016 |

The model's vectors share one direction (the mean vector's norm is 0.82)
and one dimension carries a large share (largest magnitude 0.31, against
an RMS of 0.036), so a per-vector int8 step is about 9x coarser than a
typical value. The tunings separate hits that sit 0.01-0.02 apart (the
median gap between the first and second hit is 0.015), and an error of a
few thousandths reorders them. The int8 build was fast, 0.029 / 0.17 /
0.77 ms per search with 22 MB at 20k, but it changed what people would see.
fp16 rows (half the memory, widened to f32 with `vcvt` and summed with
`vfmaq_f32`) keep the ranking and would be the next step if the 20k scan
matters on a phone.

### OCR kernels (done)

`kernels/box_average.rs` (`box_average_bgr`) and `kernels/argmax.rs`
(`argmax_rows`), JNI `NativeEngine.nativeBoxAverageBgr` (one crossing per
photo) and `nativeArgmaxRows` (one per block of logits, 40 × 18,385 per
piece). `PaddleOcrPipeline.detectorInput` and `ctcDecode` call
`NativeEngine.boxAverageBgr` / `argmaxRows`, which answer false when
`useNative` is off (the library is absent, or `forceKotlin` is set), and
the Kotlin loops run then. Unlike prediction's, these Kotlin copies stay.
A photo the detector grows (scale 1 or more) stays on the Kotlin bilinear.

- Box average: not pairwise adds after all. A box is 6 or 7 photo pixels
  wide at 12 MP and its edges move with `floor`/`ceil`, so the kernel sums
  each box's rows down every column first (`vld4q_u8` splits sixteen ARGB
  pixels into planes, `uaddw` widens into `u16`, six registers per sixteen
  columns), stores the sums `[b, g, r, 0]` per column, then adds each
  output pixel's columns as one `u32x4` and normalises the three channels
  in one vector. Integer sums and the same IEEE divides and subtracts in the
  same order make it bit-identical to the Kotlin, not just close. A box
  taller than 257 rows (a photo over about 165,000 pixels on its long side)
  would overflow the `u16` sums and takes the scalar reference.
- Argmax: four accumulators of four lanes, each a value and an index moved
  only on a strict `fcmgt`, reduced by largest value, then smallest index
  among the lanes equal to it, then the tail by the same strict rule. So a
  tie gives the first index, `-0.0` ties `0.0`, a NaN never wins and a row
  starting with a NaN answers 0, all as the Kotlin scan does.
- Parity: Rust tests compare NEON with the reference bit for bit (ten photo
  sizes including every column tail, saturated white, tall boxes; argmax
  over `testing::LENGTHS` and 18,385 with ties, zeros, NaNs and
  infinities). `PaddleOcrNativeParityTest` (`:core:intelligence`) compares
  the Kotlin with the native path on generated pages of printed text (AWT,
  level, tilted and vertical lines, sensor noise; 4000×3000, 3024×4032,
  641×480, 1203×1601, 2000×37, 37×2000, 4096², and the grown sizes): the
  detector input bit for bit, `ctcDecode`'s text and confidence over six
  kinds of logits, and the lines of a whole `read` through a stand-in
  runtime whose detector and recognizer answer from the pixels they are
  given, so any difference upstream would change the text. No PaddleOCR
  graph runs on the JVM, so that stand-in is as close to the real models
  as the test gets; the kernels being exact is what carries the result.
- Speed (`native/bench/phase0/run.sh host only=ocr`, `WM_NATIVE=kotlin` for
  the before, two runs each, load average 6.8-7.6): `detectorInput` 12 MP
  14.8-15.3 to 2.76-2.88 ms; `ctcDecode` 0.224-0.234 to 0.060 ms; `read`
  minus inference 43.1-45.1 to 21.0-21.6 ms. What is left of `read` is
  mostly `strip`'s bilinear and the bench's stand-in copying 2.9 MB of
  logits per piece. Phone not measured (no pairing).
- Size, against a build of the main it merged into (55a8517e0, with the
  sharpen kernel): libwm_engine_jni arm64-v8a 2,120,680 -> 2,129,208 B
  (+8,528), armeabi-v7a 1,546,856 -> 1,550,248 (+3,392), x86_64 2,426,960
  -> 2,433,080 (+6,120). Segment padding makes these jumpy: main's build
  with and without sharpen is the same size, and the same two kernels
  measured +4,048 / +1,296 / +2,816 on the main before it.

### GIF encoder (done, in Rust)

`wm_engine::gif` holds the whole encoder: the header, `FrameEncoder::frame`
(bounds, palette, index mapping, LZW, with its tables kept for the next
frame) and an in-memory `Encoder` for WMK Desktop. The per-pixel loops are
the kernel family `kernels/gif.rs`. JNI `gif.rs` serves `GifNative`
(`core/content`): a frame is one crossing, its pixels pinned and its bytes
returned as one `byte[]`, with the tables in a thread-local. `GifEncoder`
keeps its API and writes what Rust returns; it calls `requireLoaded`, so
there is no Kotlin fallback. The Kotlin encoder moved to
`feature/ime/src/test/.../core/media/reference/KotlinGifEncoder.kt`, as
prediction's did, and `GifParityTest` compares whole `.gif` files from the
two: the animated WebP fixture and a lossy 512 px WebP sticker decoded on
Robolectric's native graphics, 30 smooth sticker frames, flat cartoon
frames, and edge cases (one pixel, one row or column, nothing drawn, alpha
127 against 128, 1 to 300 colours, noise that refills the code table,
delays past both clamps, no frames).

- The Kotlin fix came first and is in the oracle too: `Palette.of` built its
  used-bucket list through a boxed `(0 until BUCKETS).filter`, its median
  cut boxed indices on every split, and each frame allocated about a
  megabyte of histogram, lookup and hash tables. The encoder now keeps them
  and clears what the frame used. Same bytes.
- Exactness is free here: everything is integer. The median cut sorts by
  (channel, bucket) as Kotlin's `LongArray` key did; buckets are distinct,
  so a run over 96 long sorts by setting a bit per key in a 2^15-bit map and
  reading the bits back, and a shorter one with `sort_unstable`. LZW's
  output depends only on what its table holds, so the Kotlin hash became a
  table indexed by `prefix << data_bits | index` (2 MB at 8 bits), cleared
  through the list of slots it set rather than wholesale.
- NEON, where it is lane-parallel: the bounds test sixteen pixels per step
  (alpha 128 and up is the top bit) and, in a row that draws anything,
  read only in from each end; the histogram and the index mapping work out
  four buckets at a time with shifts, masks and the opacity select, and
  leave the table accesses to scalar code, since NEON has no scatter or
  gather. The histogram also folds runs of one bucket into lane sums, one
  table update per run: Kotlin's per-pixel update waits on the last when
  neighbours share a bucket.
- Host, Phase 0 harness, same runs. The Kotlin fix: 4.36 to 3.65 ms a
  512² frame, 130.5 to 109.3 ms a 30-frame GIF. Then Rust, against the
  fixed Kotlin: 3.61 to 1.59 ms a frame (1.98 with the kernels pinned to
  scalar), 108.2 to 47.7 ms a GIF (59.6 scalar). From Phase 0's 4.4 / 135
  ms that is 2.7x. Inside Rust, a frame of the bench's smooth sticker is
  about 0.40 ms histogram, 0.37 median cut, 0.63 LZW, 0.07 index mapping
  and 0.02 bounds (0.41 for the scalar bounds). The histogram's run folding
  does nothing for that sticker, whose bucket changes every two or three
  pixels, but takes a flat cartoon frame's histogram from 0.49 to 0.09 ms.
  Phone column owed.
- Size, against the main it merged into (c8d8068f3, with the OCR
  kernels): libwm_engine_jni arm64-v8a 2,129,208 -> 2,149,240 B (+20,032),
  armeabi-v7a 1,550,248 -> 1,562,240 (+11,992), x86_64 2,433,080 ->
  2,453,056 (+19,976).

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

### Step 2: the correction walk (walk done)

`FuzzyBeamSearch.search` runs in Rust (`wm-engine`'s `fuzzy::search_units`,
JNI `PredictionNative.fuzzySearch`, one crossing per search) whenever every
trie it is given has a Rust twin: a `MappedTrie` is one, a `PackedTrie` gets
a copy of its codec bytes on first walk (`NativeFuzzySearch`), and an empty
trie is skipped. `KeyProximity` and `EditHabits` cross once by handle; the
touch frame and the key sets cross as arrays per search. Touch scoring,
ambiguous keys and their multi-unit spellings, learned habits, accents, the
half-space and supplementary letters are all in the port.

- Exactness: the Rust walk reproduces `BeamWorkspace`'s heap, push order,
  merge rule, k-th-best floor, `Math.max` and `coerceIn` semantics, UTF-16
  words and String ordering. Logarithms go through `strict_math::log`, a
  transcription of fdlibm that matches `StrictMath.log` bit for bit over
  3.4M inputs; the platform `ln` (Apple libm, bionic) differs from it in
  about 1% of results. The Kotlin walk calls `StrictMath.log` too, so the
  two agree on every runtime; on the aarch64 JVM the evals run on,
  `Math.log`, `exp` and `pow` already equal `StrictMath` (8M samples), so
  that switch changed no number there. x86_64 HotSpot's `Math.log` is an
  intrinsic that need not be fdlibm, which is why the switch was made.
- Parity: `NativeFuzzyParityTest` (`:app`), the prediction eval's 2,000 typo
  cases and 2,000 clean words over the bundled English list, through the
  plain walk, touch evidence, learned habits, every edit budget, mapped and
  in-memory twins and a user tier together, T9 frames, a nukta key, accents,
  half-spaces and Osage letters: 17,494 searches, 122,693 candidates, every
  score, edit cost, count, tier and accent bit-identical.
- Evals: the `core.prediction.eval` family (whose engine walks a
  `PackedTrie` twin and skips its empty lexicon, so it runs in Rust) gives
  byte-identical `metrics.json`, `context_miss.json` and report lines.
- Latency (`PredictionLatencyBench`, two runs per side, load average 2.2-2.6):
  keystroke (suggest + autocorrect) p50 0.442/0.449 -> 0.318/0.322 ms, p99
  0.649/0.656 -> 0.466/0.475 ms; octopus keystroke p50 0.508/0.560 ->
  0.348/0.346 ms.
- Size: arm64-v8a 1,177,680 -> 1,285,552 B (+107,872), armeabi-v7a 831,264
  -> 923,592 (+92,328), x86_64 1,311,984 -> 1,439,552 (+127,568): the walk,
  and the accent fold tables it is the first JNI caller of.
- Not deleted yet: the Kotlin walk still serves any search with a non-empty
  `Trie` (the user lexicon, the system personal dictionary), which has no
  Rust twin until step 3. `KeyProximity` stays in Kotlin for its other
  readers (glide, autocorrect's space-slip and digit checks).
  `NgramReranker` needs the lexicon's counts and moves with step 3.
  `FrequencyCodec` was ported earlier (`freq_codec`); its Kotlin file is one
  of the five `:tools:dictc` shares and stays.

### Step 3: the user lexicon and the learning stores (done)

`UserLexicon`, `NgramReranker`, `CorrectionMemory` and `CorrectionStats` are
facades over `wm-engine` (`user_lexicon`, `rerank`, `correction_memory`,
`correction_stats`; JNI `lexicon.rs`, `rerank.rs`, `corrections.rs`). The
Kotlin implementations are deleted from production and kept under
`app/src/test/.../prediction/reference/` as the parity oracles. The lexicon's
trie crosses to the fuzzy walk and the reranker by handle (`LexiconTrie`),
so every production trie now has a Rust twin.

- What stays in Kotlin: Unicode case folding (`WordKey.of`) and the
  learnability gate (`WordContext.isLearnableWord`). A load is two phases:
  Rust parses the file, Kotlin judges and folds each word, Rust builds. The
  reranker's caller folds the context and candidates the same way, and the
  correction stores take keys already folded.
- Ties made deterministic first (dfda1e508): wherever the Kotlin stores let
  `HashMap` iteration order pick a winner (trie children, follower and pair
  eviction, compaction), they now break ties by the word, and the Rust port
  matches that. Java's hash order is not portable, so this is the one
  behaviour change of the step. It moves one glide eval line: in
  `finished-stroke-guess.txt` eager mode, top1 0.943 -> 0.942 and one new
  hijack (where -> when) in four lines of the report, and 0.327 -> 0.325 in
  the fifth. The Kotlin stores at dfda1e508 give that same file byte for
  byte, so the Rust port itself moves nothing. Every other glide eval
  report and the whole `core.prediction.eval` family are byte-identical.
- File formats: the Rust stores read and write the same JSON. The parity
  tests save from both sides, compare the files as JSON values, cross-load
  each side's file, and load old and damaged files into both (both
  skip-store namings, files from before each additive field, junk words
  saved before the gate, the old 200-count marker, damaged correction
  files).
- Parity: `NativeLexiconParityTest` (random runs of every lexicon operation
  with every query, the trie node by node, compared after each step;
  compaction; old files), `NativeCorrectionsParityTest` (runs past every cap,
  expiry and halving window; old and damaged files), `NativeRerankParityTest`
  (strips over a lexicon trained on sentences drawn from the bundled English
  list, with capitals, the skip-gram backoff, recent-word bags and repeats).
  The existing `UserLexiconTest`, `CorrectionMemoryTest`,
  `CorrectionStatsTest` and `NgramRerankerTest` pass unchanged, as does the
  rest of the `core.prediction` package (951 tests).
- Latency (`PredictionLatencyBench`, two runs per side, load average
  2.8-3.3): keystroke p50 0.330/0.321 ms, p99 0.490/0.482 ms, against
  0.318/0.322 and 0.466/0.475 at step 2: unchanged within noise. The bench
  has no learned words, so it measures the crossing, not a busy lexicon.
- Size: arm64-v8a 1,285,552 -> 1,887,872 B (+602,320). Over a third of
  that is `core::slice::sort`'s stable sort instantiated once per
  comparator (121 copies in the step's code, 208 KB before stripping); the
  stores sort their exports, saves and evictions by distinct keys. Routing
  them through a few shared sort-key types would get most of it back.

### Step 2 follow-up: the Kotlin walk deleted

A plain `Trie` now has a twin too: `wm-engine`'s `UserTrie` (the `Trie` port
the lexicon already used) filled with the same entries, rebuilt whenever the
trie's new `version` counter has moved (`sources.rs`, source kind 2). It
could not be a `.wmdict`: the codec rounds frequencies (`FrequencyCodec`),
and an exact-count `Trie` read through one scored differently. Only tests
build a plain `Trie`, so every walk the keyboard makes has a twin, and the
Kotlin walk moved to `app/src/test/.../prediction/reference/` as
`KotlinFuzzyBeamSearch`, the oracle `NativeFuzzyParityTest` now compares
against. `FuzzyBeamSearch.search` keeps its signature; its `BeamWorkspace`
argument carries nothing.

### Step 4: the glide decoders (done)

`GlideBeam` (decode, align, sampleShape), `DuetBeam` and `CtcGlideBeam`'s
beam search run in Rust: `wm-engine`'s `glide` (`mod.rs`, `keymap.rs`,
`loops.rs`, `workspace.rs`, `duet.rs`, `ctc.rs`), JNI `glide.rs`, Kotlin
`NativeGlide`. The Kotlin decoders, their workspaces and `LoopScan` moved to
`app/src/test/.../gesture/reference/` as the oracles; `GlideWorkspace` and
`DuetWorkspace` keep only their size constants, and the decoders still take
them so no caller changed.

- Learned shapes stay in Kotlin (`GlideShapeStore`). A decode that reads
  them is two calls on one thread: the walk returns the words the shape
  channel will score (readings, then guesses) and the drawn stroke
  quantised; Kotlin looks up those words' shapes and, with seeding on, the
  words stored near the stroke; the finish call runs the shape channel, the
  seeding and the guess merge. Key maps cross once, as a handle on
  `GlideKeyMap`; each source's vocabulary cap crosses as the frequency
  Kotlin reads off `frequencyAtRank`.
- Exactness: f32 where Kotlin has Float and f64 where it has Double, in the
  same order of operations; Kotlin's `Long * Float` sample clock; the
  heaps, pools and quickselect swap for swap; `StrictMath` log and pow on
  the Kotlin side (no number moved on the aarch64 JVM).
- Parity (`NativeGlideParityTest`, oracles against production): the gesture
  eval corpus at every noise level plus a dwelling, looping one, against
  the bundled English list as a `Trie` and as a `PackedTrie` with a user
  tier of hyphen, apostrophe and dot words, under the shipped weights,
  wiggles, the vocabulary cap, wide anchors and seeding, with and without
  look-ahead and learned shapes: 11,250 decodes, 104,940 candidates and
  1,500 alignments and sampled shapes bit-identical. `DuetBeam` over the
  two-thumb corpus, clocked and clockless, whole words and prefixes: 9,000
  decodes, 46,123 candidates. `CtcGlideBeam` over emissions peaked along
  sampled words with noise, at both beam widths and under the cap: 2,724
  decodes, 21,792 candidates.
- Evals: the whole `core.gesture.eval` family (15 suites, the shift-detour
  eval included and not re-baselined) prints the same numbers and writes
  byte-identical reports before (step 3's merge, Kotlin decoders) and after.
  The only test changes are three loop tests that read the decoder's
  workspace, which now read the oracle's.
- Latency (`GestureLatencyBench` and `PredictionLatencyBench`, before at
  step 3's merge and after, alternating, two runs each; the machine was not
  idle, load average 8-17 with other sessions building): a whole stroke
  with six previews p50 13.5/14.3 -> 10.4/11.3 ms, p99 48.9/55.0 -> 42.3/43.7
  ms; one decode against the 4x lexicon p50 4.45/4.41 -> 3.42/4.54 ms; with
  a 6,445-word shape store 4.15/3.59 -> 3.12/2.68 ms. Typing walks the same
  Rust code before and after, and its keystroke p50 (0.382/0.345 -> 0.490/
  0.382 ms) moved with the load, not the change. Worth re-running idle.
- Size: arm64-v8a 2,010,704 -> 2,116,264 B (+105,560), armeabi-v7a
  1,462,992 -> 1,544,696 (+81,704), x86_64 2,297,184 -> 2,423,656
  (+126,472), against step 3's libraries, which already carried an unwired
  early build of the stroke decoder. The whole of glide against a step-3
  arm64 build without it: 1,887,872 -> 2,116,264 (+228,392).

### Step 5: what stays in Kotlin, and what porting it would take

`SuggestionEngine` stays in Kotlin, as planned: 5,031 lines of
orchestration over the Rust pieces, 41 public entry points. Most of what it
does is deciding which engines to ask and mixing their answers; the heavy
loops it calls are Rust now. For WMK Desktop, which already runs
`wm-engine`'s `predict::Predictor` (the typed path: `suggest`, `decide`,
the learning calls), porting the rest would mean:

- **Glide.** The decoders are done; desktop needs the orchestration around
  them: the engine's glide entry points (`tuneGlide` through
  `glideShapeOf`, about 400 lines) routing between `GlideBeam`, `DuetBeam`
  and the other `GlideDecoder`s, the contraction, apostrophe and trigger
  sources it builds per call, `GlideCoverage` (170 lines), `GlideCase`'s
  letter casing (335) and a shape store: `GlideShapeStore` (506) is Kotlin,
  file format and all.
- **The neural reading** (`NeuralGlide`): FUTO Swipe's encoder and decoder
  are ExecuTorch programs run on the app's ExecuTorch runtime
  (`core/voice`'s `FutoSwipe`). Desktop needs that runtime, or the models
  converted for one it has, and `SwipeFrame`'s features (`SwipeModel.kt`,
  194 lines); `glide::ctc` then reads the emissions as it does here.
- **Phonetic strips** (`phoneticCandidates`, `phoneticCommit`,
  `completionLatin`, `RomanizedIndex`): the transliteration engines are Rust
  already; how their readings are ranked into a strip, and the romanized
  index glide reads them through, are Kotlin.
- **Field and language context** (`seedFieldContext`, `owningLanguages`,
  the per-field mix): `language_mix` is ported; the field seeding and the
  language registry lookups are not.
- **Casing and display** (`displayOf`, `DictionaryCapitals`, `WordCase`):
  small, but Unicode case folding is the JVM's; desktop would take ICU4X
  or Rust's own tables and accept that a handful of folds differ.
- **What desktop already has twice.** `predict::Lexicon` predates
  `user_lexicon::UserLexicon` and keeps its own trie and n-gram maps. It
  should become a wrapper over `UserLexicon`, as the correction stores'
  desktop wrappers are over theirs, so the two apps read one file format
  through one implementation.
- **Not worth porting:** contacts, app names, the system personal
  dictionary, notifications, anything that reads Android providers. Desktop
  has its own sources for those, and they enter the walk as sources like
  any other.

A full port is a few thousand lines and the main risk is behaviour drift in
the mixing, which no parity test covers today: the next step for desktop
would be an engine-level parity harness (Kotlin `SuggestionEngine` against
`Predictor` on the prediction eval's sessions), then moving one entry point
at a time behind it.

Status: steps 1 to 4 done, step 5 written up; Phase 2 complete.

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
