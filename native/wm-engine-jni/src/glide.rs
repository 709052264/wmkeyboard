//! The glide decoder (`wm-engine`'s `glide`): `PredictionNative.glide*`,
//! which the Kotlin `GlideBeam` calls when every trie it decodes against has
//! a Rust twin.
//!
//! A decode with the user's learned shapes is two calls on one thread: the
//! walk ([`glideDecode`] with `phase` 1), which returns the words the shape
//! channel will score, and [`glideFinish`] with those words' shapes. Without
//! learned shapes it is the one call (`phase` 0). The walk's state between
//! the two stays in this thread's decoder.

use std::cell::RefCell;
use std::ptr::null_mut;
use std::sync::{Arc, LazyLock, RwLockReadGuard};

use jni::objects::{JByteArray, JClass, JDoubleArray, JFloatArray, JIntArray, JLongArray, JObject, JObjectArray, JString};
use jni::sys::{jfloat, jint, jlong, jobjectArray};
use jni::JNIEnv;

use wm_engine::fuzzy::{Tier, Walker};
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Candidate, Decoder, LearnedShapes, Point, Ranked, Source, Tuning};
use wm_engine::user_lexicon::UserLexicon;
use wm_engine::wmdict::Dict;

use crate::dict::{DictBytes, DICTS};
use crate::guard::{give_all_units, give_ints, guarded, object, read_units, throw_state};
use crate::handles::Registry;
use crate::lexicon::{Store, LEXICONS};

static KEYMAPS: LazyLock<Registry<KeyMap>> = LazyLock::new(Registry::new);

thread_local! {
    static DECODER: RefCell<Decoder> = RefCell::new(Decoder::new(Tuning::default()));
    /// The walk waiting for its shapes, and the key map it ran on.
    static PENDING: RefCell<Option<(Ranked, Arc<KeyMap>)>> = const { RefCell::new(None) };
}

fn floats(env: &mut JNIEnv, a: &JFloatArray) -> Option<Vec<f32>> {
    let mut out = vec![0f32; env.get_array_length(a).ok()? as usize];
    env.get_float_array_region(a, 0, &mut out).ok()?;
    Some(out)
}

fn ints(env: &mut JNIEnv, a: &JIntArray) -> Option<Vec<i32>> {
    let mut out = vec![0i32; env.get_array_length(a).ok()? as usize];
    env.get_int_array_region(a, 0, &mut out).ok()?;
    Some(out)
}

fn doubles(env: &mut JNIEnv, a: &JDoubleArray) -> Option<Vec<f64>> {
    let mut out = vec![0f64; env.get_array_length(a).ok()? as usize];
    env.get_double_array_region(a, 0, &mut out).ok()?;
    Some(out)
}

fn longs(env: &mut JNIEnv, a: &JLongArray) -> Option<Vec<i64>> {
    let mut out = vec![0i64; env.get_array_length(a).ok()? as usize];
    env.get_long_array_region(a, 0, &mut out).ok()?;
    Some(out)
}

/// `Tuning` from the Kotlin order: every field, booleans as 0/1, the
/// floats exactly representable as doubles.
fn tuning(v: &[f64]) -> Option<Tuning> {
    if v.len() < 26 {
        return None;
    }
    Some(Tuning {
        sigma: v[0] as f32,
        max_point_cost: v[1] as f32,
        gap_weight: v[2] as f32,
        gap_window: v[3] as f32,
        shape_weight: v[4],
        repeat_cost: v[5] as f32,
        unlooped_repeat: v[6] as f32,
        dwell_full: v[7] as f32,
        unclaimed_dwell: v[8] as f32,
        loop_extent: v[9] as f32,
        loop_min_arc: v[10] as f32,
        loop_radius: v[11] as f32,
        wiggle_extent: v[12] as f32,
        wiggle_weight: v[13] as f32,
        loop_exclusion: v[14] as f32,
        unclaimed_loop: v[15] as f32,
        learned_shape_gain: v[16],
        shape_seeding: v[17] != 0.0,
        shape_channel: v[18],
        start_radius: v[19] as f32,
        end_radius: v[20] as f32,
        near_radius: v[21] as f32,
        vocabulary_rank: v[22] as i32,
        look_ahead_cost: v[23],
        look_ahead_budget: v[24] as i32,
        joiner_cost: v[25] as f32,
    })
}

fn path(env: &mut JNIEnv, xs: &JFloatArray, ys: &JFloatArray, ts: &JLongArray) -> Option<Vec<Point>> {
    let (xs, ys, ts) = (floats(env, xs)?, floats(env, ys)?, longs(env, ts)?);
    Some(xs.iter().zip(&ys).zip(&ts).map(|((&x, &y), &t)| Point { x, y, t }).collect())
}

/// `glideKeyMapOpen(keyX, keyY, codePoints, keyOf)`.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideKeyMapOpen(
    mut env: JNIEnv,
    _class: JClass,
    key_x: JFloatArray,
    key_y: JFloatArray,
    code_points: JIntArray,
    key_of: JIntArray,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let map = KeyMap::new(floats(env, &key_x)?, floats(env, &key_y)?, ints(env, &code_points)?, ints(env, &key_of)?);
        Some(KEYMAPS.insert(map))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideKeyMapClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        KEYMAPS.remove(handle);
        Some(())
    })
}

