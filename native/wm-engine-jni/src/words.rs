//! Words as Unicode sees them (`wm-engine`'s `word_key` and `segment`):
//! `PredictionNative.wordKey`, `wordSurface`, `isLearnableWord` and
//! `segmentWords`.
//!
//! The keyboard folds with the Kotlin `WordKey` on the typing path, where a
//! crossing per word would cost more than the fold. These serve the
//! segmenter, which has no Kotlin twin, and the parity test that holds the
//! Rust fold to the runtime's.

use std::ptr::null_mut;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jintArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use wm_engine::{segment, word_key};

use crate::guard::{give_ints, give_units, guarded, read_units};

/// `wordKey(word)`: `WordKey.of`, unit for unit.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_wordKey(
    mut env: JNIEnv,
    _class: JClass,
    word: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let mut units = Vec::new();
        read_units(env, &word, &mut units)?;
        give_units(env, &word_key::of(&units))
    })
}

/// `wordSurface(word)`: `WordKey.surface`.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_wordSurface(
    mut env: JNIEnv,
    _class: JClass,
    word: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let mut units = Vec::new();
        read_units(env, &word, &mut units)?;
        give_units(env, &word_key::surface(&units))
    })
}

/// `isLearnableWord(word)`: `WordContext.isLearnableWord`.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_isLearnableWord(
    mut env: JNIEnv,
    _class: JClass,
    word: JString,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        let mut units = Vec::new();
        read_units(env, &word, &mut units)?;
        Some(if word_key::is_learnable(&units) { JNI_TRUE } else { JNI_FALSE })
    })
}

/// `segmentWords(text)`: the words of `text` as `[start0, end0, start1,
/// end1, ...]` in UTF-16 units.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_segmentWords(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jintArray {
    guarded(&mut env, null_mut(), |env| {
        let mut units = Vec::new();
        read_units(env, &text, &mut units)?;
        let flat: Vec<i32> = segment::words(&units)
            .into_iter()
            .flat_map(|(s, e)| [s as i32, e as i32])
            .collect();
        give_ints(env, &flat).map(|a| a.into_raw())
    })
}
