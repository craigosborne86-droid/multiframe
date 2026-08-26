// Quarter-turn rotation of a developed frame.
//
// Every photograph taken with the phone upright pays for this, and it had never
// run under test: all three capture tests asked for zero degrees, where the
// rotation returns the bitmap untouched. Measured at 232 ms on a Pixel 9 Pro XL
// for twelve and a half megapixels -- absent from every timing in the build log
// because of that gap.
//
// The reason a transpose is slow is not arithmetic, it is memory. Walking the
// source along its rows means walking the destination down its columns, so
// every pixel written lands in a different cache line and a 50 MB destination
// evicts itself continuously. Doing it in tiles fixes that: a 32x32 tile of the
// source maps to a 32x32 tile of the destination, and both are small enough to
// sit in L1 while the tile is copied. Reads stay sequential within a row, and
// writes stay within thirty-two short runs instead of scattering across the
// whole image.
//
// The framework's Matrix path remains the reference. RotateParityTest holds
// this to it pixel for pixel, which is what catches a transpose that has the
// right dimensions and the wrong handedness -- the likely way to get this
// wrong, and one that dimensions alone cannot see.

#include <jni.h>
#include <android/bitmap.h>

#include <algorithm>
#include <cstdint>

namespace {

/** Side of the square copied at a time. 32 pixels is 128 bytes a row. */
constexpr int kTile = 32;

/** Clockwise quarter turn: source (x, y) becomes destination (h-1-y, x). */
void rotate90(const uint32_t* src, int w, int h, int srcStride,
              uint32_t* dst, int dstStride) {
    for (int by = 0; by < h; by += kTile) {
        const int yEnd = std::min(by + kTile, h);
        for (int bx = 0; bx < w; bx += kTile) {
            const int xEnd = std::min(bx + kTile, w);
            for (int y = by; y < yEnd; ++y) {
                const uint32_t* row = src + static_cast<size_t>(y) * srcStride;
                const int outX = h - 1 - y;
                for (int x = bx; x < xEnd; ++x) {
                    dst[static_cast<size_t>(x) * dstStride + outX] = row[x];
                }
            }
        }
    }
}

/** Anticlockwise quarter turn: source (x, y) becomes destination (y, w-1-x). */
void rotate270(const uint32_t* src, int w, int h, int srcStride,
               uint32_t* dst, int dstStride) {
    for (int by = 0; by < h; by += kTile) {
        const int yEnd = std::min(by + kTile, h);
        for (int bx = 0; bx < w; bx += kTile) {
            const int xEnd = std::min(bx + kTile, w);
            for (int y = by; y < yEnd; ++y) {
                const uint32_t* row = src + static_cast<size_t>(y) * srcStride;
                for (int x = bx; x < xEnd; ++x) {
                    dst[static_cast<size_t>(w - 1 - x) * dstStride + y] = row[x];
                }
            }
        }
    }
}

/**
 * Half turn. No tiling: this one reads forwards and writes backwards along the
 * same row, which the prefetcher handles without help.
 */
void rotate180(const uint32_t* src, int w, int h, int srcStride,
               uint32_t* dst, int dstStride) {
    for (int y = 0; y < h; ++y) {
        const uint32_t* row = src + static_cast<size_t>(y) * srcStride;
        uint32_t* out = dst + static_cast<size_t>(h - 1 - y) * dstStride;
        for (int x = 0; x < w; ++x) {
            out[w - 1 - x] = row[x];
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_NativeRotate_nRotate(
        JNIEnv* env, jobject, jobject srcBitmap, jobject dstBitmap, jint degrees) {
    AndroidBitmapInfo srcInfo;
    AndroidBitmapInfo dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    const int w = static_cast<int>(srcInfo.width);
    const int h = static_cast<int>(srcInfo.height);
    const int dw = static_cast<int>(dstInfo.width);
    const int dh = static_cast<int>(dstInfo.height);

    // The destination has to have been made the right way round, or the writes
    // below run off the end of it.
    const bool swaps = (degrees == 90 || degrees == 270);
    if (swaps && (dw != h || dh != w)) return JNI_FALSE;
    if (!swaps && (dw != w || dh != h)) return JNI_FALSE;
    if (degrees != 90 && degrees != 180 && degrees != 270) return JNI_FALSE;

    void* srcPixels = nullptr;
    void* dstPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_FALSE;
    }

    const auto* in = static_cast<const uint32_t*>(srcPixels);
    auto* out = static_cast<uint32_t*>(dstPixels);
    const int srcStride = static_cast<int>(srcInfo.stride / 4);
    const int dstStride = static_cast<int>(dstInfo.stride / 4);

    switch (degrees) {
        case 90:  rotate90(in, w, h, srcStride, out, dstStride); break;
        case 180: rotate180(in, w, h, srcStride, out, dstStride); break;
        case 270: rotate270(in, w, h, srcStride, out, dstStride); break;
        default: break;
    }

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_TRUE;
}

}  // extern "C"
