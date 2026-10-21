# libwmzxing: wm_zxing_jni.cpp over a static zxing-cpp, readers only. Driven
# by build.sh, which passes the zxing-cpp tree in ZXING_SRC; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmzxing CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(ZXING_SRC "" CACHE PATH "zxing-cpp source tree (the repository root)")
if (NOT EXISTS "${ZXING_SRC}/core/src/ReadBarcode.h")
    message(FATAL_ERROR "ZXING_SRC must point at a zxing-cpp checkout")
endif()

# Upstream's own core library, with everything that is not a reader switched
# off: no writers (so no bundled libzint), no C API, no examples or tests.
# Every symbology stays on. Static, so the linker drops what the JNI never
# reaches and nothing of zxing-cpp is exported.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ZXING_READERS ON CACHE BOOL "" FORCE)
set(ZXING_WRITERS OFF CACHE STRING "" FORCE)
set(ZXING_C_API OFF CACHE BOOL "" FORCE)
set(ZXING_EXPERIMENTAL_API OFF CACHE BOOL "" FORCE)
set(ZXING_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ZXING_BLACKBOX_TESTS OFF CACHE BOOL "" FORCE)
set(ZXING_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(ZXING_PYTHON_MODULE OFF CACHE BOOL "" FORCE)
set(ZXING_DEPENDENCIES LOCAL CACHE STRING "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${ZXING_SRC}/core zxing EXCLUDE_FROM_ALL)
target_compile_options(ZXing PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden)

add_library(wmzxing SHARED wm_zxing_jni.cpp)
target_link_libraries(wmzxing PRIVATE ZXing)
target_compile_options(wmzxing PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
if (ANDROID)
    target_link_options(wmzxing PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--icf=all
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,--strip-all
        -Wl,-z,max-page-size=16384)
endif()
