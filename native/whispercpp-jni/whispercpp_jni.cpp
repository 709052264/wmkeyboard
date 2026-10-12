// JNI surface for whisper.cpp: open a ggml model, transcribe a clip, read the
// text as it is being decoded, stop early. Kotlin side:
// com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCpp.
//
// Text crosses the boundary as UTF-8 byte arrays, never through NewStringUTF:
// Whisper's byte-level tokens split multibyte characters, and JNI's modified
// UTF-8 has no room for a half character or a four-byte emoji.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

struct Session {
    whisper_context* ctx = nullptr;
    // One transcription at a time per model; Kotlin serialises too.
    std::mutex run_mutex;
    std::atomic<bool> abort{false};

    // What has been decoded so far, for nativePartial to read from another
    // thread. Finished segments, then the hypothesis being decoded now.
    std::mutex partial_mutex;
    std::string done;
    std::string current;
    int current_tokens = 0;
};

void throwJava(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

jbyteArray bytes(JNIEnv* env, const std::string& text) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out && !text.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()),
                                reinterpret_cast<const jbyte*>(text.data()));
    }
    return out;
}

std::string tokensToText(whisper_context* ctx, const whisper_token_data* tokens, int n) {
    const whisper_token eot = whisper_token_eot(ctx);
    std::string text;
    for (int i = 0; i < n; ++i) {
        // Everything from end-of-text up is special: task, language, timestamps.
        if (tokens[i].id >= eot) continue;
        const char* piece = whisper_token_to_str(ctx, tokens[i].id);
        if (piece) text += piece;
    }
    return text;
}

// Runs on whichever thread decodes: several at once under beam search, so it
// only records. The longest hypothesis wins a step; beams are kept sorted, so
// that is close to the best one, and it is only a preview.
void onLogits(whisper_context* ctx, whisper_state*, const whisper_token_data* tokens, int n_tokens,
              float*, void* user_data) {
    auto* s = static_cast<Session*>(user_data);
    std::string text = tokensToText(ctx, tokens, n_tokens);
    std::lock_guard<std::mutex> lock(s->partial_mutex);
    if (n_tokens >= s->current_tokens) {
        s->current_tokens = n_tokens;
        s->current = std::move(text);
    }
}

void onSegment(whisper_context* ctx, whisper_state*, int n_new, void* user_data) {
    auto* s = static_cast<Session*>(user_data);
    const int total = whisper_full_n_segments(ctx);
    std::lock_guard<std::mutex> lock(s->partial_mutex);
    for (int i = total - n_new; i < total; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text) s->done += text;
    }
    s->current.clear();
    s->current_tokens = 0;
}

bool onAbort(void* user_data) {
    return static_cast<Session*>(user_data)->abort.load();
}

// Silences whisper.cpp's and ggml's chatter on stderr, which on Android goes
// nowhere anyway.
void quiet(ggml_log_level, const char*, void*) {}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whisper_WhisperCpp_nativeOpen(
    JNIEnv* env, jclass, jstring path) {
    whisper_log_set(quiet, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return 0;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(p, params);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) return 0;
    auto* s = new Session();
    s->ctx = ctx;
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whisper_WhisperCpp_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    {
        std::lock_guard<std::mutex> lock(s->run_mutex);
        whisper_free(s->ctx);
        s->ctx = nullptr;
    }
    delete s;
}

