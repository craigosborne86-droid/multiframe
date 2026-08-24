#include "Mosaic.h"

#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>

#define LOG_TAG "MultiframeMosaic"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace multiframe {

namespace {

/**
 * Fixed-point scale for the accumulated weight.
 *
 * Blend weights are fractional -- a pixel at the feathered edge of a tile
 * contributes a few hundredths -- so storing them as a plain integer truncates
 * every partial contribution to zero and renders the whole feathered border as
 * uncovered. At 512 the smallest representable weight is 0.002 and the field
 * still holds 128 tiles at full weight, which is twice any sweep worth taking.
 */
constexpr float kWeightScale = 512.0f;
constexpr float kMaxWeight = 65535.0f / kWeightScale;

/** Applies a 3x3 projective transform to a point. */
inline bool project(const double* h, double x, double y, double& ox, double& oy) {
    const double w = h[6] * x + h[7] * y + h[8];
    if (std::fabs(w) < 1e-12) return false;
    ox = (h[0] * x + h[1] * y + h[2]) / w;
    oy = (h[3] * x + h[4] * y + h[5]) / w;
    return true;
}

/** Inverts a 3x3 in place into [out]. Returns false when singular. */
bool invert3x3(const double* a, double* out) {
    const double det =
        a[0] * (a[4] * a[8] - a[5] * a[7]) -
        a[1] * (a[3] * a[8] - a[5] * a[6]) +
        a[2] * (a[3] * a[7] - a[4] * a[6]);
    if (std::fabs(det) < 1e-12) return false;
    out[0] = (a[4] * a[8] - a[5] * a[7]) / det;
    out[1] = (a[2] * a[7] - a[1] * a[8]) / det;
    out[2] = (a[1] * a[5] - a[2] * a[4]) / det;
    out[3] = (a[5] * a[6] - a[3] * a[8]) / det;
    out[4] = (a[0] * a[8] - a[2] * a[6]) / det;
    out[5] = (a[2] * a[3] - a[0] * a[5]) / det;
    out[6] = (a[3] * a[7] - a[4] * a[6]) / det;
    out[7] = (a[1] * a[6] - a[0] * a[7]) / det;
    out[8] = (a[0] * a[4] - a[1] * a[3]) / det;
    return true;
}

}  // namespace

MosaicCanvas* MosaicCanvas::Create(int width, int height) {
    if (width <= 0 || height <= 0) return nullptr;

    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t bytes = pixels * kChannels * sizeof(uint16_t);
    if (pixels / static_cast<size_t>(width) != static_cast<size_t>(height)) return nullptr;

    const long pages = sysconf(_SC_PHYS_PAGES);
    const long pageSize = sysconf(_SC_PAGE_SIZE);
    if (pages > 0 && pageSize > 0) {
        const auto physical = static_cast<size_t>(pages) * static_cast<size_t>(pageSize);
        if (bytes > physical / 2) {
            LOGW("refusing %.0f MB canvas on a %.0f MB device",
                 bytes / (1024.0 * 1024.0), physical / (1024.0 * 1024.0));
            return nullptr;
        }
    }

    void* mem = mmap(nullptr, bytes, PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        LOGW("mmap of %.0f MB canvas failed", bytes / (1024.0 * 1024.0));
        return nullptr;
    }

    auto* canvas = new (std::nothrow) MosaicCanvas();
    if (canvas == nullptr) {
        munmap(mem, bytes);
        return nullptr;
    }
    canvas->data_ = static_cast<uint16_t*>(mem);
    canvas->bytes_ = bytes;
    canvas->width_ = width;
    canvas->height_ = height;
    // Anonymous pages arrive zeroed, which is exactly the empty state: zero
    // weight everywhere, so nothing has to be written to initialise it.
    LOGI("mosaic canvas %dx%d, %.0f MB", width, height, bytes / (1024.0 * 1024.0));
    return canvas;
}

MosaicCanvas::~MosaicCanvas() {
    if (data_ != nullptr) munmap(data_, bytes_);
    data_ = nullptr;
}

