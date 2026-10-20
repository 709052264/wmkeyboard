# libwmllama: llamacpp_jni.cpp over a static llama.cpp + ggml (CPU backend
# only) and llama.cpp's own chat-template code (common/chat.cpp and its Jinja
# interpreter), so a GGUF's template renders the way its authors wrote it.
# Driven by build.sh, which passes the llama.cpp tree in LLAMA_SRC and the
# instruction set per variant; see README.md. The same file builds the host
# library host-test.sh loads into a desktop JVM.
cmake_minimum_required(VERSION 3.18)
project(wmllama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(LLAMA_SRC "" CACHE PATH "llama.cpp source tree")
if (NOT EXISTS "${LLAMA_SRC}/include/llama.h")
    message(FATAL_ERROR "LLAMA_SRC must point at a llama.cpp checkout")
endif()
set(WM_LIB_NAME "wmllama" CACHE STRING "output library name")

# Static, CPU only, nothing but the library and the chat-template code.
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON       ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI           OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI    OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL            OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS         OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE         OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS       OFF CACHE BOOL "" FORCE)
# The build number and commit go into build-info.cpp. From git they depend on
# how deep the clone is; fixed here so every checkout builds the same bytes.
set(LLAMA_BUILD_NUMBER       "${WM_LLAMA_BUILD}"  CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMIT       "${WM_LLAMA_COMMIT}" CACHE STRING "" FORCE)
# ggml's own thread pool rather than OpenMP, which would mean shipping libomp.
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
# Cross-compiling: the instruction set comes from build.sh, not this machine.
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)
# No compiler cache, whatever the machine has: the build is what it compiles.
set(GGML_CCACHE              OFF CACHE BOOL "" FORCE)
# No GPU backends: Metal would come along on the Mac host build otherwise.
set(GGML_METAL               OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN              OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL              OFF CACHE BOOL "" FORCE)
# KleidiAI would be fetched at configure time; the plain CPU kernels it
# replaces are what every other build of ours runs.
set(GGML_CPU_KLEIDIAI        OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" llama EXCLUDE_FROM_ALL)

# The chat-template code (Jinja interpreter, template analysis) is a good part
# of the library and runs once per prompt, so it is built for size. llama's
# own code stays at -O3 with ggml: it builds the graph for every token, and
# an -Os build of it looked slower on a small model (README.md), for 12% off
# the library.
target_compile_options(llama-common PRIVATE -Os)

add_library(wmllama SHARED llamacpp_jni.cpp)
set_target_properties(wmllama PROPERTIES OUTPUT_NAME "${WM_LIB_NAME}")
target_link_libraries(wmllama PRIVATE llama-common llama)
target_compile_options(wmllama PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)

if (ANDROID)
    target_link_libraries(wmllama PRIVATE log)
    target_link_options(wmllama PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
elseif (APPLE)
    # Host test build: only the JNI entry points are exported, as on Android.
    target_link_options(wmllama PRIVATE
        -Wl,-dead_strip
        -Wl,-exported_symbol,_Java_com_wasimaster_wmkeyboard_core_localllm_LlamaNative_*)
endif()
