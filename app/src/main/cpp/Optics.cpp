// The optics corrections, as one gather.
//
// Two corrections share this pass. Brown-Conrady undistortion puts straight
// lines back where the glass bent them, and the lateral chromatic aberration
// correction rescales red and blue radially to sit on top of green. They were
// two passes in Kotlin -- correct aberration in place, copy, undistort from the
// copy -- and measured together at 3.38 seconds on a twelve megapixel frame,
// then 1.05 seconds once banded across the cores. The budget for the whole
// capture-to-review path is 800 ms.
//
// ### Why one pass and not two
//
// Both corrections are gathers: for every output pixel, work out where it came
// from and interpolate there. Composing two gathers is itself a gather. For an
// output pixel the undistortion says "sample the distorted image at s", and the
// aberration correction says "red at s is really at c + (s - c) * scaleRed" --
// so red's source is that, directly, and the intermediate image never has to
// exist.
//
// That is faster by more than the second sweep it removes: it also removes the
// 50 MB copy the second pass needed so as not to read its own output, and it
// interpolates each channel *once* rather than twice. Two bilinear resamples in
// series soften an edge measurably more than one, so the fused result is the
// sharper of the two as well as the quicker.
//
// ### Where the NEON is, and where it is not
//
// The polynomial -- r^2, r^4, r^6, the radial factor, the tangential terms, the
// rescale back to pixels -- vectorises cleanly over four adjacent output pixels
// and is done that way. The gather itself does not: four output pixels want
// four unrelated source addresses, and there is no vector load for that. So
// the coordinates are computed four at a time, unpacked, and the texel fetches
// and the blend are scalar. On this workload the fetches are the cost that
// remains, and it is a memory cost, not an arithmetic one.
//
// The Kotlin reference in Optics.applyReference does the same fused arithmetic
// in the same order. OpticsParityTest holds this to it within one code value,
// which is the bound -ffast-math permits and the bound an 8-bit output can
// show.

#include <jni.h>
#include <android/bitmap.h>
#include <arm_neon.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <functional>
#include <thread>
#include <vector>

