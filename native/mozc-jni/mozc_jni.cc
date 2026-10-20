// JNI for com.wasimaster.wmkeyboard.core.input.mozc.MozcNative, over
// mozc_bridge.h. Strings cross as modified UTF-8 through NewStringUTF, which
// is safe here: readings are kana and Mozc's candidates stay in the BMP
// apart from emoji, which go through the UTF-16 path below.
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "mozc_bridge.h"

namespace {

std::string ToStd(JNIEnv* env, jstring text) {
  if (text == nullptr) return {};
  const jsize length = env->GetStringLength(text);
  const jchar* chars = env->GetStringChars(text, nullptr);
  std::string out;
  out.reserve(length * 3);
  for (jsize i = 0; i < length; ++i) {
    uint32_t cp = chars[i];
    if (cp >= 0xD800 && cp < 0xDC00 && i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] < 0xE000) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
      ++i;
    }
    if (cp < 0x80) {
      out += static_cast<char>(cp);
    } else if (cp < 0x800) {
      out += static_cast<char>(0xC0 | (cp >> 6));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      out += static_cast<char>(0xE0 | (cp >> 12));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
      out += static_cast<char>(0xF0 | (cp >> 18));
      out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    }
  }
  env->ReleaseStringChars(text, chars);
  return out;
}

// Real UTF-8 to a Java string, supplementary characters included (emoji
// candidates), which NewStringUTF's modified UTF-8 would garble.
jstring ToJava(JNIEnv* env, const std::string& text) {
  std::u16string out;
  out.reserve(text.size());
  for (size_t i = 0; i < text.size();) {
    const unsigned char c = text[i];
    uint32_t cp;
    int extra;
    if (c < 0x80) { cp = c; extra = 0; }
    else if ((c >> 5) == 0x6) { cp = c & 0x1F; extra = 1; }
    else if ((c >> 4) == 0xE) { cp = c & 0x0F; extra = 2; }
    else { cp = c & 0x07; extra = 3; }
    if (i + extra >= text.size()) break;  // a truncated sequence
    for (int k = 1; k <= extra; ++k) cp = (cp << 6) | (text[i + k] & 0x3F);
    i += extra + 1;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out += static_cast<char16_t>(0xD800 + (cp >> 10));
      out += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
    } else {
      out += static_cast<char16_t>(cp);
    }
  }
  return env->NewString(reinterpret_cast<const jchar*>(out.data()), static_cast<jsize>(out.size()));
}

std::vector<int> ToOps(JNIEnv* env, jintArray ops) {
  std::vector<int> out;
  if (ops == nullptr) return out;
  const jsize n = env->GetArrayLength(ops);
  out.resize(n);
  if (n > 0) env->GetIntArrayRegion(ops, 0, n, reinterpret_cast<jint*>(out.data()));
  return out;
}

// A menu as the Kotlin side reads it: the candidate texts, then each
// segment's reading and best text, as strings; in `info`, the counts and
// the focus, then the id and consumed length of each candidate.
//   info[0] candidates, info[1] focus, info[2] segments,
//   info[3 + 2i] id of candidate i, info[4 + 2i] its consumed characters.
jobjectArray Pack(JNIEnv* env, const wmmozc::Menu& menu, jintArray info) {
  const jsize capacity = env->GetArrayLength(info);
  const int fits = std::max(0, (static_cast<int>(capacity) - 3) / 2);
  const int count = std::min(static_cast<int>(menu.candidates.size()), fits);
  std::vector<jint> head(3 + 2 * count);
  head[0] = count;
  head[1] = menu.focus;
  head[2] = static_cast<jint>(menu.segments.size());
  for (int i = 0; i < count; ++i) {
    head[3 + 2 * i] = menu.candidates[i].id;
    head[4 + 2 * i] = menu.candidates[i].consumed;
  }
  env->SetIntArrayRegion(info, 0, static_cast<jsize>(head.size()), head.data());
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray out = env->NewObjectArray(count + 2 * static_cast<jsize>(menu.segments.size()), string_class, nullptr);
  jsize at = 0;
  for (int i = 0; i < count; ++i) {
    jstring s = ToJava(env, menu.candidates[i].value);
    env->SetObjectArrayElement(out, at++, s);
    env->DeleteLocalRef(s);
  }
  for (const wmmozc::Segment& segment : menu.segments) {
    jstring key = ToJava(env, segment.key);
    env->SetObjectArrayElement(out, at++, key);
    env->DeleteLocalRef(key);
    jstring value = ToJava(env, segment.value);
    env->SetObjectArrayElement(out, at++, value);
    env->DeleteLocalRef(value);
  }
  return out;
}

}  // namespace

