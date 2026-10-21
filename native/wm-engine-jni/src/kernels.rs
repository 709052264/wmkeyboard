//! The kernels and the switches over the whole native fast path:
//! `NativeEngine` in `core/language`.

use jni::objects::{JByteBuffer, JClass, JFloatArray, JIntArray};
use jni::sys::{jboolean, jfloat, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use wm_engine::kernels;
use wm_engine::kernels::{upscale, yuv};

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

/// A direct buffer's first `limit` bytes (capped at its capacity), or
/// `None` when it is not direct. The slice lives as long as the Java buffer,
/// which the caller holds for the length of the call.
fn direct<'a>(env: &JNIEnv, buffer: &JByteBuffer, limit: jint) -> Option<&'a [u8]> {
    let address = env.get_direct_buffer_address(buffer).ok()?;
    let capacity = env.get_direct_buffer_capacity(buffer).ok()?;
    let len = (limit.max(0) as usize).min(capacity);
    // SAFETY: a direct buffer's address and capacity describe live memory
    // the buffer keeps for as long as it is reachable, and the Kotlin caller
    // keeps it reachable for the whole call.
    Some(if len == 0 { &[] } else { unsafe { std::slice::from_raw_parts(address, len) } })
}

/// `NativeEngine.yuvToRgb(...)`: a video frame's YUV 4:2:0 planes (direct
/// buffers as `android.media.Image` hands them over, each read up to its
/// limit) as `r << 16 | g << 8 | b` ints into `out`, `rows` × `cols` from
/// crop corner (`left`, `top`), rows `width` apart, in one crossing
/// (`kernels::yuv`). False, writing nothing, when a buffer is not direct or
/// a read or write would fall outside one.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeYuvToRgb(
    mut env: JNIEnv,
    _class: JClass,
    y: JByteBuffer,
    y_limit: jint,
    y_row: jint,
    y_pixel: jint,
    u: JByteBuffer,
    u_limit: jint,
    u_row: jint,
    u_pixel: jint,
    v: JByteBuffer,
    v_limit: jint,
    v_row: jint,
    v_pixel: jint,
    left: jint,
    top: jint,
    rows: jint,
    cols: jint,
    width: jint,
    out: JIntArray,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        if [y_row, y_pixel, u_row, u_pixel, v_row, v_pixel, left, top, rows, cols, width].iter().any(|&n| n < 0) {
            return Some(JNI_FALSE);
        }
        let plane = |data, row: jint, pixel: jint| yuv::Plane { data, row_stride: row as usize, pixel_stride: pixel as usize };
        let (Some(yb), Some(ub), Some(vb)) = (direct(env, &y, y_limit), direct(env, &u, u_limit), direct(env, &v, v_limit)) else {
            return Some(JNI_FALSE);
        };
        let (yp, up, vp) = (plane(yb, y_row, y_pixel), plane(ub, u_row, u_pixel), plane(vb, v_row, v_pixel));
        let frame = yuv::Frame { left: left as usize, top: top as usize, rows: rows as usize, cols: cols as usize, width: width as usize };
        let out = out.as_raw();
        // SAFETY: an int[]; the closure makes no JNI call while it is pinned.
        let done = unsafe { with_critical::<i32, _>(env.get_raw(), out, true, |out| yuv::yuv420_to_rgb(yp, up, vp, frame, out)) };
        Some(if done == Some(true) { JNI_TRUE } else { JNI_FALSE })
    })
}

