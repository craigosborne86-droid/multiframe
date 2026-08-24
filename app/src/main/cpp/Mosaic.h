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

#include <cstdint>
#include <cstddef>

namespace multiframe {

/**
 * A plane of accumulated colour with per-pixel coverage.
 *
 * Each pixel holds a 16-bit RGB running mean and the total weight that has
 * contributed to it. Zero weight means no tile has covered that pixel yet,
 * which is how gaps in a sweep are detected rather than rendered as black.
 */
class MosaicCanvas {
public:
    static MosaicCanvas* Create(int width, int height);
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

private:
    MosaicCanvas() = default;

    /** Three colour channels plus the accumulated weight, all 16-bit. */
    static constexpr int kChannels = 4;

    uint16_t* data_ = nullptr;
    size_t bytes_ = 0;
    int width_ = 0;
    int height_ = 0;
};

}  // namespace multiframe
