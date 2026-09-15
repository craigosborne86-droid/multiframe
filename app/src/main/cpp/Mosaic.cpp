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

namespace {

/**
 * Dinic's maximum flow on a small graph, for the seam cut.
 *
 * Integer capacities; arcs stored in pairs so the reverse arc of `e` is
 * `e ^ 1`. The grid this is run on is a few hundred nodes a side, so the
 * level graph's depth -- and the DFS's recursion -- is bounded by the grid's
 * diameter, and the whole cut is tens of milliseconds on the placement thread.
 */
class MaxFlow {
public:
    explicit MaxFlow(int nodes) : head_(static_cast<size_t>(nodes), -1), level_(static_cast<size_t>(nodes)),
                                  it_(static_cast<size_t>(nodes)) {}

    void addEdge(int u, int v, int64_t cap, int64_t reverseCap) {
        to_.push_back(v); cap_.push_back(cap); next_.push_back(head_[static_cast<size_t>(u)]);
        head_[static_cast<size_t>(u)] = static_cast<int>(to_.size()) - 1;
        to_.push_back(u); cap_.push_back(reverseCap); next_.push_back(head_[static_cast<size_t>(v)]);
        head_[static_cast<size_t>(v)] = static_cast<int>(to_.size()) - 1;
    }

    int64_t run(int s, int t) {
        int64_t flow = 0;
        while (bfs(s, t)) {
            for (size_t i = 0; i < it_.size(); ++i) it_[i] = head_[i];
            for (;;) {
                const int64_t pushed = dfs(s, t, INT64_MAX);
                if (pushed <= 0) break;
                flow += pushed;
            }
        }
        return flow;
    }

    /** After run: which nodes the source still reaches -- the source's side of the cut. */
    void sourceSide(int s, std::vector<uint8_t>& side) const {
        side.assign(head_.size(), 0);
        std::vector<int> queue;
        queue.reserve(head_.size());
        queue.push_back(s);
        side[static_cast<size_t>(s)] = 1;
        for (size_t q = 0; q < queue.size(); ++q) {
            const int u = queue[q];
            for (int e = head_[static_cast<size_t>(u)]; e != -1; e = next_[static_cast<size_t>(e)]) {
                const int v = to_[static_cast<size_t>(e)];
                if (cap_[static_cast<size_t>(e)] > 0 && !side[static_cast<size_t>(v)]) {
                    side[static_cast<size_t>(v)] = 1;
                    queue.push_back(v);
                }
            }
        }
    }

private:
    bool bfs(int s, int t) {
        std::fill(level_.begin(), level_.end(), -1);
        std::vector<int> queue;
        queue.reserve(head_.size());
        queue.push_back(s);
        level_[static_cast<size_t>(s)] = 0;
        for (size_t q = 0; q < queue.size(); ++q) {
            const int u = queue[q];
            for (int e = head_[static_cast<size_t>(u)]; e != -1; e = next_[static_cast<size_t>(e)]) {
                const int v = to_[static_cast<size_t>(e)];
                if (cap_[static_cast<size_t>(e)] > 0 && level_[static_cast<size_t>(v)] < 0) {
                    level_[static_cast<size_t>(v)] = level_[static_cast<size_t>(u)] + 1;
                    queue.push_back(v);
                }
            }
        }
        return level_[static_cast<size_t>(t)] >= 0;
    }

    int64_t dfs(int u, int t, int64_t f) {
        if (u == t) return f;
        for (int& e = it_[static_cast<size_t>(u)]; e != -1; e = next_[static_cast<size_t>(e)]) {
            const int v = to_[static_cast<size_t>(e)];
            if (cap_[static_cast<size_t>(e)] > 0 && level_[static_cast<size_t>(v)] == level_[static_cast<size_t>(u)] + 1) {
                const int64_t d = dfs(v, t, std::min(f, cap_[static_cast<size_t>(e)]));
                if (d > 0) {
                    cap_[static_cast<size_t>(e)] -= d;
                    cap_[static_cast<size_t>(e ^ 1)] += d;
                    return d;
                }
            }
        }
        return 0;
    }

    std::vector<int> head_, next_, to_;
    std::vector<int64_t> cap_;
    std::vector<int> level_, it_;
};

/** Luminance of a tile pixel, bilinear, on the 8-bit scale. */
inline float tileLuma(const uint8_t* rgba, int tileWidth, int tileHeight, int tileStride, double sx, double sy) {
    const double cx = std::clamp(sx, 0.0, tileWidth - 1.0);
    const double cy = std::clamp(sy, 0.0, tileHeight - 1.0);
    const int ix = static_cast<int>(cx);
    const int iy = static_cast<int>(cy);
    const float fx = static_cast<float>(cx) - ix;
    const float fy = static_cast<float>(cy) - iy;
    const int ix1 = std::min(ix + 1, tileWidth - 1);
    const int iy1 = std::min(iy + 1, tileHeight - 1);
    auto luma = [&](int x, int y) {
        const uint8_t* p = rgba + static_cast<size_t>(y) * tileStride + x * 4;
        return 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2];
    };
    const float top = luma(ix, iy) * (1.0f - fx) + luma(ix1, iy) * fx;
    const float bottom = luma(ix, iy1) * (1.0f - fx) + luma(ix1, iy1) * fx;
    return top * (1.0f - fy) + bottom * fy;
}

