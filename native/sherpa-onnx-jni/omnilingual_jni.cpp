// JNI surface for Meta's Omnilingual ASR CTC model, run straight on ONNX
// Runtime. Kotlin side: com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
// (nativeOmnilingual*), driven by OmnilingualEngine.
//
// sherpa-onnx's offline recognizer decodes this model too, but it has no
// say over which characters come out, and the model has no language
// conditioning: it spells by what it hears, so Hindi often comes back in Urdu
// letters and English now and then in Arabic ones. Here the logits are masked
// to the tokens of the keyboard language's script before the greedy CTC
// pick, so the model can only choose among letters the user writes with
// (greedy() says what becomes of a frame it gives to another script).
// Which tokens those are is worked out on the Kotlin side, from tokens.txt
// (OmnilingualScriptMask), and comes in with each phrase.
//
// The rest follows sherpa-onnx's offline-omnilingual-asr-ctc-model.cc: the
// model reads the raw 16 kHz waveform, (1, samples), normalised to zero mean
// and unit variance, and returns (1, frames, vocab) logits; id 0 is the CTC
// blank, and every token in tokens.txt is a single character.
//
// Same rules as sherpa_jni.cpp: ONNX Runtime is found by soname at load time
// (the link stub only names OrtGetApiBase, which is all the C++ API calls),
// and text crosses the boundary as UTF-8 bytes.

#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "onnxruntime_cxx_api.h"

namespace {

Ort::Env& env() {
    static Ort::Env e(ORT_LOGGING_LEVEL_ERROR, "wmsherpa-omnilingual");
    return e;
}

struct Omnilingual {
    std::unique_ptr<Ort::Session> session;
    std::string input;
    std::string output;
    // Each id's text; empty for the blank and the other specials (<s>, <pad>,
    // </s>, <unk>), which never reach the text.
    std::vector<std::string> tokens;
};

Omnilingual* omni(jlong h) { return reinterpret_cast<Omnilingual*>(h); }

// How far, in logits, a letter of the dictation's script may trail the blank
// and still win a frame, in a phrase the model was spelling in another
// script. Measured on macOS's Hindi voice: 2 brings back the short vowel
// signs (राहुल, मुझे, बहुत) that Urdu spelling leaves out; 3 starts adding
// letters that were never said (दि्ली, लियए).
constexpr float kForeignMargin = 2.0f;

// How much further than the phrase's median a letter held in may trail the
// other script's letter and still stand in for it. Measured on macOS's Hindi
// and English voices: Urdu's و in اور trails by about 7 where the phrase's
// median is under 1, and 2 to 4 differ by a letter or two at most.
constexpr float kStandInSpread = 4.0f;

// tokens.txt: one "<symbol> <id>" per line, the symbol a single character,
// which may itself be a space (id 4).
bool readTokens(const std::string& path, std::vector<std::string>* out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        const size_t space = line.rfind(' ');
        if (space == std::string::npos || space + 1 >= line.size()) continue;
        const long id = std::strtol(line.c_str() + space + 1, nullptr, 10);
        if (id < 0 || id > 1000000) continue;
        std::string symbol = line.substr(0, space);
        if (symbol.size() > 1 && symbol.front() == '<' && symbol.back() == '>') symbol.clear();
        if (static_cast<size_t>(id) >= out->size()) out->resize(static_cast<size_t>(id) + 1);
        (*out)[static_cast<size_t>(id)] = symbol;
    }
    return !out->empty();
}

// Greedy CTC over (frames, vocab) logits, held to the ids flagged in [mask]
// (every id when it is empty), as UTF-8.
//
// The blank and the letters compete as usual where the model writes the
// dictation's script. Where it writes another script's letter, that frame
// still says a sound, and the letter held in that scores best for it is
// nearly always the same sound in the dictation's script (Urdu ب is
// Devanagari ब, Arabic ل Latin l), so it stands in, unless it trails by far
// more than this phrase's typical stand-in: then the other script spells
// something the dictation's does not write as a letter of its own (the و in
// Urdu's اور, the ھ that marks aspiration), and the frame goes to the blank.
// A phrase spelled mostly in the other script also gives held letters
// kForeignMargin against the blank; see there.
std::string greedy(const Omnilingual& o, const float* logits, int64_t frames, int64_t vocab,
                   const std::vector<jboolean>& mask) {
    auto held = [&](int64_t id) {
        return mask.empty() || (id < static_cast<int64_t>(mask.size()) && mask[static_cast<size_t>(id)]);
    };
    auto space = [&](int64_t id) {
        return static_cast<size_t>(id) < o.tokens.size() && o.tokens[static_cast<size_t>(id)] == " ";
    };
    // Per frame: the model's own pick, the best held token (blank aside), and
    // the best held letter that is not a space, to stand in for a letter.
    std::vector<int64_t> top(static_cast<size_t>(frames)), best(static_cast<size_t>(frames)),
        letter(static_cast<size_t>(frames));
    std::vector<float> gaps;
    int64_t inside = 0, outside = 0, previousTop = -1;
    for (int64_t t = 0; t < frames; ++t) {
        const float* row = logits + t * vocab;
        int64_t tp = 0, b = -1, l = -1;
        for (int64_t id = 1; id < vocab; ++id) {
            if (row[id] > row[tp]) tp = id;
            if (!held(id)) continue;
            if (b < 0 || row[id] > row[b]) b = id;
            if (!space(id) && (l < 0 || row[id] > row[l])) l = id;
        }
        top[static_cast<size_t>(t)] = tp;
        best[static_cast<size_t>(t)] = b;
        letter[static_cast<size_t>(t)] = l;
        const bool foreign = tp != 0 && !held(tp);
        if (foreign && l >= 0) gaps.push_back(row[tp] - row[l]);
        if (tp != 0 && tp != previousTop) ++(foreign ? outside : inside);
        previousTop = tp;
    }
    float limit = 0;
    if (!gaps.empty()) {
        std::nth_element(gaps.begin(), gaps.begin() + static_cast<std::ptrdiff_t>(gaps.size() / 2), gaps.end());
        limit = gaps[gaps.size() / 2] + kStandInSpread;
    }
    const float margin = outside > inside ? kForeignMargin : 0.0f;
    std::string text;
    int64_t previous = -1;
    for (int64_t t = 0; t < frames; ++t) {
        const float* row = logits + t * vocab;
        const size_t i = static_cast<size_t>(t);
        const int64_t tp = top[i];
        int64_t pick = 0;
        if (tp != 0 && !held(tp)) {
            if (letter[i] >= 0 && row[tp] - row[letter[i]] <= limit) pick = letter[i];
        } else if (best[i] >= 0 && row[best[i]] > row[0] - margin) {
            pick = best[i];
        }
        if (pick != previous && pick != 0 && static_cast<size_t>(pick) < o.tokens.size()) {
            text += o.tokens[static_cast<size_t>(pick)];
        }
        previous = pick;
    }
    return text;
}

}  // namespace

