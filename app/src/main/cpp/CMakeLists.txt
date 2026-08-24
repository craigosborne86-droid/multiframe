cmake_minimum_required(VERSION 3.22.1)
project(multiframe LANGUAGES CXX)

add_library(multiframe SHARED
    bayer_merge.cpp
    RingBuffer.cpp
    Mosaic.cpp
)

target_compile_options(multiframe PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -Wall
    -Wextra
)

find_library(log-lib log)
# jnigraphics lets native write straight into a Bitmap's pixel store, so the
# developed image never passes through a Java array.
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(multiframe ${log-lib} ${jnigraphics-lib})