/** The cut's costs, in units of an eighth of a luma code. */
constexpr int64_t kSeamDiffScale = 8;
/** Every arc costs at least this, so a cut is never free and the shortest wins among equals. */
constexpr int64_t kSeamMinArc = 4;
/**
 * The Voronoi prior's weight: the cost per node of owning a pixel the other
 * tile is deeper at, per tile pixel of the depth difference. Four codes a
 * pixel: a seam displaced from the equidistant line by five nodes -- some
 * sixty tile pixels -- to skirt a texture the tiles place a pixel apart pays
 * about what cutting through the texture would, so it moves that far for a
 * real disagreement and no further for noise. Priced in pixels rather than
 * as a share of the tile, so a small tile is cut by the same rule as a
 * large one.
 */
constexpr float kSeamPriorPerPixel = 4.0f;
/** A tile's own outermost pixels, inside its ramp, are given away where anything else covers them. */
constexpr float kSeamEdgePenalty = 4000.0f;
/** Luma differences past this are all the same disagreement. */
constexpr float kSeamDiffCap = 64.0f;
constexpr int64_t kSeamInfinity = INT64_C(1) << 40;

}  // namespace

/**
 * The seam: which tile owns each pixel where the new one overlaps the canvas.
 *
 * ### What the hand-off was, and what it was not
 *
 * The detail band's mask already made the deeper tile win an overlap by a
 * twelfth power of the two depths -- a soft Voronoi, swapping over in a zone
 * about a fifteenth of the overlap wide. Inside that zone the two tiles were
 * still averaged, and two textures a pixel apart averaged are a double image:
 * a soft band along every seam, exactly where the sixth field test's eye
 * went. And the equidistant line is only where the seam should fall if the
 * two tiles agree there; where a registration is a pixel off, a seam through
 * a wall's brickwork shows and a seam through the sky does not, and the
 * depths know nothing about which is which.
 *
 * ### The cut
 *
 * Over the tile's footprint on the canvas, at one node per eight pixels, a
 * binary labelling -- new tile or canvas -- is chosen by a minimum cut. A
 * node the canvas does not yet cover can only be the new tile's; a node
 * outside the new tile can only be the canvas's; between them every node is
 * free. Cutting between two neighbours costs how much the two tiles disagree
 * there (`kSeamDiffScale` times the mean luma difference at both, over
 * sixteen of each node's pixels, capped, plus
 * `kSeamMinArc` so the shortest seam wins among equals), so the seam runs
 * where the tiles agree -- along a sky, an edge that registered, a shadow --
 * and not across a texture they place a pixel apart. Giving a node to the
 * shallower tile costs the depth difference times `kSeamPriorPerPixel`: the
 * Voronoi line is where the seam falls when nothing in the picture argues,
 * and the deeper tile is the sharper one, being nearer its lens's centre. A
 * node inside the new tile's own ramp -- its outermost pixels, where the
 * registration is least trusted -- is given away wherever anything else
 * covers it.
 *
 * The canvas's own depth at a node is read back from the weight it stores:
 * the detail mask's hand-off wrote `(depth / halfTile)^12`, so the twelfth
 * root of the weight is the depth of whichever tile owns the pixel now.
 *
 * ### What the seam does to the blend
 *
 * The detail band and the coarse level beneath it are then accumulated by
 * ownership rather than by weight: where the new tile owns a pixel its value
 * replaces the canvas's and its own weight is stored; where the canvas owns
 * it the new tile contributes nothing; across `Seam::kFeatherPx` at the
 * boundary the two are mixed, which is the micro-feather. Every pixel of the
 * fine band comes from one tile. The coarser bands keep their wide feathers,
 * which is what spreads a step in exposure across the overlap; a seam is a
 * decision about texture, not about level. With one tile there is no overlap,
 * no free node and no seam, and the blend is exactly what it was, to the byte
 * -- `MosaicBlendDeviceTest` holds it there.
 */
