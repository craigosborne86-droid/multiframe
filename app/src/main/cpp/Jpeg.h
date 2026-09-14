// The strip encoder, for callers that have pixels and no Bitmap.
//
// Jpeg.cpp explains the encoder. This exposes it to the linear develop, whose
// image -- fifty megapixels of three-byte RGB in an anonymous mapping -- can no
// more become a Bitmap than the mosaic canvas could, and is written down a
// descriptor from its own pages instead.

#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

namespace multiframe {
namespace jpeg {

/**
 * Encodes [pixels] and hands the finished file to [sink] in pieces.
 *
 * [components] is 3 for packed RGB or 4 for RGBA; [stride] is in bytes.
 * [threadsWanted] of zero means one strip per hardware thread. Returns false
 * if any strip fails or the sink refuses a piece, in which case whatever the
 * sink accepted so far is not a JPEG.
 */
bool encode(const uint8_t* pixels, int width, int height, size_t stride, int components,
            int quality, int threadsWanted,
            const std::function<bool(const unsigned char*, size_t)>& sink);

/** Every byte of [data] to [fd], or false. */
bool writeAll(int fd, const unsigned char* data, size_t n);

}  // namespace jpeg
}  // namespace multiframe
