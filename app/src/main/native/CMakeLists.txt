cmake_minimum_required(VERSION 3.22.1)
project(shortsclipper_native LANGUAGES C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)

add_library(whisper_jni SHARED
        whisper-jni.cpp
        ${WHISPER_DIR}/whisper.cpp
        ${WHISPER_DIR}/ggml.c
        ${WHISPER_DIR}/ggml-alloc.c
        ${WHISPER_DIR}/ggml-backend.c
        ${WHISPER_DIR}/ggml-quants.c
        )

target_include_directories(whisper_jni PRIVATE ${WHISPER_DIR})
target_compile_features(whisper_jni PRIVATE cxx_std_17)

# Optimized CPU build. whisper.cpp v1.6.2 compiles with plain NDK clang;
# NEON is enabled automatically on arm64-v8a. No OpenMP on Android.
target_compile_options(whisper_jni PRIVATE -O3 -fexceptions -frtti)

find_library(android_log_lib log)
target_link_libraries(whisper_jni PRIVATE ${android_log_lib})