bool MosaicCanvas::cutSeam(const uint8_t* rgba, int tileWidth, int tileHeight, int tileStride,
                           const double* h, const double* inverse, const Mask& detail, Seam& out) const {
    out.present = false;
    if (data_ == nullptr || rgba == nullptr || detail.power <= 0.0f) return false;

    double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
    const double cornersX[4] = {0.0, static_cast<double>(tileWidth), 0.0, static_cast<double>(tileWidth)};
    const double cornersY[4] = {0.0, 0.0, static_cast<double>(tileHeight), static_cast<double>(tileHeight)};
    for (int i = 0; i < 4; ++i) {
        double px, py;
        if (!project(h, cornersX[i], cornersY[i], px, py)) return false;
        minX = std::min(minX, px); maxX = std::max(maxX, px);
        minY = std::min(minY, py); maxY = std::max(maxY, py);
    }
    const int x0 = std::max(0, static_cast<int>(std::floor(minX)));
    const int y0 = std::max(0, static_cast<int>(std::floor(minY)));
    const int x1 = std::min(width_ - 1, static_cast<int>(std::ceil(maxX)));
    const int y1 = std::min(height_ - 1, static_cast<int>(std::ceil(maxY)));
    if (x1 < x0 || y1 < y0) return false;

    const int S = Seam::kScale;
    const int cols = (x1 - x0) / S + 1;
    const int rows = (y1 - y0) / S + 1;
    const size_t count = static_cast<size_t>(cols) * rows;

    enum : uint8_t { kOutside = 0, kMustNew = 1, kFree = 2 };
    std::vector<uint8_t> kind(count, kOutside);
    std::vector<float> costNew(count, 0.0f);   // the cost of the new tile owning the node
    std::vector<float> costOld(count, 0.0f);   // the cost of the canvas keeping it
    std::vector<float> diff(count, 0.0f);
    size_t freeNodes = 0;

    const float halfTile = std::max(detail.halfTile, 1.0f);
    const float invPower = 1.0f / detail.power;
    for (int gy = 0; gy < rows; ++gy) {
        for (int gx = 0; gx < cols; ++gx) {
            const size_t n = static_cast<size_t>(gy) * cols + gx;
            const double cx = x0 + gx * S + S * 0.5;
            const double cy = y0 + gy * S + S * 0.5;
            double sx, sy;
            if (!project(inverse, cx, cy, sx, sy)) continue;
            sx -= 0.5;
            sy -= 0.5;
            if (sx < 0.0 || sy < 0.0 || sx > tileWidth - 1.0 || sy > tileHeight - 1.0) continue;
            const int px = std::clamp(static_cast<int>(cx), 0, width_ - 1);
            const int py = std::clamp(static_cast<int>(cy), 0, height_ - 1);
            const uint16_t* cell = data_ + (static_cast<size_t>(py) * width_ + px) * kChannels;
            if (cell[3] == 0) {
                kind[n] = kMustNew;
                continue;
            }
            kind[n] = kFree;
            ++freeNodes;

            // The prior: how much deeper one tile is than the other, in tile
            // pixels, priced per node.
            const float depthNew = static_cast<float>(std::min(std::min(sx, sy), std::min(tileWidth - 1.0 - sx, tileHeight - 1.0 - sy)));
            const float depthOld = halfTile * std::pow(std::max(decodeWeight(cell[3]), 1e-6f), invPower);
            const float deeper = std::clamp(depthOld - depthNew, -halfTile, halfTile);
            if (deeper > 0.0f) costNew[n] += kSeamPriorPerPixel * deeper;
            else costOld[n] += kSeamPriorPerPixel * -deeper;
            // The new tile's own ramp: its edge is nobody's texture.
            if (depthNew < detail.feather) {
                costNew[n] += kSeamEdgePenalty * (1.0f - depthNew / std::max(detail.feather, 1.0f));
            }

            // How much the two disagree here: the tile's luma against the
            // canvas's at sixteen of the node's pixels -- every other pixel
            // each way, read at the pixel's centre exactly as accumulateFull
            // reads it, so a texture at the pixel scale is measured and not
            // blurred away between two pixels -- so a pixel of
            // misregistration reads as disagreement rather than as chance.
            float sum = 0.0f;
            int taps = 0;
            for (int ky = 1; ky < S; ky += 2) {
                for (int kx = 1; kx < S; kx += 2) {
                    const int qx = x0 + gx * S + kx;
                    const int qy = y0 + gy * S + ky;
                    if (qx < 0 || qy < 0 || qx >= width_ || qy >= height_) continue;
                    double tx, ty;
                    if (!project(inverse, qx + 0.5, qy + 0.5, tx, ty)) continue;
                    tx -= 0.5;
                    ty -= 0.5;
                    if (tx < 0.0 || ty < 0.0 || tx > tileWidth - 1.0 || ty > tileHeight - 1.0) continue;
                    const uint16_t* q = data_ + (static_cast<size_t>(qy) * width_ + qx) * kChannels;
                    if (q[3] == 0) continue;
                    const float canvasLuma = (0.2126f * q[0] + 0.7152f * q[1] + 0.0722f * q[2]) / 257.0f;
                    sum += std::min(std::fabs(tileLuma(rgba, tileWidth, tileHeight, tileStride, tx, ty) - canvasLuma), kSeamDiffCap);
                    ++taps;
                }
            }
            diff[n] = taps > 0 ? sum / taps : 0.0f;
        }
    }
    if (freeNodes == 0) return false;

    // The graph: the source is the new tile, the sink the canvas. An arc from
    // the source is cut when the node goes to the canvas, so it carries the
    // cost of the canvas keeping the node; an arc to the sink is cut when
    // the node goes to the new tile, and carries that cost.
    const int source = static_cast<int>(count);
    const int sink = static_cast<int>(count) + 1;
    MaxFlow flow(static_cast<int>(count) + 2);
    for (size_t n = 0; n < count; ++n) {
        switch (kind[n]) {
            case kMustNew:
                flow.addEdge(source, static_cast<int>(n), kSeamInfinity, 0);
                break;
            case kFree:
                if (costOld[n] > 0.0f) flow.addEdge(source, static_cast<int>(n), static_cast<int64_t>(costOld[n]), 0);
                if (costNew[n] > 0.0f) flow.addEdge(static_cast<int>(n), sink, static_cast<int64_t>(costNew[n]), 0);
                break;
            default:
                flow.addEdge(static_cast<int>(n), sink, kSeamInfinity, 0);
                break;
        }
    }
    for (int gy = 0; gy < rows; ++gy) {
        for (int gx = 0; gx < cols; ++gx) {
            const size_t n = static_cast<size_t>(gy) * cols + gx;
            if (kind[n] == kOutside) continue;
            auto link = [&](size_t m) {
                if (kind[m] == kOutside) return;
                if (kind[n] != kFree && kind[m] != kFree) return;
                const int64_t c = kSeamMinArc + static_cast<int64_t>(kSeamDiffScale * (diff[n] + diff[m]));
                flow.addEdge(static_cast<int>(n), static_cast<int>(m), c, c);
            };
            if (gx + 1 < cols) link(n + 1);
            if (gy + 1 < rows) link(n + static_cast<size_t>(cols));
        }
    }
    flow.run(source, sink);

    std::vector<uint8_t> side;
    flow.sourceSide(source, side);
    out.x0 = x0;
    out.y0 = y0;
    out.cols = cols;
    out.rows = rows;
    out.own.assign(count, 0.0f);
    size_t taken = 0;
    for (size_t n = 0; n < count; ++n) {
        const bool owns = kind[n] == kMustNew || (kind[n] == kFree && side[n]);
        out.own[n] = owns ? 1.0f : 0.0f;
        if (kind[n] == kFree && owns) ++taken;
    }
    out.present = true;
    LOGI("seam: %zu of %zu overlapping nodes to the new tile, grid %dx%d", taken, freeNodes, cols, rows);
    return true;
}

