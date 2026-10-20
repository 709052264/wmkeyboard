// IndicXlit's beam search over its two ONNX Runtime graphs. Plain C++ with no
// JNI in it, so the host test driver (host_test.cpp) runs the same code the
// phone does.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

struct OrtSessionHolder;

namespace wmxlit {

struct Hypothesis {
    std::vector<int32_t> tokens;  // target ids, without </s>
    float score;                  // sum of log-probabilities / (length + 1)
};

class Model {
public:
    // Null on failure, with the reason in *error.
    static std::unique_ptr<Model> open(const std::string& encoder, const std::string& decoder,
                                       int threads, std::string* error);
    ~Model();

    // Up to `beam` readings of `src` (source ids: the language token, the
    // letters, then </s>), best first. `allowed[i]` says whether target id i
    // may be written; ids past its end may not. At most `maxLen` tokens each.
    std::vector<Hypothesis> transliterate(const std::vector<int64_t>& src, const std::vector<uint8_t>& allowed,
                                          int beam, int maxLen);

private:
    Model();
    std::unique_ptr<OrtSessionHolder> ort_;
};

}  // namespace wmxlit
