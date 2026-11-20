cmake_minimum_required(VERSION 3.22.1)

project(aicpp C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只编推理库：不要 examples / server / tests，减小体积和编译时间
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# CI 会把 llama.cpp 源码 clone 到这个目录（已加入 .gitignore）
add_subdirectory(llama.cpp)

add_library(aicpp SHARED llama_jni.cpp)
target_link_libraries(aicpp PRIVATE llama ggml android log)

# Android 15 起要求 16KB 页对齐
add_link_options(-Wl,-z,max-page-size=16384)
