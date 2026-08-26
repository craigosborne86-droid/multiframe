cmake_minimum_required(VERSION 3.22.1)
project(multiframe LANGUAGES C CXX)

# libjpeg-turbo, vendored.
#
# The NDK ships no JPEG encoder, and Bitmap.compress reaches Skia's copy of this
# same library through a door that offers no control: one thread, one call, no
# restart interval. Encoding twelve and a half megapixels there costs about
# 240 ms and is the largest single item in a capture. What is wanted is the
# library itself, so the image can be cut into strips encoded at once.
#
# Upstream's own build system refuses add_subdirectory() and says downstream
# projects should adapt it. So the sources are listed here directly and the
# three generated headers are checked in beside them; third_party/libjpeg-turbo
# has a README recording the version, where it came from and what was removed.
# Only the ARM64 SIMD is kept, because that is the only ABI this app builds.
set(LJT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo)
add_library(jpegturbo STATIC
    ${LJT}/src/jaricom.c
    ${LJT}/src/jcapimin.c
    ${LJT}/src/jcarith.c
    ${LJT}/src/jchuff.c
    ${LJT}/src/jcicc.c
    ${LJT}/src/jcinit.c
    ${LJT}/src/jclhuff.c
    ${LJT}/src/jcmarker.c
    ${LJT}/src/jcmaster.c
    ${LJT}/src/jcomapi.c
    ${LJT}/src/jcparam.c
    ${LJT}/src/jcphuff.c
    ${LJT}/src/jctrans.c
    ${LJT}/src/jdapimin.c
    ${LJT}/src/jdarith.c
    ${LJT}/src/jdatadst.c
    ${LJT}/src/jdatasrc.c
    ${LJT}/src/jdhuff.c
    ${LJT}/src/jdicc.c
    ${LJT}/src/jdinput.c
    ${LJT}/src/jdlhuff.c
    ${LJT}/src/jdmarker.c
    ${LJT}/src/jdmaster.c
    ${LJT}/src/jdphuff.c
    ${LJT}/src/jdtrans.c
    ${LJT}/src/jerror.c
    ${LJT}/src/jfdctflt.c
    ${LJT}/src/jmemmgr.c
    ${LJT}/src/jmemnobs.c
    ${LJT}/src/jpeg_nbits.c
    ${LJT}/src/wrapper/jcapistd-12.c
    ${LJT}/src/wrapper/jcapistd-16.c
    ${LJT}/src/wrapper/jcapistd-8.c
    ${LJT}/src/wrapper/jccoefct-12.c
    ${LJT}/src/wrapper/jccoefct-8.c
    ${LJT}/src/wrapper/jccolor-12.c
    ${LJT}/src/wrapper/jccolor-16.c
    ${LJT}/src/wrapper/jccolor-8.c
    ${LJT}/src/wrapper/jcdctmgr-12.c
    ${LJT}/src/wrapper/jcdctmgr-8.c
    ${LJT}/src/wrapper/jcdiffct-12.c
    ${LJT}/src/wrapper/jcdiffct-16.c
    ${LJT}/src/wrapper/jcdiffct-8.c
    ${LJT}/src/wrapper/jclossls-12.c
    ${LJT}/src/wrapper/jclossls-16.c
    ${LJT}/src/wrapper/jclossls-8.c
    ${LJT}/src/wrapper/jcmainct-12.c
    ${LJT}/src/wrapper/jcmainct-16.c
    ${LJT}/src/wrapper/jcmainct-8.c
    ${LJT}/src/wrapper/jcprepct-12.c
    ${LJT}/src/wrapper/jcprepct-16.c
    ${LJT}/src/wrapper/jcprepct-8.c
    ${LJT}/src/wrapper/jcsample-12.c
    ${LJT}/src/wrapper/jcsample-16.c
    ${LJT}/src/wrapper/jcsample-8.c
    ${LJT}/src/wrapper/jdapistd-12.c
    ${LJT}/src/wrapper/jdapistd-16.c
    ${LJT}/src/wrapper/jdapistd-8.c
    ${LJT}/src/wrapper/jdcoefct-12.c
    ${LJT}/src/wrapper/jdcoefct-8.c
    ${LJT}/src/wrapper/jdcolor-12.c
    ${LJT}/src/wrapper/jdcolor-16.c
    ${LJT}/src/wrapper/jdcolor-8.c
    ${LJT}/src/wrapper/jddctmgr-12.c
    ${LJT}/src/wrapper/jddctmgr-8.c
    ${LJT}/src/wrapper/jddiffct-12.c
    ${LJT}/src/wrapper/jddiffct-16.c
    ${LJT}/src/wrapper/jddiffct-8.c
    ${LJT}/src/wrapper/jdlossls-12.c
    ${LJT}/src/wrapper/jdlossls-16.c
    ${LJT}/src/wrapper/jdlossls-8.c
    ${LJT}/src/wrapper/jdmainct-12.c
    ${LJT}/src/wrapper/jdmainct-16.c
    ${LJT}/src/wrapper/jdmainct-8.c
    ${LJT}/src/wrapper/jdmerge-12.c
    ${LJT}/src/wrapper/jdmerge-8.c
    ${LJT}/src/wrapper/jdpostct-12.c
    ${LJT}/src/wrapper/jdpostct-16.c
    ${LJT}/src/wrapper/jdpostct-8.c
    ${LJT}/src/wrapper/jdsample-12.c
    ${LJT}/src/wrapper/jdsample-16.c
    ${LJT}/src/wrapper/jdsample-8.c
    ${LJT}/src/wrapper/jfdctfst-12.c
    ${LJT}/src/wrapper/jfdctfst-8.c
    ${LJT}/src/wrapper/jfdctint-12.c
    ${LJT}/src/wrapper/jfdctint-8.c
    ${LJT}/src/wrapper/jidctflt-12.c
    ${LJT}/src/wrapper/jidctflt-8.c
    ${LJT}/src/wrapper/jidctfst-12.c
    ${LJT}/src/wrapper/jidctfst-8.c
    ${LJT}/src/wrapper/jidctint-12.c
    ${LJT}/src/wrapper/jidctint-8.c
    ${LJT}/src/wrapper/jidctred-12.c
    ${LJT}/src/wrapper/jidctred-8.c
    ${LJT}/src/wrapper/jquant1-12.c
    ${LJT}/src/wrapper/jquant1-8.c
    ${LJT}/src/wrapper/jquant2-12.c
    ${LJT}/src/wrapper/jquant2-8.c
    ${LJT}/src/wrapper/jutils-12.c
    ${LJT}/src/wrapper/jutils-16.c
    ${LJT}/src/wrapper/jutils-8.c
    ${LJT}/simd/arm/jccolor-neon.c
    ${LJT}/simd/arm/jcgray-neon.c
    ${LJT}/simd/arm/aarch64/jchuff-neon.c
    ${LJT}/simd/arm/jcphuff-neon.c
    ${LJT}/simd/arm/jcsample-neon.c
    ${LJT}/simd/arm/jdcolor-neon.c
    ${LJT}/simd/arm/jdmerge-neon.c
    ${LJT}/simd/arm/jdsample-neon.c
    ${LJT}/simd/arm/jfdctfst-neon.c
    ${LJT}/simd/arm/jfdctint-neon.c
    ${LJT}/simd/arm/jidctfst-neon.c
    ${LJT}/simd/arm/jidctint-neon.c
    ${LJT}/simd/arm/jidctred-neon.c
    ${LJT}/simd/arm/jquanti-neon.c
    ${LJT}/simd/arm/aarch64/jsimdcpu.c
    ${LJT}/simd/jsimd.c
)
target_include_directories(jpegturbo PUBLIC
    ${LJT}/src
    ${LJT}/generated
    ${LJT}/simd
    ${LJT}/generated/simd/arm
)
# Upstream's own warning set; this is not our code and we are not going to fix
# its style. Errors still stop the build.
target_compile_options(jpegturbo PRIVATE -O3 -w -fvisibility=hidden)
target_compile_definitions(jpegturbo PRIVATE NEON_INTRINSICS)

add_library(multiframe SHARED
    bayer_merge.cpp
    RingBuffer.cpp
    Mosaic.cpp
    Align.cpp
    Rotate.cpp
    Jpeg.cpp
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
target_link_libraries(multiframe ${log-lib} ${jnigraphics-lib} jpegturbo)
