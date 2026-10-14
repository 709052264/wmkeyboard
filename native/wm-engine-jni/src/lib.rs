//! JNI bridge exposing wm-engine to the Android app.
//!
//! Kotlin side: `com.wasimaster.wmkeyboard.core.transliteration.EngineNative`,
//! whose `external fun`s are declared in the same order as the entry points
//! below. Every function takes and returns Java strings (UTF-16 on the Java
//! side, `String` here), or an array of them; enums cross as ordinals in the
//! order the Kotlin enum declares them. A panic inside an engine is caught
//! and rethrown as a `RuntimeException`, never an abort of the keyboard.
//!
//! Lengths returned to Kotlin (`clusterDeleteLength`) are UTF-16 units, as
//! the engine already counts them.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jint, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use wm_engine::bengali_graphemes::VowelKeyForm;
use wm_engine::bijoy::Version;
use wm_engine::indic_script::IndicScript;
use wm_engine::khipro::Variant;

/// Runs `f`, turning a panic into a Java `RuntimeException` and `fallback`.
fn guarded<T>(env: &mut JNIEnv, fallback: T, f: impl FnOnce(&mut JNIEnv) -> Option<T>) -> T {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Some(value)) => value,
        Ok(None) => fallback,
        Err(payload) => {
            let message = payload
                .downcast_ref::<String>()
                .cloned()
                .or_else(|| payload.downcast_ref::<&str>().map(|s| s.to_string()))
                .unwrap_or_else(|| "wm-engine panicked".to_string());
            let _ = env.throw_new("java/lang/RuntimeException", format!("wm-engine: {message}"));
            fallback
        }
    }
}

fn read(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

fn give(env: &mut JNIEnv, s: String) -> Option<jstring> {
    env.new_string(s).ok().map(|s| s.into_raw())
}

fn give_all(env: &mut JNIEnv, items: Vec<String>) -> Option<jobjectArray> {
    let class = env.find_class("java/lang/String").ok()?;
    let array: JObjectArray = env.new_object_array(items.len() as i32, class, JString::default()).ok()?;
    for (i, item) in items.into_iter().enumerate() {
        let value = env.new_string(item).ok()?;
        env.set_object_array_element(&array, i as i32, value).ok()?;
    }
    Some(array.into_raw())
}

/// A `String -> String` entry point.
macro_rules! string_fn {
    ($name:ident, $call:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(mut env: JNIEnv, _class: JClass, input: JString) -> jstring {
            guarded(&mut env, null_mut(), |env| {
                let input = read(env, &input)?;
                let f: fn(&str) -> String = $call;
                give(env, f(&input))
            })
        }
    };
}

/// A `String -> Array<String>` entry point.
macro_rules! strings_fn {
    ($name:ident, $call:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(mut env: JNIEnv, _class: JClass, input: JString) -> jobjectArray {
            guarded(&mut env, null_mut(), |env| {
                let input = read(env, &input)?;
                let f: fn(&str) -> Vec<String> = $call;
                give_all(env, f(&input))
            })
        }
    };
}

// ---- Phonetic engines -------------------------------------------------------

string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_avro, wm_engine::avro::transliterate);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_assamese, wm_engine::assamese::transliterate);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_assameseToBengali, wm_engine::assamese::to_bengali);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_hindi, wm_engine::hindi::transliterate);
strings_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_hindiVariants, wm_engine::hindi::variants);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_urdu, wm_engine::urdu::transliterate);
strings_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_urduVariants, wm_engine::urdu::variants);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_arabic, wm_engine::arabic::transliterate);
strings_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_arabicVariants, wm_engine::arabic::variants);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_persian, wm_engine::persian::transliterate);
strings_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_persianVariants, wm_engine::persian::variants);

/// `EngineNative.indic(languageId, input)`: the Indic engine for one of its
/// eight languages; an unknown id passes the input through.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_indic(
    mut env: JNIEnv,
    _class: JClass,
    language_id: JString,
    input: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let language_id = read(env, &language_id)?;
        let input = read(env, &input)?;
        let out = match wm_engine::indic_profiles::for_language(&language_id) {
            Some(engine) => engine.transliterate(&input),
            None => input,
        };
        give(env, out)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_indicVariants(
    mut env: JNIEnv,
    _class: JClass,
    language_id: JString,
    input: JString,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let language_id = read(env, &language_id)?;
        let input = read(env, &input)?;
        let out = match wm_engine::indic_profiles::for_language(&language_id) {
            Some(engine) => engine.variants(&input),
            None => vec![input],
        };
        give_all(env, out)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_indicRomanPrep(
    mut env: JNIEnv,
    _class: JClass,
    language_id: JString,
    input: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let language_id = read(env, &language_id)?;
        let input = read(env, &input)?;
        give(env, wm_engine::indic_profiles::roman_prep(&language_id, &input))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_indicDevanagariPrep(
    mut env: JNIEnv,
    _class: JClass,
    language_id: JString,
    dev: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let language_id = read(env, &language_id)?;
        let dev = read(env, &dev)?;
        give(env, wm_engine::indic_profiles::devanagari_prep(&language_id, &dev))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_indicRerankFlatList(
    mut env: JNIEnv,
    _class: JClass,
    language_id: JString,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        let language_id = read(env, &language_id)?;
        Some(if wm_engine::indic_profiles::rerank_flat_list(&language_id) { JNI_TRUE } else { JNI_FALSE })
    })
}

fn script_of(ordinal: jint) -> IndicScript {
    IndicScript::ALL.get(ordinal.max(0) as usize).copied().unwrap_or(IndicScript::Devanagari)
}

/// `EngineNative.indicToDevanagari(scriptOrdinal, text)`; the ordinal is
/// `IndicScript`'s, in declaration order.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_indicToDevanagari(
    mut env: JNIEnv,
    _class: JClass,
    script: jint,
    text: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let text = read(env, &text)?;
        give(env, script_of(script).to_devanagari(&text))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_indicFromDevanagari(
    mut env: JNIEnv,
    _class: JClass,
    script: jint,
    dev: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let dev = read(env, &dev)?;
        give(env, script_of(script).from_devanagari(&dev))
    })
}

