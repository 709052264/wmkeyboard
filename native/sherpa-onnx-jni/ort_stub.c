// A link-time stand-in for ONNX Runtime, never shipped. sherpa-onnx reaches
// the runtime through one exported function, OrtGetApiBase, and everything
// else through the table it returns; linking against this stub gives
// libwmsherpa.so a NEEDED entry for libonnxruntime.so and one undefined
// symbol, which the real library (from Maven Central, loaded first) answers
// at runtime. So the build needs no prebuilt binary, and F-Droid can make the
// same bytes from source. Unversioned on purpose: the real symbol is
// OrtGetApiBase@@VERS_<version>, and an unversioned reference binds to
// whichever version is loaded.
void *OrtGetApiBase(void) { return 0; }