extern "C" {

// Opens the model and its tokens.txt with [threads] intra-op threads; 0 when
// either will not load.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeOmnilingual(
    JNIEnv* jni, jclass, jstring model, jstring tokens, jint threads) {
    const char* m = jni->GetStringUTFChars(model, nullptr);
    const char* t = jni->GetStringUTFChars(tokens, nullptr);
    const std::string modelPath = m ? m : "", tokensPath = t ? t : "";
    if (m) jni->ReleaseStringUTFChars(model, m);
    if (t) jni->ReleaseStringUTFChars(tokens, t);
    if (modelPath.empty() || tokensPath.empty()) return 0;
    try {
        auto out = std::make_unique<Omnilingual>();
        if (!readTokens(tokensPath, &out->tokens)) return 0;
        out->tokens[0].clear();  // the blank, <s> in this tokens.txt
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads > 0 ? threads : 1);
        options.SetInterOpNumThreads(1);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        out->session = std::make_unique<Ort::Session>(env(), modelPath.c_str(), options);
        Ort::AllocatorWithDefaultOptions allocator;
        out->input = out->session->GetInputNameAllocated(0, allocator).get();
        out->output = out->session->GetOutputNameAllocated(0, allocator).get();
        return reinterpret_cast<jlong>(out.release());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeOmnilingualFree(JNIEnv*, jclass, jlong h) {
    delete omni(h);
}

// The first n samples of a phrase (16 kHz, [-1, 1]) decoded whole; its text,
// UTF-8. [allowed], when not null, holds one flag per token id: only flagged
// ids can win a frame, so the text keeps to the script they spell. Ids past
// its end are left out; an empty one holds nothing back.
JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeOmnilingualDecode(
    JNIEnv* jni, jclass, jlong h, jfloatArray samples, jint n, jbooleanArray allowed) {
    Omnilingual* o = omni(h);
    std::string text;
    if (o && n > 0) {
        std::vector<float> x(static_cast<size_t>(n));
        jni->GetFloatArrayRegion(samples, 0, n, x.data());
        std::vector<jboolean> mask;
        if (allowed) {
            mask.resize(static_cast<size_t>(jni->GetArrayLength(allowed)));
            if (!mask.empty()) jni->GetBooleanArrayRegion(allowed, 0, static_cast<jsize>(mask.size()), mask.data());
        }
        // Zero mean, unit variance over the whole phrase. The variance comes
        // from centred values, as in sherpa-onnx: E[x^2] - E[x]^2 cancels
        // badly in float32 when the signal sits near a constant.
        double sum = 0;
        for (float v : x) sum += v;
        const float mean = static_cast<float>(sum / x.size());
        double squares = 0;
        for (float v : x) squares += static_cast<double>(v - mean) * (v - mean);
        const float scale = 1.0f / std::sqrt(static_cast<float>(squares / x.size()) + 1e-5f);
        for (float& v : x) v = (v - mean) * scale;
        try {
            auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
            const std::array<int64_t, 2> shape = {1, n};
            Ort::Value input = Ort::Value::CreateTensor<float>(memory, x.data(), x.size(), shape.data(), shape.size());
            const char* in = o->input.c_str();
            const char* out = o->output.c_str();
            auto result = o->session->Run(Ort::RunOptions{nullptr}, &in, &input, 1, &out, 1);
            const std::vector<int64_t> dims = result[0].GetTensorTypeAndShapeInfo().GetShape();
            if (dims.size() == 3 && dims[0] == 1) {
                text = greedy(*o, result[0].GetTensorData<float>(), dims[1], dims[2], mask);
            }
        } catch (...) {
            text.clear();
        }
    }
    // Spaces collapsed and trimmed, as sherpa-onnx's result has them.
    std::string tidy;
    for (char c : text) {
        if (c == ' ' && (tidy.empty() || tidy.back() == ' ')) continue;
        tidy += c;
    }
    while (!tidy.empty() && tidy.back() == ' ') tidy.pop_back();
    jbyteArray bytes = jni->NewByteArray(static_cast<jsize>(tidy.size()));
    if (bytes && !tidy.empty()) {
        jni->SetByteArrayRegion(bytes, 0, static_cast<jsize>(tidy.size()), reinterpret_cast<const jbyte*>(tidy.data()));
    }
    return bytes;
}

}  // extern "C"
