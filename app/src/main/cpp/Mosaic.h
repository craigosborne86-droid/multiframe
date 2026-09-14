// Mosaic compositing canvas.
//
// Projects overlapping frames onto one plane and blends them into a single
// large image. The canvas cannot live on the managed heap for the same reason
// the raw ring cannot: an 80 megapixel canvas is 610 MB against a 256 MB cap,
// and the uncapped pairing on this device would be 2 GB.
//
// Storage is a running weighted mean rather than separate sums. Accumulating
// sum-of-values and sum-of-weights would need floats to avoid overflow, at
// 16 bytes per pixel; keeping the mean and the accumulated weight instead
// costs 8, which is the difference between a canvas that fits and one that
// does not. Updating a mean incrementally is exact enough over the few dozen
// tiles a sweep produces.

#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstddef>

namespace multiframe {

/**
 * How much a tile pixel at [edge] tile pixels from the tile's nearest edge
 * counts.
 *
 * Two parts. The ramp, `min(1, edge / feather)^2`, takes the outermost pixels
 * to nothing so a registration error at the tile's border is hidden; every
 * band has one. The hand-off, `(edge / halfTile)^power`, is what the detail
 * band alone adds: it makes the tile that is *deeper* at a pixel win it by a
 * wide margin, which is a soft Voronoi -- the tile whose centre is nearest owns
 * the pixel, and the two swap over in a narrow zone about the equidistant line
 * rather than averaging across the whole overlap. Averaging is what a ramp
 * alone does everywhere both ramps have reached one, and two textures a pixel
 * apart averaged are a double image. A power of zero is the ramp alone.
 */
struct Mask {
    float feather = 64.0f;
    float power = 0.0f;
    float halfTile = 1.0f;

    inline float weight(float edge) const {
        float w = std::clamp(edge / std::max(feather, 1.0f), 0.0f, 1.0f);
        w *= w;
        if (power > 0.0f && w > 0.0f) {
            // Floored at the smallest weight the canvas stores, so a pixel
            // the ramp has reached is covered however shallow it sits: the
            // hand-off decides who wins an overlap, never whether a pixel a
            // single tile reached is a pixel at all.
            w *= std::max(std::pow(std::clamp(edge / halfTile, 0.0f, 1.0f), power), 1.0f / 65536.0f);
        }
        return w;
    }
};

/**
 * A plane of accumulated colour with per-pixel coverage.
 *
 * Each pixel holds a 16-bit RGB running mean and the total weight that has
 * contributed to it. Zero weight means no tile has covered that pixel yet,
 * which is how gaps in a sweep are detected rather than rendered as black.
 */
class MosaicCanvas {
public:
    /**
     * [bands] is how many frequency bands the blend keeps apart: 1 is the
     * feathered running mean alone, 3 adds two coarser levels (see AddTile).
     */
    static MosaicCanvas* Create(int width, int height, int bands = 3);
    ~MosaicCanvas();

    MosaicCanvas(const MosaicCanvas&) = delete;
    MosaicCanvas& operator=(const MosaicCanvas&) = delete;

    int width() const { return width_; }
    int height() const { return height_; }
    size_t bytes() const { return bytes_; }

    /**
     * Projects one tile onto the canvas through [h], which maps tile
     * coordinates to canvas coordinates.
     *
     * [feather] is the width in pixels over which a tile's contribution ramps
     * up from its edge. Without it every tile boundary is a visible line: two
     * frames never agree exactly, because of lens shading, slight exposure
     * differences and registration error of a fraction of a pixel, so a hard
     * edge between them shows. Ramping the weight makes the transition
     * gradual enough that the eye cannot find it.
     *
     * Returns the number of canvas pixels this tile contributed to.
     */
    long AddTile(const uint8_t* rgba, int tileWidth, int tileHeight, int tileStride,
                 const double* h, float feather);

    /**
     * The same, blending in three frequency bands.
     *
     * [feathers] is one ramp width per band, narrowest first, in tile pixels:
     * the finest detail crosses a seam over `feathers[0]`, and the broad
     * differences between tiles -- exposure, colour -- are spread over
     * `feathers[2]`. See the note on the canvas levels in Mosaic.cpp. With a
     * canvas created for one band this is AddTile with `feathers[0]`.
     */
    long AddTileBands(const uint8_t* rgba, int tileWidth, int tileHeight, int tileStride,
                      const double* h, const float* feathers);

    int bands() const { return bands_; }

    /** Fraction of the canvas that any tile has covered. */
    float Coverage() const;

    /**
     * Writes the finished image as RGBA into [out].
     *
     * Uncovered pixels come out transparent, so a caller can crop to the
     * covered region rather than being handed black bars from an incomplete
     * sweep.
     */
    void Render(uint8_t* out, int outStride) const;

    /**
     * The bounding box of every pixel any tile reached.
     *
     * A sweep is never a neat rectangle: the canvas is sized for the whole
     * plan, and a hand-held pan covers a ragged part of it. Writing the full
     * canvas therefore frames the result in black, which is exactly what the
     * transparent uncovered pixels were meant to avoid -- and JPEG has no
     * alpha to carry them. Returns false when nothing was covered.
     */
    bool CoveredBounds(int* x, int* y, int* w, int* h) const;

    /**
     * Collapses the region [x,y,w,h] into packed RGBA8888 at the start of the
     * canvas's own memory, consuming the accumulator.
     *
     * The result is four bytes a pixel against the accumulator's eight, so it
     * fits inside the mapping that already exists and the write pointer always
     * trails the read pointer. That is the entire point: an 80 MP result
     * otherwise needs a 320 MB buffer alongside the 610 MB canvas, and this
     * phone had 1.4 GB free.
     *
     * Nothing can be composited or measured afterwards -- the weights are gone.
     * One shot, which is what saving is.
     */
    bool Flatten(int x, int y, int w, int h);

    /** The flattened pixels. Meaningful only after a successful Flatten. */
    const void* pixels() const { return data_; }

    /** Whether the accumulator has been consumed. */
    bool flattened() const { return flattened_; }

private:
    MosaicCanvas() = default;

    /** Three colour channels plus the accumulated weight, all 16-bit. */
    static constexpr int kChannels = 4;

    /** The pyramid's step between levels: each coarser level is this much smaller per axis. */
    static constexpr int kLevelScale = 4;

    /**
     * A running-mean canvas at a coarser scale. Two per coarser level: one
     * blended with that level's own wide feather, one with the finer level's,
     * whose difference is what the band correction is made of.
     */
    struct Level {
        uint16_t* data = nullptr;
        size_t bytes = 0;
        int width = 0;
        int height = 0;
        int scale = 1;
    };

    /** Composes the blended value of one full-resolution pixel into [rgb], scaled 0..65535. */
    bool Compose(int x, int y, float* rgb) const;

    long accumulateFull(const uint8_t* rgba, int tileWidth, int tileHeight, int tileStride,
                        const double* h, const Mask& mask);
    void accumulateLevel(Level& level, int tileWidth, int tileHeight,
                         const double* inverse, const Mask& mask,
                         const float* pyramid, int pyramidWidth, int pyramidHeight);

    uint16_t* data_ = nullptr;
    size_t bytes_ = 0;
    bool flattened_ = false;
    int width_ = 0;
    int height_ = 0;
    int bands_ = 1;
    // wide_[k] and narrow_[k] for coarser level k+1, k = 0 .. bands_-2.
    Level wide_[2];
    Level narrow_[2];
};

}  // namespace multiframe
