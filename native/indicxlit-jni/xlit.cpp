// See xlit.h. The graphs are the ones scripts/export_indicxlit.py in the data
// repository writes:
//
//   encoder  src [1,S] int64 -> cross_k, cross_v [L,1,H,S,D] float
//   decoder  token [B,1] int64, pos [1] int64, self_k/self_v [L,B,H,T,D],
//            cross_k/cross_v -> logp [B,V], new_k/new_v [L,B,H,T+1,D]
//
// The decoder carries its own self-attention cache, so each step feeds one
// token per hypothesis. The search is fairseq's: 2*beam expansions a step,
// a hypothesis that writes </s> is finished with its score divided by its
// length counting the </s>, and the search stops once `beam` have finished.
#define ORT_API_MANUAL_INIT
#include "xlit.h"

#include <onnxruntime_cxx_api.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <utility>

struct OrtSessionHolder {
    Ort::Env env{nullptr};
    Ort::Session encoder{nullptr};
    Ort::Session decoder{nullptr};
};

namespace wmxlit {

namespace {

constexpr int64_t kEos = 2;
constexpr int64_t kLayers = 6;
constexpr int64_t kHeads = 4;
constexpr int64_t kHeadDim = 64;
constexpr float kNegInf = -std::numeric_limits<float>::infinity();

const char* const kEncIn[] = {"src"};
const char* const kEncOut[] = {"cross_k", "cross_v"};
const char* const kDecIn[] = {"token", "pos", "self_k", "self_v", "cross_k", "cross_v"};
const char* const kDecOut[] = {"logp", "new_k", "new_v"};

struct Live {
    std::vector<int32_t> tokens;
    float score;
};

struct Expansion {
    float score;
    int from;
    int32_t token;
};

}  // namespace

Model::Model() : ort_(new OrtSessionHolder) {}

Model::~Model() = default;

std::unique_ptr<Model> Model::open(const std::string& encoder, const std::string& decoder, int threads,
                                   std::string* error) {
    const OrtApiBase* base = OrtGetApiBase();
    const OrtApi* api = base ? base->GetApi(ORT_API_VERSION) : nullptr;
    if (!api) {
        *error = "ONNX Runtime is older than this bridge's headers";
        return nullptr;
    }
    Ort::InitApi(api);
    try {
        std::unique_ptr<Model> model(new Model);
        model->ort_->env = Ort::Env(ORT_LOGGING_LEVEL_ERROR, "wmxlit");
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(std::max(1, threads));
        options.SetInterOpNumThreads(1);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        model->ort_->encoder = Ort::Session(model->ort_->env, encoder.c_str(), options);
        model->ort_->decoder = Ort::Session(model->ort_->env, decoder.c_str(), options);
        return model;
    } catch (const Ort::Exception& e) {
        *error = e.what();
        return nullptr;
    }
}

std::vector<Hypothesis> Model::transliterate(const std::vector<int64_t>& src, const std::vector<uint8_t>& allowed,
                                             int beam, int maxLen) {
    std::vector<Hypothesis> done;
    if (src.empty() || beam < 1 || maxLen < 1) return done;
    Ort::MemoryInfo cpu = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

    int64_t srcShape[] = {1, static_cast<int64_t>(src.size())};
    Ort::Value srcTensor = Ort::Value::CreateTensor<int64_t>(
        cpu, const_cast<int64_t*>(src.data()), src.size(), srcShape, 2);
    std::vector<Ort::Value> cross = ort_->encoder.Run(Ort::RunOptions{nullptr}, kEncIn, &srcTensor, 1, kEncOut, 2);

    std::vector<Live> live{{{}, 0.0f}};
    // The self-attention cache, [L,B,H,T,D], T steps long.
    std::vector<float> cacheK, cacheV;
    // A zero-length tensor still wants a pointer.
    float dummy = 0.0f;
    std::vector<int64_t> tokens;
    std::vector<Expansion> expansions;

    for (int step = 0; step < maxLen; step++) {
        const int64_t b = static_cast<int64_t>(live.size());
        const int64_t t = step;
        tokens.resize(b);
        for (int64_t i = 0; i < b; i++) tokens[i] = live[i].tokens.empty() ? kEos : live[i].tokens.back();
        int64_t tokenShape[] = {b, 1};
        int64_t posValue = step;
        int64_t posShape[] = {1};
        int64_t cacheShape[] = {kLayers, b, kHeads, t, kHeadDim};
        const size_t cacheSize = static_cast<size_t>(kLayers * b * kHeads * t * kHeadDim);
        Ort::Value inputs[] = {
            Ort::Value::CreateTensor<int64_t>(cpu, tokens.data(), tokens.size(), tokenShape, 2),
            Ort::Value::CreateTensor<int64_t>(cpu, &posValue, 1, posShape, 1),
            Ort::Value::CreateTensor<float>(cpu, cacheSize ? cacheK.data() : &dummy, cacheSize, cacheShape, 5),
            Ort::Value::CreateTensor<float>(cpu, cacheSize ? cacheV.data() : &dummy, cacheSize, cacheShape, 5),
            std::move(cross[0]),
            std::move(cross[1]),
        };
        std::vector<Ort::Value> out = ort_->decoder.Run(Ort::RunOptions{nullptr}, kDecIn, inputs, 6, kDecOut, 3);
        // The encoder's outputs go back for the next step.
        cross[0] = std::move(inputs[4]);
        cross[1] = std::move(inputs[5]);

        const float* logp = out[0].GetTensorData<float>();
        const int64_t vocab = out[0].GetTensorTypeAndShapeInfo().GetShape()[1];
        expansions.clear();
        for (int64_t i = 0; i < b; i++) {
            const float* row = logp + i * vocab;
            for (int64_t j = 0; j < vocab; j++) {
                if (j >= static_cast<int64_t>(allowed.size()) || !allowed[j]) continue;
                if (j == kEos && step == 0) continue;
                const float s = live[i].score + row[j];
                if (s == kNegInf || std::isnan(s)) continue;
                expansions.push_back({s, static_cast<int>(i), static_cast<int32_t>(j)});
            }
        }
        const size_t keep = std::min(expansions.size(), static_cast<size_t>(2 * beam));
        std::partial_sort(expansions.begin(), expansions.begin() + keep, expansions.end(),
                          [](const Expansion& a, const Expansion& c) { return a.score > c.score; });

        std::vector<Live> next;
        std::vector<int> origin;
        for (size_t k = 0; k < keep; k++) {
            const Expansion& e = expansions[k];
            if (e.token == kEos) {
                if (static_cast<int>(done.size()) < beam) {
                    const auto& toks = live[e.from].tokens;
                    done.push_back({toks, e.score / static_cast<float>(toks.size() + 1)});
                }
            } else {
                Live grown{live[e.from].tokens, e.score};
                grown.tokens.push_back(e.token);
                next.push_back(std::move(grown));
                origin.push_back(e.from);
            }
            if (static_cast<int>(next.size()) == beam) break;
        }
        if (static_cast<int>(done.size()) >= beam || next.empty()) {
            live.clear();
            break;
        }

        // Gather the new cache rows ([L,B,H,T+1,D]) in the order of `next`.
        const int64_t t1 = t + 1;
        const size_t row = static_cast<size_t>(kHeads * t1 * kHeadDim);
        const float* newK = out[1].GetTensorData<float>();
        const float* newV = out[2].GetTensorData<float>();
        const size_t nb = next.size();
        cacheK.resize(static_cast<size_t>(kLayers) * nb * row);
        cacheV.resize(cacheK.size());
        for (int64_t l = 0; l < kLayers; l++) {
            for (size_t i = 0; i < nb; i++) {
                const size_t from = (static_cast<size_t>(l) * b + origin[i]) * row;
                const size_t to = (static_cast<size_t>(l) * nb + i) * row;
                std::memcpy(cacheK.data() + to, newK + from, row * sizeof(float));
                std::memcpy(cacheV.data() + to, newV + from, row * sizeof(float));
            }
        }
        live = std::move(next);
    }
    // Out of length: what is still alive counts, as if it ended here.
    for (const Live& l : live) {
        if (static_cast<int>(done.size()) >= beam) break;
        done.push_back({l.tokens, l.score / static_cast<float>(l.tokens.size() + 1)});
    }
    std::stable_sort(done.begin(), done.end(),
                     [](const Hypothesis& a, const Hypothesis& c) { return a.score > c.score; });
    return done;
}

}  // namespace wmxlit
