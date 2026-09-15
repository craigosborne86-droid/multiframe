#include "Mosaic.h"

#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>
#include <vector>

#define LOG_TAG "MultiframeMosaic"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace multiframe {

namespace {

/**
 * The accumulated weight, stored as a logarithm.
 *
 * It was fixed point at 512 per unit, which held 128 tiles at full weight and
 * resolved a two-hundredth -- enough for a feather. The detail band's hand-off
 * is not a feather: the tile that is deeper at a pixel outweighs the shallower
 * one by a twelfth power of their depths, and at the far side of an overlap
 * that is a weight of a millionth against one. Fixed point stored that as
 * zero, the pixel read as never covered, and the next tile to arrive took it
 * as its first sample. Sixteen bits of exponent instead: code 0 is uncovered,
 * and every other code is 2 to the power of (code - 32768) / 2048 -- sixteen
 * octaves either side of one at a thirtieth of a percent, which the running
 * mean's ratio of weights is more than accurate enough with.
 */
constexpr float kWeightCodesPerOctave = 2048.0f;
constexpr float kWeightZeroCode = 32768.0f;

inline uint16_t encodeWeight(float w) {
    if (w <= 0.0f) return 0;
    const float code = kWeightZeroCode + std::log2(w) * kWeightCodesPerOctave;
    return static_cast<uint16_t>(std::clamp(code, 1.0f, 65535.0f));
}

inline float decodeWeight(uint16_t code) {
    if (code == 0) return 0.0f;
    return std::exp2((static_cast<float>(code) - kWeightZeroCode) / kWeightCodesPerOctave);
}

/**
 * The hand-off's steepness. With two tiles overlapping by 35% of their width
 * the zone where neither is at least ten times the other is about a fifteenth
 * of the overlap; a registration error shows as a double image only there.
 */
constexpr float kHandoffPower = 12.0f;

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

namespace {

/** An anonymous mapping of [bytes], or null. Pages arrive zeroed, which is the empty state. */
uint16_t* mapCanvas(size_t bytes) {
    void* mem = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        LOGW("mmap of %.0f MB canvas failed", bytes / (1024.0 * 1024.0));
        return nullptr;
    }
    return static_cast<uint16_t*>(mem);
}

}  // namespace

MosaicCanvas* MosaicCanvas::Create(int width, int height, int bands) {
    if (width <= 0 || height <= 0) return nullptr;
    bands = std::clamp(bands, 1, 3);

    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t bytes = pixels * kChannels * sizeof(uint16_t);
    if (pixels / static_cast<size_t>(width) != static_cast<size_t>(height)) return nullptr;

    // The coarser levels: two canvases each, at a quarter and a sixteenth of
    // the side. Together an eighth again of the full canvas, and a hundred
    // and twenty-eighth on top of that.
    size_t total = bytes;
    int levelW[2], levelH[2];
    size_t levelBytes[2] = {0, 0};
    for (int k = 0; k < bands - 1; ++k) {
        const int scale = k == 0 ? kLevelScale : kLevelScale * kLevelScale;
        levelW[k] = (width + scale - 1) / scale;
        levelH[k] = (height + scale - 1) / scale;
        levelBytes[k] = static_cast<size_t>(levelW[k]) * levelH[k] * kChannels * sizeof(uint16_t);
        total += 2 * levelBytes[k];
    }

    const long pages = sysconf(_SC_PHYS_PAGES);
    const long pageSize = sysconf(_SC_PAGE_SIZE);
    if (pages > 0 && pageSize > 0) {
        const auto physical = static_cast<size_t>(pages) * static_cast<size_t>(pageSize);
        if (total > physical / 2) {
            LOGW("refusing %.0f MB canvas on a %.0f MB device",
                 total / (1024.0 * 1024.0), physical / (1024.0 * 1024.0));
            return nullptr;
        }
    }

    auto* canvas = new (std::nothrow) MosaicCanvas();
    if (canvas == nullptr) return nullptr;
    canvas->data_ = mapCanvas(bytes);
    if (canvas->data_ == nullptr) {
        delete canvas;
        return nullptr;
    }
    canvas->bytes_ = bytes;
    canvas->width_ = width;
    canvas->height_ = height;
    canvas->bands_ = bands;
    for (int k = 0; k < bands - 1; ++k) {
        for (Level* level : {&canvas->wide_[k], &canvas->narrow_[k]}) {
            level->data = mapCanvas(levelBytes[k]);
            if (level->data == nullptr) {
                delete canvas;
                return nullptr;
            }
            level->bytes = levelBytes[k];
            level->width = levelW[k];
            level->height = levelH[k];
            level->scale = k == 0 ? kLevelScale : kLevelScale * kLevelScale;
        }
    }
    LOGI("mosaic canvas %dx%d, %.0f MB, %d bands (%.0f MB in all)", width, height,
         bytes / (1024.0 * 1024.0), bands, total / (1024.0 * 1024.0));
    return canvas;
}

