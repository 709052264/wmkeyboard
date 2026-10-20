// JNI surface for CTranslate2 running Meta's NLLB-200 (distilled 600M, int8):
// load the model directory once, translate a batch of sentences between two
// of its 200 languages. Kotlin side: com.wasimaster.wmkeyboard.core.translate.Ct2Native.
//
// Text crosses the boundary as UTF-8 byte arrays, never as Java strings: JNI's
// modified UTF-8 writes an emoji as two three-byte surrogates, which
// SentencePiece would not read as one character. Language tags are NLLB's
// FLORES-200 codes ("ben_Beng"), plain ASCII, so they come as strings.
//
// The tokenisation is NLLB's as Hugging Face's tokenizer does it: the source
// is [source tag] + SentencePiece pieces + "</s>", and decoding starts from
// the target tag, which the result then drops.
//
// A handle may not be used from two threads at once; Kotlin serialises every
// call behind one lock. Every C++ exception becomes a Java RuntimeException,
// so a bad file costs a translation, not the keyboard.

#include <jni.h>

#include <algorithm>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "ctranslate2/logging.h"
#include "ctranslate2/translator.h"
#include "sentencepiece_processor.h"

namespace {

struct Engine {
    std::unique_ptr<ctranslate2::Translator> translator;
    sentencepiece::SentencePieceProcessor pieces;
};

// Decoding stops here even if the model never says "</s>": a sentence that
// reaches it has gone into a loop, which greedy NLLB can on odd input.
constexpr size_t kMaxDecoding = 256;

void throwJava(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass error = env->FindClass("java/lang/RuntimeException");
    if (error != nullptr) env->ThrowNew(error, message);
}

std::string string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
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

jobjectArray toJava(JNIEnv* env, const std::vector<std::string>& texts) {
    jclass bytes = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(texts.size()), bytes, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < texts.size(); ++i) {
        const std::string& text = texts[i];
        jbyteArray array = env->NewByteArray(static_cast<jsize>(text.size()));
        if (array == nullptr) return nullptr;
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        env->SetObjectArrayElement(out, static_cast<jsize>(i), array);
        env->DeleteLocalRef(array);
    }
    return out;
}

Engine* engine(jlong handle) { return reinterpret_cast<Engine*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    ctranslate2::set_log_level(ctranslate2::LogLevel::Off);
    return JNI_VERSION_1_6;
}

// modelDir holds CTranslate2's model.bin, config.json and
// shared_vocabulary.txt plus NLLB's sentencepiece.bpe.model. threads is how
// many cores one translation may use.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_Ct2Native_nativeLoad(JNIEnv* env, jobject, jstring modelDir, jint threads) {
    try {
        const std::string dir = string(env, modelDir);
        auto loaded = std::make_unique<Engine>();
        const auto status = loaded->pieces.Load(dir + "/sentencepiece.bpe.model");
        if (!status.ok()) throw std::runtime_error(status.ToString());
        ctranslate2::ReplicaPoolConfig config;
        config.num_threads_per_replica = static_cast<size_t>(std::max(1, static_cast<int>(threads)));
        // int8 weights as stored; activations in float32, the only path on a phone CPU.
        loaded->translator = std::make_unique<ctranslate2::Translator>(
            dir, ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8, std::vector<int>{0}, false, config);
        return reinterpret_cast<jlong>(loaded.release());
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "NLLB model failed to load");
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_Ct2Native_nativeFree(JNIEnv*, jobject, jlong handle) {
    delete engine(handle);
}

// Each of texts (UTF-8, one sentence each) from source into target, in order.
// beam 1 is greedy search.
JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_Ct2Native_nativeTranslate(
        JNIEnv* env, jobject, jlong handle, jobjectArray texts, jstring sourceTag, jstring targetTag, jint beam) {
    try {
        Engine* e = engine(handle);
        const std::string source = string(env, sourceTag);
        const std::string target = string(env, targetTag);
        const std::vector<std::string> inputs = utf8(env, texts);
        std::vector<std::vector<std::string>> batch;
        std::vector<std::vector<std::string>> prefixes;
        size_t longest = 0;
        batch.reserve(inputs.size());
        for (const std::string& text : inputs) {
            std::vector<std::string> tokens{source};
            std::vector<std::string> pieces;
            const auto status = e->pieces.Encode(text, &pieces);
            if (!status.ok()) throw std::runtime_error(status.ToString());
            tokens.insert(tokens.end(), pieces.begin(), pieces.end());
            tokens.push_back("</s>");
            longest = std::max(longest, tokens.size());
            batch.push_back(std::move(tokens));
            prefixes.push_back({target});
        }
        ctranslate2::TranslationOptions options;
        options.beam_size = static_cast<size_t>(std::max(1, static_cast<int>(beam)));
        // A translation runs to about twice its source at most; the cap
        // stops a loop well before kMaxDecoding on a short line.
        options.max_decoding_length = std::min(kMaxDecoding, longest * 2 + 10);
        options.max_input_length = 512;
        auto results = e->translator->translate_batch(batch, prefixes, options);
        std::vector<std::string> out;
        out.reserve(results.size());
        for (const auto& result : results) {
            std::vector<std::string> tokens = result.hypotheses.empty() ? std::vector<std::string>{} : result.hypotheses[0];
            // The first token is the target tag the decoding started from.
            if (!tokens.empty() && tokens.front() == target) tokens.erase(tokens.begin());
            std::string text;
            const auto status = e->pieces.Decode(tokens, &text);
            if (!status.ok()) throw std::runtime_error(status.ToString());
            out.push_back(std::move(text));
        }
        return toJava(env, out);
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "NLLB translation failed");
    }
    return nullptr;
}

}  // extern "C"