/// `NativeEngine.boxBlur(pixels, width, height, radius, passes)`: `passes`
/// rounds of the frosted-glass bake's horizontal then vertical box blur over
/// ARGB `pixels`, in place, in one crossing (`kernels::box_blur`). False,
/// with nothing changed, when the shape does not fit the array.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeBoxBlur(
    mut env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
    width: jint,
    height: jint,
    radius: jint,
    passes: jint,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        if width < 0 || height < 0 || radius < 0 || passes < 0 {
            return Some(JNI_FALSE);
        }
        let (w, h) = (width as usize, height as usize);
        // The pass in between lands here, not in a second Java array.
        let mut scratch = vec![0u32; w.checked_mul(h)?];
        let pixels = pixels.as_raw();
        // SAFETY: an int[] read and written as u32 colour ints; the closure
        // makes no JNI call while it is pinned.
        let done = unsafe {
            with_critical::<u32, _>(env.get_raw(), pixels, true, |px| {
                kernels::box_blur::box_blur_argb(px, w, h, radius as usize, passes as usize, &mut scratch)
            })
        };
        Some(if done == Some(true) { JNI_TRUE } else { JNI_FALSE })
    })
}

/// `NativeEngine.upscaleInput(...)`: `UpscaleTiles.input` in one crossing
/// (`kernels::upscale::planar_input`). False, writing nothing, when the
/// arrays do not fit the shape.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeUpscaleInput(
    mut env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
    width: jint,
    height: jint,
    x0: jint,
    y0: jint,
    side: jint,
    out: JFloatArray,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        if width <= 0 || height <= 0 || side < 0 {
            return Some(JNI_FALSE);
        }
        let (pixels, out) = (pixels.as_raw(), out.as_raw());
        // SAFETY: an int[] read as u32 and a float[]; the closures make no
        // JNI call while they are pinned.
        let done = unsafe {
            let raw = env.get_raw();
            with_critical::<u32, _>(raw, pixels, false, |px| {
                with_critical::<f32, _>(raw, out, true, |out| {
                    upscale::planar_input(px, width as usize, height as usize, x0 as i64, y0 as i64, side as usize, out)
                })
            })
        };
        Some(if done.flatten() == Some(true) { JNI_TRUE } else { JNI_FALSE })
    })
}

/// `NativeEngine.upscaleOutput(...)`: `UpscaleTiles.output` in one crossing
/// (`kernels::upscale::packed_output`). False, writing nothing, when the
/// arrays do not fit the shape.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeUpscaleOutput(
    mut env: JNIEnv,
    _class: JClass,
    output: JFloatArray,
    out_side: jint,
    w: jint,
    h: jint,
    out: JIntArray,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        if out_side < 0 || w < 0 || h < 0 {
            return Some(JNI_FALSE);
        }
        let (output, out) = (output.as_raw(), out.as_raw());
        // SAFETY: a float[] and an int[] written as u32; the closures make
        // no JNI call while they are pinned.
        let done = unsafe {
            let raw = env.get_raw();
            with_critical::<f32, _>(raw, output, false, |o| {
                with_critical::<u32, _>(raw, out, true, |out| upscale::packed_output(o, out_side as usize, w as usize, h as usize, out))
            })
        };
        Some(if done.flatten() == Some(true) { JNI_TRUE } else { JNI_FALSE })
    })
}

/// `NativeEngine.mergeAlpha(...)`: `UpscaleEngine`'s alpha merge of one
/// tile in one crossing (`kernels::upscale::merge_alpha`). False, writing
/// nothing, when the arrays do not fit the shape.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeMergeAlpha(
    mut env: JNIEnv,
    _class: JClass,
    rgb: JIntArray,
    w: jint,
    h: jint,
    alpha: JIntArray,
    alpha_row: jint,
    from: jint,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        if w < 0 || h < 0 || alpha_row < 0 || from < 0 {
            return Some(JNI_FALSE);
        }
        let (rgb, alpha) = (rgb.as_raw(), alpha.as_raw());
        // SAFETY: two int[]s as u32; the closures make no JNI call while
        // they are pinned.
        let done = unsafe {
            let raw = env.get_raw();
            with_critical::<u32, _>(raw, alpha, false, |a| {
                with_critical::<u32, _>(raw, rgb, true, |p| upscale::merge_alpha(p, w as usize, h as usize, a, alpha_row as usize, from as usize))
            })
        };
        Some(if done.flatten() == Some(true) { JNI_TRUE } else { JNI_FALSE })
    })
}