MosaicCanvas::~MosaicCanvas() {
    if (data_ != nullptr) munmap(data_, bytes_);
    data_ = nullptr;
    for (int k = 0; k < 2; ++k) {
        for (Level* level : {&wide_[k], &narrow_[k]}) {
            if (level->data != nullptr) munmap(level->data, level->bytes);
            level->data = nullptr;
        }
    }
}

long MosaicCanvas::AddTile(const uint8_t* rgba, int tileWidth, int tileHeight,
                           int tileStride, const double* h, float feather) {
    Mask mask;
    mask.feather = feather;
    return accumulateFull(rgba, tileWidth, tileHeight, tileStride, h, mask);
}

long MosaicCanvas::accumulateFull(const uint8_t* rgba, int tileWidth, int tileHeight,
                                  int tileStride, const double* h, const Mask& mask) {
    if (data_ == nullptr || flattened_) return 0;
    if (rgba == nullptr || tileWidth <= 0 || tileHeight <= 0) return 0;

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
            // See Mask for the hand-off the detail band adds to it.
            const float edge = static_cast<float>(std::min(
                std::min(sx, sy),
                std::min(tileWidth - 1.0 - sx, tileHeight - 1.0 - sy)));
            const float weight = mask.weight(edge);
            if (weight <= 0.0f) continue;

            // Four bilinear samples a quarter-pixel either side of the point,
            // averaged -- the tile prefiltered by a half-pixel box, then read
            // bilinearly. Not for softness: for the noise. A projected sample
            // almost never lands on a tile pixel centre, and where it does a
            // plain bilinear read carries that pixel's whole noise, while a
            // quarter-pixel away it averages two or four and carries half or
            // a quarter of it. On a noisy tile that is a periodic modulation
            // of the grain's variance at the beat between the tile's grid and
            // the canvas's -- the field test's "4K TV screen" mesh over every
            // dark tile. Spread over four sub-samples the variance is nearly
            // uniform and the mesh is gone; the detail the mosaic exists to
            // gain is a quarter-pixel wider, which the sharpen already paid.
            float acc[3] = {0.0f, 0.0f, 0.0f};
            for (int k = 0; k < 4; ++k) {
                const double ssx = std::clamp(sx + ((k & 1) ? 0.25 : -0.25), 0.0, tileWidth - 1.0);
                const double ssy = std::clamp(sy + ((k & 2) ? 0.25 : -0.25), 0.0, tileHeight - 1.0);
                const int ix = static_cast<int>(ssx);
                const int iy = static_cast<int>(ssy);
                const float fx = static_cast<float>(ssx) - ix;
                const float fy = static_cast<float>(ssy) - iy;
                const int ix1 = std::min(ix + 1, tileWidth - 1);
                const int iy1 = std::min(iy + 1, tileHeight - 1);
                const uint8_t* p00 = rgba + static_cast<size_t>(iy) * tileStride + ix * 4;
                const uint8_t* p10 = rgba + static_cast<size_t>(iy) * tileStride + ix1 * 4;
                const uint8_t* p01 = rgba + static_cast<size_t>(iy1) * tileStride + ix * 4;
                const uint8_t* p11 = rgba + static_cast<size_t>(iy1) * tileStride + ix1 * 4;
                for (int c = 0; c < 3; ++c) {
                    // Bilinear, because the projected sample almost never lands
                    // on a tile pixel centre. Nearest neighbour here would undo
                    // the resolution the mosaic exists to gain.
                    const float top = p00[c] * (1.0f - fx) + p10[c] * fx;
                    const float bottom = p01[c] * (1.0f - fx) + p11[c] * fx;
                    acc[c] += top * (1.0f - fy) + bottom * fy;
                }
            }

            uint16_t* px = row + static_cast<size_t>(x) * kChannels;
            const float accumulated = decodeWeight(px[3]);
            const float total = accumulated + weight;
            if (total <= 0.0f) continue;

            for (int c = 0; c < 3; ++c) {
                const float sample = acc[c] * (0.25f * 257.0f);

                // Running weighted mean: no separate sum to overflow, and the
                // canvas stays at eight bytes per pixel instead of sixteen.
                const float current = static_cast<float>(px[c]);
                const float updated = current + (sample - current) * (weight / total);
                px[c] = static_cast<uint16_t>(std::clamp(updated, 0.0f, 65535.0f));
            }
            px[3] = encodeWeight(total);
            ++touched;
        }
    }
    return touched;
}

