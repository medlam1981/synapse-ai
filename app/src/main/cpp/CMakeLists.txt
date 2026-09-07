cmake_minimum_required(VERSION 3.22.1)
project("medlam_engine" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable ARM NEON & architecture optimizations
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC -pthread")

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Source files
set(SOURCE_FILES
    medlam_inference_engine.cpp
    jni_bridge.cpp
)

# Shared library
add_library(medlam_engine SHARED ${SOURCE_FILES})

# Link prebuilt llama.cpp libraries
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so"
)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so"
)

find_library(log-lib log)

target_link_libraries(
    medlam_engine
    llama
    ggml
    ${log-lib}
    m
    dl
)
