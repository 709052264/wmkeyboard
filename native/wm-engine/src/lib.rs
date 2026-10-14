//! WM Keyboard's text engines, written once.
//!
//! The Android app has run these engines in Kotlin under `core/language` and
//! `core/prediction`. WMK Desktop needs the same engines on macOS, Windows and
//! Linux, and a second implementation would drift from the first, so they
//! move here one at a time: the Rust port is checked against the same
//! conformance fixtures as the Kotlin original, the Android app switches to
//! it through JNI once that corpus is green, and the Kotlin copy is deleted.
//!
//! Ported so far:
//!
//! - [`khipro`]: the Khipro m17n interpreter (Bengali, compositional).
//!
//! Still Kotlin: Avro phonetic, Hindi and Urdu phonetic, the Indic tables,
//! the Bijoy ANSI converter, prediction and glide.
//!
//! The crate has no dependencies and does no I/O. Its tables are compiled in
//! from the Android app's own resource files, so the two never disagree about
//! which upstream revision they run.

pub mod khipro;
pub mod sexp;
