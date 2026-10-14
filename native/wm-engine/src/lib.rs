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
//! - [`avro`]: the keyboard's own Avro-style phonetic transliterator.
//! - [`avro_desktop`]: desktop Avro's grammars and lookups out of ibus-avro's
//!   data (readings, autocorrect, suffixes, the search expression).
//! - [`hindi`]: Hindi phonetic, into Devanagari.
//! - [`arabic_script`] with [`urdu`], [`arabic`] and [`persian`]: the shared
//!   roman-to-Arabic-script walk and its three tables.
//! - [`indic`] with [`indic_script`], [`indic_profiles`] and one module per
//!   language (Marathi, Gujarati, Punjabi, Odia, Tamil, Telugu, Kannada,
//!   Malayalam): the shared Indic engine and its profiles.
//!
//! Still Kotlin: Assamese, the Bijoy ANSI converter, the romanizers and
//! phonetic indexes, the Bengali grapheme helpers, Avro's dictionary walk,
//! prediction and glide.
//!
//! The crate does no I/O. Its tables are compiled in from the Android app's
//! own resource and asset files, so the two never disagree about which
//! upstream revision they run.

pub mod arabic;
pub mod arabic_script;
pub mod avro;
pub mod avro_desktop;
pub mod gujarati;
pub mod hindi;
pub mod indic;
pub mod indic_profiles;
pub mod indic_script;
pub mod kannada;
pub mod khipro;
pub mod malayalam;
pub mod marathi;
pub mod odia;
pub mod persian;
pub mod punjabi;
pub mod sexp;
pub mod tamil;
pub mod telugu;
pub mod unicode;
pub mod urdu;
