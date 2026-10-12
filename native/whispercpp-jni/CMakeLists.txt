# libwmwhispercpp: whispercpp_jni.cpp over a static whisper.cpp + ggml (CPU
# backend only). Driven by build.sh, which passes the whisper.cpp tree in
# WHISPER_SRC and the instruction set per variant; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmwhispercpp C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_SRC "" CACHE PATH "whisper.cpp source tree")
if (NOT EXISTS "${WHISPER_SRC}/include/whisper.h")
    message(FATAL_ERROR "WHISPER_SRC must point at a whisper.cpp checkout")
endif()

# Static, CPU only, nothing but the library.
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2             OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL             OFF CACHE BOOL "" FORCE)
# ggml's own thread pool rather than OpenMP, which would mean shipping libomp.
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
# Cross-compiling: the instruction set comes from build.sh, not this Mac.
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_SRC}" whisper EXCLUDE_FROM_ALL)

add_library(wmwhispercpp SHARED whispercpp_jni.cpp)
set_target_properties(wmwhispercpp PROPERTIES OUTPUT_NAME "${WM_LIB_NAME}")
target_link_libraries(wmwhispercpp PRIVATE whisper log)
target_compile_options(wmwhispercpp PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(wmwhispercpp PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
