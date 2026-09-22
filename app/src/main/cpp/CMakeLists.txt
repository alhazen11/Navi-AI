cmake_minimum_required(VERSION 3.22.1)
project("naviai_ncnn")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ncnn SHARED IMPORTED)

set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}/lib/libncnn.so"
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}/include"
)

add_library(naviai_ncnn SHARED
    native_detector.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_include_directories(naviai_ncnn PRIVATE
    "${CMAKE_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}/include"
)

target_link_libraries(
    naviai_ncnn
    ncnn
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