long MosaicCanvas::AddTile(const uint8_t* rgba, int tileWidth, int tileHeight,
                           int tileStride, const double* h, float feather) {
    if (data_ == nullptr || rgba == nullptr || tileWidth <= 0 || tileHeight <= 0) return 0;

    double inverse[9];
    if (!invert3x3(h, inverse)) return 0;

    // Only the region this tile can reach needs visiting. Projecting the four
    // corners bounds it; the projection of a rectangle under a homography is a
    // quadrilateral, so its axis-aligned bounding box is what to iterate.
    double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
    const double cornersX[4] = {0.0, static_cast<double>(tileWidth), 0.0,
                                static_cast<double>(tileWidth)};
    const double cornersY[4] = {0.0, 0.0, static_cast<double>(tileHeight),
                                static_cast<double>(tileHeight)};
    for (int i = 0; i < 4; ++i) {
        double px, py;
        if (!project(h, cornersX[i], cornersY[i], px, py)) return 0;
        minX = std::min(minX, px); maxX = std::max(maxX, px);
        minY = std::min(minY, py); maxY = std::max(maxY, py);
    }

    const int x0 = std::max(0, static_cast<int>(std::floor(minX)));
    const int y0 = std::max(0, static_cast<int>(std::floor(minY)));
    const int x1 = std::min(width_ - 1, static_cast<int>(std::ceil(maxX)));
    const int y1 = std::min(height_ - 1, static_cast<int>(std::ceil(maxY)));
    if (x1 < x0 || y1 < y0) return 0;

    const float safeFeather = std::max(feather, 1.0f);
    long touched = 0;

    for (int y = y0; y <= y1; ++y) {
        uint16_t* row = data_ + static_cast<size_t>(y) * width_ * kChannels;
        for (int x = x0; x <= x1; ++x) {
            double sx, sy;
            if (!project(inverse, x + 0.5, y + 0.5, sx, sy)) continue;
            // Half-pixel offsets put the sample at the centre of the canvas
            // pixel; without them the whole mosaic sits half a pixel off.
            sx -= 0.5;
            sy -= 0.5;
            if (sx < 0.0 || sy < 0.0 ||
                sx > tileWidth - 1.0 || sy > tileHeight - 1.0) {
                continue;
            }

            // Feather: full weight in the middle, ramping to nothing at the
            // tile's edge, so no boundary between two tiles is a hard line.
            const float edge = static_cast<float>(std::min(
                std::min(sx, sy),
                std::min(tileWidth - 1.0 - sx, tileHeight - 1.0 - sy)));
            float weight = std::clamp(edge / safeFeather, 0.0f, 1.0f);
            if (weight <= 0.0f) continue;
            // Squared, so the ramp starts and ends smoothly instead of with a
            // visible change of gradient.
            weight *= weight;

            const int ix = static_cast<int>(sx);
            const int iy = static_cast<int>(sy);
            const float fx = static_cast<float>(sx) - ix;
            const float fy = static_cast<float>(sy) - iy;
            const int ix1 = std::min(ix + 1, tileWidth - 1);
            const int iy1 = std::min(iy + 1, tileHeight - 1);

            const uint8_t* p00 = rgba + static_cast<size_t>(iy) * tileStride + ix * 4;
            const uint8_t* p10 = rgba + static_cast<size_t>(iy) * tileStride + ix1 * 4;
            const uint8_t* p01 = rgba + static_cast<size_t>(iy1) * tileStride + ix * 4;
            const uint8_t* p11 = rgba + static_cast<size_t>(iy1) * tileStride + ix1 * 4;

            uint16_t* px = row + static_cast<size_t>(x) * kChannels;
            const float accumulated = static_cast<float>(px[3]) / kWeightScale;
            const float total = accumulated + weight;
            if (total <= 0.0f) continue;

            for (int c = 0; c < 3; ++c) {
                // Bilinear, because the projected sample almost never lands on
                // a tile pixel centre. Nearest neighbour here would undo the
                // resolution the mosaic exists to gain.
                const float top = p00[c] * (1.0f - fx) + p10[c] * fx;
                const float bottom = p01[c] * (1.0f - fx) + p11[c] * fx;
                const float sample = (top * (1.0f - fy) + bottom * fy) * 257.0f;

                // Running weighted mean: no separate sum to overflow, and the
                // canvas stays at eight bytes per pixel instead of sixteen.
                const float current = static_cast<float>(px[c]);
                const float updated = current + (sample - current) * (weight / total);
                px[c] = static_cast<uint16_t>(std::clamp(updated, 0.0f, 65535.0f));
            }
            px[3] = static_cast<uint16_t>(std::min(total, kMaxWeight) * kWeightScale);
            ++touched;
        }
    }
    return touched;
}

