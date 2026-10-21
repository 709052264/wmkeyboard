# libwmsherpa: sherpa_jni.cpp over a static sherpa-onnx (C API, speech
# recognition only). Driven by build.sh, which passes the sherpa-onnx tree in
# SHERPA_SRC and points sherpa-onnx's own CMake at ONNX Runtime's headers and
# a link stub through SHERPA_ONNXRUNTIME_INCLUDE_DIR / _LIB_DIR; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmsherpa C CXX)

set(CMAKE_CXX_STANDARD 17)
set(SHERPA_SRC "" CACHE PATH "sherpa-onnx source tree")
if (NOT EXISTS "${SHERPA_SRC}/sherpa-onnx/c-api/c-api.h")
    message(FATAL_ERROR "SHERPA_SRC must point at a sherpa-onnx checkout")
endif()

# Static, recognition only: no TTS, diarization, audio I/O, servers or tools.
# Speech out (vits_jni.cpp) runs MMS VITS voices on ONNX Runtime directly:
# sherpa-onnx's TTS would link espeak-ng (GPL-3.0) for phonemes they never use.
# The Omnilingual model (omnilingual_jni.cpp) runs on it directly as well, so
# its logits can be held to one script before the CTC pick, and so do the
# token classifiers (tagger_jni.cpp) that punctuate dictation. Speech
# enhancement is always built: dpdfnet_jni.cpp drives its streaming denoiser.
set(BUILD_SHARED_LIBS                      OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_C_API               ON  CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_JNI                 OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PYTHON              OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TESTS               OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_CHECK               OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PORTAUDIO           OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_WEBSOCKET           OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_BINARY              OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TTS                 OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_BUILD_C_API_EXAMPLES       OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_GPU                 OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE ON CACHE BOOL "" FORCE)

add_subdirectory("${SHERPA_SRC}" sherpa-onnx EXCLUDE_FROM_ALL)

add_library(wmsherpa SHARED sherpa_jni.cpp vits_jni.cpp omnilingual_jni.cpp dpdfnet_jni.cpp tagger_jni.cpp)
target_include_directories(wmsherpa PRIVATE "${SHERPA_SRC}" "$ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR}")
target_link_libraries(wmsherpa PRIVATE sherpa-onnx-c-api log)
target_compile_options(wmsherpa PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(wmsherpa PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
