# asr4all-jni: Offline Asr4All on a selective ExecuTorch

The Asr4All models (#289) are streaming English speech recognisers exported
for [ExecuTorch](https://github.com/pytorch/executorch): a CTC acoustic model
that commits 40 ms frames as audio arrives, and a second pass that adds
punctuation, capitals and corrections. This directory is the runtime for them.
Kotlin entry points: `Asr4AllNative` (this bridge), `Asr4AllStream` (the
streaming decode and the second pass) and `Asr4AllEngine`, all in
`core/voice/src/main/.../asr4all`.

The bridge is a plain tensor pipe: open a `.pte`, describe a method's tensors,
run a method. Nothing in it knows what a model is for, so another ExecuTorch
model needs only its own Kotlin pipeline and, if it uses operators this build
leaves out, a longer `ops.txt`.

It is not the published `executorch-android` AAR. That one carries every
operator, the LLM, image and ASR runners, fbjni and its own `libc++_shared.so`
(9 MB on arm64, 12 MB on x86_64, no armv7). This build carries only the
operators in `ops.txt` (the list each Asr4All bundle ships, the same for all
three sizes) and the XNNPACK delegate, links libc++ statically, and builds
armv7 too. On arm64 that is 2.4 MB against the AAR's 9.1 MB `libexecutorch.so`
plus fbjni and its C++ runtime.

The built libraries are committed under `core/voice/src/main/jniLibs/`, so the
app builds without an NDK, and both editions, full and lite, carry them.
F-Droid builds lite and `scandelete`s that directory, so its build has no
such engine. Rebuild after you
change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/asr4all-jni/build.sh
```

It needs CMake, Ninja and a Python with ExecuTorch's code generator on `PATH`
(`pip install executorch torch pyyaml`): selective builds generate the
operator registration from `ops.txt`. ExecuTorch is fetched at the pinned
commit unless `EXECUTORCH_SRC` points at a checkout of it, which ExecuTorch
requires to be named `executorch`. Each ABI is a full ExecuTorch build, so the
script takes a while.

## Provenance

ExecuTorch `v1.5.1`, commit `3b60683923245cf472b7323426920e15623ba361`,
unmodified, BSD-3-Clause (credited in the app's About screen).

The models are not bundled. The keyboard downloads the `executorch/xnnpack_int8`
export of each size from its Hugging Face repo at a pinned revision, and
checks both files against the SHA-256 in `Asr4AllSize`. The weights are under
their own restricted-use licence, which allows dictation and forbids
surveillance and profiling; its text is in the app's licence assets.

## Checked on the desktop

`CMakeLists.txt` also builds a macOS dylib. Through it the real `Asr4AllStream`
ran on the JVM: logits matched ExecuTorch's Python runtime to 2e-6, and on
16-bit audio the transcript, punctuation included, matched the bundle's own
reference script word for word, on a 4-second and a 57-second clip.

## Contract with Kotlin

- `nativeOpen(path) -> Long`: a handle (the program memory-mapped), or 0.
- `nativeClose(handle)`.
- `nativeMethodMeta(handle, method) -> LongArray?`: input count, output count,
  then `[dtype, rank, dims...]` per tensor; null without such a method.
- `nativeExecute(handle, method, inputs, shapes) -> Array<Any?>`: one primitive
  array per input (float32 and float16 as `FloatArray`, int64 as `LongArray`,
  int32 as `IntArray`, bool and uint8 as `ByteArray`, converted to what the
  method declares), outputs back as data and shape pairs. Throws
  `IllegalStateException` when the method fails.
- One thread at a time per program; `Asr4AllEngine` keeps every call on one.
