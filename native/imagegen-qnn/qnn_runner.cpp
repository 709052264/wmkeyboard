// The sticker generator's NPU runner: Qualcomm AI Hub's precompiled Stable
// Diffusion context binaries on the Hexagon tensor processor, through the
// QNN runtime the app already delivers for Compute = NPU (NpuRuntime in
// :core:intelligence). Kotlin side:
// com.wasimaster.wmkeyboard.core.imagegen.QnnImageNative.
//
// Nothing from Qualcomm is linked: libQnnHtp.so and libQnnSystem.so are
// opened by path from the directory NpuRuntime unpacked them into, and only
// their interface tables are used. The QNN API headers this is compiled
// against are fetched by build.sh at a pinned commit and never committed.
//
// Tensors cross as raw bytes in the graph's own types (uint16 for AI Hub's
// w8a16 builds); quantisation is Kotlin's, from the scale and offset
// nativeDescribe reports. Calls on one graph must not overlap.

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"

#define LOG_TAG "WMImageGenQnn"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

using GetProviders = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
using GetSystemProviders = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*);

struct Runtime {
    void* htp = nullptr;
    void* system = nullptr;
    QNN_INTERFACE_VER_TYPE qnn{};
    QNN_SYSTEM_INTERFACE_VER_TYPE sys{};
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    QnnHtpDevice_PerfInfrastructure_t perf{};
    uint32_t powerId = 0;
    bool havePower = false;
};

std::mutex lock;
Runtime* runtime = nullptr;

// One graph input or output: a deep copy of what the context binary
// declares, so it outlives the system context it was read from.
struct Spec {
    std::string name;
    std::vector<uint32_t> dims;
    Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
    size_t bytes = 0;
    float scale = 0;
    int32_t offset = 0;
};

struct Graph {
    Qnn_ContextHandle_t context = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    std::string name;
    std::vector<Spec> inputs;
    std::vector<Spec> outputs;
};

size_t elementBytes(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8:
        case QNN_DATATYPE_BOOL_8:
            return 1;
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16:
        case QNN_DATATYPE_FLOAT_16:
        case QNN_DATATYPE_SFIXED_POINT_16:
        case QNN_DATATYPE_UFIXED_POINT_16:
            return 2;
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32:
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_SFIXED_POINT_32:
        case QNN_DATATYPE_UFIXED_POINT_32:
            return 4;
        case QNN_DATATYPE_INT_64:
        case QNN_DATATYPE_UINT_64:
        case QNN_DATATYPE_FLOAT_64:
            return 8;
        default:
            return 0;
    }
}

// Version 1 and 2 tensors share their leading fields; v2 adds to the end.
Qnn_TensorV1_t& fields(Qnn_Tensor_t& t) { return t.v1; }

bool copySpec(const Qnn_Tensor_t& src, Spec& out) {
    if (src.version != QNN_TENSOR_VERSION_1 && src.version != QNN_TENSOR_VERSION_2) return false;
    const Qnn_TensorV1_t& f = src.v1;
    out.name = f.name ? f.name : "";
    out.dims.assign(f.dimensions, f.dimensions + f.rank);
    out.tensor = src;
    Qnn_TensorV1_t& d = fields(out.tensor);
    d.name = out.name.c_str();
    d.dimensions = out.dims.data();
    d.memType = QNN_TENSORMEMTYPE_RAW;
    d.clientBuf = QNN_CLIENT_BUFFER_INIT;
    if (src.version == QNN_TENSOR_VERSION_2) {
        out.tensor.v2.isDynamicDimensions = nullptr;
        out.tensor.v2.sparseParams = QNN_SPARSE_PARAMS_INIT;
    }
    size_t count = 1;
    for (uint32_t x : out.dims) count *= x;
    out.bytes = count * elementBytes(f.dataType);
    if (f.quantizeParams.encodingDefinition == QNN_DEFINITION_DEFINED &&
        f.quantizeParams.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        out.scale = f.quantizeParams.scaleOffsetEncoding.scale;
        out.offset = f.quantizeParams.scaleOffsetEncoding.offset;
    }
    // Per-axis encodings are not copied; AI Hub's graph I/O does not use them.
    d.quantizeParams.quantizationEncoding = out.scale != 0 ? QNN_QUANTIZATION_ENCODING_SCALE_OFFSET
                                                           : d.quantizeParams.quantizationEncoding;
    return out.bytes > 0;
}

