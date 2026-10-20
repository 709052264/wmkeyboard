#include "imagegen.hpp"

#include <MNN/expr/Executor.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <MNN/expr/Module.hpp>

#include <cstring>
#include <sstream>

namespace wmimagegen {

using MNN::BackendConfig;
using MNN::ScheduleConfig;
using MNN::Express::Executor;
using MNN::Express::Module;
using MNN::Express::VARP;

struct Model::Impl {
    std::shared_ptr<Executor::RuntimeManager> runtime;
    std::shared_ptr<Module> module;
    std::vector<std::string> inputs;
};

Model::Model(Impl* impl) : impl_(impl) {}

Model::~Model() { delete impl_; }

std::unique_ptr<Model> Model::load(const std::string& path, const std::vector<std::string>& inputs,
                                   const std::vector<std::string>& outputs, int threads) {
    ScheduleConfig schedule;
    schedule.type = MNN_FORWARD_CPU;
    schedule.numThread = threads > 0 ? threads : 4;
    BackendConfig backend;
    // Float32 compute always. MNN's float16 path gives a wrong UNet output
    // (measured: cosine 0.41-0.59 against the reference at most timesteps),
    // and a one-step model magnifies any error in its noise prediction.
    backend.precision = BackendConfig::Precision_Normal;
    backend.power = BackendConfig::Power_High;
    backend.memory = BackendConfig::Memory_Low;
    schedule.backendConfig = &backend;
    std::shared_ptr<Executor::RuntimeManager> runtime(Executor::RuntimeManager::createRuntimeManager(schedule));
    if (!runtime) return nullptr;
    Module::Config config;
    config.shapeMutable = true;
    std::shared_ptr<Module> module(Module::load(inputs, outputs, path.c_str(), runtime, &config));
    if (!module) return nullptr;
    auto* impl = new Impl{runtime, module, inputs};
    return std::unique_ptr<Model>(new Model(impl));
}

bool Model::run(const std::vector<Tensor>& inputs, std::vector<float>& out, std::vector<int>& outShape) {
    using namespace MNN::Express;
    if (inputs.size() != impl_->inputs.size()) return false;
    std::vector<VARP> vars;
    vars.reserve(inputs.size());
    for (const auto& t : inputs) {
        size_t count = 1;
        for (int d : t.shape) count *= static_cast<size_t>(d);
        VARP v;
        if (!t.ints.empty()) {
            if (t.ints.size() != count) return false;
            v = _Input(t.shape, NCHW, halide_type_of<int32_t>());
            auto* p = v->writeMap<int32_t>();
            if (!p) return false;
            std::memcpy(p, t.ints.data(), count * sizeof(int32_t));
        } else {
            if (t.floats.size() != count) return false;
            v = _Input(t.shape, NCHW, halide_type_of<float>());
            auto* p = v->writeMap<float>();
            if (!p) return false;
            std::memcpy(p, t.floats.data(), count * sizeof(float));
        }
        vars.push_back(v);
    }
    auto outs = impl_->module->onForward(vars);
    if (outs.empty() || outs[0].get() == nullptr) return false;
    VARP o = outs[0];
    auto* info = o->getInfo();
    if (!info) return false;
    if (info->order == NC4HW4) {
        o = _Convert(o, NCHW);
        info = o->getInfo();
        if (!info) return false;
    }
    const float* p = o->readMap<float>();
    if (!p) return false;
    out.assign(p, p + info->size);
    outShape.assign(info->dim.begin(), info->dim.end());
    return true;
}

namespace {

std::string quote(const std::string& s) {
    std::string r = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') r += '\\';
        if (static_cast<unsigned char>(c) < 0x20) continue;
        r += c;
    }
    return r + "\"";
}

}  // namespace

std::string describe(const std::string& path) {
    std::shared_ptr<Module> module(Module::load({}, {}, path.c_str()));
    if (!module) return "";
    const Module::Info* info = module->getInfo();
    if (!info) return "";
    std::ostringstream json;
    json << "{\"inputs\":[";
    for (size_t i = 0; i < info->inputNames.size(); ++i) {
        if (i) json << ",";
        json << "{\"name\":" << quote(info->inputNames[i]) << ",\"dims\":[";
        if (i < info->inputs.size()) {
            const auto& in = info->inputs[i];
            for (size_t d = 0; d < in.dim.size(); ++d) json << (d ? "," : "") << in.dim[d];
            json << "],\"type\":\"" << (in.type.code == halide_type_float ? "float" : "int") << "\"}";
        } else {
            json << "],\"type\":\"float\"}";
        }
    }
    json << "],\"outputs\":[";
    for (size_t i = 0; i < info->outputNames.size(); ++i) json << (i ? "," : "") << quote(info->outputNames[i]);
    json << "],\"biz\":" << quote(info->bizCode) << "}";
    return json.str();
}

}  // namespace wmimagegen