/// `found` as `glideDecode` returns it: `Object[] { String[] words,
/// double[] [score, shapeCost] per word, int[] [user tier, ahead] per word
/// plus `extra` }`.
fn give_candidates(env: &mut JNIEnv, found: &[Candidate], extra: &[i32]) -> Option<jobjectArray> {
    let reals: Vec<f64> = found.iter().flat_map(|c| [c.score, c.shape_cost]).collect();
    let mut flags: Vec<i32> = found.iter().flat_map(|c| [(c.tier == Tier::User) as i32, c.ahead]).collect();
    flags.extend_from_slice(extra);
    // SAFETY: each is the local reference its constructor just returned.
    let words = unsafe { JObject::from_raw(give_all_units(env, found.iter().map(|c| c.word.as_slice()))?) };
    let doubles = env.new_double_array(reals.len() as i32).ok()?;
    env.set_double_array_region(&doubles, 0, &reals).ok()?;
    let ints = give_ints(env, &flags)?;
    let class = env.find_class("java/lang/Object").ok()?;
    let out = env.new_object_array(3, &class, JObject::null()).ok()?;
    env.set_object_array_element(&out, 0, &words).ok()?;
    env.set_object_array_element(&out, 1, &doubles).ok()?;
    env.set_object_array_element(&out, 2, &ints).ok()?;
    Some(out.into_raw())
}

/// `glideDecode(tuning, xs, ys, ts, keyMap, keyWidth, dicts, kinds, weights,
/// tiers, minFrequencies, limit, lookAhead, phase)`.
///
/// `kinds` and `tiers` as for `fuzzySearch`; `minFrequencies` is each
/// source's vocabulary cap. Phase 0 decodes outright; phase 1 stops before
/// the shape channel and returns every word it will score, readings first
/// (the int array's last slot then holds the reading count), for
/// `glideFinish`. Null when the stroke is no decode at all.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideDecode(
    mut env: JNIEnv,
    _class: JClass,
    tuning_values: JDoubleArray,
    xs: JFloatArray,
    ys: JFloatArray,
    ts: JLongArray,
    keymap: jlong,
    key_width: jfloat,
    dicts: JLongArray,
    kinds: JIntArray,
    weights: JDoubleArray,
    tiers: JIntArray,
    min_frequencies: JIntArray,
    limit: jint,
    look_ahead: jint,
    phase: jint,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let Some(tuning) = tuning(&doubles(env, &tuning_values)?) else {
            return throw_state(env, "wm-engine: a full glide tuning");
        };
        let path = path(env, &xs, &ys, &ts)?;
        let keys = object!(env, KEYMAPS, keymap);
        let handles = longs(env, &dicts)?;
        let kinds = ints(env, &kinds)?;
        let weights = doubles(env, &weights)?;
        let tiers = ints(env, &tiers)?;
        let floors = ints(env, &min_frequencies)?;
        let mut held: Vec<Arc<Dict<DictBytes>>> = Vec::new();
        let mut stores: Vec<Arc<Store>> = Vec::new();
        for (&handle, &kind) in handles.iter().zip(&kinds) {
            if kind == 1 {
                stores.push(object!(env, LEXICONS, handle));
            } else {
                held.push(object!(env, DICTS, handle));
            }
        }
        let guards: Vec<RwLockReadGuard<UserLexicon>> =
            stores.iter().map(|s| s.lexicon.read().unwrap_or_else(|e| e.into_inner())).collect();
        let (mut next_dict, mut next_lexicon) = (0, 0);
        let mut sources: Vec<Source> = Vec::with_capacity(handles.len());
        for i in 0..handles.len() {
            let walker: &dyn Walker = if kinds[i] == 1 {
                next_lexicon += 1;
                guards[next_lexicon - 1].trie()
            } else {
                next_dict += 1;
                held[next_dict - 1].as_ref()
            };
            sources.push(Source {
                walker,
                log_weight: weights[i],
                tier: if tiers[i] == 1 { Tier::User } else { Tier::Dictionary },
                min_frequency: floors[i],
            });
        }
        let limit = limit.max(0) as usize;
        let look_ahead = look_ahead.max(0) as usize;
        let ranked = DECODER.with_borrow_mut(|decoder| {
            decoder.tuning = tuning;
            decoder.decode_ranked(&path, &keys, key_width, &sources, limit, look_ahead)
        });
        drop(sources);
        drop(guards);
        let Some(ranked) = ranked else { return Some(null_mut()) };
        if phase == 0 {
            let found = DECODER.with_borrow_mut(|decoder| decoder.finish(ranked, &keys, None, None));
            return give_candidates(env, &found, &[]);
        }
        let mut all: Vec<Candidate> = ranked.read.clone();
        all.extend(ranked.guesses.iter().cloned());
        let read_count = ranked.read.len() as i32;
        PENDING.with_borrow_mut(|p| *p = Some((ranked, keys.clone())));
        give_candidates(env, &all, &[read_count])
    })
}

