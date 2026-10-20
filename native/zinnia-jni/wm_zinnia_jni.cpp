// JNI surface for zinnia: open a model file, classify one written character.
// Kotlin side: com.wasimaster.wmkeyboard.core.handwriting.ZinniaNative.
//
// zinnia recognises a single character per call. Kotlin splits a line of ink
// into characters, fits each into the square box the models were trained on
// and hands over integer points, so nothing here knows about the panel.
//
// Results cross as UTF-8 byte arrays, never as Java strings: JNI's modified
// UTF-8 encodes a character outside the BMP differently from the model's
// plain UTF-8, so Kotlin decodes.
//
// A recogniser only reads its model after open (classify is const and keeps
// its scratch on the stack), so two threads may classify at once. Kotlin
// still serialises close against classify.

#include <jni.h>

#include <cstring>
#include <memory>
#include <new>

#include "zinnia.h"

namespace {

zinnia::Recognizer* from(jlong handle) { return reinterpret_cast<zinnia::Recognizer*>(handle); }

// The model stores each label in a fixed 16-byte field; a label that fills it
// would have no terminator, so never read past it.
constexpr size_t kLabelBytes = 16;

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_handwriting_ZinniaNative_nativeOpen(JNIEnv* env, jobject, jstring path) {
    const char* file = env->GetStringUTFChars(path, nullptr);
    if (file == nullptr) return 0;
    zinnia::Recognizer* recognizer = zinnia::Recognizer::create();
    if (recognizer != nullptr && !recognizer->open(file)) {
        delete recognizer;
        recognizer = nullptr;
    }
    env->ReleaseStringUTFChars(path, file);
    return reinterpret_cast<jlong>(recognizer);
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_handwriting_ZinniaNative_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete from(handle);
}

JNIEXPORT jint JNICALL
Java_com_wasimaster_wmkeyboard_core_handwriting_ZinniaNative_nativeSize(JNIEnv*, jobject, jlong handle) {
    return static_cast<jint>(from(handle)->size());
}

// points holds x0, y0, x1, y1 ... for every stroke in writing order;
// strokeEnds[i] is the number of points written once stroke i is done. The
// character sits in a box `size` units square. Fills scores (when not null)
// with one score per result and returns the results, best first, or null on
// bad input.
JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_handwriting_ZinniaNative_nativeClassify(
        JNIEnv* env, jobject, jlong handle, jint size, jintArray points, jintArray strokeEnds,
        jint nbest, jfloatArray scores) {
    if (size <= 0 || nbest <= 0) return nullptr;
    const jsize pointInts = env->GetArrayLength(points);
    const jsize strokes = env->GetArrayLength(strokeEnds);
    if (strokes <= 0 || pointInts % 2 != 0) return nullptr;

    std::unique_ptr<zinnia::Character> character(zinnia::Character::create());
    if (!character) return nullptr;
    character->set_width(static_cast<size_t>(size));
    character->set_height(static_cast<size_t>(size));

    jint* xy = env->GetIntArrayElements(points, nullptr);
    jint* ends = env->GetIntArrayElements(strokeEnds, nullptr);
    if (xy == nullptr || ends == nullptr) {
        if (xy != nullptr) env->ReleaseIntArrayElements(points, xy, JNI_ABORT);
        if (ends != nullptr) env->ReleaseIntArrayElements(strokeEnds, ends, JNI_ABORT);
        return nullptr;
    }
    bool ok = true;
    jint start = 0;
    for (jsize s = 0; s < strokes && ok; ++s) {
        const jint end = ends[s];
        if (end < start || end * 2 > pointInts) {
            ok = false;
            break;
        }
        for (jint p = start; p < end; ++p) {
            character->add(static_cast<size_t>(s), xy[2 * p], xy[2 * p + 1]);
        }
        start = end;
    }
    env->ReleaseIntArrayElements(points, xy, JNI_ABORT);
    env->ReleaseIntArrayElements(strokeEnds, ends, JNI_ABORT);
    if (!ok) return nullptr;

    std::unique_ptr<zinnia::Result> result(from(handle)->classify(*character, static_cast<size_t>(nbest)));
    if (!result) return nullptr;

    const size_t count = result->size();
    jclass byteArray = env->FindClass("[B");
    if (byteArray == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(count), byteArray, nullptr);
    if (out == nullptr) return nullptr;
    const jsize scoreSlots = scores != nullptr ? env->GetArrayLength(scores) : 0;
    for (size_t i = 0; i < count; ++i) {
        const char* label = result->value(i);
        const size_t length = label != nullptr ? strnlen(label, kLabelBytes) : 0;
        jbyteArray item = env->NewByteArray(static_cast<jsize>(length));
        if (item == nullptr) return nullptr;
        if (length > 0) {
            env->SetByteArrayRegion(item, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(label));
        }
        env->SetObjectArrayElement(out, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
        if (static_cast<jsize>(i) < scoreSlots) {
            const jfloat score = result->score(i);
            env->SetFloatArrayRegion(scores, static_cast<jsize>(i), 1, &score);
        }
    }
    return out;
}

}  // extern "C"