long MosaicCanvas::AddTile(const uint8_t* rgba, int tileWidth, int tileHeight,
                           int tileStride, const double* h, float feather) {
    Mask mask;
    mask.feather = feather;
    return accumulateFull(rgba, tileWidth, tileHeight, tileStride, h, mask);
}

long MosaicCanvas::accumulateFull(const uint8_t* rgba, int tileWidth, int tileHeight,
                                  int tileStride, const double* h, const Mask& mask,
                                  const Seam* seam) {
    if (data_ == nullptr || flattened_) return 0;
    if (seam != nullptr && !seam->present) seam = nullptr;
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

            // By weight, or by ownership. With no seam -- one band, or a
            // tile that overlaps nothing -- the running weighted mean. With a
            // seam, and something already here, the share the cut gave this
            // tile: all, nothing, or the micro-feather's fraction, and the
            // stored weight becomes this tile's own where it owns the pixel,
            // so the next tile's cut reads this tile's depth here.
            float share = weight / total;
            float stored = total;
            if (seam != nullptr && accumulated > 0.0f) {
                share = seam->shareAt(x + 0.5f, y + 0.5f);
                if (share <= 0.0f) continue;
                stored = share >= 0.5f ? weight : accumulated;
            }

            for (int c = 0; c < 3; ++c) {
                const float sample = acc[c] * (0.25f * 257.0f);

                // Running weighted mean: no separate sum to overflow, and the
                // canvas stays at eight bytes per pixel instead of sixteen.
                const float current = static_cast<float>(px[c]);
                const float updated = current + (sample - current) * share;
                px[c] = static_cast<uint16_t>(std::clamp(updated, 0.0f, 65535.0f));
            }
            px[3] = encodeWeight(stored);
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

    double inverse[9];
    if (!invert3x3(h, inverse)) return 0;

    // Where this tile takes over from the canvas in the detail band, decided
    // before anything is written: the cut reads the canvas as it stands.
    Seam seam;
    cutSeam(rgba, tileWidth, tileHeight, tileStride, h, inverse, masks[0], seam);

    const long touched = accumulateFull(rgba, tileWidth, tileHeight, tileStride, h, masks[0], &seam);
    if (touched == 0) return 0;

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
    // The narrow canvas of the first coarser level carries the detail mask,
    // seam included: it is the level the detail band's correction is made
    // against, and the two have to agree about who owns a pixel.
    accumulateLevel(narrow_[0], tileWidth, tileHeight, inverse, masks[0], g1.data(), w1, h1, &seam);

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
                                   const float* pyramid, int pyramidWidth, int pyramidHeight,
                                   const Seam* seam) {
    if (level.data == nullptr) return;
    if (seam != nullptr && !seam->present) seam = nullptr;
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
            // By ownership where the seam decides, as in accumulateFull.
            float share = weight / total;
            float stored = total;
            if (seam != nullptr && accumulated > 0.0f) {
                share = seam->shareAt(static_cast<float>((x + 0.5) * s), static_cast<float>((y + 0.5) * s));
                if (share <= 0.0f) continue;
                stored = share >= 0.5f ? weight : accumulated;
            }
            for (int c = 0; c < 3; ++c) {
                const float top = p00[c] * (1.0f - fx) + p10[c] * fx;
                const float bottom = p01[c] * (1.0f - fx) + p11[c] * fx;
                const float sample = (top * (1.0f - fy) + bottom * fy) * 257.0f;
                const float current = static_cast<float>(cell[c]);
                const float updated = current + (sample - current) * share;
                cell[c] = static_cast<uint16_t>(std::clamp(updated, 0.0f, 65535.0f));
            }
            cell[3] = encodeWeight(stored);
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
        for (int c = 0; c < 3; ++c) rgb[c] += wide[c] - narrow[c];
    }
    return true;
}