namespace {

/** The Brown-Conrady model in the coordinates of the image being corrected. */
struct Distortion {
    float fx, fy, cx, cy, skew;
    float k1, k2, k3, p1, p2;
    bool present;
};

/** Radial scale of red and blue about the frame centre. */
struct Aberration {
    float red, blue;
    float cx, cy;
};

/**
 * Where output pixel (x, y) came from in the distorted source.
 *
 * The model runs undistorted-to-distorted, which is the direction a gather
 * wants: the question is "where did this output pixel come from", and that is
 * the forward evaluation, with no inversion.
 */
inline void sourceFor(const Distortion& d, float x, float y, float& sx, float& sy) {
    const float yn = (y - d.cy) / d.fy;
    const float xn = (x - d.cx - d.skew * yn) / d.fx;

    const float r2 = xn * xn + yn * yn;
    const float r4 = r2 * r2;
    const float r6 = r4 * r2;
    const float radial = 1.0f + d.k1 * r2 + d.k2 * r4 + d.k3 * r6;

    const float xy = xn * yn;
    const float dx = radial * xn + 2.0f * d.p1 * xy + d.p2 * (r2 + 2.0f * xn * xn);
    const float dy = radial * yn + d.p1 * (r2 + 2.0f * yn * yn) + 2.0f * d.p2 * xy;

    sx = d.fx * dx + d.skew * dy + d.cx;
    sy = d.fy * dy + d.cy;
}

/**
 * The same, for four adjacent output pixels at once.
 *
 * Every term is elementwise, so the four lanes are simply the four x values
 * with a shared y. This is where the arithmetic of the pass lives, and it is
 * the part that vectorises.
 */
inline void sourceFor4(const Distortion& d, float32x4_t x, float y,
                       float32x4_t& sx, float32x4_t& sy) {
    const float ynS = (y - d.cy) / d.fy;
    const float32x4_t yn = vdupq_n_f32(ynS);
    const float32x4_t xn = vmulq_n_f32(
        vsubq_f32(x, vdupq_n_f32(d.cx + d.skew * ynS)), 1.0f / d.fx);

    const float32x4_t r2 = vmlaq_f32(vmulq_f32(xn, xn), yn, yn);
    const float32x4_t r4 = vmulq_f32(r2, r2);
    const float32x4_t r6 = vmulq_f32(r4, r2);

    float32x4_t radial = vdupq_n_f32(1.0f);
    radial = vmlaq_n_f32(radial, r2, d.k1);
    radial = vmlaq_n_f32(radial, r4, d.k2);
    radial = vmlaq_n_f32(radial, r6, d.k3);

    const float32x4_t xy = vmulq_f32(xn, yn);
    const float32x4_t xx2 = vaddq_f32(r2, vmulq_n_f32(vmulq_f32(xn, xn), 2.0f));
    const float32x4_t yy2 = vaddq_f32(r2, vmulq_n_f32(vmulq_f32(yn, yn), 2.0f));

    float32x4_t dx = vmulq_f32(radial, xn);
    dx = vmlaq_n_f32(dx, xy, 2.0f * d.p1);
    dx = vmlaq_n_f32(dx, xx2, d.p2);

    float32x4_t dy = vmulq_f32(radial, yn);
    dy = vmlaq_n_f32(dy, yy2, d.p1);
    dy = vmlaq_n_f32(dy, xy, 2.0f * d.p2);

    sx = vaddq_f32(vmlaq_n_f32(vmulq_n_f32(dx, d.fx), dy, d.skew), vdupq_n_f32(d.cx));
    sy = vaddq_f32(vmulq_n_f32(dy, d.fy), vdupq_n_f32(d.cy));
}

/**
 * The border, read as a mirror.
 *
 * The same rule as LensDistortion.reflectIntoFrame: a position inside the
 * frame reads as it is; a position `d` past an edge reads `d` inside it;
 * past the mirror's reach is clamped. An edge clamp repeated the frame's
 * edge row -- the demosaic's 3x3 border gather, alternating in colour
 * column by column -- into every row a correction overshot, which the
 * field test saw as a band of vertical ribs along the top of the frame.
 */
inline float32x4_t reflectIntoFrame4(float32x4_t v, int max) {
    const float32x4_t zero = vdupq_n_f32(0.0f);
    const float32x4_t m = vdupq_n_f32(static_cast<float>(max));
    const uint32x4_t below = vcltq_f32(v, zero);
    const uint32x4_t above = vcgtq_f32(v, m);
    // Below: |v|, or the near edge past the mirror's reach.
    const float32x4_t mirroredBelow = vnegq_f32(v);
    const float32x4_t fromBelow = vbslq_f32(vcgtq_f32(mirroredBelow, m), zero, mirroredBelow);
    // Above: 2*max - v, or the near edge past the reach.
    const float32x4_t mirroredAbove = vsubq_f32(vaddq_f32(m, m), v);
    const float32x4_t fromAbove = vbslq_f32(vcltq_f32(mirroredAbove, zero), m, mirroredAbove);
    float32x4_t r = vbslq_f32(below, fromBelow, v);
    r = vbslq_f32(above, fromAbove, r);
    return r;
}

inline float reflectIntoFrame(float v, int max) {
    const float m = static_cast<float>(max);
    if (v >= 0.0f && v <= m) return v;
    if (v < 0.0f) {
        const float r = -v;
        return r > m ? 0.0f : r;
    }
    const float r = 2.0f * m - v;
    return r < 0.0f ? m : r;
}

/**
 * Bilinear sample of one channel at four positions, the border mirrored.
 *
 * The four positions arrive as vectors and the result leaves as one. Between
 * them the only scalar work is the sixteen texel fetches, which have no vector
 * form: four output pixels want four unrelated addresses. Everything either
 * side -- clamp, split into integer and fraction, the blend, the rounding --
 * is done across the four lanes at once. This is what the port needed to be
 * fast: the scalar version did the blend forty operations at a time, per
 * channel, per pixel, and that was most of the cost.
 *
 * Clamped before the split into integer and fraction: taking the fraction of an
 * out-of-range coordinate and then clamping the integer part leaves the two
 * describing different points. Rounded to nearest even at the end, matching
 * Kotlin's round() -- the four weights sum to a hair under one in float, and
 * truncation turned a flat 128 into 127 across the whole frame.
 *
 * Returns the four channel values as int32 lanes in 0..255.
 */
inline int32x4_t sample4(const uint32_t* src, int stride, int maxX, int maxY,
                         float32x4_t fx, float32x4_t fy, int shift) {
    const float32x4_t cx = reflectIntoFrame4(fx, maxX);
    const float32x4_t cy = reflectIntoFrame4(fy, maxY);

    // Truncation is floor here, since the clamp made every lane non-negative.
    const int32x4_t x0 = vcvtq_s32_f32(cx);
    const int32x4_t y0 = vcvtq_s32_f32(cy);
    const float32x4_t tx = vsubq_f32(cx, vcvtq_f32_s32(x0));
    const float32x4_t ty = vsubq_f32(cy, vcvtq_f32_s32(y0));

    const int32x4_t x1 = vminq_s32(vaddq_s32(x0, vdupq_n_s32(1)), vdupq_n_s32(maxX));
    const int32x4_t y1 = vminq_s32(vaddq_s32(y0, vdupq_n_s32(1)), vdupq_n_s32(maxY));

    // The gather. Sixteen scalar fetches into four small arrays, then back into
    // lanes. The store-and-reload is the cost of having no gather instruction,
    // and it is cheap next to the fetches it serves.
    alignas(16) int32_t ax0[4], ay0[4], ax1[4], ay1[4];
    vst1q_s32(ax0, x0);
    vst1q_s32(ay0, y0);
    vst1q_s32(ax1, x1);
    vst1q_s32(ay1, y1);

    alignas(16) uint32_t t00[4], t10[4], t01[4], t11[4];
    for (int i = 0; i < 4; ++i) {
        const uint32_t* row0 = src + static_cast<size_t>(ay0[i]) * stride;
        const uint32_t* row1 = src + static_cast<size_t>(ay1[i]) * stride;
        t00[i] = row0[ax0[i]];
        t10[i] = row0[ax1[i]];
        t01[i] = row1[ax0[i]];
        t11[i] = row1[ax1[i]];
    }

    // Channel extraction across the lanes: shift, mask, widen to float.
    const uint32x4_t mask = vdupq_n_u32(0xFF);
    const int32x4_t sh = vdupq_n_s32(-shift);
    const float32x4_t v00 = vcvtq_f32_u32(vandq_u32(vshlq_u32(vld1q_u32(t00), sh), mask));
    const float32x4_t v10 = vcvtq_f32_u32(vandq_u32(vshlq_u32(vld1q_u32(t10), sh), mask));
    const float32x4_t v01 = vcvtq_f32_u32(vandq_u32(vshlq_u32(vld1q_u32(t01), sh), mask));
    const float32x4_t v11 = vcvtq_f32_u32(vandq_u32(vshlq_u32(vld1q_u32(t11), sh), mask));

    // The blend, four lanes at once.
    const float32x4_t top = vmlaq_f32(v00, vsubq_f32(v10, v00), tx);
    const float32x4_t bottom = vmlaq_f32(v01, vsubq_f32(v11, v01), tx);
    const float32x4_t v = vmlaq_f32(top, vsubq_f32(bottom, top), ty);

    // vcvtnq: round to nearest, ties to even -- the same as rintf and as
    // Kotlin's round(). Then clamp to the byte.
    const int32x4_t r = vcvtnq_s32_f32(v);
    return vminq_s32(vmaxq_s32(r, vdupq_n_s32(0)), vdupq_n_s32(255));
}

/** The scalar form, for the row tail the vector loop does not cover. */
inline int sampleChannel(const uint32_t* src, int stride, int maxX, int maxY,
                         float fx, float fy, int shift) {
    const float cx = reflectIntoFrame(fx, maxX);
    const float cy = reflectIntoFrame(fy, maxY);

    const int x0 = static_cast<int>(cx);
    const int y0 = static_cast<int>(cy);
    const int x1 = x0 < maxX ? x0 + 1 : x0;
    const int y1 = y0 < maxY ? y0 + 1 : y0;
    const float tx = cx - static_cast<float>(x0);
    const float ty = cy - static_cast<float>(y0);

    const uint32_t* row0 = src + static_cast<size_t>(y0) * stride;
    const uint32_t* row1 = src + static_cast<size_t>(y1) * stride;
    const float v00 = static_cast<float>((row0[x0] >> shift) & 0xFF);
    const float v10 = static_cast<float>((row0[x1] >> shift) & 0xFF);
    const float v01 = static_cast<float>((row1[x0] >> shift) & 0xFF);
    const float v11 = static_cast<float>((row1[x1] >> shift) & 0xFF);

    const float top = v00 + (v10 - v00) * tx;
    const float bottom = v01 + (v11 - v01) * tx;
    const float v = top + (bottom - top) * ty;

    // rintf: round to nearest, ties to even, matching Kotlin's round().
    const int r = static_cast<int>(rintf(v));
    return std::min(std::max(r, 0), 255);
}

/**
 * Runs [body] over horizontal bands, claimed dynamically.
 *
 * The same shape as bayer_merge.cpp's, for the same reason: Android cores are
 * heterogeneous, and equal bands per thread would run the pass at the speed of
 * the slowest core. Many small bands let the fast cores take more of them.
 */
void parallelBands(int height, const std::function<void(int, int)>& body) {
    const unsigned hw = std::thread::hardware_concurrency();
    int threads = static_cast<int>(std::max(2u, hw));
    threads = std::min(threads, std::max(1, height));

    constexpr int kBandsPerThread = 6;
    constexpr int kMinBandRows = 8;
    const int bandRows = std::max(kMinBandRows,
                                  (height + threads * kBandsPerThread - 1) /
                                      (threads * kBandsPerThread));
    const int bands = (height + bandRows - 1) / bandRows;

    std::atomic<int> nextBand{0};
    std::vector<std::thread> pool;
    pool.reserve(static_cast<size_t>(threads));

    auto worker = [&]() {
        for (;;) {
            const int index = nextBand.fetch_add(1, std::memory_order_relaxed);
            if (index >= bands) return;
            const int start = index * bandRows;
            const int end = std::min(start + bandRows, height);
            if (start >= end) return;
            body(start, end);
        }
    };

    for (int t = 1; t < threads; ++t) pool.emplace_back(worker);
    worker();
    for (auto& th : pool) th.join();
}

// The channel layout of an RGBA_8888 Android bitmap, in memory order. Read as
// a little-endian uint32 the bytes land as R in the low byte, then G, B, A --
// which is the *opposite* of the ARGB int layout Kotlin's IntArray uses. The
// Kotlin reference operates on getPixels() output, where red is at bit 16; this
// operates on the locked buffer, where red is at bit 0. Same picture, different
// byte order, and the shifts below are the locked-buffer ones.
constexpr int kShiftR = 0;
constexpr int kShiftG = 8;
constexpr int kShiftB = 16;
constexpr uint32_t kAlpha = 0xFF000000u;

/**
 * The fused pass over one band of rows.
 *
 * For each output pixel: undistort to find where it came from, then for red
 * and blue apply the aberration rescale about the frame centre to that
 * position, and interpolate each channel once at its own source.
 */
void correctBand(const uint32_t* src, uint32_t* dst, int width, int srcStride,
                 int dstStride, int maxX, int maxY, const Distortion& d,
                 const Aberration& a, int yStart, int yEnd) {
    // Pre-built lane offsets 0..3, added to a broadcast x.
    const float32x4_t lane = {0.0f, 1.0f, 2.0f, 3.0f};

    // The aberration rescale, as vectors: centre and the two scales.
    const float32x4_t acx = vdupq_n_f32(a.cx);
    const float32x4_t acy = vdupq_n_f32(a.cy);
    const uint32x4_t alpha = vdupq_n_u32(kAlpha);

    for (int y = yStart; y < yEnd; ++y) {
        uint32_t* out = dst + static_cast<size_t>(y) * dstStride;
        const float fy = static_cast<float>(y);
        const float32x4_t fyv = vdupq_n_f32(fy);

        int x = 0;
        for (; x + 4 <= width; x += 4) {
            // Where these four output pixels came from.
            float32x4_t sx, sy;
            if (d.present) {
                const float32x4_t xs = vaddq_f32(vdupq_n_f32(static_cast<float>(x)), lane);
                sourceFor4(d, xs, fy, sx, sy);
            } else {
                sx = vaddq_f32(vdupq_n_f32(static_cast<float>(x)), lane);
                sy = fyv;
            }

            // Green at the geometric source; red and blue rescaled about the
            // frame centre from there.
            const int32x4_t g = sample4(src, srcStride, maxX, maxY, sx, sy, kShiftG);

            const float32x4_t dx = vsubq_f32(sx, acx);
            const float32x4_t dy = vsubq_f32(sy, acy);
            const float32x4_t rx = vmlaq_n_f32(acx, dx, a.red);
            const float32x4_t ry = vmlaq_n_f32(acy, dy, a.red);
            const int32x4_t r = sample4(src, srcStride, maxX, maxY, rx, ry, kShiftR);

            const float32x4_t bx = vmlaq_n_f32(acx, dx, a.blue);
            const float32x4_t by = vmlaq_n_f32(acy, dy, a.blue);
            const int32x4_t b = sample4(src, srcStride, maxX, maxY, bx, by, kShiftB);

            // Pack four pixels and store them in one go.
            const uint32x4_t packed = vorrq_u32(
                vorrq_u32(alpha, vshlq_n_u32(vreinterpretq_u32_s32(b), kShiftB)),
                vorrq_u32(vshlq_n_u32(vreinterpretq_u32_s32(g), kShiftG),
                          vreinterpretq_u32_s32(r)));
            vst1q_u32(out + x, packed);
        }

        // The tail the vector loop did not cover.
        for (; x < width; ++x) {
            float sx, sy;
            if (d.present) {
                sourceFor(d, static_cast<float>(x), fy, sx, sy);
            } else {
                sx = static_cast<float>(x);
                sy = fy;
            }
            const int g = sampleChannel(src, srcStride, maxX, maxY, sx, sy, kShiftG);
            const float rx = a.cx + (sx - a.cx) * a.red;
            const float ry = a.cy + (sy - a.cy) * a.red;
            const int r = sampleChannel(src, srcStride, maxX, maxY, rx, ry, kShiftR);
            const float bx = a.cx + (sx - a.cx) * a.blue;
            const float by = a.cy + (sy - a.cy) * a.blue;
            const int b = sampleChannel(src, srcStride, maxX, maxY, bx, by, kShiftB);
            out[x] = kAlpha | (static_cast<uint32_t>(b) << kShiftB) |
                     (static_cast<uint32_t>(g) << kShiftG) |
                     (static_cast<uint32_t>(r) << kShiftR);
        }
    }
}

}  // namespace

