cmake_minimum_required(VERSION 3.22.1)
project("medlam_engine" C CXX)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enforce ARMv8.2-A hardware dot-product vectorization, FP16, LTO and 16KB page alignment
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -flto -fvectorize -march=armv8.2-a+dotprod+fp16 -pthread -fPIC")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -flto -fvectorize -march=armv8.2-a+dotprod+fp16 -pthread -fPIC")

set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -flto -fvectorize -march=armv8.2-a+dotprod+fp16 -pthread -fPIC")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -flto -fvectorize -march=armv8.2-a+dotprod+fp16 -pthread -fPIC")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -flto -Wl,-z,max-page-size=16384")

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

# Link prebuilt llama.cpp and ggml core libraries
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so"
)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so"
)

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-base.so"
)

find_library(log-lib log)

target_link_libraries(
    medlam_engine
    llama
    ggml
    ggml-base
    ${log-lib}
    m
    dl
    c++_shared
)

target_link_options(medlam_engine PRIVATE "-Wl,-z,max-page-size=16384")