bool graphInfo(const QnnSystemContext_GraphInfo_t& g, const char*& name, uint32_t& nIn, Qnn_Tensor_t*& in,
               uint32_t& nOut, Qnn_Tensor_t*& out) {
    switch (g.version) {
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
            name = g.graphInfoV1.graphName; nIn = g.graphInfoV1.numGraphInputs; in = g.graphInfoV1.graphInputs;
            nOut = g.graphInfoV1.numGraphOutputs; out = g.graphInfoV1.graphOutputs;
            return true;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
            name = g.graphInfoV2.graphName; nIn = g.graphInfoV2.numGraphInputs; in = g.graphInfoV2.graphInputs;
            nOut = g.graphInfoV2.numGraphOutputs; out = g.graphInfoV2.graphOutputs;
            return true;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
            name = g.graphInfoV3.graphName; nIn = g.graphInfoV3.numGraphInputs; in = g.graphInfoV3.graphInputs;
            nOut = g.graphInfoV3.numGraphOutputs; out = g.graphInfoV3.graphOutputs;
            return true;
        default:
            return false;
    }
}

bool firstGraph(const QnnSystemContext_BinaryInfo_t* info, const QnnSystemContext_GraphInfo_t*& g) {
    uint32_t n = 0;
    QnnSystemContext_GraphInfo_t* graphs = nullptr;
    switch (info->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            n = info->contextBinaryInfoV1.numGraphs; graphs = info->contextBinaryInfoV1.graphs; break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            n = info->contextBinaryInfoV2.numGraphs; graphs = info->contextBinaryInfoV2.graphs; break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            n = info->contextBinaryInfoV3.numGraphs; graphs = info->contextBinaryInfoV3.graphs; break;
        default:
            return false;
    }
    if (n < 1 || !graphs) return false;
    g = &graphs[0];
    return true;
}

// The HTP at its highest clocks and never asleep while a model is loaded: a
// 20-step generation is 40 UNet calls back to back, and DCVS ramping up from
// idle each time costs more than the calls. Released in shutdown.
void voteBurst(Runtime& r) {
    if (!r.qnn.deviceGetInfrastructure) return;
    QnnDevice_Infrastructure_t infra = nullptr;
    if (r.qnn.deviceGetInfrastructure(&infra) != QNN_SUCCESS || !infra) return;
    auto* htp = reinterpret_cast<QnnHtpDevice_Infrastructure_t*>(infra);
    if (htp->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) return;
    r.perf = htp->perfInfra;
    if (!r.perf.createPowerConfigId || !r.perf.setPowerConfig) return;
    if (r.perf.createPowerConfigId(0, 0, &r.powerId) != QNN_SUCCESS) return;
    r.havePower = true;
    QnnHtpPerfInfrastructure_PowerConfig_t c = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT;
    c.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    auto& v = c.dcvsV3Config;
    v.contextId = r.powerId;
    v.setDcvsEnable = 1;
    v.dcvsEnable = 0;
    v.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    v.setSleepLatency = 1;
    v.sleepLatency = 40;
    v.setSleepDisable = 1;
    v.sleepDisable = 1;
    v.setBusParams = 1;
    v.busVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    v.busVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    v.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    v.setCoreParams = 1;
    v.coreVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    v.coreVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    v.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    const QnnHtpPerfInfrastructure_PowerConfig_t* configs[] = {&c, nullptr};
    if (r.perf.setPowerConfig(r.powerId, configs) != QNN_SUCCESS) LOGW("burst vote refused");
}

void destroy(Runtime* r) {
    if (!r) return;
    if (r->havePower && r->perf.destroyPowerConfigId) r->perf.destroyPowerConfigId(r->powerId);
    if (r->device && r->qnn.deviceFree) r->qnn.deviceFree(r->device);
    if (r->backend && r->qnn.backendFree) r->qnn.backendFree(r->backend);
    if (r->system) dlclose(r->system);
    if (r->htp) dlclose(r->htp);
    delete r;
}

