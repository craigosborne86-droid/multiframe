// Develop for the sub-pixel canvas. See LinearDevelop.h for what it is; this
// is how it is arranged.
//
// The canvas is 300 MB of 16-bit RGB and the developed image is 150 MB of
// 8-bit RGB, and neither can be a Bitmap or a Java array. Both are anonymous
// mappings. The develop walks the canvas in strips from the top, and as each
// strip's output is written its input pages are given back to the kernel, so
// the footprint never exceeds the canvas alone: the output grows as the input
// shrinks. The sharpen then runs over the output in place, and the strip
// encoder writes the file from the output's own pages.
//
// The arithmetic from the colour matrix onward is Tone.h -- the same functions
// the CFA develop runs -- so the tone of a 2x photograph is the tone of its
// ordinary sibling. The Kotlin LinearDeveloper is the reference for the whole
// chain and LinearDevelopParityTest holds this to it.

#include "LinearDevelop.h"

#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#include "Jpeg.h"
#include "Chroma.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LinearDevelop", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "LinearDevelop", __VA_ARGS__)

namespace multiframe {
namespace linear {

namespace {

using namespace multiframe::tone;

/**
 * Canvas rows per strip. The strip is the unit of page release, so it wants to
 * be large enough that the release is a rounding error and small enough that
 * the output written ahead of the input freed is not itself a footprint: 512
 * rows of an 8160-wide canvas is 12 MB of output.
 */
constexpr int kStripRows = 512;

/**
 * Bilinear lens shading gain on a three-channel grid.
 *
 * The same interpolation as tone::shadingGain and ShadingMap.gainAt, on a grid
 * with three gains per cell rather than the sensor's four, because a canvas
 * cell is a colour rather than a site. Mirrors LensShading.rgbGainAt, which the
 * Kotlin reference uses.
 */
inline float shadingGain3(const float* gains, int columns, int rows,
                          int x, int y, int width, int height, int channel) {
    if (gains == nullptr || columns <= 0 || rows <= 0 || width <= 1 || height <= 1) {
        return 1.0f;
    }
    const float fx = (static_cast<float>(x) / (width - 1)) * (columns - 1);
    const float fy = (static_cast<float>(y) / (height - 1)) * (rows - 1);

    const int x0 = std::clamp(static_cast<int>(fx), 0, columns - 1);
    const int y0 = std::clamp(static_cast<int>(fy), 0, rows - 1);
    const int x1 = std::min(x0 + 1, columns - 1);
    const int y1 = std::min(y0 + 1, rows - 1);
    const float tx = std::clamp(fx - x0, 0.0f, 1.0f);
    const float ty = std::clamp(fy - y0, 0.0f, 1.0f);

    auto cell = [&](int c, int r) {
        return gains[(static_cast<size_t>(r) * columns + c) * 3 + channel];
    };
    const float top = cell(x0, y0) * (1.0f - tx) + cell(x1, y0) * tx;
    const float bottom = cell(x0, y1) * (1.0f - tx) + cell(x1, y1) * tx;
    return top * (1.0f - ty) + bottom * ty;
}

/**
 * Shading and white balance for one sensor row, as a gain per canvas column.
 *
 * Both canvas rows over a sensor row share it, and both canvas columns over a
 * sensor column do too, so the row is built once per sensor row from half as
 * many interpolations as it has entries. White balance is folded in because it
 * is a per-channel constant and this is the last per-channel multiply before
 * the matrix.
 */
void buildGainRow(const Params& p, int sensorRow, int width, int height,
                  std::vector<float>& gainRow) {
    const int sensorW = width / 2;
    const int sensorH = height / 2;
    gainRow.resize(static_cast<size_t>(width) * 3);
    for (int sx = 0; sx < sensorW; ++sx) {
        for (int c = 0; c < 3; ++c) {
            const float g = p.wb[c] * (p.shading != nullptr
                ? shadingGain3(p.shading, p.shadingColumns, p.shadingRows, sx, sensorRow,
                               sensorW, sensorH, c)
                : 1.0f);
            gainRow[(static_cast<size_t>(2 * sx)) * 3 + c] = g;
            gainRow[(static_cast<size_t>(2 * sx + 1)) * 3 + c] = g;
        }
    }
    // An odd canvas width has one column past the last sensor pair.
    if (width & 1) {
        for (int c = 0; c < 3; ++c) {
            gainRow[(static_cast<size_t>(width - 1)) * 3 + c] =
                gainRow[(static_cast<size_t>(width - 2)) * 3 + c];
        }
    }
}

/** Black level to linear, gain row, matrix, exposure: one pixel's linear RGB after the gain. */
inline void linearPixel(const uint16_t* s, const float* gr, const Params& p, float invRange,
                        float& r, float& g, float& b) {
    const float* m = p.matrix;
    const float gain = p.tone.exposureGain;
    const float r0 = std::max((static_cast<float>(s[0]) - p.black[0]) * invRange, 0.0f) * gr[0];
    const float g0 = std::max((static_cast<float>(s[1]) - p.black[1]) * invRange, 0.0f) * gr[1];
    const float b0 = std::max((static_cast<float>(s[2]) - p.black[2]) * invRange, 0.0f) * gr[2];
    r = (m[0] * r0 + m[1] * g0 + m[2] * b0) * gain;
    g = (m[3] * r0 + m[4] * g0 + m[5] * b0) * gain;
    b = (m[6] * r0 + m[7] * g0 + m[8] * b0) * gain;
}

/**
 * The bilateral grid for the canvas, from each 2x2 block's mean linear RGB
 * after the gain -- the same chain as the pixels, at a quarter of them.
 * Mirrors BilateralGrid.fromCanvas.
 */
void buildGrid(const uint16_t* rgb16, int width, int height, const Params& p, float invRange,
               BilateralGrid& grid) {
    grid.init(width, height);
    std::vector<float> gainRow;
    for (int by = 0; by + 1 < height; by += 2) {
        buildGainRow(p, by >> 1, width, height, gainRow);
        const uint16_t* row0 = rgb16 + static_cast<size_t>(by) * width * 3;
        const uint16_t* row1 = row0 + static_cast<size_t>(width) * 3;
        for (int bx = 0; bx + 1 < width; bx += 2) {
            float r = 0, g = 0, b = 0;
            for (int dy = 0; dy < 2; ++dy) {
                const uint16_t* row = dy ? row1 : row0;
                for (int dx = 0; dx < 2; ++dx) {
                    float pr, pg, pb;
                    linearPixel(row + static_cast<size_t>(bx + dx) * 3,
                                gainRow.data() + static_cast<size_t>(bx + dx) * 3, p, invRange, pr, pg, pb);
                    r += 0.25f * pr; g += 0.25f * pg; b += 0.25f * pb;
                }
            }
            grid.splat(bx, by, 0.2126f * r + 0.7152f * g + 0.0722f * b);
        }
    }
    grid.finish();
}

/** Black level to linear, gain row, matrix, exposure, local tone, tone, display: one row. */
void developRow(const uint16_t* src, int width, int y, const Params& p, const float* gainRow,
                float invRange, const ShoulderLut& shoulder, const DisplayLut& display,
                const BilateralGrid* grid, uint8_t* dst) {
    for (int x = 0; x < width; ++x) {
        const uint16_t* s = src + static_cast<size_t>(x) * 3;
        const float* gr = gainRow + static_cast<size_t>(x) * 3;
        float r, g, b;
        linearPixel(s, gr, p, invRange, r, g, b);
        if (grid != nullptr) {
            const float k = grid->gain(x, y, 0.2126f * r + 0.7152f * g + 0.0722f * b, p.localTone);
            r *= k; g *= k; b *= k;
        }
        renderLinear(r, g, b, p.tone, shoulder);

        uint8_t* d = dst + static_cast<size_t>(x) * 3;
        d[0] = display(r);
        d[1] = display(g);
        d[2] = display(b);
    }
}

inline float lumaOf(int r, int g, int b) {
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

inline uint8_t shiftChannel(int value, float shift) {
    const int moved = static_cast<int>(std::lround(value + shift));
    return static_cast<uint8_t>(std::clamp(moved, 0, 255));
}

/**
 * Capture sharpening on three-byte RGB, in place, in bands.
 *
 * The same mask as NativeMerge's nSharpen and Sharpen.kt: a 3x3 box on luma,
 * the detail above a threshold added back to all three channels equally. The
 * difference is where the luma lives. The CFA develop keeps a float plane for
 * the whole image, which at fifty megapixels would be 200 MB; here each band
 * computes luma for its own rows plus one above and below, and the plane is
 * never larger than a band.
 *
 * Every neighbour a pixel reads comes from that band-local luma, computed
 * from the untouched image before the band writes anything, so writing in
 * place is safe for the same reason it is in nSharpen.
 */
void sharpenRgb8(uint8_t* base, int width, int height, size_t stride,
                 float amount, float threshold, float maxShift) {
    if (amount <= 0.0f || width < 3 || height < 3) return;

    parallelBands(height, [&](int y0, int y1) {
        const int from = std::max(y0, 1);
        const int to = std::min(y1, height - 1);
        if (from >= to) return;

        // Luma for rows from-1 .. to inclusive.
        const int lumaRows = (to - from) + 2;
        std::vector<float> luma(static_cast<size_t>(lumaRows) * width);
        for (int j = 0; j < lumaRows; ++j) {
            const uint8_t* row = base + static_cast<size_t>(from - 1 + j) * stride;
            float* l = luma.data() + static_cast<size_t>(j) * width;
            for (int x = 0; x < width; ++x) {
                const uint8_t* q = row + static_cast<size_t>(x) * 3;
                l[x] = lumaOf(q[0], q[1], q[2]);
            }
        }

        for (int y = from; y < to; ++y) {
            uint8_t* row = base + static_cast<size_t>(y) * stride;
            const float* centre = luma.data() + static_cast<size_t>(y - from + 1) * width;
            const float* above = centre - width;
            const float* below = centre + width;
            int x = 1;

#if defined(__ARM_NEON)
            // Four pixels of mask at a time; the pixel update stays scalar
            // because three-byte pixels do not lane up. The nine additions are
            // performed in the scalar loop's order, lane-wise, so the two
            // agree exactly.
            const float32x4_t vThreshold = vdupq_n_f32(threshold);
            const float32x4_t vAmount = vdupq_n_f32(amount);
            const float32x4_t vMaxShift = vdupq_n_f32(maxShift);
            const float32x4_t vMinShift = vdupq_n_f32(-maxShift);
            const float32x4_t vNine = vdupq_n_f32(9.0f);
            for (; x + 3 <= width - 2; x += 4) {
                float32x4_t sum = vld1q_f32(above + x - 1);
                sum = vaddq_f32(sum, vld1q_f32(above + x));
                sum = vaddq_f32(sum, vld1q_f32(above + x + 1));
                sum = vaddq_f32(sum, vld1q_f32(centre + x - 1));
                const float32x4_t here = vld1q_f32(centre + x);
                sum = vaddq_f32(sum, here);
                sum = vaddq_f32(sum, vld1q_f32(centre + x + 1));
                sum = vaddq_f32(sum, vld1q_f32(below + x - 1));
                sum = vaddq_f32(sum, vld1q_f32(below + x));
                sum = vaddq_f32(sum, vld1q_f32(below + x + 1));
                const float32x4_t detail = vsubq_f32(here, vdivq_f32(sum, vNine));
                const uint32x4_t keep = vcltq_f32(vabsq_f32(detail), vThreshold);
                const float32x4_t shift =
                    vminq_f32(vmaxq_f32(vmulq_f32(detail, vAmount), vMinShift), vMaxShift);

                float shifts[4];
                uint32_t keeps[4];
                vst1q_f32(shifts, shift);
                vst1q_u32(keeps, keep);
                for (int k = 0; k < 4; ++k) {
                    if (keeps[k]) continue;
                    uint8_t* q = row + static_cast<size_t>(x + k) * 3;
                    q[0] = shiftChannel(q[0], shifts[k]);
                    q[1] = shiftChannel(q[1], shifts[k]);
                    q[2] = shiftChannel(q[2], shifts[k]);
                }
            }
#endif

            for (; x < width - 1; ++x) {
                float sum = above[x - 1];
                sum += above[x];
                sum += above[x + 1];
                sum += centre[x - 1];
                sum += centre[x];
                sum += centre[x + 1];
                sum += below[x - 1];
                sum += below[x];
                sum += below[x + 1];
                const float detail = centre[x] - sum / 9.0f;
                if (std::fabs(detail) < threshold) continue;
                const float shift = std::clamp(detail * amount, -maxShift, maxShift);
                uint8_t* q = row + static_cast<size_t>(x) * 3;
                q[0] = shiftChannel(q[0], shift);
                q[1] = shiftChannel(q[1], shift);
                q[2] = shiftChannel(q[2], shift);
            }
        }
    });
}

/** Gives back the canvas rows [row0, row1) that lie in whole pages. */
void releaseRows(uint16_t* rgb16, int width, int row0, int row1) {
    const long page = sysconf(_SC_PAGE_SIZE);
    if (page <= 0) return;
    const size_t rowBytes = static_cast<size_t>(width) * 3 * sizeof(uint16_t);
    const auto begin = reinterpret_cast<uintptr_t>(rgb16) + static_cast<size_t>(row0) * rowBytes;
    const auto end = reinterpret_cast<uintptr_t>(rgb16) + static_cast<size_t>(row1) * rowBytes;
    const uintptr_t mask = static_cast<uintptr_t>(page) - 1;
    const uintptr_t first = (begin + mask) & ~mask;
    const uintptr_t last = end & ~mask;
    if (last > first) {
        madvise(reinterpret_cast<void*>(first), last - first, MADV_DONTNEED);
    }
}

}  // namespace

float autoExposure(const uint16_t* rgb16, int width, int height, const Params& p) {
    const float lo = std::min(p.black[0], std::min(p.black[1], p.black[2]));
    const float range = std::max(1.0f, static_cast<float>(p.white) - lo);

    std::vector<float> samples;
    samples.reserve(8192);
    const int stepY = std::max(1, height / 400);
    const int stepX = std::max(1, width / 400);
    for (int y = 0; y < height; y += stepY) {
        const uint16_t* row = rgb16 + static_cast<size_t>(y) * width * 3;
        for (int x = 0; x < width; x += stepX) {
            const float lin = (static_cast<float>(row[static_cast<size_t>(x) * 3 + 1]) - p.black[1]) / range;
            samples.push_back(std::max(lin, 0.0f) * p.wb[1]);
        }
    }
    if (samples.size() < 32) return 1.0f;
    std::sort(samples.begin(), samples.end());
    const float bright = samples[static_cast<size_t>((samples.size() - 1) * p.autoPercentile)];
    if (bright <= 1e-5f) return 64.0f;
    return std::clamp(p.autoTarget / bright, 0.25f, 64.0f);
}

bool developToRgb8(uint16_t* rgb16, int width, int height, const Params& params,
                   uint8_t* out, size_t outStride, bool release, Stats* stats) {
    if (rgb16 == nullptr || out == nullptr || width < 2 || height < 2) return false;
    ensureGammaLut();

    Params p = params;
    const int64_t tExposure = nowMicros();
    if (p.tone.exposureGain <= 0.0f) {
        p.tone.exposureGain = autoExposure(rgb16, width, height, p);
    }
    const int64_t tDevelop = nowMicros();

    const float lo = std::min(p.black[0], std::min(p.black[1], p.black[2]));
    const float invRange = 1.0f / std::max(1.0f, static_cast<float>(p.white) - lo);

    DisplayLut display;
    buildDisplayLut(display, p.tone);
    ShoulderLut shoulder;
    buildShoulderLut(shoulder, p.tone);

    // The local operator's grid, when asked for, built before any strip of
    // the canvas is released: it reads the whole canvas.
    BilateralGrid grid;
    const BilateralGrid* gridPtr = nullptr;
    const int64_t tGrid = nowMicros();
    if (p.localTone > 0.0f) {
        buildGrid(rgb16, width, height, p, invRange, grid);
        gridPtr = &grid;
        LOGI("linear develop: local tone grid %lldms", static_cast<long long>((nowMicros() - tGrid) / 1000));
    }

    for (int s0 = 0; s0 < height; s0 += kStripRows) {
        const int s1 = std::min(s0 + kStripRows, height);
        parallelBands(s1 - s0, [&](int b0, int b1) {
            std::vector<float> gainRow;
            int builtFor = -1;
            for (int y = s0 + b0; y < s0 + b1; ++y) {
                const int sensorRow = y >> 1;
                if (sensorRow != builtFor) {
                    buildGainRow(p, sensorRow, width, height, gainRow);
                    builtFor = sensorRow;
                }
                developRow(rgb16 + static_cast<size_t>(y) * width * 3, width, y, p, gainRow.data(),
                           invRange, shoulder, display, gridPtr, out + static_cast<size_t>(y) * outStride);
            }
        });
        if (release) releaseRows(rgb16, width, s0, s1);
    }
    const int64_t tSharpen = nowMicros();

    // The colour speckle first, so the sharpen never sees it. See Chroma.h.
    chroma::smooth<3>(out, width, height, outStride, p.chromaRadius, p.chromaSigma, p.chromaAmount);
    sharpenRgb8(out, width, height, outStride, p.sharpenAmount, p.sharpenThreshold, p.sharpenMaxShift);
    const int64_t tEnd = nowMicros();

    if (stats != nullptr) {
        stats->exposureGain = p.tone.exposureGain;
        stats->exposureMicros = tDevelop - tExposure;
        stats->developMicros = tSharpen - tDevelop;
        stats->sharpenMicros = tEnd - tSharpen;
    }
    LOGI("linear develop %dx%d: exposure %lldms (gain %.2f), develop %lldms, sharpen %lldms",
         width, height, static_cast<long long>((tDevelop - tExposure) / 1000), p.tone.exposureGain,
         static_cast<long long>((tSharpen - tDevelop) / 1000),
         static_cast<long long>((tEnd - tSharpen) / 1000));
    return true;
}

bool developToJpeg(uint16_t* rgb16, int width, int height, const Params& p, int quality,
                   const std::function<bool(const unsigned char*, size_t)>& sink, Stats* stats) {
    if (width <= 0 || height <= 0) return false;
    const size_t stride = static_cast<size_t>(width) * 3;
    const size_t bytes = stride * static_cast<size_t>(height);

    void* mem = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        LOGW("mmap of %.0f MB developed image failed", bytes / 1048576.0);
        return false;
    }
    auto* out = static_cast<uint8_t*>(mem);

    Stats local;
    const bool developed = developToRgb8(rgb16, width, height, p, out, stride, true, &local);
    bool ok = false;
    if (developed) {
        const int64_t tEncode = nowMicros();
        ok = jpeg::encode(out, width, height, stride, 3, quality, 0, sink);
        local.encodeMicros = nowMicros() - tEncode;
        LOGI("linear develop encode %lldms, %s", static_cast<long long>(local.encodeMicros / 1000),
             ok ? "ok" : "failed");
    }
    munmap(mem, bytes);
    if (stats != nullptr) *stats = local;
    return ok;
}

}  // namespace linear
}  // namespace multiframe
