// JNI surface for zxing-cpp's readers: find and decode barcodes in a camera
// frame's luminance plane or in a picture's pixels.
// Kotlin side: com.wasimaster.wmkeyboard.core.barcode.ZxingNative.
//
// One crossing per frame. A frame's Y plane is read where CameraX left it (a
// direct ByteBuffer, never copied); a picture's ARGB ints are pinned with
// GetPrimitiveArrayCritical for the read, which makes no JNI call. Every
// decoded symbol's text crosses back as UTF-8 bytes, never as a Java string:
// JNI's modified UTF-8 spells a character outside the BMP differently, and a
// QR code may well hold an emoji.
//
// Nothing is kept between calls, so any thread may call. zxing-cpp throws on
// bad input (a zero-sized view, for one); every entry point catches it and
// rethrows as a RuntimeException once nothing is pinned.

#include <jni.h>

#include <cstdint>
#include <exception>
#include <string>
#include <utility>
#include <vector>

#include "ReadBarcode.h"

static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "an ARGB int is read as B, G, R, A bytes");

namespace {

using namespace ZXing;

// Bits of the `effort` argument, mirrored in ZxingNative.
constexpr jint kTryHarder = 1;
constexpr jint kTryInvert = 2;

struct Found {
    std::vector<std::pair<uint32_t, std::string>> codes;
    std::string error;
};

ReaderOptions optionsFor(jint effort, jint maxSymbols) {
    ReaderOptions options;
    // Every format this build reads (the default empty set), the text as the
    // symbol spells it (ML Kit's rawValue, not a GS1 rendering), and turned
    // linear codes too: a phone is held either way up.
    options.setTryHarder((effort & kTryHarder) != 0)
        .setTryInvert((effort & kTryInvert) != 0)
        .setTryRotate(true)
        .setTryDownscale(true)
        .setTextMode(TextMode::Plain)
        .setMaxNumberOfSymbols(static_cast<uint8_t>(maxSymbols < 1 ? 1 : maxSymbols > 255 ? 255 : maxSymbols));
    return options;
}

void read(const ImageView& image, jint effort, jint maxSymbols, Found& found) {
    try {
        for (const Barcode& barcode : ReadBarcodes(image, optionsFor(effort, maxSymbols))) {
            if (!barcode.isValid()) continue;
            BarcodeFormat format = barcode.format();
            std::string text = barcode.text();
            // ML Kit's convention, which the scanner's flow grew up with: a
            // UPC code as its own 12 or 8 digits, not as the 13-digit EAN-13
            // ISO/IEC 15420 normalises it to. A UPC-A symbol is an EAN-13
            // whose first digit is 0, so zxing-cpp reports it as one.
            if (format == BarcodeFormat::UPCE) {
                std::string original = barcode.extra(BarcodeExtra::UPCE);
                if (!original.empty()) text = std::move(original);
            } else if (format == BarcodeFormat::EAN13 && text.size() == 13 && text[0] == '0') {
                format = BarcodeFormat::UPCA;
                text.erase(0, 1);
            }
            found.codes.emplace_back(static_cast<uint32_t>(format), std::move(text));
        }
    } catch (const std::exception& e) {
        found.error = e.what();
    } catch (...) {
        found.error = "zxing-cpp failed";
    }
}

void throwRuntime(JNIEnv* env, const std::string& message) {
    jclass runtime = env->FindClass("java/lang/RuntimeException");
    if (runtime != nullptr) env->ThrowNew(runtime, message.c_str());
}

// The texts as UTF-8 byte arrays, each code's format in `formats` (as many as
// it holds); null when nothing was found. Throws what the read caught.
jobjectArray give(JNIEnv* env, const Found& found, jintArray formats) {
    if (!found.error.empty()) {
        throwRuntime(env, found.error);
        return nullptr;
    }
    if (found.codes.empty()) return nullptr;
    jclass byteArray = env->FindClass("[B");
    if (byteArray == nullptr) return nullptr;
    const jsize count = static_cast<jsize>(found.codes.size());
    jobjectArray out = env->NewObjectArray(count, byteArray, nullptr);
    if (out == nullptr) return nullptr;
    const jsize slots = formats != nullptr ? env->GetArrayLength(formats) : 0;
    for (jsize i = 0; i < count; ++i) {
        const auto& [format, text] = found.codes[static_cast<size_t>(i)];
        jbyteArray item = env->NewByteArray(static_cast<jsize>(text.size()));
        if (item == nullptr) return nullptr;
        if (!text.empty()) {
            env->SetByteArrayRegion(item, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        }
        env->SetObjectArrayElement(out, i, item);
        env->DeleteLocalRef(item);
        if (i < slots) {
            const jint value = static_cast<jint>(format);
            env->SetIntArrayRegion(formats, i, 1, &value);
        }
    }
    return out;
}

}  // namespace

extern "C" {

// A camera frame's Y plane: `width` by `height` luminance bytes, rows
// `rowStride` apart, in a direct ByteBuffer.
JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_barcode_ZxingNative_nativeReadLuma(
        JNIEnv* env, jobject, jobject buffer, jint width, jint height, jint rowStride,
        jint effort, jint maxSymbols, jintArray formats) {
    if (buffer == nullptr || width <= 0 || height <= 0 || rowStride < width) return nullptr;
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr) {
        throwRuntime(env, "not a direct buffer");
        return nullptr;
    }
    if (capacity < static_cast<jlong>(height - 1) * rowStride + width) {
        throwRuntime(env, "buffer smaller than the frame");
        return nullptr;
    }
    Found found;
    read(ImageView(data, width, height, ImageFormat::Lum, rowStride), effort, maxSymbols, found);
    return give(env, found, formats);
}

// A picture as Bitmap.getPixels gives it: `width` by `height` ARGB ints.
JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_barcode_ZxingNative_nativeReadPixels(
        JNIEnv* env, jobject, jintArray argb, jint width, jint height,
        jint effort, jint maxSymbols, jintArray formats) {
    if (argb == nullptr || width <= 0 || height <= 0) return nullptr;
    if (env->GetArrayLength(argb) < static_cast<jlong>(width) * height) {
        throwRuntime(env, "fewer pixels than the picture");
        return nullptr;
    }
    Found found;
    void* pixels = env->GetPrimitiveArrayCritical(argb, nullptr);
    if (pixels == nullptr) return nullptr;
    read(ImageView(static_cast<const uint8_t*>(pixels), width, height, ImageFormat::BGRA), effort, maxSymbols, found);
    env->ReleasePrimitiveArrayCritical(argb, pixels, JNI_ABORT);
    return give(env, found, formats);
}

}  // extern "C"