Runtime* create(const std::string& dir) {
    // The DSP loads the skel by name from this search path.
    std::string adsp = dir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
    auto* r = new Runtime();
    r->htp = dlopen((dir + "/libQnnHtp.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    r->system = dlopen((dir + "/libQnnSystem.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!r->htp || !r->system) {
        LOGW("dlopen: %s", dlerror());
        destroy(r);
        return nullptr;
    }
    auto providers = reinterpret_cast<GetProviders>(dlsym(r->htp, "QnnInterface_getProviders"));
    auto sysProviders = reinterpret_cast<GetSystemProviders>(dlsym(r->system, "QnnSystemInterface_getProviders"));
    const QnnInterface_t** list = nullptr;
    uint32_t n = 0;
    const QnnSystemInterface_t** sysList = nullptr;
    uint32_t sysN = 0;
    if (!providers || providers(&list, &n) != QNN_SUCCESS || !sysProviders ||
        sysProviders(&sysList, &sysN) != QNN_SUCCESS) {
        destroy(r);
        return nullptr;
    }
    bool found = false;
    for (uint32_t i = 0; i < n && !found; ++i) {
        if (list[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
            list[i]->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
            r->qnn = list[i]->QNN_INTERFACE_VER_NAME;
            found = true;
        }
    }
    bool sysFound = false;
    for (uint32_t i = 0; i < sysN && !sysFound; ++i) {
        if (sysList[i]->systemApiVersion.major == QNN_SYSTEM_API_VERSION_MAJOR &&
            sysList[i]->systemApiVersion.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
            r->sys = sysList[i]->QNN_SYSTEM_INTERFACE_VER_NAME;
            sysFound = true;
        }
    }
    if (!found || !sysFound) {
        LOGW("no QNN interface at API %d.%d or newer", QNN_API_VERSION_MAJOR, QNN_API_VERSION_MINOR);
        destroy(r);
        return nullptr;
    }
    if (r->qnn.backendCreate(nullptr, nullptr, &r->backend) != QNN_SUCCESS) {
        destroy(r);
        return nullptr;
    }
    // Some phones have no device API; the backend then picks the device itself.
    if (r->qnn.deviceCreate && r->qnn.deviceCreate(nullptr, nullptr, &r->device) != QNN_SUCCESS) r->device = nullptr;
    voteBurst(*r);
    return r;
}

class Mapped {
public:
    explicit Mapped(const std::string& path) {
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return;
        struct stat st{};
        if (fstat(fd, &st) == 0 && st.st_size > 0) {
            size_ = static_cast<size_t>(st.st_size);
            // Writable private pages: the API takes a non-const buffer.
            data_ = mmap(nullptr, size_, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
            if (data_ == MAP_FAILED) data_ = nullptr;
        }
        close(fd);
    }
    ~Mapped() {
        if (data_) munmap(data_, size_);
    }
    void* data() const { return data_; }
    size_t size() const { return size_; }

private:
    void* data_ = nullptr;
    size_t size_ = 0;
};

Graph* load(Runtime& r, const std::string& path) {
    Mapped file(path);
    if (!file.data()) return nullptr;
    QnnSystemContext_Handle_t sysCtx = nullptr;
    if (r.sys.systemContextCreate(&sysCtx) != QNN_SUCCESS) return nullptr;
    std::unique_ptr<Graph> g(new Graph());
    const QnnSystemContext_BinaryInfo_t* info = nullptr;
    Qnn_ContextBinarySize_t infoSize = 0;
    const QnnSystemContext_GraphInfo_t* gi = nullptr;
    bool ok = r.sys.systemContextGetBinaryInfo(sysCtx, file.data(), file.size(), &info, &infoSize) == QNN_SUCCESS &&
              info && firstGraph(info, gi);
    if (ok) {
        const char* name = nullptr;
        uint32_t nIn = 0, nOut = 0;
        Qnn_Tensor_t* in = nullptr;
        Qnn_Tensor_t* out = nullptr;
        ok = graphInfo(*gi, name, nIn, in, nOut, out) && name;
        if (ok) {
            g->name = name;
            g->inputs.resize(nIn);
            g->outputs.resize(nOut);
            for (uint32_t i = 0; i < nIn && ok; ++i) ok = copySpec(in[i], g->inputs[i]);
            for (uint32_t i = 0; i < nOut && ok; ++i) ok = copySpec(out[i], g->outputs[i]);
        }
    }
    r.sys.systemContextFree(sysCtx);
    if (!ok) return nullptr;
    // Spec names point into the vectors' own strings; re-point after the resize settled.
    for (auto* list : {&g->inputs, &g->outputs})
        for (auto& s : *list) {
            fields(s.tensor).name = s.name.c_str();
            fields(s.tensor).dimensions = s.dims.data();
        }
    if (r.qnn.contextCreateFromBinary(r.backend, r.device, nullptr, file.data(), file.size(), &g->context,
                                      nullptr) != QNN_SUCCESS) {
        LOGW("contextCreateFromBinary failed for %s", path.c_str());
        return nullptr;
    }
    if (r.qnn.graphRetrieve(g->context, g->name.c_str(), &g->graph) != QNN_SUCCESS) {
        r.qnn.contextFree(g->context, nullptr);
        return nullptr;
    }
    return g.release();
}

void appendSpecs(std::ostringstream& json, const std::vector<Spec>& specs) {
    json << "[";
    for (size_t i = 0; i < specs.size(); ++i) {
        const Spec& s = specs[i];
        if (i) json << ",";
        json << "{\"name\":\"";
        for (char c : s.name) if (c != '"' && c != '\\' && static_cast<unsigned char>(c) >= 0x20) json << c;
        json << "\",\"dims\":[";
        for (size_t d = 0; d < s.dims.size(); ++d) json << (d ? "," : "") << s.dims[d];
        json << "],\"type\":" << static_cast<int>(s.tensor.v1.dataType) << ",\"bytes\":" << s.bytes
             << ",\"scale\":" << s.scale << ",\"offset\":" << s.offset << "}";
    }
    json << "]";
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = s ? env->GetStringUTFChars(s, nullptr) : nullptr;
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_QnnImageNative_nativeInit(JNIEnv* env, jobject, jstring dir) {
    std::lock_guard<std::mutex> g(lock);
    if (runtime) return JNI_TRUE;
    runtime = create(str(env, dir));
    return runtime ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_QnnImageNative_nativeLoad(JNIEnv* env, jobject, jstring path) {
    std::lock_guard<std::mutex> g(lock);
    if (!runtime) return 0;
    return reinterpret_cast<jlong>(load(*runtime, str(env, path)));
}

// {"graph":"...","inputs":[{"name","dims","type","bytes","scale","offset"}],"outputs":[...]}
// A real value is (q + offset) * scale.
JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_QnnImageNative_nativeDescribe(JNIEnv* env, jobject, jlong handle) {
    auto* g = reinterpret_cast<Graph*>(handle);
    if (!g) return nullptr;
    std::ostringstream json;
    json << "{\"graph\":\"" << g->name << "\",\"inputs\":";
    appendSpecs(json, g->inputs);
    json << ",\"outputs\":";
    appendSpecs(json, g->outputs);
    json << "}";
    return env->NewStringUTF(json.str().c_str());
}

// inputs and outputs: one byte array per tensor, in the order nativeDescribe
// lists them, each exactly that tensor's byte length.
JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_QnnImageNative_nativeRun(JNIEnv* env, jobject, jlong handle,
                                                                     jobjectArray inputs, jobjectArray outputs) {
    auto* g = reinterpret_cast<Graph*>(handle);
    if (!g || !runtime || !inputs || !outputs) return JNI_FALSE;
    if (env->GetArrayLength(inputs) != static_cast<jsize>(g->inputs.size()) ||
        env->GetArrayLength(outputs) != static_cast<jsize>(g->outputs.size()))
        return JNI_FALSE;
    std::vector<std::vector<uint8_t>> inBufs(g->inputs.size()), outBufs(g->outputs.size());
    std::vector<Qnn_Tensor_t> ins, outs;
    for (size_t i = 0; i < g->inputs.size(); ++i) {
        auto a = static_cast<jbyteArray>(env->GetObjectArrayElement(inputs, static_cast<jsize>(i)));
        if (!a || static_cast<size_t>(env->GetArrayLength(a)) != g->inputs[i].bytes) return JNI_FALSE;
        inBufs[i].resize(g->inputs[i].bytes);
        env->GetByteArrayRegion(a, 0, static_cast<jsize>(inBufs[i].size()), reinterpret_cast<jbyte*>(inBufs[i].data()));
        env->DeleteLocalRef(a);
        Qnn_Tensor_t t = g->inputs[i].tensor;
        fields(t).clientBuf.data = inBufs[i].data();
        fields(t).clientBuf.dataSize = static_cast<uint32_t>(inBufs[i].size());
        ins.push_back(t);
    }
    for (size_t i = 0; i < g->outputs.size(); ++i) {
        outBufs[i].resize(g->outputs[i].bytes);
        Qnn_Tensor_t t = g->outputs[i].tensor;
        fields(t).clientBuf.data = outBufs[i].data();
        fields(t).clientBuf.dataSize = static_cast<uint32_t>(outBufs[i].size());
        outs.push_back(t);
    }
    Qnn_ErrorHandle_t err = runtime->qnn.graphExecute(g->graph, ins.data(), static_cast<uint32_t>(ins.size()),
                                                      outs.data(), static_cast<uint32_t>(outs.size()), nullptr,
                                                      nullptr);
    if (err != QNN_SUCCESS) {
        LOGW("graphExecute %s: %lu", g->name.c_str(), static_cast<unsigned long>(err));
        return JNI_FALSE;
    }
    for (size_t i = 0; i < g->outputs.size(); ++i) {
        auto a = static_cast<jbyteArray>(env->GetObjectArrayElement(outputs, static_cast<jsize>(i)));
        if (!a || static_cast<size_t>(env->GetArrayLength(a)) != outBufs[i].size()) return JNI_FALSE;
        env->SetByteArrayRegion(a, 0, static_cast<jsize>(outBufs[i].size()), reinterpret_cast<jbyte*>(outBufs[i].data()));
        env->DeleteLocalRef(a);
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_QnnImageNative_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* g = reinterpret_cast<Graph*>(handle);
    if (!g) return;
    std::lock_guard<std::mutex> l(lock);
    if (runtime && g->context) runtime->qnn.contextFree(g->context, nullptr);
    delete g;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_QnnImageNative_nativeShutdown(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    destroy(runtime);
    runtime = nullptr;
}

}  // extern "C"
