// JNI surface for Bergamot, the translation engine inside Firefox: load one
// direction's model files, translate a batch of texts with it or through
// English with two of them, and unpack the zstd files Mozilla publishes the
// models as. Kotlin side: com.wasimaster.wmkeyboard.core.translate.BergamotNative.
//
// Text crosses the boundary as UTF-8 byte arrays, never as Java strings: JNI's
// modified UTF-8 writes an emoji as two three-byte surrogates, which neither
// SentencePiece nor the sentence splitter reads as one character.
//
// A service translates on the calling thread (BlockingService), and neither
// it nor a model may be used from two threads at once. Kotlin serialises
// every call behind one lock.
//
// Marian reports a fatal error by aborting the process. JNI_OnLoad switches
// that to an exception, which every entry point below turns into a Java
// RuntimeException, so a bad file costs a translation, not the keyboard.

#include <jni.h>

#include <cstdio>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "common/logging.h"
#include "translator/service.h"
#include "zstd.h"

namespace {

using marian::bergamot::AlignedMemory;
using marian::bergamot::BlockingService;
using marian::bergamot::MemoryBundle;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

// The alignments Firefox's engine worker allocates each file with.
constexpr size_t kModelAlignment = 256;
constexpr size_t kOtherAlignment = 64;

void throwJava(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass error = env->FindClass("java/lang/RuntimeException");
    if (error != nullptr) env->ThrowNew(error, message);
}

std::string path(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

AlignedMemory readFile(const std::string& file, size_t alignment) {
    std::unique_ptr<FILE, int (*)(FILE*)> in(std::fopen(file.c_str(), "rb"), &std::fclose);
    if (!in) throw std::runtime_error("cannot open " + file);
    std::fseek(in.get(), 0, SEEK_END);
    const long size = std::ftell(in.get());
    std::fseek(in.get(), 0, SEEK_SET);
    if (size <= 0) throw std::runtime_error("empty file " + file);
    AlignedMemory memory(static_cast<size_t>(size), alignment);
    if (std::fread(memory.begin(), 1, static_cast<size_t>(size), in.get()) != static_cast<size_t>(size)) {
        throw std::runtime_error("short read of " + file);
    }
    return memory;
}

// A YAML single-quoted scalar: the only escape is a doubled quote.
std::string yamlQuoted(const std::string& value) {
    std::string out = "'";
    for (char c : value) {
        out += c;
        if (c == '\'') out += '\'';
    }
    return out + "'";
}

std::vector<std::string> utf8(JNIEnv* env, jobjectArray texts) {
    const jsize n = env->GetArrayLength(texts);
    std::vector<std::string> out;
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto array = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
        const jsize length = array == nullptr ? 0 : env->GetArrayLength(array);
        std::string text(static_cast<size_t>(length), '\0');
        if (length > 0) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(&text[0]));
        if (array != nullptr) env->DeleteLocalRef(array);
        out.push_back(std::move(text));
    }
    return out;
}

jobjectArray toJava(JNIEnv* env, const std::vector<Response>& responses) {
    jclass bytes = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(responses.size()), bytes, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < responses.size(); ++i) {
        const std::string& text = responses[i].target.text;
        jbyteArray array = env->NewByteArray(static_cast<jsize>(text.size()));
        if (array == nullptr) return nullptr;
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        env->SetObjectArrayElement(out, static_cast<jsize>(i), array);
        env->DeleteLocalRef(array);
    }
    return out;
}

std::vector<ResponseOptions> plainOptions(size_t count) {
    ResponseOptions options;
    options.qualityScores = false;
    options.alignment = false;
    options.HTML = false;
    return std::vector<ResponseOptions>(count, options);
}

std::shared_ptr<TranslationModel>* model(jlong handle) {
    return reinterpret_cast<std::shared_ptr<TranslationModel>*>(handle);
}

