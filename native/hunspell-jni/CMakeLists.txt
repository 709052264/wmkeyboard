# libwmhunspell: wm_hunspell_jni.cpp over a static Hunspell. Driven by
# build.sh, which passes the Hunspell tree in HUNSPELL_SRC; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmhunspell CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(HUNSPELL_SRC "" CACHE PATH "Hunspell source tree")
if (NOT EXISTS "${HUNSPELL_SRC}/src/hunspell/hunspell.hxx")
    message(FATAL_ERROR "HUNSPELL_SRC must point at a Hunspell checkout")
endif()
set(HS ${HUNSPELL_SRC}/src/hunspell)

# Upstream's own list (src/hunspell/Makefile.am, libhunspell_1_7_la_SOURCES).
# hunzip stays: FileMgr links it for .dic.hz files, though the keyboard only
# ever hands it plain ones.
add_library(hunspell STATIC
    ${HS}/affentry.cxx
    ${HS}/affixmgr.cxx
    ${HS}/csutil.cxx
    ${HS}/filemgr.cxx
    ${HS}/hashmgr.cxx
    ${HS}/hunspell.cxx
    ${HS}/hunzip.cxx
    ${HS}/phonet.cxx
    ${HS}/replist.cxx
    ${HS}/suggestmgr.cxx)
target_include_directories(hunspell PUBLIC ${HS})
target_compile_definitions(hunspell PUBLIC HUNSPELL_STATIC NDEBUG)
target_compile_options(hunspell PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden -w)

add_library(wmhunspell SHARED wm_hunspell_jni.cpp)
target_link_libraries(wmhunspell PRIVATE hunspell)
target_compile_options(wmhunspell PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(wmhunspell PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--icf=all
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
