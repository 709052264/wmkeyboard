plugins {
    // The library-only Play split of the `onnx` gate: native/gates.json.
    id("wmkeyboard.native-split")
    // Microsoft's libonnxruntime.so, out of its AAR, as this split's library.
    id("wmkeyboard.onnxruntime")
}