/**
 * Multi-band blending, in the running-mean form the canvas already keeps.
 *
 * ### What a seam is, and what one feather cannot do about it
 *
 * Two tiles never agree exactly. Registration is off by a fraction of a pixel,
 * the lens shades their corners differently, and the exposure lock still lets
 * the sensor's gain and the scene's light differ a little between them. A
 * single feather trades these off against each other: narrow, and the broad
 * difference in level between the tiles shows as a band across the seam; wide,
 * and the fine detail of the two tiles is averaged where they overlap, which
 * doubles every edge by the registration error. There is no width that hides
 * both, because they live at different frequencies.
 *
 * ### The blend
 *
 * The standard answer (Burt & Adelson) is to blend each frequency band of the
 * tiles with a feather proportional to its wavelength. A tile's Laplacian band
 * k is `G_k - up(G_{k+1})`, its Gaussian level less the next coarser one
 * upsampled, and blending that band with mask M_k gives
 *
 *     sum_t (G_k(t) - up(G_{k+1}(t))) M_k(t) / sum_t M_k(t)
 *       = A_k - up(B_{k+1})
 *
 * where A_k is the running mean of G_k under mask M_k and B_{k+1} is the
 * running mean of G_{k+1} under the *finer* mask M_k -- the approximation being
 * that upsampling commutes with the weighted mean, exact wherever the mask is
 * locally flat. So each coarser level keeps two canvases, `wide_` (its own
 * feather) and `narrow_` (the finer feather), and the finished pixel is
 *
 *     out = A_0 - up(B_1) + up(A_1 - up(B_2) + up(A_2))
 *
 * Every canvas is a running weighted mean of 16-bit RGB, which is the form
 * the level-0 canvas has always had, so nothing here can overflow and the
 * whole thing costs an eighth again of the canvas.
 *
 * ### The A/A null
 *
 * With one tile, every canvas at a level holds that tile's own Gaussian level
 * wherever it holds anything, whatever its mask, so `A_k = B_k` and the
 * corrections cancel exactly: the three-band output is the one-band output to
 * the byte. `MosaicBlendDeviceTest` holds it there before trusting the seam
 * measurement, for the reason every speed test here carries a null.
 *
 * ### The pyramid step
 *
 * Four per level, not two. The corrections are for exposure and colour, which
 * are very low frequency, and two coarser levels at a quarter and a sixteenth
 * of the side reach the wavelengths that matter while costing an eighth of the
 * canvas rather than a third.
 */
long MosaicCanvas::AddTileBands(const uint8_t* rgba, int tileWidth, int tileHeight,
                                int tileStride, const double* h, const float* feathers) {
    if (bands_ <= 1 || feathers == nullptr) {
        return AddTile(rgba, tileWidth, tileHeight, tileStride, h, feathers != nullptr ? feathers[0] : 64.0f);
    }
    // The detail band's mask hands off; the coarser bands' masks ramp. The
    // "narrow" canvas of each coarser level carries the finer level's mask,
    // hand-off included, since that is the mask whose blend it corrects.
    const float halfTile = 0.5f * std::min(tileWidth, tileHeight);
    Mask masks[3];
    for (int k = 0; k < 3; ++k) {
        masks[k].feather = feathers[k];
        masks[k].halfTile = halfTile;
    }
    masks[0].power = kHandoffPower;

    const long touched = accumulateFull(rgba, tileWidth, tileHeight, tileStride, h, masks[0]);
    if (touched == 0) return 0;

    double inverse[9];
    if (!invert3x3(h, inverse)) return touched;

    // The tile's Gaussian levels: box averages of four by four, then of those.
    // A box rather than a Gaussian because the level is only ever read back
    // bilinearly at a quarter-pixel scale, where the difference is nothing,
    // and because every tile pixel then counts exactly once.
    const int w1 = tileWidth / kLevelScale;
    const int h1 = tileHeight / kLevelScale;
    if (w1 < 2 || h1 < 2) return touched;
    std::vector<float> g1(static_cast<size_t>(w1) * h1 * 3);
    for (int j = 0; j < h1; ++j) {
        for (int i = 0; i < w1; ++i) {
            float sum[3] = {0, 0, 0};
            for (int dy = 0; dy < kLevelScale; ++dy) {
                const uint8_t* row = rgba + static_cast<size_t>(j * kLevelScale + dy) * tileStride;
                for (int dx = 0; dx < kLevelScale; ++dx) {
                    const uint8_t* px = row + static_cast<size_t>(i * kLevelScale + dx) * 4;
                    sum[0] += px[0]; sum[1] += px[1]; sum[2] += px[2];
                }
            }
            float* out = g1.data() + (static_cast<size_t>(j) * w1 + i) * 3;
            const float inv = 1.0f / (kLevelScale * kLevelScale);
            out[0] = sum[0] * inv; out[1] = sum[1] * inv; out[2] = sum[2] * inv;
        }
    }
    accumulateLevel(wide_[0], tileWidth, tileHeight, inverse, masks[1], g1.data(), w1, h1);
    accumulateLevel(narrow_[0], tileWidth, tileHeight, inverse, masks[0], g1.data(), w1, h1);

    if (bands_ >= 3) {
        const int w2 = w1 / kLevelScale;
        const int h2 = h1 / kLevelScale;
        if (w2 < 2 || h2 < 2) return touched;
        std::vector<float> g2(static_cast<size_t>(w2) * h2 * 3);
        for (int j = 0; j < h2; ++j) {
            for (int i = 0; i < w2; ++i) {
                float sum[3] = {0, 0, 0};
                for (int dy = 0; dy < kLevelScale; ++dy) {
                    for (int dx = 0; dx < kLevelScale; ++dx) {
                        const float* px = g1.data() + (static_cast<size_t>(j * kLevelScale + dy) * w1 + (i * kLevelScale + dx)) * 3;
                        sum[0] += px[0]; sum[1] += px[1]; sum[2] += px[2];
                    }
                }
                float* out = g2.data() + (static_cast<size_t>(j) * w2 + i) * 3;
                const float inv = 1.0f / (kLevelScale * kLevelScale);
                out[0] = sum[0] * inv; out[1] = sum[1] * inv; out[2] = sum[2] * inv;
            }
        }
        accumulateLevel(wide_[1], tileWidth, tileHeight, inverse, masks[2], g2.data(), w2, h2);
        accumulateLevel(narrow_[1], tileWidth, tileHeight, inverse, masks[1], g2.data(), w2, h2);
    }
    return touched;
}

