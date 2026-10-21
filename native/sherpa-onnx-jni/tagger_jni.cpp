// JNI surface for token classifiers run straight on ONNX Runtime: a model
// that reads token ids and gives each one a label. Kotlin side:
// com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative (nativeTagger*),
// driven by com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation,
// which puts punctuation into dictation from the engines that write none.
//
// The model takes (1, tokens) int64 `input_ids`, and an `attention_mask` of
// the same shape when it has that input (all ones: one unpadded row), and
// returns (1, tokens, labels) logits as its first output. The label is the
// argmax; tokenising and reading the labels back onto the words is Kotlin's.
//
// The weights may sit in an external data file beside the model, which ONNX
// Runtime maps into memory rather than copying, so the session costs a
// fraction of the file's size in private memory.
//
// Same rules as sherpa_jni.cpp: ONNX Runtime is found by soname at load time
// (the link stub only names OrtGetApiBase, which is all the C++ API calls).

#include <jni.h>

#include <array>
#include <memory>
#include <string>
#include <vector>

#include "onnxruntime_cxx_api.h"

namespace {

Ort::Env& env() {
    static Ort::Env e(ORT_LOGGING_LEVEL_ERROR, "wmsherpa-tagger");
    return e;
}

struct Tagger {
    std::unique_ptr<Ort::Session> session;
    std::string ids;
    std::string mask;  // empty when the model has no attention mask
    std::string output;
};

Tagger* tagger(jlong h) { return reinterpret_cast<Tagger*>(h); }

}  // namespace

extern "C" {

// Opens [model] with [threads] intra-op threads; 0 when it will not load or
// takes no `input_ids`.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeTagger(
    JNIEnv* jni, jclass, jstring model, jint threads) {
    const char* m = jni->GetStringUTFChars(model, nullptr);
    const std::string path = m ? m : "";
    if (m) jni->ReleaseStringUTFChars(model, m);
    if (path.empty()) return 0;
    try {
        auto out = std::make_unique<Tagger>();
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads > 0 ? threads : 1);
        options.SetInterOpNumThreads(1);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        out->session = std::make_unique<Ort::Session>(env(), path.c_str(), options);
        Ort::AllocatorWithDefaultOptions allocator;
        for (size_t i = 0; i < out->session->GetInputCount(); ++i) {
            const std::string name = out->session->GetInputNameAllocated(i, allocator).get();
            if (name == "input_ids") out->ids = name;
            else if (name == "attention_mask") out->mask = name;
            else return 0;  // an input this bridge cannot fill
        }
        if (out->ids.empty()) return 0;
        out->output = out->session->GetOutputNameAllocated(0, allocator).get();
        return reinterpret_cast<jlong>(out.release());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeTaggerFree(JNIEnv*, jclass, jlong h) {
    delete tagger(h);
}

// The label of each of the first [n] token ids of [ids], specials and all:
// the argmax of its logits. Null when the run fails.
JNIEXPORT jintArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeTag(
    JNIEnv* jni, jclass, jlong h, jintArray ids, jint n) {
    Tagger* t = tagger(h);
    if (!t || n <= 0 || n > jni->GetArrayLength(ids)) return nullptr;
    std::vector<jint> raw(static_cast<size_t>(n));
    jni->GetIntArrayRegion(ids, 0, n, raw.data());
    std::vector<int64_t> x(raw.begin(), raw.end());
    std::vector<int64_t> ones(x.size(), 1);
    std::vector<jint> labels;
    try {
        auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
        const std::array<int64_t, 2> shape = {1, n};
        std::vector<Ort::Value> inputs;
        std::vector<const char*> names;
        inputs.push_back(Ort::Value::CreateTensor<int64_t>(memory, x.data(), x.size(), shape.data(), shape.size()));
        names.push_back(t->ids.c_str());
        if (!t->mask.empty()) {
            inputs.push_back(Ort::Value::CreateTensor<int64_t>(memory, ones.data(), ones.size(), shape.data(), shape.size()));
            names.push_back(t->mask.c_str());
        }
        const char* out = t->output.c_str();
        auto result = t->session->Run(Ort::RunOptions{nullptr}, names.data(), inputs.data(), inputs.size(), &out, 1);
        const std::vector<int64_t> dims = result[0].GetTensorTypeAndShapeInfo().GetShape();
        if (dims.size() != 3 || dims[0] != 1 || dims[1] != n || dims[2] < 1) return nullptr;
        const float* logits = result[0].GetTensorData<float>();
        const int64_t classes = dims[2];
        labels.resize(static_cast<size_t>(n));
        for (int64_t i = 0; i < n; ++i) {
            const float* row = logits + i * classes;
            int64_t best = 0;
            for (int64_t c = 1; c < classes; ++c) {
                if (row[c] > row[best]) best = c;
            }
            labels[static_cast<size_t>(i)] = static_cast<jint>(best);
        }
    } catch (...) {
        return nullptr;
    }
    jintArray out = jni->NewIntArray(n);
    if (out) jni->SetIntArrayRegion(out, 0, n, labels.data());
    return out;
}

}  // extern "C"
