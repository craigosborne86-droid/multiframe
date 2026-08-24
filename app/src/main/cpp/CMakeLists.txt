cmake_minimum_required(VERSION 3.22.1)
project(multiframe LANGUAGES CXX)

add_library(multiframe SHARED bayer_merge.cpp)

target_compile_options(multiframe PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -Wall
    -Wextra
)

find_library(log-lib log)
target_link_libraries(multiframe ${log-lib})