/**
 * Projects one tile's Gaussian level into one coarse canvas as a running mean.
 *
 * Each coarse pixel stands for the full-resolution canvas point at its centre;
 * that point is taken back through the inverse homography into the tile, where
 * the feather is measured in tile pixels exactly as AddTile measures it, and
 * the tile's level is read bilinearly at the matching quarter-scale position.
 */
void MosaicCanvas::accumulateLevel(Level& level, int tileWidth, int tileHeight,
                                   const double* inverse, const Mask& mask,
                                   const float* pyramid, int pyramidWidth, int pyramidHeight) {
    if (level.data == nullptr) return;
    const double s = level.scale;

    // The tile's corners forward through h bound the region, as in AddTile;
    // here the bound is taken by inverting the inverse rather than being
    // handed h, so the two stay consistent.
    double hh[9];
    if (!invert3x3(inverse, hh)) return;
    double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
    const double cx[4] = {0.0, static_cast<double>(tileWidth), 0.0, static_cast<double>(tileWidth)};
    const double cy[4] = {0.0, 0.0, static_cast<double>(tileHeight), static_cast<double>(tileHeight)};
    for (int i = 0; i < 4; ++i) {
        double px, py;
        if (!project(hh, cx[i], cy[i], px, py)) return;
        minX = std::min(minX, px); maxX = std::max(maxX, px);
        minY = std::min(minY, py); maxY = std::max(maxY, py);
    }
    const int x0 = std::max(0, static_cast<int>(std::floor(minX / s)) - 1);
    const int y0 = std::max(0, static_cast<int>(std::floor(minY / s)) - 1);
    const int x1 = std::min(level.width - 1, static_cast<int>(std::ceil(maxX / s)) + 1);
    const int y1 = std::min(level.height - 1, static_cast<int>(std::ceil(maxY / s)) + 1);
    if (x1 < x0 || y1 < y0) return;

    for (int y = y0; y <= y1; ++y) {
        uint16_t* row = level.data + static_cast<size_t>(y) * level.width * kChannels;
        for (int x = x0; x <= x1; ++x) {
            // The full-resolution canvas point at this coarse pixel's centre.
            double sx, sy;
            if (!project(inverse, (x + 0.5) * s, (y + 0.5) * s, sx, sy)) continue;
            sx -= 0.5;
            sy -= 0.5;
            if (sx < 0.0 || sy < 0.0 || sx > tileWidth - 1.0 || sy > tileHeight - 1.0) continue;

            const float edge = static_cast<float>(std::min(
                std::min(sx, sy), std::min(tileWidth - 1.0 - sx, tileHeight - 1.0 - sy)));
            const float weight = mask.weight(edge);
            if (weight <= 0.0f) continue;

            // The level's pixel (i, j) is the box over tile pixels
            // [i*s, (i+1)*s), centred at (i + 0.5) * s.
            const double px = sx / s - 0.5;
            const double py = sy / s - 0.5;
            const int ix = std::clamp(static_cast<int>(std::floor(px)), 0, pyramidWidth - 1);
            const int iy = std::clamp(static_cast<int>(std::floor(py)), 0, pyramidHeight - 1);
            const int ix1 = std::min(ix + 1, pyramidWidth - 1);
            const int iy1 = std::min(iy + 1, pyramidHeight - 1);
            const float fx = std::clamp(static_cast<float>(px - ix), 0.0f, 1.0f);
            const float fy = std::clamp(static_cast<float>(py - iy), 0.0f, 1.0f);
            const float* p00 = pyramid + (static_cast<size_t>(iy) * pyramidWidth + ix) * 3;
            const float* p10 = pyramid + (static_cast<size_t>(iy) * pyramidWidth + ix1) * 3;
            const float* p01 = pyramid + (static_cast<size_t>(iy1) * pyramidWidth + ix) * 3;
            const float* p11 = pyramid + (static_cast<size_t>(iy1) * pyramidWidth + ix1) * 3;

            uint16_t* cell = row + static_cast<size_t>(x) * kChannels;
            const float accumulated = decodeWeight(cell[3]);
            const float total = accumulated + weight;
            if (total <= 0.0f) continue;
            for (int c = 0; c < 3; ++c) {
                const float top = p00[c] * (1.0f - fx) + p10[c] * fx;
                const float bottom = p01[c] * (1.0f - fx) + p11[c] * fx;
                const float sample = (top * (1.0f - fy) + bottom * fy) * 257.0f;
                const float current = static_cast<float>(cell[c]);
                const float updated = current + (sample - current) * (weight / total);
                cell[c] = static_cast<uint16_t>(std::clamp(updated, 0.0f, 65535.0f));
            }
            cell[3] = encodeWeight(total);
        }
    }
}