extern "C" {

/**
 * Corrects [srcBitmap] into [dstBitmap], both RGBA_8888 and the same size.
 *
 * [model] is ten floats -- fx, fy, cx, cy, skew, k1, k2, k3, p1, p2 -- or null
 * for no geometric correction. [scaleRed] and [scaleBlue] are the aberration
 * scales, 1.0 for none. Returns false for anything it will not handle, and the
 * caller falls back to the Kotlin reference.
 */
JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_NativeOptics_nCorrect(
        JNIEnv* env, jobject, jobject srcBitmap, jobject dstBitmap,
        jfloatArray model, jfloat scaleRed, jfloat scaleBlue) {
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
    if (srcInfo.width != dstInfo.width || srcInfo.height != dstInfo.height) {
        return JNI_FALSE;
    }

    const int width = static_cast<int>(srcInfo.width);
    const int height = static_cast<int>(srcInfo.height);
    if (width <= 0 || height <= 0) return JNI_FALSE;

    Distortion d{};
    d.present = false;
    if (model != nullptr) {
        if (env->GetArrayLength(model) < 10) return JNI_FALSE;
        jfloat m[10];
        env->GetFloatArrayRegion(model, 0, 10, m);
        d.fx = m[0]; d.fy = m[1]; d.cx = m[2]; d.cy = m[3]; d.skew = m[4];
        d.k1 = m[5]; d.k2 = m[6]; d.k3 = m[7]; d.p1 = m[8]; d.p2 = m[9];
        if (d.fx <= 0.0f || d.fy <= 0.0f) return JNI_FALSE;
        d.present = true;
    }

    Aberration a{};
    a.red = scaleRed;
    a.blue = scaleBlue;
    // The same centre the Kotlin estimator measured against.
    a.cx = static_cast<float>(width - 1) / 2.0f;
    a.cy = static_cast<float>(height - 1) / 2.0f;

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
    const int maxX = width - 1;
    const int maxY = height - 1;

    parallelBands(height, [&](int yStart, int yEnd) {
        correctBand(in, out, width, srcStride, dstStride, maxX, maxY, d, a, yStart, yEnd);
    });

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_TRUE;
}

}  // extern "C"