// ---- Khipro -----------------------------------------------------------------

fn variant_of(ordinal: jint) -> Variant {
    Variant::ALL.get(ordinal.max(0) as usize).copied().unwrap_or(Variant::Touchscreen)
}

/// `EngineNative.khipro(roman, variantOrdinal)`; the ordinal is
/// `Khipro.Variant`'s, in declaration order.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_khipro(
    mut env: JNIEnv,
    _class: JClass,
    roman: JString,
    variant: jint,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let roman = read(env, &roman)?;
        give(env, wm_engine::khipro::convert(&roman, variant_of(variant)))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_khiproWarm(
    mut env: JNIEnv,
    _class: JClass,
    variant: jint,
) {
    guarded(&mut env, (), |_| {
        wm_engine::khipro::warm(variant_of(variant));
        Some(())
    })
}

strings_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_khiproSpellings, wm_engine::khipro_romanizer::spellings);

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_khiproRomanizerWarm(
    mut env: JNIEnv,
    _class: JClass,
) {
    guarded(&mut env, (), |_| {
        wm_engine::khipro_romanizer::warm();
        Some(())
    })
}

// ---- Bijoy ANSI -------------------------------------------------------------

/// `EngineNative.bijoy(text, versionNumber, before)`.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_bijoy(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    version: jint,
    before: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let text = read(env, &text)?;
        let before = read(env, &before)?;
        give(env, wm_engine::bijoy::convert(&text, Version::of(version.max(0) as u32), &before))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_bijoyNeedsConversion(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        let text = read(env, &text)?;
        Some(if wm_engine::bijoy::needs_conversion(&text) { JNI_TRUE } else { JNI_FALSE })
    })
}

// ---- Bengali graphemes --------------------------------------------------------

/// `EngineNative.clusterDeleteLength(text)`: UTF-16 units to delete.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_clusterDeleteLength(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jint {
    guarded(&mut env, 0, |env| {
        let text = read(env, &text)?;
        Some(wm_engine::bengali_graphemes::cluster_delete_length(&text) as jint)
    })
}

fn form_of(ordinal: jint) -> VowelKeyForm {
    match ordinal {
        1 => VowelKeyForm::Kar,
        2 => VowelKeyForm::Glide,
        _ => VowelKeyForm::Independent,
    }
}

fn ordinal_of(form: VowelKeyForm) -> jint {
    match form {
        VowelKeyForm::Independent => 0,
        VowelKeyForm::Kar => 1,
        VowelKeyForm::Glide => 2,
    }
}

/// `EngineNative.vowelFormAfter(previous)`: `previous` is a code point, or
/// -1 for nothing before the cursor; the result is `VowelKeyForm`'s ordinal.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_vowelFormAfter(
    mut env: JNIEnv,
    _class: JClass,
    previous: jint,
) -> jint {
    guarded(&mut env, 0, |_| {
        let previous = if previous < 0 { None } else { char::from_u32(previous as u32) };
        Some(ordinal_of(wm_engine::bengali_graphemes::vowel_form_after(previous)))
    })
}

/// `EngineNative.vowelKeyText(kar, formOrdinal)`: null for a non-vowel key.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_vowelKeyText(
    mut env: JNIEnv,
    _class: JClass,
    kar: jint,
    form: jint,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let kar = char::from_u32(kar.max(0) as u32)?;
        let text = wm_engine::bengali_graphemes::vowel_key_text(kar, form_of(form))?;
        give(env, text)
    })
}

// ---- Romanizers ---------------------------------------------------------------

string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_bengaliRomanize, wm_engine::bengali_romanizer::romanize);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_bengaliRomanizeWord, wm_engine::bengali_romanizer::romanize_word);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_bengaliNormalize, wm_engine::bengali_romanizer::normalize);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_devanagariRomanize, wm_engine::devanagari_romanizer::romanize);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_devanagariRomanizeWord, wm_engine::devanagari_romanizer::romanize_word);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_devanagariNormalize, wm_engine::devanagari_romanizer::normalize);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_urduRomanize, wm_engine::urdu_romanizer::romanize);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_urduRomanizeWord, wm_engine::urdu_romanizer::romanize_word);
string_fn!(Java_com_wasimaster_wmkeyboard_core_transliteration_EngineNative_urduNormalize, wm_engine::urdu_romanizer::normalize);

#[cfg(test)]
mod tests {
    //! The dispatch helpers; the JNI entry points themselves need a JVM and
    //! are exercised by the Kotlin suites through `EngineNative`.

    use super::*;

    #[test]
    fn ordinals_follow_the_kotlin_declaration_order() {
        assert_eq!(Variant::Touchscreen, variant_of(0));
        assert_eq!(Variant::DarkDesktop, variant_of(3));
        assert_eq!(Variant::Touchscreen, variant_of(99));
        assert_eq!(IndicScript::Devanagari, script_of(0));
        assert_eq!(IndicScript::Malayalam, script_of(8));
        assert_eq!(IndicScript::Devanagari, script_of(-1));
        for form in [VowelKeyForm::Independent, VowelKeyForm::Kar, VowelKeyForm::Glide] {
            assert_eq!(form, form_of(ordinal_of(form)));
        }
    }
}