namespace {

/**
 * Reads two running-mean canvases of one level bilinearly at a full-resolution
 * canvas point, through the same taps.
 *
 * The same taps, and that is the point. Each level's pair hold the same tile
 * content under two masks, and the correction is their difference; a tap that
 * one holds and the other does not -- the narrow mask reaches nothing where
 * the wide one still does, at every tile's edge -- would put a difference into
 * the correction that is about the taps and not about the tiles. Only taps
 * both canvases hold are used, renormalised, so with one tile the two reads
 * are equal to the bit and the correction is exactly nothing. False when no
 * tap is shared.
 */
bool sampleLevelPair(const uint16_t* a, const uint16_t* b, int width, int height, int scale,
                     double x, double y, float* rgbA, float* rgbB) {
    const double px = (x + 0.5) / scale - 0.5;
    const double py = (y + 0.5) / scale - 0.5;
    const int ix = static_cast<int>(std::floor(px));
    const int iy = static_cast<int>(std::floor(py));
    const float fx = static_cast<float>(px - ix);
    const float fy = static_cast<float>(py - iy);
    const float weights[4] = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
    const int xs[4] = {ix, ix + 1, ix, ix + 1};
    const int ys[4] = {iy, iy, iy + 1, iy + 1};
    float sumA[3] = {0, 0, 0};
    float sumB[3] = {0, 0, 0};
    float wsum = 0.0f;
    for (int t = 0; t < 4; ++t) {
        const int cx = std::clamp(xs[t], 0, width - 1);
        const int cy = std::clamp(ys[t], 0, height - 1);
        const size_t i = (static_cast<size_t>(cy) * width + cx) * 4;
        if (a[i + 3] == 0 || b[i + 3] == 0) continue;
        for (int c = 0; c < 3; ++c) {
            sumA[c] += weights[t] * a[i + c];
            sumB[c] += weights[t] * b[i + c];
        }
        wsum += weights[t];
    }
    if (wsum <= 1e-6f) return false;
    for (int c = 0; c < 3; ++c) {
        rgbA[c] = sumA[c] / wsum;
        rgbB[c] = sumB[c] / wsum;
    }
    return true;
}

}  // namespace

/**
 * The noise floor: where two tiles overlap in the shadows at high ISO, part
 * of the finest band is given up for the level beneath it.
 *
 * ### What the cross-hatch is
 *
 * A tile's grain, projected onto the canvas, is not stationary: bilinear
 * resampling carries a pixel's whole variance where a sample lands on a
 * tile pixel and half or a quarter of it between, so the grain's variance
 * beats with the tile's grid against the canvas's. The half-pixel
 * prefilter in accumulateFull takes most of that out, not all. In one tile
 * what remains is a faint mesh; where two tiles overlap, each turned a
 * little against the other by the hand, two meshes at two orientations
 * lie over each other and the eye reads the sum as weave -- the fifth
 * field test's cross-hatching over every dark overlap at ISO 3200. It is
 * an artefact of the detail band alone: the coarser bands are box means of
 * sixteen and two hundred and fifty-six pixels and carry none of it.
 *
 * ### What is done about it
 *
 * The finest band's own contribution -- A_0 less the coarse level beneath
 * it, up(B_1) -- is scaled back by a factor of three things: how high the
 * ISO was (the canvas is told, MosaicCanvas.kt turns it into an
 * attenuation), how much of an overlap the pixel is (the log of the wide
 * level's accumulated weight: one tile at full weight is none, two is
 * one), and how dark the pixel is (the coarse level's luminance, full in
 * the shadows and nothing from the midtones up). Where all three are full
 * half the fine band goes and the shadow is the 4x4 mean plus half its
 * detail, which at ISO 3200 is half its noise. Nowhere else changes: a
 * single tile is composed exactly as before, to the bit, because the
 * overlap factor there is zero and the subtraction is of zero. The blend's
 * A/A null (one tile in one band and three) holds with the floor set, and
 * MosaicBlendDeviceTest holds it there.
 *
 * Not a denoise of the picture: a decision about which of two tiles' noise
 * to keep less of where they meet, keyed on where the eye sees the beat.
 */
