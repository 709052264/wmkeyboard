//! `GifNative` (core/content): the GIF encoder behind `GifEncoder`,
//! `wm_engine::gif`. A frame is one crossing: its pixels pinned, its bytes
//! back as one `byte[]`.

use std::cell::RefCell;

use jni::objects::{JClass, JIntArray};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;

use wm_engine::gif::{self, FrameEncoder, MAX_DIMENSION};

use crate::guard::{guarded, throw_state, with_critical};

thread_local! {
    // The palette, histogram and LZW tables, about a megabyte, kept for the
    // thread's next frame; and the frame's bytes.
    static FRAMES: RefCell<(FrameEncoder, Vec<u8>)> = RefCell::new((FrameEncoder::new(), Vec::new()));
}

fn fits(width: jint, height: jint) -> bool {
    (1..=MAX_DIMENSION as jint).contains(&width) && (1..=MAX_DIMENSION as jint).contains(&height)
}

/// `GifNative.header(width, height)`: the file's header and loop block.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_media_GifNative_header(
    mut env: JNIEnv,
    _this: JClass,
    width: jint,
    height: jint,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        if !fits(width, height) {
            return throw_state(env, "gif: canvas out of range");
        }
        let mut out = Vec::with_capacity(32);
        gif::header(width as usize, height as usize, &mut out);
        env.byte_array_from_slice(&out).ok().map(|a| a.into_raw())
    })
}

/// `GifNative.frame(argb, width, height, durationMs)`: one frame of
/// `width * height` colour ints, encoded (`FrameEncoder::frame`). The
/// pixels are pinned, not copied, while it runs.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_media_GifNative_frame(
    mut env: JNIEnv,
    _this: JClass,
    argb: JIntArray,
    width: jint,
    height: jint,
    duration_ms: jint,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let pixels = env.get_array_length(&argb).ok()? as usize;
        if !fits(width, height) || pixels != width as usize * height as usize {
            return throw_state(env, "gif: frame does not fit the canvas");
        }
        FRAMES.with(|cell| {
            let (encoder, out) = &mut *cell.borrow_mut();
            out.clear();
            // SAFETY: an int[] (the Kotlin signature says so), read as u32
            // colour ints, and the closure makes no JNI call while it is pinned.
            unsafe {
                with_critical::<u32, _>(env.get_raw(), argb.as_raw(), false, |argb| {
                    encoder.frame(argb, width as usize, height as usize, duration_ms, out)
                })
            }?;
            env.byte_array_from_slice(out).ok().map(|a| a.into_raw())
        })
    })
}
