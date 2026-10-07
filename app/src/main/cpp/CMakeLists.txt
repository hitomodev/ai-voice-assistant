cmake_minimum_required(VERSION 3.18.1)
project(assistant_llm CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Set here because this NDK and CMake pair passes no optimization level of its own, and
# unoptimized the model runs dozens of times slower.
add_compile_options(-O3)

# llama.cpp is fetched at a pinned release.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
FetchContent_Declare(llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
        GIT_TAG v0.6.0
        GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(assistant_llm SHARED llama_jni.cpp llm_engine.cpp)
target_link_libraries(assistant_llm PRIVATE llama log)