/// `glideFinish(words, shapes, seeds)`: the last phase-1 decode on this
/// thread, finished with each of `words`' learned shapes (`shapes[i]`, a
/// `byte[][]`, possibly empty) and, when seeding is on, the words whose
/// stored shapes sit near the stroke (`seeds`, else null).
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideFinish(
    mut env: JNIEnv,
    _class: JClass,
    words: JObjectArray,
    shapes: JObjectArray,
    seeds: JObjectArray,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let Some((ranked, keys)) = PENDING.with_borrow_mut(|p| p.take()) else {
            return throw_state(env, "wm-engine: glideFinish without a phase-1 glideDecode on this thread");
        };
        let mut learned: LearnedShapes = LearnedShapes::new();
        let count = env.get_array_length(&words).ok()?;
        for i in 0..count {
            let word = JString::from(env.get_object_array_element(&words, i).ok()?);
            let mut units = Vec::new();
            read_units(env, &word, &mut units)?;
            env.delete_local_ref(word).ok()?;
            let list = JObjectArray::from(env.get_object_array_element(&shapes, i).ok()?);
            let n = env.get_array_length(&list).ok()?;
            let mut stored = Vec::with_capacity(n as usize);
            for j in 0..n {
                let bytes = JByteArray::from(env.get_object_array_element(&list, j).ok()?);
                let v: Vec<u8> = env.convert_byte_array(&bytes).ok()?;
                stored.push(v.into_iter().map(|b| b as i8).collect());
                env.delete_local_ref(bytes).ok()?;
            }
            env.delete_local_ref(list).ok()?;
            learned.insert(units, stored);
        }
        let near = if seeds.is_null() {
            None
        } else {
            let n = env.get_array_length(&seeds).ok()?;
            let mut near = Vec::with_capacity(n as usize);
            for i in 0..n {
                let word = JString::from(env.get_object_array_element(&seeds, i).ok()?);
                let mut units = Vec::new();
                read_units(env, &word, &mut units)?;
                env.delete_local_ref(word).ok()?;
                near.push(units);
            }
            Some(near)
        };
        let found = DECODER.with_borrow_mut(|decoder| decoder.finish(ranked, &keys, Some(&learned), near.as_deref()));
        give_candidates(env, &found, &[])
    })
}

/// `glideAlign(tuning, word, xs, ys, ts, keyMap, keyWidth)`: `[keys, x, y,
/// samples, chars, sampleCount]` flattened as `IntArray keys + samples +
/// chars + [sampleCount]` and the coordinates through `floatsOut`, or null.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideAlign(
    mut env: JNIEnv,
    _class: JClass,
    tuning_values: JDoubleArray,
    word: JString,
    xs: JFloatArray,
    ys: JFloatArray,
    ts: JLongArray,
    keymap: jlong,
    key_width: jfloat,
    floats_out: JFloatArray,
) -> jni::sys::jintArray {
    guarded(&mut env, null_mut(), |env| {
        let Some(tuning) = tuning(&doubles(env, &tuning_values)?) else {
            return throw_state(env, "wm-engine: a full glide tuning");
        };
        let mut units = Vec::new();
        read_units(env, &word, &mut units)?;
        let path = path(env, &xs, &ys, &ts)?;
        let keys = object!(env, KEYMAPS, keymap);
        let aligned = DECODER.with_borrow_mut(|decoder| {
            decoder.tuning = tuning;
            decoder.align(&units, &path, &keys, key_width)
        });
        let Some(a) = aligned else { return Some(null_mut()) };
        let mut coords = a.x.clone();
        coords.extend_from_slice(&a.y);
        if env.get_array_length(&floats_out).ok()? < coords.len() as i32 {
            return throw_state(env, "wm-engine: the out arrays are too short");
        }
        env.set_float_array_region(&floats_out, 0, &coords).ok()?;
        let mut flat = a.keys.clone();
        flat.extend_from_slice(&a.samples);
        flat.extend_from_slice(&a.chars);
        flat.push(a.sample_count);
        give_ints(env, &flat).map(|a| a.into_raw())
    })
}

/// `glideSampleShape(xs, ys, ts, keyWidth)`: the stroke as the shape store
/// keeps it, or null.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideSampleShape(
    mut env: JNIEnv,
    _class: JClass,
    xs: JFloatArray,
    ys: JFloatArray,
    ts: JLongArray,
    key_width: jfloat,
) -> jni::sys::jbyteArray {
    guarded(&mut env, null_mut(), |env| {
        let path = path(env, &xs, &ys, &ts)?;
        let shape = DECODER.with_borrow_mut(|decoder| decoder.sample_shape(&path, key_width));
        let Some(shape) = shape else { return Some(null_mut()) };
        let bytes: Vec<u8> = shape.into_iter().map(|b| b as u8).collect();
        env.byte_array_from_slice(&bytes).ok().map(|a| a.into_raw())
    })
}