namespace {

/**
 * A luma plane over a rectangle of the canvas at a coarse scale, with a mask
 * of which cells hold anything: the two things the refinement compares.
 */
struct LumaGrid {
    int cols = 0, rows = 0;
    std::vector<float> luma;
    std::vector<uint8_t> valid;
    float at(int x, int y) const { return luma[static_cast<size_t>(y) * cols + x]; }
    bool ok(int x, int y) const {
        return x >= 0 && y >= 0 && x < cols && y < rows && valid[static_cast<size_t>(y) * cols + x];
    }
};

/** The tile's luma at a tile position, bilinear; false outside the tile. */
inline bool tileLumaAt(const uint8_t* rgba, int tileWidth, int tileHeight, int tileStride,
                       double sx, double sy, float& out) {
    if (sx < 0.0 || sy < 0.0 || sx > tileWidth - 1.0 || sy > tileHeight - 1.0) return false;
    const int ix = static_cast<int>(sx);
    const int iy = static_cast<int>(sy);
    const float fx = static_cast<float>(sx) - ix;
    const float fy = static_cast<float>(sy) - iy;
    const int ix1 = std::min(ix + 1, tileWidth - 1);
    const int iy1 = std::min(iy + 1, tileHeight - 1);
    auto lumaOf = [&](int x, int y) {
        const uint8_t* p = rgba + static_cast<size_t>(y) * tileStride + x * 4;
        return (p[0] + 2.0f * p[1] + p[2]) * 0.25f;
    };
    const float top = lumaOf(ix, iy) * (1.0f - fx) + lumaOf(ix1, iy) * fx;
    const float bottom = lumaOf(ix, iy1) * (1.0f - fx) + lumaOf(ix1, iy1) * fx;
    out = top * (1.0f - fy) + bottom * fy;
    return true;
}

/**
 * Zero-mean normalised cross-correlation of the tile grid against the canvas
 * grid displaced by (dx, dy) cells, over the cells both hold, restricted to
 * the block [bx0, bx1) x [by0, by1) of the tile grid. Returns -2 where too
 * few cells overlap to mean anything; the count is written to [cells].
 */
float nccAt(const LumaGrid& canvas, const LumaGrid& tile, int dx, int dy,
            int bx0, int by0, int bx1, int by1, int minCells, int* cells) {
    double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
    int n = 0;
    for (int y = by0; y < by1; ++y) {
        for (int x = bx0; x < bx1; ++x) {
            if (!tile.ok(x, y) || !canvas.ok(x + dx, y + dy)) continue;
            const double a = tile.at(x, y);
            const double b = canvas.at(x + dx, y + dy);
            sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b;
            ++n;
        }
    }
    if (cells != nullptr) *cells = n;
    if (n < minCells) return -2.0f;
    const double va = saa - sa * sa / n;
    const double vb = sbb - sb * sb / n;
    if (va <= 1e-6 * n || vb <= 1e-6 * n) return -2.0f;
    return static_cast<float>((sab - sa * sb / n) / std::sqrt(va * vb));
}

/** The sub-cell vertex of a parabola through three correlations, clamped to half a cell. */
inline float parabolaPeak(float before, float at, float after) {
    const float denom = before - 2.0f * at + after;
    if (denom >= -1e-9f) return 0.0f;
    return std::clamp((before - after) / (2.0f * denom), -0.5f, 0.5f);
}

/**
 * Builds the two grids over the canvas rectangle [x0, y0] .. [x1, y1] at
 * [scale] canvas pixels a cell: the canvas's own luma where it holds
 * anything, and the tile's luma read through [inverse] at each cell centre.
 * A canvas cell is valid where at least half its samples are covered; the
 * canvas is sampled at a stride inside the cell, since a cell of thirty-two
 * pixels does not need all thousand of them to know its mean.
 */
void buildGrids(const uint16_t* data, int canvasWidth, int canvasHeight, int channels,
                int x0, int y0, int x1, int y1, int scale,
                const uint8_t* rgba, int tileWidth, int tileHeight, int tileStride,
                const double* inverse, LumaGrid& canvasGrid, LumaGrid& tileGrid) {
    const int cols = (x1 - x0) / scale;
    const int rows = (y1 - y0) / scale;
    canvasGrid.cols = tileGrid.cols = cols;
    canvasGrid.rows = tileGrid.rows = rows;
    canvasGrid.luma.assign(static_cast<size_t>(cols) * rows, 0.0f);
    canvasGrid.valid.assign(static_cast<size_t>(cols) * rows, 0);
    tileGrid.luma.assign(static_cast<size_t>(cols) * rows, 0.0f);
    tileGrid.valid.assign(static_cast<size_t>(cols) * rows, 0);
    const int stride = std::max(1, scale / 4);
    for (int gy = 0; gy < rows; ++gy) {
        for (int gx = 0; gx < cols; ++gx) {
            const int cx0 = x0 + gx * scale;
            const int cy0 = y0 + gy * scale;
            double sum = 0.0;
            int covered = 0, samples = 0;
            for (int y = cy0; y < cy0 + scale; y += stride) {
                for (int x = cx0; x < cx0 + scale; x += stride) {
                    ++samples;
                    const uint16_t* px = data + (static_cast<size_t>(y) * canvasWidth + x) * channels;
                    if (px[3] == 0) continue;
                    sum += (px[0] + 2.0 * px[1] + px[2]) * (0.25 / 257.0);
                    ++covered;
                }
            }
            const size_t i = static_cast<size_t>(gy) * cols + gx;
            if (covered * 2 >= samples && covered > 0) {
                canvasGrid.luma[i] = static_cast<float>(sum / covered);
                canvasGrid.valid[i] = 1;
            }
            // The tile over the same cell, through the inverse placement,
            // at the same sample points: a box mean like the canvas's, so
            // the two grids carry the same frequencies. Read at the cell's
            // centre alone, the tile's fine texture aliased into the grid
            // and correlated with anything.
            double tileSum = 0.0;
            int tileHits = 0;
            for (int y = cy0; y < cy0 + scale; y += stride) {
                for (int x = cx0; x < cx0 + scale; x += stride) {
                    double sx, sy;
                    if (!project(inverse, x + 0.5, y + 0.5, sx, sy)) continue;
                    float l;
                    if (!tileLumaAt(rgba, tileWidth, tileHeight, tileStride, sx - 0.5, sy - 0.5, l)) continue;
                    tileSum += l;
                    ++tileHits;
                }
            }
            if (tileHits * 2 >= samples && tileHits > 0) {
                tileGrid.luma[i] = static_cast<float>(tileSum / tileHits);
                tileGrid.valid[i] = 1;
            }
        }
    }
}

/** The refinement's scales and reach. See the note on refinement below. */
constexpr int kRefineCoarseScale = 32;
constexpr int kRefineFineScale = 4;
/** In coarse cells: a quarter of a 4080-wide tile, the most the attitude has ever been out by. */
constexpr int kRefineCoarseReach = 32;
/** In fine cells, around the coarse answer: two coarse cells' worth of slack. */
constexpr int kRefineFineReach = 8;
constexpr int kRefineBlocks = 4;
constexpr int kRefineMinCells = 64;
constexpr float kRefineMinNcc = 0.45f;
/** A block's peak has to stand this far above the correlation two cells either side of it. */
constexpr float kRefinePeakDrop = 0.01f;
/** Chance-level correlations taken off an offset's score, in units of 1/sqrt(cells). */
constexpr float kRefineChanceLevels = 2.0f;

}  // namespace

