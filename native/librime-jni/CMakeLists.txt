# libwmrime: rime_jni.cc and rime_bridge.cc over a static librime and its
# dependencies (yaml-cpp, leveldb, marisa-trie, OpenCC, Boost headers), all in
# one shared library. Driven by build.sh, which passes the librime checkout in
# RIME_SRC and an unpacked Boost in BOOST_SRC; see README.md.
#
# librime's own CMake finds its dependencies as installed packages. Building
# them in one tree here instead, from librime's pinned submodules, keeps the
# whole library one configure and one link, with nothing installed anywhere.
cmake_minimum_required(VERSION 3.18)
project(wmrime C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(RIME_SRC "" CACHE PATH "librime source tree, with deps/ submodules")
set(BOOST_SRC "" CACHE PATH "Boost source tree (the -cmake release layout)")
set(RIME_VERSION "" CACHE STRING "librime release, for RimeGetVersion")
option(WM_HOST_TEST "Build the host test driver instead of the Android library" OFF)

if (NOT EXISTS "${RIME_SRC}/src/rime_api.h")
    message(FATAL_ERROR "RIME_SRC must point at a librime checkout")
endif()
if (NOT EXISTS "${BOOST_SRC}/libs/config/include/boost/config.hpp")
    message(FATAL_ERROR "BOOST_SRC must point at an unpacked Boost")
endif()

# Everything static, nothing installed, no tests or tools.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

# Boost: librime uses only header-only libraries (algorithm, regex since
# 1.76, signals2, interprocess, uuid, range, crc, scope_exit, vmd). Every
# library's include directory goes on the path, since the headers reach
# across libraries; nothing of Boost is compiled.
file(GLOB BOOST_INCLUDE_DIRS LIST_DIRECTORIES true
    "${BOOST_SRC}/libs/*/include" "${BOOST_SRC}/libs/numeric/*/include")
add_library(wm_boost INTERFACE)
target_include_directories(wm_boost SYSTEM INTERFACE ${BOOST_INCLUDE_DIRS})
target_compile_definitions(wm_boost INTERFACE BOOST_ALL_NO_LIB BOOST_DLL_USE_STD_FS)

# yaml-cpp
set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_CONTRIB OFF CACHE BOOL "" FORCE)
set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
set(YAML_CPP_FORMAT_SOURCE OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_SRC}/deps/yaml-cpp" yaml-cpp EXCLUDE_FROM_ALL)

# leveldb, without snappy: the user dictionaries are small, and a store this
# library writes is only ever read by this library.
set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
# Under clang leveldb adds -Werror -Wthread-safety as PUBLIC options, which
# would hold every file that links it, librime included, to a newer clang's
# warnings. Answering its compiler check in advance leaves them off.
set(HAVE_CLANG_THREAD_SAFETY OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_SRC}/deps/leveldb" leveldb EXCLUDE_FROM_ALL)

# marisa-trie: one copy, for librime and OpenCC both (OpenCC bundles its own,
# which would be a second set of the same symbols).
set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(ENABLE_NATIVE_CODE OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_SRC}/deps/marisa-trie" marisa EXCLUDE_FROM_ALL)

# OpenCC, the library only. Its own CMake also builds the command-line tools
# and compiles the conversion data by running them, which a cross build
# cannot do; the data ships with the schema packs instead, as text
# dictionaries OpenCC reads directly.
set(OPENCC_SRC "${RIME_SRC}/deps/opencc")
set(OPENCC_GEN "${CMAKE_CURRENT_BINARY_DIR}/opencc-gen")
file(WRITE "${OPENCC_GEN}/opencc_config.h" "#pragma once\n#define OPENCC_ENABLE_DARTS\n")
file(WRITE "${OPENCC_GEN}/Opencc_Export.h"
    "#pragma once\n#define OPENCC_EXPORT\n#define OPENCC_NO_EXPORT\n#define OPENCC_DEPRECATED\n")
