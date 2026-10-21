// JNI for com.wasimaster.wmkeyboard.core.input.rime.RimeNative: a thin skin
// over rime_bridge.cc. Strings cross as UTF-16 both ways, never through
// NewStringUTF, whose "modified UTF-8" would mangle every character outside
// the Basic Multilingual Plane, and CJK Extension B onwards is exactly that.
#include <jni.h>

#include <string>
#include <vector>

#include "rime_bridge.h"

namespace {

std::string ToUtf8(JNIEnv* env, jstring value) {
    std::string out;
    if (!value) return out;
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return out;
    out.reserve(length);
    for (jsize i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        }
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18));
            out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

jstring ToJava(JNIEnv* env, const std::string& value) {
    std::vector<jchar> out;
    out.reserve(value.size());
    const auto* bytes = reinterpret_cast<const unsigned char*>(value.data());
    const size_t size = value.size();
    for (size_t i = 0; i < size;) {
        uint32_t c = bytes[i];
        size_t extra = c < 0x80 ? 0 : c < 0xE0 ? 1 : c < 0xF0 ? 2 : 3;
        if (extra == 0) {
            ++i;
        } else if (i + extra >= size) {
            break;  // a truncated sequence: drop it rather than read past the end
        } else {
            c &= extra == 1 ? 0x1F : extra == 2 ? 0x0F : 0x07;
            for (size_t k = 1; k <= extra; ++k) c = (c << 6) | (bytes[i + k] & 0x3F);
            i += extra + 1;
        }
        if (c >= 0x10000) {
            c -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (c >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (c & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(c));
        }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

wmrime::Paths ToPaths(JNIEnv* env, jstring shared, jstring user, jstring version) {
    return {ToUtf8(env, shared), ToUtf8(env, user), ToUtf8(env, version)};
}

jobjectArray ToArray(JNIEnv* env, const std::vector<std::string>& items) {
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(items.size()), string_class, nullptr);
    for (size_t i = 0; i < items.size(); ++i) {
        jstring item = ToJava(env, items[i]);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    env->DeleteLocalRef(string_class);
    return array;
}

}  // namespace

#define RIME_JNI(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_com_wasimaster_wmkeyboard_core_input_rime_RimeNative_##name

RIME_JNI(jboolean, nativeStart)(JNIEnv* env, jclass, jstring shared, jstring user, jstring version) {
    return wmrime::Start(ToPaths(env, shared, user, version)) ? JNI_TRUE : JNI_FALSE;
}

RIME_JNI(void, nativeStop)(JNIEnv*, jclass) {
    wmrime::Stop();
}

RIME_JNI(void, nativeBeginDeploy)(JNIEnv* env, jclass, jstring shared, jstring user, jstring version) {
    wmrime::BeginDeploy(ToPaths(env, shared, user, version));
}

RIME_JNI(jboolean, nativeDeploySchema)(JNIEnv* env, jclass, jstring file) {
    return wmrime::DeploySchema(ToUtf8(env, file)) ? JNI_TRUE : JNI_FALSE;
}

RIME_JNI(jboolean, nativeDeployConfig)(JNIEnv* env, jclass, jstring name, jstring key) {
    return wmrime::DeployConfig(ToUtf8(env, name), ToUtf8(env, key)) ? JNI_TRUE : JNI_FALSE;
}

// "id\tname" per deployed schema.
RIME_JNI(jobjectArray, nativeSchemaList)(JNIEnv* env, jclass) {
    std::vector<std::string> items;
    for (const auto& [id, name] : wmrime::SchemaList()) items.push_back(id + "\t" + name);
    return ToArray(env, items);
}

RIME_JNI(jlong, nativeCreateSession)(JNIEnv*, jclass) {
    return static_cast<jlong>(wmrime::CreateSession());
}

RIME_JNI(void, nativeDestroySession)(JNIEnv*, jclass, jlong session) {
    wmrime::DestroySession(static_cast<uint64_t>(session));
}

RIME_JNI(jboolean, nativeSelectSchema)(JNIEnv* env, jclass, jlong session, jstring schema) {
    return wmrime::SelectSchema(static_cast<uint64_t>(session), ToUtf8(env, schema)) ? JNI_TRUE : JNI_FALSE;
}

RIME_JNI(void, nativeSetOption)(JNIEnv* env, jclass, jlong session, jstring option, jboolean value) {
    wmrime::SetOption(static_cast<uint64_t>(session), ToUtf8(env, option), value == JNI_TRUE);
}

RIME_JNI(jboolean, nativeSetInput)(JNIEnv* env, jclass, jlong session, jstring input) {
    return wmrime::SetInput(static_cast<uint64_t>(session), ToUtf8(env, input)) ? JNI_TRUE : JNI_FALSE;
}

RIME_JNI(jstring, nativeInput)(JNIEnv* env, jclass, jlong session) {
    return ToJava(env, wmrime::Input(static_cast<uint64_t>(session)));
}

// The candidates of the segment being composed, at most [limit]. [spans]
// must hold limit + 1 ints: spans[0] gets the byte offset the segment starts
// at, spans[i + 1] the byte offset candidate i ends at. [comments], when
// given, must hold limit strings and gets each candidate's comment (the
// spelling it was found under, where the schema hints spellings). Null when
// the session is gone.
RIME_JNI(jobjectArray, nativeMenu)(JNIEnv* env, jclass, jlong session, jint limit, jintArray spans,
                                   jobjectArray comments) {
    if (limit <= 0 || env->GetArrayLength(spans) < limit + 1) return nullptr;
    if (comments && env->GetArrayLength(comments) < limit) return nullptr;
    wmrime::Menu menu;
    if (!wmrime::GetMenu(static_cast<uint64_t>(session), static_cast<size_t>(limit), &menu)) return nullptr;
    std::vector<jint> out(menu.ends.size() + 1);
    out[0] = static_cast<jint>(menu.start);
    for (size_t i = 0; i < menu.ends.size(); ++i) out[i + 1] = menu.ends[i];
    env->SetIntArrayRegion(spans, 0, static_cast<jsize>(out.size()), out.data());
    if (comments) {
        for (size_t i = 0; i < menu.comments.size(); ++i) {
            jstring comment = ToJava(env, menu.comments[i]);
            env->SetObjectArrayElement(comments, static_cast<jsize>(i), comment);
            env->DeleteLocalRef(comment);
        }
    }
    return ToArray(env, menu.texts);
}

// Null when there is no such candidate; the committed text when the pick
// finished the input; empty when composing goes on.
RIME_JNI(jstring, nativeSelect)(JNIEnv* env, jclass, jlong session, jint index) {
    std::string committed;
    if (index < 0 || !wmrime::Select(static_cast<uint64_t>(session), static_cast<size_t>(index), &committed)) {
        return nullptr;
    }
    return ToJava(env, committed);
}

RIME_JNI(void, nativeClear)(JNIEnv*, jclass, jlong session) {
    wmrime::Clear(static_cast<uint64_t>(session));
}

RIME_JNI(jstring, nativeVersion)(JNIEnv* env, jclass) {
    return ToJava(env, wmrime::Version());
}