/**
 * ### Refinement: the tile is placed where the canvas says it is
 *
 * A tile arrives with a placement from one of two tiers -- a homography
 * fitted to corners on a 320-pixel proxy, or the attitude's rotation --
 * and either can be a hundred pixels out on a 4080-pixel tile: the proxy is
 * a thirteenth of the scale, so one proxy pixel of fit error is thirteen on
 * the canvas, and the attitude drifts and is read a frame from the exposure.
 * The eighth field test's tower stood three times in one sky, two of them
 * translucent: two tiles placed a few hundred pixels apart, the coarse bands
 * averaging both. No blend hides a misplacement of that size. What fixes it
 * is the measurement the canvas itself offers: where the new tile overlaps
 * tiles already drawn, the pixels say exactly how far off the placement is.
 *
 * So before a tile is drawn it is matched against the canvas in the overlap,
 * coarse to fine. A luma grid of the overlap at thirty-two canvas pixels a
 * cell is searched for the whole-tile translation out to a quarter of a tile
 * by normalised cross-correlation -- exposure and shading differences between
 * tiles cancel in it. Then the overlap is cut into blocks at four pixels a
 * cell, each searched around that answer for its own offset and refined to a
 * fraction of a cell, and each block that matched with a clear peak is
 * returned as one correspondence: where the placement put its centre, where
 * the canvas found it. The caller fits the correction -- a translation from
 * one block, a homography from many -- and composes it onto the placement.
 * Sky returns no blocks (no texture, no correlation), a tower returns its
 * blocks, and a tile that overlaps nothing returns none and is placed as it
 * came.
 */