# librime includes <opencc/Config.hpp>, so the headers sit under an opencc/ directory.
file(GLOB OPENCC_HEADERS "${OPENCC_SRC}/src/*.hpp" "${OPENCC_SRC}/src/opencc.h")
file(COPY ${OPENCC_HEADERS} "${OPENCC_GEN}/opencc_config.h" "${OPENCC_GEN}/Opencc_Export.h"
    DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/include/opencc")
add_library(wm_opencc STATIC
    ${OPENCC_SRC}/src/Config.cpp
    ${OPENCC_SRC}/src/Conversion.cpp
    ${OPENCC_SRC}/src/ConversionChain.cpp
    ${OPENCC_SRC}/src/Converter.cpp
    ${OPENCC_SRC}/src/Dict.cpp
    ${OPENCC_SRC}/src/DictConverter.cpp
    ${OPENCC_SRC}/src/DictEntry.cpp
    ${OPENCC_SRC}/src/DictGroup.cpp
    ${OPENCC_SRC}/src/Lexicon.cpp
    ${OPENCC_SRC}/src/MarisaDict.cpp
    ${OPENCC_SRC}/src/MaxMatchSegmentation.cpp
    ${OPENCC_SRC}/src/PhraseExtract.cpp
    ${OPENCC_SRC}/src/SerializedValues.cpp
    ${OPENCC_SRC}/src/SimpleConverter.cpp
    ${OPENCC_SRC}/src/Segmentation.cpp
    ${OPENCC_SRC}/src/TextDict.cpp
    ${OPENCC_SRC}/src/UTF8StringSlice.cpp
    ${OPENCC_SRC}/src/UTF8Util.cpp
    ${OPENCC_SRC}/src/BinaryDict.cpp
    ${OPENCC_SRC}/src/DartsDict.cpp)
target_include_directories(wm_opencc
    PUBLIC "${CMAKE_CURRENT_BINARY_DIR}/include"
    PRIVATE "${OPENCC_GEN}" "${OPENCC_SRC}/src"
        "${OPENCC_SRC}/deps/rapidjson-1.1.0" "${OPENCC_SRC}/deps/darts-clone-0.32")
target_compile_definitions(wm_opencc PUBLIC Opencc_BUILT_AS_STATIC)
target_link_libraries(wm_opencc PUBLIC marisa)

# librime itself: core, dict, gears and levers, no plugins, no glog. Object
# files rather than an archive, because each module registers itself from a
# static initialiser that nothing references, and the linker drops archive
# members nothing references.
file(GLOB RIME_SOURCES
    "${RIME_SRC}/src/*.cc"
    "${RIME_SRC}/src/rime/*.cc"
    "${RIME_SRC}/src/rime/algo/*.cc"
    "${RIME_SRC}/src/rime/config/*.cc"
    "${RIME_SRC}/src/rime/dict/*.cc"
    "${RIME_SRC}/src/rime/gear/*.cc"
    "${RIME_SRC}/src/rime/lever/*.cc")
list(SORT RIME_SOURCES)
set(RIME_GEN "${CMAKE_CURRENT_BINARY_DIR}/rime-gen")
file(WRITE "${RIME_GEN}/rime/build_config.h"
    "#ifndef RIME_BUILD_CONFIG_H_\n#define RIME_BUILD_CONFIG_H_\n#endif\n")
add_library(wm_rime OBJECT ${RIME_SOURCES})
target_include_directories(wm_rime PUBLIC
    "${RIME_GEN}" "${RIME_SRC}/src" "${RIME_SRC}/include")
target_compile_definitions(wm_rime PUBLIC
    RIME_VERSION="${RIME_VERSION}" RIME_EXTRA_MODULES= YAML_CPP_STATIC_DEFINE)
target_compile_options(wm_rime PRIVATE -Wno-deprecated-declarations)
target_link_libraries(wm_rime PUBLIC wm_boost yaml-cpp leveldb marisa wm_opencc)

if (WM_HOST_TEST)
    add_executable(rime_host_test host_test.cc rime_bridge.cc $<TARGET_OBJECTS:wm_rime>)
    target_link_libraries(rime_host_test PRIVATE wm_rime)
    # The JNI library for the desktop JVM, so RimeNative can be driven from
    # Kotlin on the host (see README.md).
    # A JDK's headers, or a directory holding just a jni.h (a JRE has none).
    set(JNI_INCLUDE "" CACHE PATH "directory with jni.h")
    file(GLOB JNI_INCLUDE_DIRS LIST_DIRECTORIES true ${JNI_INCLUDE} "$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/*")
    add_library(wmrime_host SHARED rime_jni.cc rime_bridge.cc $<TARGET_OBJECTS:wm_rime>)
    set_target_properties(wmrime_host PROPERTIES OUTPUT_NAME wmrime)
    target_include_directories(wmrime_host PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(wmrime_host PRIVATE wm_rime)
    return()
endif()

add_library(wmrime SHARED rime_jni.cc rime_bridge.cc $<TARGET_OBJECTS:wm_rime>)
target_link_libraries(wmrime PRIVATE wm_rime log)
target_compile_options(wmrime PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(wmrime PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
