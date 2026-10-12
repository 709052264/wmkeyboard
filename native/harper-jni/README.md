# harper-jni: offline grammar engine

Rust cdylib wrapping [Harper](https://github.com/automattic/harper)
(`harper-core`) behind a tiny JNI surface for the keyboard's grammar tool.
Kotlin entry point: `com.wasimaster.wmkeyboard.core.grammar.HarperNative`.

The prebuilt `libharper_jni.so` files are committed under
`core/intelligence/src/main/jniLibs/<abi>/`, so the app builds without a Rust
toolchain. Rebuild them after you change this crate or bump `harper-core`:

```sh
# One-time setup
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
brew install --cask android-ndk   # or any NDK; set ANDROID_NDK_HOME

# Build all supported ABIs (arm64-v8a, armeabi-v7a, x86_64) into core/intelligence (from this directory)
ANDROID_NDK_HOME=/opt/homebrew/share/android-ndk \
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 24 \
  -o ../../core/intelligence/src/main/jniLibs build --release
```

Host-side tests (`cargo test`) exercise the lint→JSON path without Android.

## Vendored harper-core and harper-brill

`vendor/` holds harper-core and harper-brill 2.5.0, wired in through
`[patch.crates-io]`, with two changes and nothing else (tests and benches left
out):

- Their embedded data (the dictionary word list, the Brill tagger and chunker,
  the neural chunker's model and vocabulary) is stored raw-deflated
  (`*.deflate`) and inflated once on first use with `miniz_oxide`. Lint output
  is byte-for-byte the same as the published crates on harper-core's own test
  sources in all four dialects (`cargo test -- --ignored corpus_snapshot` with
  `HARPER_CORPUS` and `HARPER_CORPUS_OUT` set, against a build of the
  published crates).
- `Document::parse` uses the Brill chunker instead of the neural one, which
  lets LTO drop the Burn framework and its model. harper-core's own lint tests:
  5,249 of 5,250 pass, against 5,250 with the neural chunker; the one change
  is a false "missing to" on "a standard and expected feature for any
  e-commerce site selling visually-driven products". The suite runs 2.3 times
  as fast.

Together: arm64 5.5 MB to 2.4 MB, armv7 4.9 to 1.9, x86_64 6.0 to 2.7.

To bump harper-core: copy the new versions of both crates into `vendor/`
(without `tests` and `benches`), deflate the same five files with raw deflate
(`zlib.compressobj(9, zlib.DEFLATED, -15)`), and re-apply the two marked
"WM Keyboard" edits in `harper-brill/src/lib.rs`,
`harper-core/src/spell/mutable_dictionary.rs` and `harper-core/src/document.rs`,
plus the `miniz_oxide` dependency in both `Cargo.toml`s. Then check both
claims above again before rebuilding.

## Contract with Kotlin

- `nativeLint(text, dialect) -> String` returns a JSON array of
  `{start, end, original, kind, message, priority, suggestions:[{kind, text}]}`.
  `start` and `end` are **UTF-16** code-unit indices, which is how Kotlin
  indexes a string. The char→UTF-16 conversion happens on the Rust side.
- The `dialect` ordinal matches `GrammarDialect` in `SettingsRepository.kt`:
  0 American, 1 British, 2 Canadian, 3 Australian. Append-only.
- Harper's `LintGroup` is not `Send`, so the linter cache is thread-local.
  `GrammarChecker` funnels every call through one dedicated thread.