int MosaicCanvas::RefineTile(const uint8_t* rgba, int tileWidth, int tileHeight, int tileStride,
                             const double* h, RefineMatch* out, int maxMatches) const {
    if (data_ == nullptr || flattened_ || rgba == nullptr || out == nullptr || maxMatches <= 0) return 0;
    if (tileWidth <= 0 || tileHeight <= 0) return 0;
    double inverse[9];
    if (!invert3x3(h, inverse)) return 0;

    // The tile's footprint on the canvas, widened by the coarse reach so the
    // search can look past it, clamped to the canvas.
    double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
    const double cornersX[4] = {0.0, static_cast<double>(tileWidth), 0.0, static_cast<double>(tileWidth)};
    const double cornersY[4] = {0.0, 0.0, static_cast<double>(tileHeight), static_cast<double>(tileHeight)};
    for (int i = 0; i < 4; ++i) {
        double px, py;
        if (!project(h, cornersX[i], cornersY[i], px, py)) return 0;
        minX = std::min(minX, px); maxX = std::max(maxX, px);
        minY = std::min(minY, py); maxY = std::max(maxY, py);
    }
    const int reachPx = kRefineCoarseReach * kRefineCoarseScale;
    const int x0 = std::max(0, static_cast<int>(std::floor(minX)) - reachPx);
    const int y0 = std::max(0, static_cast<int>(std::floor(minY)) - reachPx);
    const int x1 = std::min(width_, static_cast<int>(std::ceil(maxX)) + reachPx);
    const int y1 = std::min(height_, static_cast<int>(std::ceil(maxY)) + reachPx);
    if (x1 - x0 < kRefineCoarseScale * 4 || y1 - y0 < kRefineCoarseScale * 4) return 0;

    // Coarse: the whole tile's translation.
    LumaGrid canvasCoarse, tileCoarse;
    buildGrids(data_, width_, height_, kChannels, x0, y0, x1, y1, kRefineCoarseScale,
               rgba, tileWidth, tileHeight, tileStride, inverse, canvasCoarse, tileCoarse);
    int overlap = 0;
    nccAt(canvasCoarse, tileCoarse, 0, 0, 0, 0, tileCoarse.cols, tileCoarse.rows, 1, &overlap);
    if (overlap < kRefineMinCells / 4) return 0;

    // The coarse search. A displacement that leaves a corner of the tile
    // on a corner of the canvas correlates a handful of cells with anything
    // -- the first version put a tile a quarter of a tile off at the edge
    // of its search that way -- so each offset's correlation is judged
    // against its own chance level: the correlation of n unrelated cells
    // is about 1/sqrt(n) either way, and twice that is taken off. An
    // offset sharing few cells has to be very right to win; the true one,
    // sharing hundreds, loses a tenth.
    float bestNcc = -2.0f;
    float bestScore = -2.0f;
    int bestDx = 0, bestDy = 0;
    int mostCells = 0;
    for (int dy = -kRefineCoarseReach; dy <= kRefineCoarseReach; ++dy) {
        for (int dx = -kRefineCoarseReach; dx <= kRefineCoarseReach; ++dx) {
            int n = 0;
            const float ncc = nccAt(canvasCoarse, tileCoarse, dx, dy, 0, 0,
                                    tileCoarse.cols, tileCoarse.rows, kRefineMinCells / 2, &n);
            mostCells = std::max(mostCells, n);
            if (ncc < -1.0f) continue;
            const float score = ncc - kRefineChanceLevels / std::sqrt(static_cast<float>(n));
            if (score > bestScore) { bestScore = score; bestNcc = ncc; bestDx = dx; bestDy = dy; }
        }
    }
    LOGI("refine: coarse grid %dx%d, overlap %d cells at rest, most %d, best ncc %.3f at (%d, %d) cells",
         tileCoarse.cols, tileCoarse.rows, overlap, mostCells, bestNcc, bestDx, bestDy);
    if (bestNcc < kRefineMinNcc) return 0;
    const int coarseDx = bestDx * kRefineCoarseScale;
    const int coarseDy = bestDy * kRefineCoarseScale;

    // Fine: blocks of the overlap, each around the coarse answer.
    LumaGrid canvasFine, tileFine;
    buildGrids(data_, width_, height_, kChannels, x0, y0, x1, y1, kRefineFineScale,
               rgba, tileWidth, tileHeight, tileStride, inverse, canvasFine, tileFine);
    const int fdx = coarseDx / kRefineFineScale;
    const int fdy = coarseDy / kRefineFineScale;

    // The overlap's bounds in fine cells: tile cells whose displaced canvas cell holds something.
    int ox0 = tileFine.cols, oy0 = tileFine.rows, ox1 = -1, oy1 = -1;
    for (int y = 0; y < tileFine.rows; ++y) {
        for (int x = 0; x < tileFine.cols; ++x) {
            if (!tileFine.ok(x, y) || !canvasFine.ok(x + fdx, y + fdy)) continue;
            ox0 = std::min(ox0, x); ox1 = std::max(ox1, x);
            oy0 = std::min(oy0, y); oy1 = std::max(oy1, y);
        }
    }
    if (ox1 < ox0 || oy1 < oy0) return 0;

    int written = 0;
    const int blockW = std::max(1, (ox1 - ox0 + 1) / kRefineBlocks);
    const int blockH = std::max(1, (oy1 - oy0 + 1) / kRefineBlocks);
    for (int by = 0; by < kRefineBlocks && written < maxMatches; ++by) {
        for (int bx = 0; bx < kRefineBlocks && written < maxMatches; ++bx) {
            const int bx0 = ox0 + bx * blockW;
            const int by0 = oy0 + by * blockH;
            const int bx1 = (bx == kRefineBlocks - 1) ? ox1 + 1 : bx0 + blockW;
            const int by1 = (by == kRefineBlocks - 1) ? oy1 + 1 : by0 + blockH;

            float best = -2.0f;
            int bdx = fdx, bdy = fdy;
            for (int dy = fdy - kRefineFineReach; dy <= fdy + kRefineFineReach; ++dy) {
                for (int dx = fdx - kRefineFineReach; dx <= fdx + kRefineFineReach; ++dx) {
                    const float ncc = nccAt(canvasFine, tileFine, dx, dy, bx0, by0, bx1, by1,
                                            kRefineMinCells, nullptr);
                    if (ncc > best) { best = ncc; bdx = dx; bdy = dy; }
                }
            }
            if (best < kRefineMinNcc) continue;
            // A peak at the edge of the search is not a peak.
            if (std::abs(bdx - fdx) >= kRefineFineReach || std::abs(bdy - fdy) >= kRefineFineReach) continue;

            // The peak has to be a peak in both axes: a block on a plain
            // edge correlates equally well anywhere along it, and its
            // offset along the edge means nothing. Judged two cells out,
            // because a true offset halfway between two cells correlates
            // equally at both of them, and that tie is the answer, not a
            // ridge.
            const float left = nccAt(canvasFine, tileFine, bdx - 1, bdy, bx0, by0, bx1, by1, kRefineMinCells, nullptr);
            const float right = nccAt(canvasFine, tileFine, bdx + 1, bdy, bx0, by0, bx1, by1, kRefineMinCells, nullptr);
            const float up = nccAt(canvasFine, tileFine, bdx, bdy - 1, bx0, by0, bx1, by1, kRefineMinCells, nullptr);
            const float down = nccAt(canvasFine, tileFine, bdx, bdy + 1, bx0, by0, bx1, by1, kRefineMinCells, nullptr);
            const float left2 = nccAt(canvasFine, tileFine, bdx - 2, bdy, bx0, by0, bx1, by1, kRefineMinCells, nullptr);
            const float right2 = nccAt(canvasFine, tileFine, bdx + 2, bdy, bx0, by0, bx1, by1, kRefineMinCells, nullptr);
            const float up2 = nccAt(canvasFine, tileFine, bdx, bdy - 2, bx0, by0, bx1, by1, kRefineMinCells, nullptr);
            const float down2 = nccAt(canvasFine, tileFine, bdx, bdy + 2, bx0, by0, bx1, by1, kRefineMinCells, nullptr);
            const float dropX = best - std::max(left2, right2);
            const float dropY = best - std::max(up2, down2);
            if (dropX < kRefinePeakDrop || dropY < kRefinePeakDrop) continue;
            const float subX = parabolaPeak(left, best, right);
            const float subY = parabolaPeak(up, best, down);

            // The block's centre on the canvas as placed, and as found: the
            // canvas cell (x + dx, y + dy) holds what the tile's cell (x, y)
            // shows, so the tile is really dx cells further along.
            const float fromX = x0 + (bx0 + bx1) * 0.5f * kRefineFineScale;
            const float fromY = y0 + (by0 + by1) * 0.5f * kRefineFineScale;
            RefineMatch& m = out[written++];
            m.fromX = fromX;
            m.fromY = fromY;
            m.toX = fromX + (bdx + subX) * kRefineFineScale;
            m.toY = fromY + (bdy + subY) * kRefineFineScale;
            m.ncc = best;
        }
    }
    return written;
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

/**
 * The refinement, over a bitmap: writes up to `out.length / 5` matches as
 * (fromX, fromY, toX, toY, ncc) runs into [out] and returns how many.
 */
JNIEXPORT jint JNICALL
Java_dev_multiframe_camera_pipeline_MosaicCanvas_nRefineTile(
        JNIEnv* env, jobject, jlong handle, jobject bitmap, jdoubleArray transform, jfloatArray out) {
    auto* canvas = reinterpret_cast<multiframe::MosaicCanvas*>(handle);
    if (canvas == nullptr || bitmap == nullptr || transform == nullptr || out == nullptr) return 0;
    if (env->GetArrayLength(transform) < 9) return 0;
    const jsize capacity = env->GetArrayLength(out) / 5;
    if (capacity <= 0) return 0;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 0;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;

    double h[9];
    env->GetDoubleArrayRegion(transform, 0, 9, h);
    std::vector<multiframe::MosaicCanvas::RefineMatch> matches(static_cast<size_t>(capacity));
    const int n = canvas->RefineTile(static_cast<const uint8_t*>(pixels),
                                     static_cast<int>(info.width), static_cast<int>(info.height),
                                     static_cast<int>(info.stride), h, matches.data(), capacity);
    AndroidBitmap_unlockPixels(env, bitmap);

    std::vector<float> packed(static_cast<size_t>(n) * 5);
    for (int i = 0; i < n; ++i) {
        packed[i * 5 + 0] = matches[i].fromX;
        packed[i * 5 + 1] = matches[i].fromY;
        packed[i * 5 + 2] = matches[i].toX;
        packed[i * 5 + 3] = matches[i].toY;
        packed[i * 5 + 4] = matches[i].ncc;
    }
    if (n > 0) env->SetFloatArrayRegion(out, 0, n * 5, packed.data());
    return n;
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
