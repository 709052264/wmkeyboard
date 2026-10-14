# wm-engine: the text engines, written once

WM Keyboard's phonetic and prediction engines have lived in Kotlin under
`core/language` and `core/prediction`. WMK Desktop, the companion for macOS,
Windows and Linux, needs the same engines, and a second implementation would
drift from the first. So the engines move here, one at a time, and both apps
run this crate: the Android app through a JNI bridge, the desktop app directly.

The move is incremental on purpose. Each engine is ported against the same
conformance fixtures the Kotlin original uses, the Android app switches to the
Rust version once that corpus is green, and only then is the Kotlin copy
deleted. Prediction and glide, which change weekly, stay in Kotlin until the
rest is settled.

## What is here

| Module | Engine | Status |
|---|---|---|
| `khipro` | Khipro m17n interpreter, Classic and Dark, touch and desktop specs | ported; passes all four upstream `khipro-testcases` tables |
| `sexp` | the S-expression reader the m17n files need | ported |

Still Kotlin: Avro phonetic, Hindi and Urdu phonetic, the Indic tables, the
Bijoy ANSI converter, composers, prediction, glide.

## Data

The crate does no I/O. Its tables are compiled in with `include_str!` from the
Android app's own resource files, for instance
`core/language/src/main/resources/khipro/*.mim`, so the two implementations
cannot run different upstream revisions. `scripts/update-khipro.sh` refreshes
those files for both.

## Tests

```sh
cargo test
```

Runs the Khipro conformance tables from
`core/language/src/test/resources/khipro/` and the unit cases mirrored from
`KhiproTest.kt`. A failure against a table is a disagreement with upstream:
fix the interpreter, never the table.

## Bridges

None yet. The Android bridge will be a separate `wm-engine-jni` crate built
the way `harper-jni` is (`cargo ndk`, prebuilt `.so` files committed under a
module's `jniLibs`, rebuilt from source by F-Droid). The desktop app depends
on this crate as a path or git dependency.
