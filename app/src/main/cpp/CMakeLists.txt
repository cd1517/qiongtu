cmake_minimum_required(VERSION 3.22)
project(xiutu_raw CXX)

# LibRaw（LGPL 2.1 / CDDL 1.0 二选一，许可证见 libraw/LICENSE.*）
set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libraw)
file(GLOB_RECURSE LIBRAW_SOURCES ${LIBRAW_DIR}/src/*.cpp)

add_library(xiutu_raw SHARED raw_jni.cpp ${LIBRAW_SOURCES})
target_include_directories(xiutu_raw PRIVATE ${LIBRAW_DIR})
target_compile_definitions(xiutu_raw PRIVATE USE_ZLIB LIBRAW_NODLL)
target_compile_options(xiutu_raw PRIVATE -O3 -w -fopenmp -fexceptions -frtti)
target_link_libraries(xiutu_raw z log -fopenmp -static-openmp)
