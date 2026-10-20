// The sticker generator's native half: one MNN module (a text encoder, a
// UNet, an image decoder or an image classifier) loaded from a .mnn file and
// run on float or int32 tensors. The diffusion loop, the scheduler and the
// tokenizer are Kotlin's (core/intelligence .../imagegen); this file only
// moves tensors in and out of MNN, so the same code serves every model.
//
// Shared by the JNI surface (wm_imagegen_jni.cpp) and the desktop check
// (desktop/imagegen_cli.cpp).
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace wmimagegen {

struct Tensor {
    std::vector<int> shape;
    std::vector<float> floats;  // used when ints is empty
    std::vector<int32_t> ints;
};

class Model {
public:
    // Null when the file does not load or does not have the named tensors.
    static std::unique_ptr<Model> load(const std::string& path, const std::vector<std::string>& inputs,
                                       const std::vector<std::string>& outputs, int threads);
    ~Model();

    // The first output as NCHW floats, its shape in outShape. False when MNN
    // refused the inputs (wrong count, shape or type).
    bool run(const std::vector<Tensor>& inputs, std::vector<float>& out, std::vector<int>& outShape);

private:
    struct Impl;
    explicit Model(Impl* impl);
    Impl* impl_;
};

// The tensors a .mnn file declares, as JSON:
// {"inputs":[{"name":"x","dims":[1,4,-1,-1],"type":"float"}],"outputs":["y"],"biz":"..."}
// Empty when the file is not an MNN model.
std::string describe(const std::string& path);

}  // namespace wmimagegen
