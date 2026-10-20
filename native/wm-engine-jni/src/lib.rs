//! JNI bridge exposing wm-engine to the Android app.
//!
//! One module per Kotlin class it serves:
//!
//! - [`transliteration`]: `EngineNative` (core/language), the phonetic
//!   engines, romanizers, Bijoy and graphemes.
//! - [`kernels`]: `NativeEngine` (core/language), CPU features, the
//!   scalar switch and the data-parallel kernels.
//! - [`dict`]: `PredictionNative` (core/prediction), the mapped `.wmdict`
//!   dictionaries and `.wmng` n-gram packs.
//!
//! The rules every entry point follows (native/RUST_NEON_PLAN.md):
//!
//! - Its body runs inside [`guard::guarded`], so a panic becomes a Java
//!   exception, never an abort of the keyboard.
//! - Work is batched: one crossing per query or per array, never one per
//!   element. Primitive arrays are pinned (`GetPrimitiveArrayCritical`) or
//!   copied once; strings cross as UTF-16 ([`guard::read_units`]).
//! - Data that outlives a call (a mapped dictionary, a decoder) stays here
//!   behind a [`handles::Registry`] handle.

mod dict;
mod guard;
mod handles;
mod kernels;
mod transliteration;