void MosaicCanvas::SetNoiseFloor(float detailAttenuation) {
    detailAttenuation_ = std::clamp(detailAttenuation, 0.0f, 1.0f);
}

namespace {

/** How much of an overlap the pixel is, 0 at one tile's full weight and 1 at two, from the weight code. */
inline float overlapAt(const uint16_t* level, int levelWidth, int levelHeight,
                       int scale, int x, int y) {
    if (level == nullptr) return 0.0f;
    const int ix = std::clamp(static_cast<int>((x + 0.5) / scale), 0, levelWidth - 1);
    const int iy = std::clamp(static_cast<int>((y + 0.5) / scale), 0, levelHeight - 1);
    const uint16_t code = level[(static_cast<size_t>(iy) * levelWidth + ix) * 4 + 3];
    if (code == 0) return 0.0f;
    // The code is 2048 per octave about one: the log the factor wants.
    return std::clamp((static_cast<float>(code) - kWeightZeroCode) / kWeightCodesPerOctave, 0.0f, 1.0f);
}

/** Full in the shadows, nothing from the midtones up; [luma] on the 16-bit scale. */
inline float shadowAt(float luma) {
    constexpr float kLow = 48.0f * 257.0f;
    constexpr float kHigh = 144.0f * 257.0f;
    const float t = std::clamp((luma - kLow) / (kHigh - kLow), 0.0f, 1.0f);
    return 1.0f - t * t * (3.0f - 2.0f * t);
}

}  // namespace

bool MosaicCanvas::Compose(int x, int y, float* rgb) const {
    const uint16_t* px = data_ + (static_cast<size_t>(y) * width_ + x) * kChannels;
    if (px[3] == 0) return false;
    rgb[0] = px[0]; rgb[1] = px[1]; rgb[2] = px[2];
    // Each coarser level adds (wide - narrow) upsampled: the band's blend
    // under its own feather less the same band as the finer level blended it.
    // A level that has nothing at this point on either canvas contributes
    // nothing -- exactly what it would with one tile, where the two agree.
    for (int k = 0; k < bands_ - 1; ++k) {
        float wide[3], narrow[3];
        const Level& w = wide_[k];
        const Level& n = narrow_[k];
        if (!sampleLevelPair(w.data, n.data, w.width, w.height, w.scale, x, y, wide, narrow)) continue;
        if (k == 0 && detailAttenuation_ > 0.0f) {
            // The noise floor, see SetNoiseFloor: `narrow` here is up(B_1),
            // the coarse level beneath the finest band under the finest
            // mask, so A_0 - narrow is the finest band's own detail.
            const float overlap = overlapAt(w.data, w.width, w.height, w.scale, x, y);
            if (overlap > 0.0f) {
                const float luma = 0.2126f * narrow[0] + 0.7152f * narrow[1] + 0.0722f * narrow[2];
                const float give = detailAttenuation_ * overlap * shadowAt(luma);
                if (give > 0.0f) {
                    for (int c = 0; c < 3; ++c) rgb[c] -= give * (rgb[c] - narrow[c]);
                }
            }
        }
        for (int c = 0; c < 3; ++c) rgb[c] += wide[c] - narrow[c];
    }
    return true;
}

float MosaicCanvas::Coverage() const {
    if (data_ == nullptr || flattened_) return 0.0f;
    const size_t pixels = static_cast<size_t>(width_) * height_;
    size_t covered = 0;
    for (size_t i = 0; i < pixels; ++i) {
        if (data_[i * kChannels + 3] > 0) ++covered;
    }
    return pixels == 0 ? 0.0f : static_cast<float>(covered) / pixels;
}

void MosaicCanvas::Render(uint8_t* out, int outStride) const {
    if (data_ == nullptr || flattened_ || out == nullptr) return;
    for (int y = 0; y < height_; ++y) {
        uint8_t* dst = out + static_cast<size_t>(y) * outStride;
        for (int x = 0; x < width_; ++x) {
            uint8_t* q = dst + static_cast<size_t>(x) * 4;
            float rgb[3];
            if (!Compose(x, y, rgb)) {
                // Never covered. Transparent rather than black, so a caller can
                // crop to what was actually shot instead of framing black bars.
                q[0] = q[1] = q[2] = 0;
                q[3] = 0;
                continue;
            }
            for (int c = 0; c < 3; ++c) {
                q[c] = static_cast<uint8_t>(std::clamp(static_cast<int>(rgb[c] / 257.0f + 0.5f), 0, 255));
            }
            q[3] = 255;
        }
    }
}