/**
 * Transcribes [pcm] (16 kHz mono float). [languages] are Whisper codes: none
 * detects among all, one is forced, several are detected among those only.
 * Returns "<code>\n<text>" as UTF-8 bytes, or null when it was stopped.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whisper_WhisperCpp_nativeTranscribe(
    JNIEnv* env, jclass, jlong handle, jfloatArray audio, jobjectArray languages, jboolean translate,
    jstring prompt, jint beam_size, jint threads) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || !s->ctx) {
        throwJava(env, "Whisper model is not open");
        return nullptr;
    }
    const jsize count = audio ? env->GetArrayLength(audio) : 0;
    if (count <= 0) {
        throwJava(env, "No audio");
        return nullptr;
    }
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(audio, 0, count, pcm.data());

    std::vector<std::string> codes;
    const jsize nLang = languages ? env->GetArrayLength(languages) : 0;
    for (jsize i = 0; i < nLang; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(languages, i));
        if (!js) continue;
        const char* c = env->GetStringUTFChars(js, nullptr);
        if (c && whisper_lang_id(c) >= 0) codes.emplace_back(c);
        if (c) env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }
    std::string promptText;
    if (prompt) {
        const char* p = env->GetStringUTFChars(prompt, nullptr);
        if (p) promptText = p;
        if (p) env->ReleaseStringUTFChars(prompt, p);
    }
    if (env->ExceptionCheck()) return nullptr;

    std::lock_guard<std::mutex> run(s->run_mutex);
    s->abort = false;
    {
        std::lock_guard<std::mutex> lock(s->partial_mutex);
        s->done.clear();
        s->current.clear();
        s->current_tokens = 0;
    }
    const int nThreads = std::max(1, static_cast<int>(threads));
    const bool multilingual = whisper_is_multilingual(s->ctx);

    // Several languages: Whisper's own detection, but only over the ones the
    // user dictates in. It costs one extra encoder pass, and it is what keeps
    // a short phrase from being read as some third language.
    std::string language;
    if (multilingual && codes.size() == 1) {
        language = codes[0];
    } else if (multilingual && codes.size() > 1) {
        const int window = std::min(count, 16000 * 30);
        std::vector<float> probs(static_cast<size_t>(whisper_lang_max_id() + 1), 0.f);
        if (whisper_pcm_to_mel(s->ctx, pcm.data(), window, nThreads) == 0 &&
            whisper_lang_auto_detect(s->ctx, 0, nThreads, probs.data()) >= 0) {
            float best = -1.f;
            for (const auto& c : codes) {
                const float p = probs[static_cast<size_t>(whisper_lang_id(c.c_str()))];
                if (p > best) {
                    best = p;
                    language = c;
                }
            }
        } else {
            language = codes[0];
        }
    }

    const bool beam = beam_size > 1;
    whisper_full_params wp = whisper_full_default_params(beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    wp.n_threads = nThreads;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.translate = multilingual && translate == JNI_TRUE;
    wp.language = multilingual ? (language.empty() ? "auto" : language.c_str()) : "en";
    wp.detect_language = false;
    // Timestamps only earn their keep where the clip runs past one window and
    // has to be cut into segments.
    wp.no_timestamps = count < 16000 * 25;
    wp.suppress_blank = true;
    // "[Music]", "(laughs)", "♪": captions for sounds, never dictation.
    wp.suppress_nst = true;
    // One pass. The temperature fallback re-decodes a doubtful window up to
    // five times, and a phone pays for every one of them.
    wp.temperature_inc = 0.0f;
    // Without the fallback nothing else stops a decoder that has fallen into
    // repeating itself, so a window gets a token budget its speech could
    // plausibly fill: about 20 tokens a second, which is generous even for a
    // script whose letters take several byte-level tokens each.
    const int windowSeconds = std::min(count, 16000 * 30) / 16000 + 1;
    wp.max_tokens = std::clamp(32 + windowSeconds * 20, 64, 440);
    if (beam) {
        wp.beam_search.beam_size = beam_size;
    } else {
        wp.greedy.best_of = 1;
    }
    if (!promptText.empty()) {
        wp.initial_prompt = promptText.c_str();
        wp.carry_initial_prompt = true;
    }
    wp.logits_filter_callback = onLogits;
    wp.logits_filter_callback_user_data = s;
    wp.new_segment_callback = onSegment;
    wp.new_segment_callback_user_data = s;
    wp.abort_callback = onAbort;
    wp.abort_callback_user_data = s;

    const int rc = whisper_full(s->ctx, wp, pcm.data(), count);
    {
        // The next clip's preview starts empty, not on this clip's words.
        std::lock_guard<std::mutex> lock(s->partial_mutex);
        s->done.clear();
        s->current.clear();
        s->current_tokens = 0;
    }
    if (s->abort.load()) return nullptr;
    if (rc != 0) {
        throwJava(env, "Whisper could not transcribe this clip");
        return nullptr;
    }

    std::string out;
    const int lang = whisper_full_lang_id(s->ctx);
    out += lang >= 0 ? whisper_lang_str(lang) : (language.empty() ? "" : language.c_str());
    out += '\n';
    const int n = whisper_full_n_segments(s->ctx);
    for (int i = 0; i < n; ++i) {
        const char* text = whisper_full_get_segment_text(s->ctx, i);
        if (text) out += text;
    }
    return bytes(env, out);
}

/** What has been decoded so far, as UTF-8 bytes; empty before the first token. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whisper_WhisperCpp_nativePartial(JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return bytes(env, "");
    std::string text;
    {
        std::lock_guard<std::mutex> lock(s->partial_mutex);
        text = s->done + s->current;
    }
    return bytes(env, text);
}

/** Ends the running transcription at the next step; it returns null. */
extern "C" JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whisper_WhisperCpp_nativeAbort(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s) s->abort = true;
}
