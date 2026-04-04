cmake_minimum_required(VERSION 3.22.1)
project(qwen_asr_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(qwen_asr_jni SHARED NativeQwenAsrJni.cpp)

find_library(log-lib log)

set(QWEN_ASR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/qwen-asr)
set(QWEN_ASR_SRCS
    ${QWEN_ASR_DIR}/qwen_asr.c
    ${QWEN_ASR_DIR}/qwen_asr_encoder.c
    ${QWEN_ASR_DIR}/qwen_asr_decoder.c
    ${QWEN_ASR_DIR}/qwen_asr_tokenizer.c
    ${QWEN_ASR_DIR}/qwen_asr_safetensors.c
    ${QWEN_ASR_DIR}/qwen_asr_kernels.c
    ${QWEN_ASR_DIR}/qwen_asr_kernels_generic.c
    ${QWEN_ASR_DIR}/qwen_asr_audio.c
)

if(ANDROID_ABI MATCHES "arm64-v8a|armeabi-v7a")
    list(APPEND QWEN_ASR_SRCS ${QWEN_ASR_DIR}/qwen_asr_kernels_neon.c)
elseif(ANDROID_ABI MATCHES "x86_64|x86")
    list(APPEND QWEN_ASR_SRCS ${QWEN_ASR_DIR}/qwen_asr_kernels_avx.c)
endif()

foreach(src_file IN LISTS QWEN_ASR_SRCS)
    if(NOT EXISTS ${src_file})
        message(FATAL_ERROR "Missing required qwen-asr source: ${src_file}. Initialize the submodule under ${QWEN_ASR_DIR}.")
    endif()
endforeach()

if(NOT EXISTS ${QWEN_ASR_DIR}/qwen_asr.h)
    message(FATAL_ERROR "Missing required qwen-asr header: ${QWEN_ASR_DIR}/qwen_asr.h. Initialize the submodule.")
endif()

if(NOT EXISTS ${QWEN_ASR_DIR}/qwen_asr_kernels.h)
    message(FATAL_ERROR "Missing required qwen-asr header: ${QWEN_ASR_DIR}/qwen_asr_kernels.h. Initialize the submodule.")
endif()

add_library(qwen_asr_core STATIC ${QWEN_ASR_SRCS})
target_include_directories(qwen_asr_core PUBLIC ${QWEN_ASR_DIR})
target_compile_options(qwen_asr_core PRIVATE -O3 -ffast-math)

target_include_directories(qwen_asr_jni PRIVATE ${QWEN_ASR_DIR})

target_link_libraries(
    qwen_asr_jni
    PRIVATE
    qwen_asr_core
    ${log-lib}
    m
)
