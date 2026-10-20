//! JNI surface of divvunspell (divvun-fst) for the keyboard: open a GiellaLT
//! speller archive (.bhfst, or the older .zhfst), ask whether a word is spelt
//! right, ask for spellings, and unpack the .pkt.tar.zst a GiellaLT release
//! ships its archive in. Kotlin side:
//! `com.wasimaster.wmkeyboard.core.prediction.DivvunNative`.
//!
//! Every entry point catches panics, so a broken archive the user imported
//! is a refusal rather than a dead keyboard, and none throws into Java.

use std::fs::File;
use std::io::{BufReader, Read, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::Arc;

use divvun_fst::archive::SpellerArchive;
use divvun_fst::speller::{Speller, SpellerConfig};
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

/// One open archive: the speller and the configuration its suggestions run with.
pub struct Checker {
    // The archive owns the memory maps the speller reads; it goes last.
    speller: Arc<dyn Speller + Send + Sync>,
    config: SpellerConfig,
    _archive: Arc<dyn SpellerArchive + Send + Sync>,
}

/// How many search nodes one suggestion run may pop before it settles for what
/// it found. The search is best-first, so a stop costs the dearest tail, not
/// the answer; without a bound a word with nothing near it sweeps everything
/// the error model reaches, which is seconds on a phone (README.md).
pub const SEARCH_BUDGET: u64 = 60_000;

impl Checker {
    /// Opens the archive at [path], which must end in `.bhfst` or `.zhfst`.
    pub fn open(path: &str) -> Result<Checker, String> {
        let archive = divvun_fst::archive::open(Path::new(path)).map_err(|e| e.to_string())?;
        let mut config = archive.bundled_config().cloned().unwrap_or_else(SpellerConfig::default);
        if config.search_budget.map_or(true, |b| b > SEARCH_BUDGET) {
            config.search_budget = Some(SEARCH_BUDGET);
        }
        Ok(Checker { speller: archive.speller(), config, _archive: archive })
    }

    pub fn is_correct(&self, word: &str) -> bool {
        Arc::clone(&self.speller).is_correct_with_config(word, &self.config)
    }

    /// Spellings of [word], best first, at most [limit], never [word] itself.
    pub fn suggest(&self, word: &str, limit: usize) -> Vec<String> {
        let mut config = self.config.clone();
        // One more than asked: the word itself may come back among them.
        config.n_best = Some(limit + 1);
        let mut out = Vec::with_capacity(limit);
        for s in Arc::clone(&self.speller).suggest_with_config(word, &config) {
            let value = s.value().to_string();
            if value != word && !out.contains(&value) {
                out.push(value);
            }
            if out.len() == limit {
                break;
            }
        }
        out
    }
}

/// Writes the member of the zstd-compressed tar at [source] named [member]
/// (or, when [member] is empty, its first `.bhfst` that is not a `-x-`
/// variant such as `se-x-predict.bhfst`) to [target].
pub fn unpack(source: &str, member: &str, target: &str) -> Result<(), String> {
    let file = File::open(source).map_err(|e| format!("{source}: {e}"))?;
    let decoder = zstd::stream::read::Decoder::new(BufReader::new(file)).map_err(|e| e.to_string())?;
    let mut archive = tar::Archive::new(decoder);
    for entry in archive.entries().map_err(|e| e.to_string())? {
        let mut entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path().map_err(|e| e.to_string())?.to_string_lossy().into_owned();
        let name = path.rsplit('/').next().unwrap_or(&path).to_string();
        let wanted = if member.is_empty() {
            name.ends_with(".bhfst") && !name.contains("-x-")
        } else {
            name == member
        };
        if !wanted {
            continue;
        }
        let mut out = File::create(target).map_err(|e| format!("{target}: {e}"))?;
        let mut buffer = vec![0u8; 1 << 16];
        loop {
            let n = entry.read(&mut buffer).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            out.write_all(&buffer[..n]).map_err(|e| e.to_string())?;
        }
        out.sync_all().map_err(|e| e.to_string())?;
        return Ok(());
    }
    Err(if member.is_empty() { "no .bhfst in the package".into() } else { format!("no {member} in the package") })
}

fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn checker<'a>(handle: jlong) -> Option<&'a Checker> {
    if handle == 0 {
        None
    } else {
        // SAFETY: a nonzero handle is a Box<Checker> from nativeOpen that
        // nativeClose has not freed yet; Kotlin never closes one in use.
        Some(unsafe { &*(handle as *const Checker) })
    }
}

/// Points the temporary directory at [dir] when the process has none it can
/// write: Android's default is /data/local/tmp, which an app cannot, and a
/// .zhfst with compressed members unpacks into it.
fn set_temp_dir(dir: &str) {
    if dir.is_empty() {
        return;
    }
    let usable = std::env::var_os("TMPDIR").map_or(false, |t| {
        let probe = Path::new(&t).join(".wmdivvun-probe");
        let ok = File::create(&probe).is_ok();
        let _ = std::fs::remove_file(&probe);
        ok
    });
    if !usable {
        std::env::set_var("TMPDIR", dir);
    }
}

/// A checker over the archive at `path`, or 0 when it does not open.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_DivvunNative_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    temp_dir: JString,
) -> jlong {
    let (Some(path), temp) = (string(&mut env, &path), string(&mut env, &temp_dir)) else {
        return 0;
    };
    catch_unwind(|| {
        set_temp_dir(temp.as_deref().unwrap_or(""));
        Checker::open(&path).map_or(0, |c| Box::into_raw(Box::new(c)) as jlong)
    })
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_DivvunNative_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: see `checker`; this is the one place a handle is freed.
        let _ = catch_unwind(|| drop(unsafe { Box::from_raw(handle as *mut Checker) }));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_DivvunNative_nativeIsCorrect(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    word: JString,
) -> jboolean {
    let (Some(c), Some(word)) = (checker(handle), string(&mut env, &word)) else {
        return JNI_FALSE;
    };
    match catch_unwind(AssertUnwindSafe(|| c.is_correct(&word))) {
        Ok(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// Spellings of `word`, best first, at most `limit`; null when the search failed.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_DivvunNative_nativeSuggest(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    word: JString,
    limit: jint,
) -> jobjectArray {
    let (Some(c), Some(word)) = (checker(handle), string(&mut env, &word)) else {
        return std::ptr::null_mut();
    };
    let Ok(found) = catch_unwind(AssertUnwindSafe(|| c.suggest(&word, limit.max(0) as usize))) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.new_object_array(found.len() as jint, "java/lang/String", JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (i, s) in found.iter().enumerate() {
        let Ok(js) = env.new_string(s) else { return std::ptr::null_mut() };
        if env.set_object_array_element(&array, i as jint, &js).is_err() {
            return std::ptr::null_mut();
        }
        let _ = env.delete_local_ref(js);
    }
    array.into_raw()
}

/// Unpacks the speller out of a GiellaLT `.pkt.tar.zst`; null on success, else what went wrong.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_DivvunNative_nativeUnpack(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    member: JString,
    target: JString,
) -> jstring {
    let source = string(&mut env, &source).unwrap_or_default();
    let member = string(&mut env, &member).unwrap_or_default();
    let target = string(&mut env, &target).unwrap_or_default();
    let result = catch_unwind(|| unpack(&source, &member, &target))
        .unwrap_or_else(|_| Err("the unpacker failed".into()));
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(message) => env.new_string(message).map_or(std::ptr::null_mut(), |s| s.into_raw()),
    }
}
