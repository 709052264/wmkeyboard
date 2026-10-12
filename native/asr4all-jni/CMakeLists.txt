# libwmasr4all: asr4all_jni.cpp over a selective, static ExecuTorch build
# (the ops in ops.txt, the XNNPACK delegate, the Module extension). build.sh
# builds and installs ExecuTorch first and passes its prefix in ET_PREFIX; see
# README.md. The same file builds a macOS dylib for testing on the desktop.
cmake_minimum_required(VERSION 3.24)
project(wmasr4all CXX)

set(CMAKE_CXX_STANDARD 17)
set(ET_PREFIX "" CACHE PATH "ExecuTorch install prefix")
# NO_CMAKE_FIND_ROOT_PATH: the NDK toolchain confines package lookups to its
# sysroot, and the ExecuTorch install is outside it.
find_package(executorch CONFIG REQUIRED PATHS "${ET_PREFIX}/lib/cmake/ExecuTorch"
    NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)

add_library(wmasr4all SHARED asr4all_jni.cpp)
if (JNI_INCLUDE)
    target_include_directories(wmasr4all PRIVATE "${JNI_INCLUDE}")
endif()
target_include_directories(wmasr4all PRIVATE "${ET_PREFIX}/include")
# Kernels and the delegate register themselves from static initialisers, which
# a plain static link would drop as unreferenced; both targets carry their own
# whole-archive link options, so they are named plainly here.
target_link_libraries(wmasr4all PRIVATE
    executorch_selected_kernels
    xnnpack_backend
    extension_module_static
    extension_tensor
    extension_data_loader
    extension_flat_tensor
    extension_named_data_map
    portable_kernels
    executorch
    executorch_core
)
target_compile_options(wmasr4all PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
if (ANDROID)
    target_link_libraries(wmasr4all PRIVATE log)
    target_link_options(wmasr4all PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
endif()
