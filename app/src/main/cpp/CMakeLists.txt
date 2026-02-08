cmake_minimum_required(VERSION 3.22.1)
project(secondsense_llama)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp_android
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG master
)
FetchContent_MakeAvailable(llama_cpp_android)

add_library(secondsense_llama SHARED
    secondsense-llama-jni.cpp
)

target_include_directories(secondsense_llama PRIVATE
    ${llama_cpp_android_SOURCE_DIR}
    ${llama_cpp_android_SOURCE_DIR}/common
)

target_compile_features(secondsense_llama PRIVATE cxx_std_17)

target_link_libraries(secondsense_llama
    llama
    common
    android
    log
)
