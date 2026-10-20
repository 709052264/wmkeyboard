# libwmimagegen_qnn: qnn_runner.cpp against Qualcomm's QNN API headers, which
# build.sh fetches at a pinned commit into QNN_INCLUDE. Links nothing of
# Qualcomm's: the runtime libraries are opened by path at run time.
cmake_minimum_required(VERSION 3.18)
project(wmimagegen_qnn CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(wmimagegen_qnn SHARED qnn_runner.cpp)
target_include_directories(wmimagegen_qnn PRIVATE ${QNN_INCLUDE})
target_link_libraries(wmimagegen_qnn PRIVATE log dl)
target_compile_options(wmimagegen_qnn PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(wmimagegen_qnn PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--icf=all
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--strip-all
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
