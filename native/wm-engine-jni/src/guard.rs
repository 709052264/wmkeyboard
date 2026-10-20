//! What every entry point is built from: the panic guard, and the
//! conversions between Java and Rust values.
//!
//! The rule: an entry point's whole body runs inside [`guarded`]. A panic
//! anywhere below it becomes a Java exception the Kotlin caller can catch
//! (and the keyboard falls back or shows nothing), never an abort of the
//! process. A body returns `None` when a JNI call failed, which means a Java
//! exception is already pending; the entry point then returns its fallback
//! and the exception surfaces in Kotlin as soon as the call returns.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JIntArray, JObjectArray, JString};
use jni::sys::{jarray, jboolean};
use jni::sys::{jchar, jobjectArray, jsize, jstring};
use jni::JNIEnv;

/// Runs `f`, turning a panic into a Java `RuntimeException` and `fallback`.
pub fn guarded<T>(env: &mut JNIEnv, fallback: T, f: impl FnOnce(&mut JNIEnv) -> Option<T>) -> T {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Some(value)) => value,
        Ok(None) => fallback,
        Err(payload) => {
            let message = payload
                .downcast_ref::<String>()
                .cloned()
                .or_else(|| payload.downcast_ref::<&str>().map(|s| s.to_string()))
                .unwrap_or_else(|| "wm-engine panicked".to_string());
            // A panic while an exception is already pending (a failed JNI
            // call, then a bug) keeps the first one: throwing over it is not
            // allowed.
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/RuntimeException", format!("wm-engine: {message}"));
            }
            fallback
        }
    }
}

/// The object behind `$handle` in `$registry`, or (from the enclosing
/// body) `None` with an `IllegalStateException` thrown.
macro_rules! object {
    ($env:expr, $registry:expr, $handle:expr) => {
        match $registry.get($handle) {
            Some(object) => object,
            None => return $crate::guard::throw_state($env, "wm-engine: closed or unknown handle"),
        }
    };
}
pub(crate) use object;

/// Throws `IllegalStateException(message)` and returns `None`, for a body
/// to bail out with: `return throw_state(env, "closed")`.
pub fn throw_state<T>(env: &mut JNIEnv, message: &str) -> Option<T> {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
    None
}

pub fn read(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

/// A Java string's UTF-16 units into `out` (cleared first), exactly as
/// Kotlin holds them, lone surrogates included: one `GetStringRegion`, no
/// modified-UTF-8 round trip. The dictionaries store UTF-16 too, so a
/// lookup never decodes at all.
pub fn read_units(env: &mut JNIEnv, s: &JString, out: &mut Vec<u16>) -> Option<()> {
    out.clear();
    if s.is_null() {
        return None;
    }
    let raw = env.get_raw();
    // SAFETY: `raw` is this thread's live JNIEnv and `s` a live local
    // reference; the region read stays within the length just asked for,
    // into a buffer of exactly that many units.
    unsafe {
        let len = (**raw).GetStringLength?(raw, s.as_raw()).max(0);
        out.reserve(len as usize);
        (**raw).GetStringRegion?(raw, s.as_raw(), 0, len, out.as_mut_ptr() as *mut jchar);
        out.set_len(len as usize);
    }
    (!env.exception_check().unwrap_or(true)).then_some(())
}

/// A Java string from UTF-16 units, as they are.
pub fn give_units(env: &mut JNIEnv, units: &[u16]) -> Option<jstring> {
    let raw = env.get_raw();
    // SAFETY: `units` is valid for `units.len()` reads for the call.
    let s = unsafe { (**raw).NewString?(raw, units.as_ptr() as *const jchar, units.len() as jsize) };
    (!s.is_null()).then_some(s)
}

pub fn give(env: &mut JNIEnv, s: String) -> Option<jstring> {
    env.new_string(s).ok().map(|s| s.into_raw())
}

pub fn give_all(env: &mut JNIEnv, items: Vec<String>) -> Option<jobjectArray> {
    let class = env.find_class("java/lang/String").ok()?;
    let array: JObjectArray = env.new_object_array(items.len() as i32, class, JString::default()).ok()?;
    for (i, item) in items.into_iter().enumerate() {
        let value = env.new_string(item).ok()?;
        env.set_object_array_element(&array, i as i32, value).ok()?;
    }
    Some(array.into_raw())
}

/// A `String[]` of UTF-16 words. Each element's local reference is dropped
/// as it is stored, so a long list cannot overflow the local frame.
pub fn give_all_units<'a>(env: &mut JNIEnv, items: impl ExactSizeIterator<Item = &'a [u16]>) -> Option<jobjectArray> {
    let class = env.find_class("java/lang/String").ok()?;
    let array: JObjectArray = env.new_object_array(items.len() as i32, &class, JString::default()).ok()?;
    for (i, item) in items.enumerate() {
        let value = give_units(env, item)?;
        // SAFETY: `value` is the local reference NewString just returned.
        let value = unsafe { JString::from_raw(value) };
        env.set_object_array_element(&array, i as i32, &value).ok()?;
        env.delete_local_ref(value).ok()?;
    }
    Some(array.into_raw())
}

/// An `int[]` holding `values`.
pub fn give_ints(env: &mut JNIEnv, values: &[i32]) -> Option<JIntArray<'static>> {
    let array = env.new_int_array(values.len() as jsize).ok()?;
    env.set_int_array_region(&array, 0, values).ok()?;
    // SAFETY: re-wrapping the same local reference; its real lifetime is the
    // native frame, which outlives every use the caller makes of it.
    Some(unsafe { JIntArray::from_raw(array.into_raw()) })
}

/// A primitive array pinned for the length of `f`, as a slice of `T`:
/// `GetPrimitiveArrayCritical`, so no copy on ART for a non-moving array.
/// Nothing in `f` may call back into the JVM, which is why it gets the
/// slices and not the env. `write_back` copies changes back (`0` mode) or
/// discards them (`JNI_ABORT`) when the VM did copy.
///
/// # Safety
/// `array` must be a Java array whose element type is `T`, and `raw` this
/// thread's live env.
/// `raw` is the env's raw pointer (`JNIEnv::get_raw`), so pins can nest.
pub unsafe fn with_critical<T, R>(
    raw: *mut jni::sys::JNIEnv,
    array: jarray,
    write_back: bool,
    f: impl FnOnce(&mut [T]) -> R,
) -> Option<R> {
    let len = (**raw).GetArrayLength?(raw, array).max(0) as usize;
    let mut is_copy: jboolean = 0;
    let ptr = (**raw).GetPrimitiveArrayCritical?(raw, array, &mut is_copy) as *mut T;
    if ptr.is_null() {
        return None;
    }
    let result = f(std::slice::from_raw_parts_mut(ptr, len));
    let mode = if write_back { 0 } else { jni::sys::JNI_ABORT };
    (**raw).ReleasePrimitiveArrayCritical?(raw, array, ptr as *mut std::ffi::c_void, mode);
    Some(result)
}
