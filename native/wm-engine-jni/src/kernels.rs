//! The kernels and the switches over the whole native fast path:
//! `NativeEngine` in `core/language`.

use jni::objects::{JClass, JFloatArray, JIntArray};
use jni::sys::{jboolean, jfloat, jint, JNI_FALSE, JNI_TRUE};
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

/// `NativeEngine.boxAverageBgr(...)`: the photo `pixels` (ARGB, `width` ×
/// `height`) shrunk by box averaging at `scale` into the `outW` × `outH`
/// corner of `out`, rows `stride` pixels apart, normalised BGR with
/// `norm` = the three means then the three deviations; the rest of `out`
/// zeroed. One crossing per photo. False, with `out` untouched, for a shape
/// the kernel does not take (a scale of 1 or more, arrays too small).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeBoxAverageBgr(
    mut env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
    width: jint,
    height: jint,
    scale: jfloat,
    out_w: jint,
    out_h: jint,
    stride: jint,
    norm: JFloatArray,
    out: JFloatArray,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        if [width, height, out_w, out_h, stride].iter().any(|&v| v < 0) {
            return Some(JNI_FALSE);
        }
        let mut n = [0f32; 6];
        env.get_float_array_region(&norm, 0, &mut n).ok()?;
        let fit = kernels::box_average::Fit {
            width: width as usize,
            height: height as usize,
            scale,
            out_w: out_w as usize,
            out_h: out_h as usize,
            stride: stride as usize,
        };
        let (pixels, out) = (pixels.as_raw(), out.as_raw());
        // SAFETY: an int[] read as u32 (same size and alignment) and a
        // float[]; the closures make no JNI call while they are pinned.
        let done = unsafe {
            let raw = env.get_raw();
            with_critical::<u32, _>(raw, pixels, false, |pixels| {
                with_critical::<f32, _>(raw, out, true, |out| {
                    kernels::box_average::box_average_bgr(pixels, fit, [n[0], n[1], n[2]], [n[3], n[4], n[5]], out)
                })
            })
        };
        Some(if done.flatten() == Some(true) { JNI_TRUE } else { JNI_FALSE })
    })
}

/// `NativeEngine.argmaxRows(data, cols, out)`: the index of the first
/// largest value of every `cols`-wide row of `data` into `out`, in one
/// crossing per block of logits; returns the rows answered.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeArgmaxRows(
    mut env: JNIEnv,
    _class: JClass,
    data: JFloatArray,
    cols: jint,
    out: JIntArray,
) -> jint {
    guarded(&mut env, 0, |env| {
        if cols <= 0 {
            return Some(0);
        }
        let (data, out) = (data.as_raw(), out.as_raw());
        // SAFETY: a float[] and an int[] written as u32; the closures make
        // no JNI call while they are pinned.
        let rows = unsafe {
            let raw = env.get_raw();
            with_critical::<f32, _>(raw, data, false, |data| {
                with_critical::<u32, _>(raw, out, true, |out| kernels::argmax::argmax_rows(data, cols as usize, out))
            })
        };
        rows.flatten().map(|rows| rows as jint)
    })
}