#define MOZC_JNI(ret, name) \
  extern "C" JNIEXPORT ret JNICALL Java_com_wasimaster_wmkeyboard_core_input_mozc_MozcNative_##name

MOZC_JNI(jstring, nativeStart)(JNIEnv* env, jclass, jstring data_file, jstring user_dir) {
  std::string error;
  if (wmmozc::Start(ToStd(env, data_file), ToStd(env, user_dir), &error)) return nullptr;
  return ToJava(env, error.empty() ? std::string("Mozc did not start") : error);
}

MOZC_JNI(void, nativeStop)(JNIEnv*, jclass) { wmmozc::Stop(); }

MOZC_JNI(jstring, nativeDataVersion)(JNIEnv* env, jclass) {
  return ToJava(env, wmmozc::DataVersion());
}

MOZC_JNI(void, nativeSetKanaModifierInsensitive)(JNIEnv*, jclass, jboolean on) {
  wmmozc::SetKanaModifierInsensitive(on == JNI_TRUE);
}

MOZC_JNI(jobjectArray, nativeSuggest)(JNIEnv* env, jclass, jstring reading, jstring preceding,
                                      jint limit, jintArray info) {
  wmmozc::Menu menu;
  if (!wmmozc::Suggest(ToStd(env, reading), ToStd(env, preceding), limit, &menu)) return nullptr;
  return Pack(env, menu, info);
}

MOZC_JNI(jobjectArray, nativeConvert)(JNIEnv* env, jclass, jstring reading, jstring preceding,
                                      jintArray ops, jint limit, jintArray info) {
  wmmozc::Menu menu;
  if (!wmmozc::Convert(ToStd(env, reading), ToStd(env, preceding), ToOps(env, ops), limit, &menu)) {
    return nullptr;
  }
  return Pack(env, menu, info);
}

MOZC_JNI(jobjectArray, nativePredict)(JNIEnv* env, jclass, jstring preceding, jint limit,
                                      jintArray info) {
  wmmozc::Menu menu;
  if (!wmmozc::Predict(ToStd(env, preceding), limit, &menu)) return nullptr;
  return Pack(env, menu, info);
}

MOZC_JNI(jint, nativeCommitSuggestion)(JNIEnv* env, jclass, jstring reading, jstring preceding,
                                       jint id) {
  return wmmozc::CommitSuggestion(ToStd(env, reading), ToStd(env, preceding), id);
}

MOZC_JNI(jint, nativeCommitConversion)(JNIEnv* env, jclass, jstring reading, jstring preceding,
                                       jintArray ops, jint id) {
  return wmmozc::CommitConversion(ToStd(env, reading), ToStd(env, preceding), ToOps(env, ops), id);
}

MOZC_JNI(jint, nativeCommitPrediction)(JNIEnv* env, jclass, jstring preceding, jint id) {
  return wmmozc::CommitPrediction(ToStd(env, preceding), id);
}

MOZC_JNI(void, nativeResetContext)(JNIEnv*, jclass) { wmmozc::ResetContext(); }

MOZC_JNI(jboolean, nativeSync)(JNIEnv*, jclass) { return wmmozc::Sync() ? JNI_TRUE : JNI_FALSE; }

MOZC_JNI(jboolean, nativeClearHistory)(JNIEnv*, jclass) {
  return wmmozc::ClearHistory() ? JNI_TRUE : JNI_FALSE;
}