bool MosaicCanvas::CoveredBounds(int* x, int* y, int* w, int* h) const {
    if (data_ == nullptr || flattened_ || x == nullptr || y == nullptr ||
        w == nullptr || h == nullptr) {
        return false;
    }

    int minX = width_;
    int minY = height_;
    int maxX = -1;
    int maxY = -1;
    for (int row = 0; row < height_; ++row) {
        const uint16_t* src = data_ + static_cast<size_t>(row) * width_ * kChannels;
        int rowMin = -1;
        int rowMax = -1;
        for (int col = 0; col < width_; ++col) {
            if (src[static_cast<size_t>(col) * kChannels + 3] == 0) continue;
            if (rowMin < 0) rowMin = col;
            rowMax = col;
        }
        if (rowMin < 0) continue;
        if (row < minY) minY = row;
        maxY = row;
        if (rowMin < minX) minX = rowMin;
        if (rowMax > maxX) maxX = rowMax;
    }

    if (maxX < 0 || maxY < 0) return false;
    *x = minX;
    *y = minY;
    *w = maxX - minX + 1;
    *h = maxY - minY + 1;
    return true;
}

bool MosaicCanvas::CoveredInterior(int* x, int* y, int* w, int* h) const {
    if (data_ == nullptr || flattened_ || x == nullptr || y == nullptr ||
        w == nullptr || h == nullptr) {
        return false;
    }

    // heights[col]: how many covered rows end at the current row in this
    // column. The largest rectangle whose bottom edge is the current row is
    // the largest rectangle under that histogram, found with a stack of
    // rising columns; over every row that is the largest rectangle anywhere.
    std::vector<int> heights(static_cast<size_t>(width_), 0);
    std::vector<int> stack;
    stack.reserve(static_cast<size_t>(width_) + 1);

    long long bestArea = 0;
    int bestX = 0, bestY = 0, bestW = 0, bestH = 0;
    for (int row = 0; row < height_; ++row) {
        const uint16_t* src = data_ + static_cast<size_t>(row) * width_ * kChannels;
        for (int col = 0; col < width_; ++col) {
            heights[col] = src[static_cast<size_t>(col) * kChannels + 3] != 0 ? heights[col] + 1 : 0;
        }
        stack.clear();
        for (int col = 0; col <= width_; ++col) {
            const int current = col == width_ ? 0 : heights[col];
            while (!stack.empty() && heights[stack.back()] >= current) {
                const int top = stack.back();
                stack.pop_back();
                const int height = heights[top];
                const int left = stack.empty() ? 0 : stack.back() + 1;
                const int width = col - left;
                const long long area = static_cast<long long>(width) * height;
                if (area > bestArea) {
                    bestArea = area;
                    bestX = left;
                    bestY = row - height + 1;
                    bestW = width;
                    bestH = height;
                }
            }
            stack.push_back(col);
        }
    }

    if (bestArea <= 0) return false;
    *x = bestX;
    *y = bestY;
    *w = bestW;
    *h = bestH;
    return true;
}

bool MosaicCanvas::Flatten(int x, int y, int w, int h) {
    if (data_ == nullptr || flattened_) return false;
    if (w <= 0 || h <= 0 || x < 0 || y < 0) return false;
    if (x + w > width_ || y + h > height_) return false;

    auto* out = reinterpret_cast<uint8_t*>(data_);
    size_t o = 0;
    for (int row = 0; row < h; ++row) {
        for (int col = 0; col < w; ++col) {
            // The whole pixel is read before anything is written: at the origin
            // of an uncropped canvas the read and write addresses are the same
            // one, and everywhere after it the write trails by four bytes a
            // pixel. That is what makes writing into the source safe. The
            // coarser levels live in their own mappings and are untouched.
            float rgb[3];
            const bool covered = Compose(x + col, y + row, rgb);

            if (!covered) {
                // Nothing reached here. Black rather than transparent, because
                // the destination is JPEG and JPEG has no alpha -- the crop is
                // what keeps these to the ragged edge of a real sweep.
                out[o] = out[o + 1] = out[o + 2] = 0;
                out[o + 3] = 0;
            } else {
                for (int c = 0; c < 3; ++c) {
                    out[o + c] = static_cast<uint8_t>(std::clamp(static_cast<int>(rgb[c] / 257.0f + 0.5f), 0, 255));
                }
                out[o + 3] = 255;
            }
            o += 4;
        }
    }

    flattened_ = true;
    LOGI("flattened %dx%d from %dx%d canvas, %.0f MB in place",
         w, h, width_, height_, o / (1024.0 * 1024.0));
    return true;
}

}  // namespace multiframe

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

#include <android/bitmap.h>
#include <android/data_space.h>
#include <cerrno>

