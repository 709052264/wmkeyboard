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

The whole of the Kotlin `core/transliteration` package, module for module.

| Module | Engine |
|---|---|
| `khipro`, `sexp` | the Khipro m17n interpreter, Classic and Dark, touch and desktop specs |
| `avro`, `avro_desktop` | the keyboard's Avro transliterator; desktop Avro's grammars and lookups from ibus-avro's data |
| `hindi` | Hindi phonetic into Devanagari |
| `arabic_script`, `urdu`, `arabic`, `persian` | the shared roman-to-Arabic-script walk and its three tables |
| `indic`, `indic_script`, `indic_profiles`, one module per language | the Indic engine for Marathi, Gujarati, Punjabi, Odia, Tamil, Telugu, Kannada and Malayalam |
| `assamese` | Assamese on the Avro engine |
| `bengali_graphemes` | cluster-aware backspace and vowel-key forms |
| `bengali_romanizer`, `devanagari_romanizer`, `urdu_romanizer`, `khipro_romanizer` | script back to Latin |
| `bijoy`, `bijoy_tables` | Unicode Bengali to the three Bijoy ANSI encodings |
| `phonetic_index`, `bengali_index`, `hindi_index`, `indic_index`, `urdu_index` | reverse-phonetic lookup over word lists |
| `phonetic_key_map` | the key maps the settings pages draw, tested against the engines |
| `unicode` | the letter and mark classes `std` has no table for |

Still Kotlin: Avro's dictionary walk, prediction, glide.

## Data

The crate does no I/O. Its tables are compiled in with `include_str!` from the
Android app's own resource and asset files, for instance
`core/language/src/main/resources/khipro/*.mim` and
`app/src/main/assets/avro/*.json`, so the two implementations cannot run
different upstream revisions. `scripts/update-khipro.sh` refreshes the Khipro
files for both. The Bijoy tables are generated from the Kotlin
`BijoyAnsiTables.kt`; regenerate `src/bijoy_tables.rs` when that changes.

## Tests

```sh
cargo test
```

Every Kotlin test for the package is mirrored, including the four upstream
Khipro conformance tables and the Bijoy converters' outputs for all three
encodings. A failure against a table is a disagreement with upstream: fix the
interpreter, never the table.

## Bridges

None yet. The Android bridge will be a separate `wm-engine-jni` crate built
the way `harper-jni` is (`cargo ndk`, prebuilt `.so` files committed under a
module's `jniLibs`, rebuilt from source by F-Droid). The desktop app depends
on this crate as a git dependency.