BlockingService* service(jlong handle) { return reinterpret_cast<BlockingService*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    marian::setThrowExceptionOnAbort(true);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_BergamotNative_nativeNewService(JNIEnv* env, jobject, jint cacheSize) {
    try {
        BlockingService::Config config;
        config.cacheSize = static_cast<size_t>(cacheSize < 0 ? 0 : cacheSize);
        config.logger.level = "off";
        return reinterpret_cast<jlong>(new BlockingService(config));
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "Bergamot service failed to start");
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_BergamotNative_nativeFreeService(JNIEnv*, jobject, jlong handle) {
    delete service(handle);
}

// vocabs holds one path (a shared vocabulary) or two (source, then target).
// int8shiftAll is for an `intgemm8.bin` model, the alphas variant otherwise,
// exactly as Firefox decides.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_BergamotNative_nativeLoadModel(
        JNIEnv* env, jobject, jstring modelPath, jstring lexPath, jobjectArray vocabPaths, jboolean int8shiftAll) {
    try {
        MemoryBundle bundle;
        bundle.models.emplace_back(readFile(path(env, modelPath), kModelAlignment));
        if (lexPath != nullptr) bundle.shortlist = readFile(path(env, lexPath), kOtherAlignment);
        const jsize vocabCount = env->GetArrayLength(vocabPaths);
        if (vocabCount < 1 || vocabCount > 2) throw std::runtime_error("one or two vocabularies expected");
        const std::string sourceVocab = path(env, static_cast<jstring>(env->GetObjectArrayElement(vocabPaths, 0)));
        const std::string targetVocab =
            vocabCount == 2 ? path(env, static_cast<jstring>(env->GetObjectArrayElement(vocabPaths, 1))) : sourceVocab;
        auto source = std::make_shared<AlignedMemory>(readFile(sourceVocab, kOtherAlignment));
        bundle.vocabs.push_back(source);
        if (vocabCount == 2) {
            bundle.vocabs.push_back(std::make_shared<AlignedMemory>(readFile(targetVocab, kOtherAlignment)));
        } else {
            bundle.vocabs.push_back(source);
        }
        // Firefox's settings for its engine worker, with its "cpu-threads: 0"
        // (translate on the calling thread) and no quality model.
        std::string config =
            "beam-size: 1\n"
            "normalize: 1.0\n"
            "word-penalty: 0\n"
            "max-length-break: 128\n"
            "mini-batch-words: 1024\n"
            "workspace: 128\n"
            "max-length-factor: 2.0\n"
            "skip-cost: true\n"
            "cpu-threads: 0\n"
            "quiet: true\n"
            "quiet-translation: true\n"
            "ssplit-mode: paragraph\n";
        config += int8shiftAll ? "gemm-precision: int8shiftAll\n" : "gemm-precision: int8shiftAlphaAll\n";
        // The vocabularies come from the bundle, but Marian still indexes this
        // list when it builds the embedding layer (for factored vocabularies).
        // Firefox's WASM reads past an empty one without noticing; a native
        // build reads freed memory, so the paths go in too.
        config += "vocabs:\n  - " + yamlQuoted(sourceVocab) + "\n  - " + yamlQuoted(targetVocab) + "\n";
        // The shortlist needs no line here: a non-empty bundle.shortlist is used as it is.
        auto loaded = std::make_shared<TranslationModel>(config, std::move(bundle));
        return reinterpret_cast<jlong>(new std::shared_ptr<TranslationModel>(std::move(loaded)));
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "Bergamot model failed to load");
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_BergamotNative_nativeFreeModel(JNIEnv*, jobject, jlong handle) {
    delete model(handle);
}

JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_BergamotNative_nativeTranslate(
        JNIEnv* env, jobject, jlong serviceHandle, jlong modelHandle, jobjectArray texts) {
    try {
        std::vector<std::string> sources = utf8(env, texts);
        const size_t count = sources.size();
        auto responses = service(serviceHandle)->translateMultiple(*model(modelHandle), std::move(sources), plainOptions(count));
        return toJava(env, responses);
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "Bergamot translation failed");
    }
    return nullptr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_BergamotNative_nativePivot(
        JNIEnv* env, jobject, jlong serviceHandle, jlong firstHandle, jlong secondHandle, jobjectArray texts) {
    try {
        std::vector<std::string> sources = utf8(env, texts);
        const size_t count = sources.size();
        auto responses = service(serviceHandle)
                             ->pivotMultiple(*model(firstHandle), *model(secondHandle), std::move(sources), plainOptions(count));
        return toJava(env, responses);
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "Bergamot translation failed");
    }
    return nullptr;
}

// Unpacks one .zst file into another, streaming, so a 40 MB model never sits
// in memory twice. Returns null on success, else what went wrong.
JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_BergamotNative_nativeUnzstd(
        JNIEnv* env, jobject, jstring sourcePath, jstring targetPath) {
    std::string from = path(env, sourcePath);
    std::string to = path(env, targetPath);
    std::unique_ptr<FILE, int (*)(FILE*)> in(std::fopen(from.c_str(), "rb"), &std::fclose);
    if (!in) return env->NewStringUTF("cannot open the download");
    std::unique_ptr<FILE, int (*)(FILE*)> out(std::fopen(to.c_str(), "wb"), &std::fclose);
    if (!out) return env->NewStringUTF("cannot write the model file");
    std::unique_ptr<ZSTD_DCtx, size_t (*)(ZSTD_DCtx*)> context(ZSTD_createDCtx(), &ZSTD_freeDCtx);
    if (!context) return env->NewStringUTF("out of memory");
    std::vector<char> inBuffer(ZSTD_DStreamInSize());
    std::vector<char> outBuffer(ZSTD_DStreamOutSize());
    size_t last = 0;
    bool any = false;
    for (;;) {
        const size_t read = std::fread(inBuffer.data(), 1, inBuffer.size(), in.get());
        if (read == 0) break;
        any = true;
        ZSTD_inBuffer input = {inBuffer.data(), read, 0};
        while (input.pos < input.size) {
            ZSTD_outBuffer output = {outBuffer.data(), outBuffer.size(), 0};
            last = ZSTD_decompressStream(context.get(), &output, &input);
            if (ZSTD_isError(last)) return env->NewStringUTF(ZSTD_getErrorName(last));
            if (std::fwrite(outBuffer.data(), 1, output.pos, out.get()) != output.pos) {
                return env->NewStringUTF("out of storage");
            }
        }
    }
    if (!any) return env->NewStringUTF("the download is empty");
    // Non-zero here means the stream stopped part way through a frame.
    if (last != 0) return env->NewStringUTF("the download is truncated");
    if (std::fflush(out.get()) != 0) return env->NewStringUTF("out of storage");
    return nullptr;
}

}  // extern "C"