using multiframe::MosaicCanvas;

namespace {
inline MosaicCanvas* canvasOf(jlong handle) {
    return reinterpret_cast<MosaicCanvas*>(handle);
}

/**
 * Sink for the compressor, which hands over the JPEG in pieces as it produces
 * them rather than all at once. Writing each piece straight to the descriptor
 * is what keeps the encoded image out of memory as well as the decoded one.
 */
bool writeToDescriptor(void* context, const void* data, size_t size) {
    const int fd = static_cast<int>(reinterpret_cast<intptr_t>(context));
    const auto* bytes = static_cast<const uint8_t*>(data);
    while (size > 0) {
        const ssize_t written = write(fd, bytes, size);
        if (written < 0) {
            if (errno == EINTR) continue;
            LOGW("write to descriptor failed: %d", errno);
            return false;
        }
        if (written == 0) return false;
        bytes += written;
        size -= static_cast<size_t>(written);
    }
    return true;
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nCreate(
        JNIEnv*, jobject, jint width, jint height, jint bands) {
    return reinterpret_cast<jlong>(MosaicCanvas::Create(width, height, bands));
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nDestroy(JNIEnv*, jobject, jlong handle) {
    delete canvasOf(handle);
}

JNIEXPORT jlong JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nAddTile(
        JNIEnv* env, jobject, jlong handle, jobject tile,
        jdoubleArray jh, jfloatArray jfeathers) {
    MosaicCanvas* canvas = canvasOf(handle);
    if (canvas == nullptr) return 0;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, tile, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 0;

    double h[9];
    env->GetDoubleArrayRegion(jh, 0, 9, h);
    float feathers[3] = {64.0f, 256.0f, 1024.0f};
    const jsize n = jfeathers != nullptr ? env->GetArrayLength(jfeathers) : 0;
    if (n > 0) env->GetFloatArrayRegion(jfeathers, 0, std::min<jsize>(n, 3), feathers);
    for (int k = 1; k < 3; ++k) feathers[k] = std::max(feathers[k], feathers[k - 1]);

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, tile, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return 0;
    }
    const long touched = canvas->AddTileBands(
        static_cast<const uint8_t*>(pixels),
        static_cast<int>(info.width), static_cast<int>(info.height),
        static_cast<int>(info.stride), h, feathers);
    AndroidBitmap_unlockPixels(env, tile);
    return touched;
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nSetNoiseFloor(
        JNIEnv*, jobject, jlong handle, jfloat attenuation) {
    MosaicCanvas* canvas = canvasOf(handle);
    if (canvas != nullptr) canvas->SetNoiseFloor(attenuation);
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

JNIEXPORT jlong JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nCompressTo(
        JNIEnv*, jobject, jlong handle, jint fd, jint quality) {
    MosaicCanvas* canvas = canvasOf(handle);
    if (canvas == nullptr || fd < 0) return -1;

    int x = 0;
    int y = 0;
    int w = 0;
    int h = 0;
    // Zero and negative mean different things to the caller: nothing was ever
    // covered and the canvas is untouched, against the canvas having been
    // collapsed into pixels and the write failing anyway. Only the second one
    // ends the canvas.
    if (!canvas->CoveredBounds(&x, &y, &w, &h)) {
        LOGW("nothing covered, no image to write");
        return 0;
    }
    // The picture inside what was covered, not the box around it: the box
    // frames every corner the tiles' turns left unreached in black.
    int ix = x, iy = y, iw = w, ih = h;
    if (canvas->CoveredInterior(&ix, &iy, &iw, &ih)) {
        LOGI("covered %dx%d at %d,%d; interior %dx%d at %d,%d", w, h, x, y, iw, ih, ix, iy);
        x = ix; y = iy; w = iw; h = ih;
    }
    if (!canvas->Flatten(x, y, w, h)) return -1;

    AndroidBitmapInfo info{};
    info.width = static_cast<uint32_t>(w);
    info.height = static_cast<uint32_t>(h);
    info.stride = static_cast<uint32_t>(w) * 4;
    info.format = ANDROID_BITMAP_FORMAT_RGBA_8888;
    info.flags = 0;

    // Compresses from the canvas's own pages. No Bitmap, so nothing of image
    // size is allocated to write an image the phone could not otherwise hold.
    const int result = AndroidBitmap_compress(
        &info, ADATASPACE_SRGB, canvas->pixels(),
        ANDROID_BITMAP_COMPRESS_FORMAT_JPEG, quality,
        reinterpret_cast<void*>(static_cast<intptr_t>(fd)), writeToDescriptor);

    if (result != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGW("compress failed: %d", result);
        return -1;
    }
    LOGI("wrote %dx%d (%.1f MP) at quality %d", w, h, w * h / 1e6, quality);
    return (static_cast<jlong>(w) << 32) | static_cast<jlong>(h);
}

}  // extern "C"