float MosaicCanvas::Coverage() const {
    if (data_ == nullptr) return 0.0f;
    const size_t pixels = static_cast<size_t>(width_) * height_;
    size_t covered = 0;
    for (size_t i = 0; i < pixels; ++i) {
        if (data_[i * kChannels + 3] > 0) ++covered;
    }
    return pixels == 0 ? 0.0f : static_cast<float>(covered) / pixels;
}

void MosaicCanvas::Render(uint8_t* out, int outStride) const {
    if (data_ == nullptr || out == nullptr) return;
    for (int y = 0; y < height_; ++y) {
        const uint16_t* src = data_ + static_cast<size_t>(y) * width_ * kChannels;
        uint8_t* dst = out + static_cast<size_t>(y) * outStride;
        for (int x = 0; x < width_; ++x) {
            const uint16_t* px = src + static_cast<size_t>(x) * kChannels;
            uint8_t* q = dst + static_cast<size_t>(x) * 4;
            if (px[3] == 0) {
                // Never covered. Transparent rather than black, so a caller can
                // crop to what was actually shot instead of framing black bars.
                q[0] = q[1] = q[2] = 0;
                q[3] = 0;
                continue;
            }
            for (int c = 0; c < 3; ++c) {
                q[c] = static_cast<uint8_t>(std::clamp(px[c] / 257, 0, 255));
            }
            q[3] = 255;
        }
    }
}

}  // namespace multiframe

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

#include <android/bitmap.h>

using multiframe::MosaicCanvas;

namespace {
inline MosaicCanvas* canvasOf(jlong handle) {
    return reinterpret_cast<MosaicCanvas*>(handle);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nCreate(
        JNIEnv*, jobject, jint width, jint height) {
    return reinterpret_cast<jlong>(MosaicCanvas::Create(width, height));
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nDestroy(JNIEnv*, jobject, jlong handle) {
    delete canvasOf(handle);
}

JNIEXPORT jlong JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nAddTile(
        JNIEnv* env, jobject, jlong handle, jobject tile,
        jdoubleArray jh, jfloat feather) {
    MosaicCanvas* canvas = canvasOf(handle);
    if (canvas == nullptr) return 0;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, tile, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 0;

    double h[9];
    env->GetDoubleArrayRegion(jh, 0, 9, h);

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, tile, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return 0;
    }
    const long touched = canvas->AddTile(
        static_cast<const uint8_t*>(pixels),
        static_cast<int>(info.width), static_cast<int>(info.height),
        static_cast<int>(info.stride), h, feather);
    AndroidBitmap_unlockPixels(env, tile);
    return touched;
}

JNIEXPORT jfloat JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nCoverage(JNIEnv*, jobject, jlong handle) {
    MosaicCanvas* canvas = canvasOf(handle);
    return canvas == nullptr ? 0.0f : canvas->Coverage();
}

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nRenderInto(
        JNIEnv* env, jobject, jlong handle, jobject bitmap) {
    MosaicCanvas* canvas = canvasOf(handle);
    if (canvas == nullptr) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (static_cast<int>(info.width) != canvas->width() ||
        static_cast<int>(info.height) != canvas->height()) {
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    canvas->Render(static_cast<uint8_t*>(pixels), static_cast<int>(info.stride));
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

}  // extern "C"
