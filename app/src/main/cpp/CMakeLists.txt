cmake_minimum_required(VERSION 3.22.1)
project("medlam_engine" C CXX)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Safe SIMD Vectorization compatible with Cortex-A73 (ARMv8.0-A NEON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -fvectorize -march=armv8-a+simd -pthread -fPIC")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -fvectorize -march=armv8-a+simd -pthread -fPIC")

set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -fvectorize -march=armv8-a+simd -pthread -fPIC")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -fvectorize -march=armv8-a+simd -pthread -fPIC")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

set(SOURCE_FILES
    medlam_inference_engine.cpp
    jni_bridge.cpp
)

add_library(medlam_engine SHARED ${SOURCE_FILES})

# Link prebuilt core libraries
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
    c++_shared
)

target_link_options(medlam_engine PRIVATE "-Wl,-z,max-page-size=16384")
