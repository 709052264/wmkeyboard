# libwmzinnia: wm_zinnia_jni.cpp over a static zinnia. Driven by build.sh,
# which passes the zinnia tree in ZINNIA_SRC; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmzinnia CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(ZINNIA_SRC "" CACHE PATH "zinnia source tree (the repository root)")
set(ZS ${ZINNIA_SRC}/zinnia)
if (NOT EXISTS "${ZS}/zinnia.h")
    message(FATAL_ERROR "ZINNIA_SRC must point at a zinnia checkout")
endif()

# Only the recogniser's half of upstream's libzinnia (zinnia/Makefile.am):
# the trainer, the SVM solver, the C API wrapper and the command-line options
# parser are for building models, which happens off the phone. The defines
# stand in for the config.h configure would write; the one the repository
# carries was generated on someone's Linux box, so it is not used.
add_library(zinnia STATIC
    ${ZS}/character.cpp
    ${ZS}/feature.cpp
    ${ZS}/recognizer.cpp
    ${ZS}/sexp.cpp)
target_include_directories(zinnia PUBLIC ${ZS})
target_compile_definitions(zinnia PRIVATE
    HAVE_MMAP HAVE_SYS_MMAN_H HAVE_FCNTL_H HAVE_UNISTD_H
    HAVE_SYS_STAT_H HAVE_SYS_TYPES_H HAVE_STRING_H NDEBUG)
target_compile_options(zinnia PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden -w)

add_library(wmzinnia SHARED wm_zinnia_jni.cpp)
target_link_libraries(wmzinnia PRIVATE zinnia)
target_compile_options(wmzinnia PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
if (ANDROID)
    target_link_options(wmzinnia PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--icf=all
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
endif()
