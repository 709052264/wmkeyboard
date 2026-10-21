//! The kernels and the switches over the whole native fast path:
//! `NativeEngine` in `core/language`.

use jni::objects::{JClass, JFloatArray, JIntArray};
use jni::sys::{jboolean, jfloat, jint, JNI_TRUE};
use jni::JNIEnv;

use wm_engine::kernels;

use crate::guard::{guarded, with_critical};

/// `NativeEngine.cpuFeatures()`: what the kernels may use on this CPU, as
/// `kernels::Features::bits` (detected, whatever the switch says).
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeCpuFeatures(
    mut env: JNIEnv,
    _class: JClass,
) -> jint {
    guarded(&mut env, 0, |_| Some(kernels::detected().bits() as jint))
}

/// `NativeEngine.setForceScalar(on)`: every kernel to its scalar reference.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_setForceScalar(
    mut env: JNIEnv,
    _class: JClass,
    on: jboolean,
) {
    guarded(&mut env, (), |_| {
        kernels::set_force_scalar(on == JNI_TRUE);
        Some(())
    })
}

/// `NativeEngine.dotRows(query, matrix, dim, out)`: `query · row` for every
/// `dim`-wide row of `matrix` into `out`, in one crossing; returns the rows
/// scored. The arrays are pinned, not copied, for the length of the loop.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeDotRows(
    mut env: JNIEnv,
    _class: JClass,
    query: JFloatArray,
    matrix: JFloatArray,
    dim: jint,
    out: JFloatArray,
) -> jint {
    guarded(&mut env, 0, |env| {
        if dim <= 0 {
            return Some(0);
        }
        // The query is small: copied once, so only two arrays are pinned.
        let mut q = vec![0f32; env.get_array_length(&query).ok()? as usize];
        env.get_float_array_region(&query, 0, &mut q).ok()?;
        let (matrix, out) = (matrix.as_raw(), out.as_raw());
        // SAFETY: both are float[] (the Kotlin signature says so), and the
        // closures make no JNI call while the arrays are pinned. Pinning
        // nests: the inner array is released before the outer one.
        let rows = unsafe {
            let raw = env.get_raw();
            with_critical::<f32, _>(raw, matrix, false, |matrix| {
                with_critical::<f32, _>(raw, out, true, |out| {
                    kernels::dot::dot_rows_f32(&q, matrix, dim as usize, out)
                })
            })
        };
        rows.flatten().map(|rows| rows as jint)
    })
}

/// `NativeEngine.sharpen(src, width, first, count, amount, out)`: the photo
/// editor's sharpen of rows `first..first + count` of `src` into `out`, in
/// one crossing (`kernels::sharpen::sharpen_argb`); returns the rows written,
/// 0 when the shape does not fit the arrays. Both arrays are pinned, not
/// copied, for the length of the loop.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeSharpen(
    mut env: JNIEnv,
    _class: JClass,
    src: JIntArray,
    width: jint,
    first: jint,
    count: jint,
    amount: jfloat,
    out: JIntArray,
) -> jint {
    guarded(&mut env, 0, |env| {
        if width <= 0 || first < 0 || count < 0 {
            return Some(0);
        }
        let (src, out) = (src.as_raw(), out.as_raw());
        // SAFETY: both are int[] (the Kotlin signature says so), read as
        // u32 colour ints, and the closures make no JNI call while the
        // arrays are pinned. Pinning nests: the inner array is released
        // before the outer one.
        let rows = unsafe {
            let raw = env.get_raw();
            with_critical::<u32, _>(raw, src, false, |src| {
                with_critical::<u32, _>(raw, out, true, |out| {
                    kernels::sharpen::sharpen_argb(src, width as usize, first as usize, count as usize, amount, out)
                })
            })
        };
        rows.flatten().map(|rows| rows as jint)
    })
}
