// The develop for an image that is already full colour.
//
// The sub-pixel canvas arrives as 16-bit RGB in sensor counts at twice the
// sensor's resolution: black still in it, shading and white balance not yet
// applied, and no colour filter array to demosaic because every cell has all
// three channels measured. So the chain in REFERENCE.md §3.1 runs from black
// level to the colour matrix with the demosaic step removed, and everything
// after the matrix is the same arithmetic as the CFA develop, from the same
// header. What this file adds is the arrangement: fifty megapixels that cannot
// be a Bitmap, developed in strips into three-byte RGB and handed to the strip
// encoder, with the canvas's own pages given back as they are consumed.

#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

#include "Tone.h"

namespace multiframe {
namespace linear {

struct Params {
    /** Black level per output channel, in raw counts. Green is the mean of its two sites. */
    float black[3] = {0.0f, 0.0f, 0.0f};
    /** White level in raw counts; the range is white less the lowest black. */
    int white = 1023;
    /** White balance gain per output channel. */
    float wb[3] = {1.0f, 1.0f, 1.0f};
    /** Row-major 3x3, white-balanced camera RGB to linear sRGB. */
    float matrix[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    /**
     * Lens shading, three channels per cell, row-major, or null. The grid spans
     * the *sensor*, so a canvas cell reads it at half its own coordinates.
     */
    const float* shading = nullptr;
    int shadingColumns = 0;
    int shadingRows = 0;

    /** exposureGain <= 0 means measure it from the canvas, as the CFA develop does. */
    tone::ToneParams tone;
    float autoPercentile = 0.92f;
    float autoTarget = 0.40f;
    /**
     * The meter's own gain -- two to the guard's pull undone -- or zero for
     * none; the measured gain is held within half a stop under it and three
     * quarters over (DevelopParams.meteredGain, RawDeveloper.boundedGain).
     */
    float meteredGain = 0.0f;

    /** The local tone operator's strength, REFERENCE §3.5; zero is off. */
    float localTone = 0.0f;
    /** Its shadow lift: stops per stop of range beyond the held range, and the ceiling. Zero is off. */
    float shadowLift = 0.0f;
    float shadowLiftMax = 3.0f;

    /** Capture sharpening; amount <= 0 disables. Mirrors Sharpen.kt. */
    float sharpenAmount = 0.45f;
    float sharpenThreshold = 3.5f;
    float sharpenMaxShift = 20.0f;

    /** Chrominance smoothing before the sharpen; amount <= 0 disables. Mirrors ChromaSmooth.kt. */
    int chromaRadius = 2;
    float chromaSigma = 12.0f;
    float chromaAmount = 1.0f;
};

/** Stage timings and the exposure that was used, for the log and the tests. */
struct Stats {
    float exposureGain = 1.0f;
    int64_t exposureMicros = 0;
    int64_t developMicros = 0;
    int64_t sharpenMicros = 0;
    int64_t encodeMicros = 0;
};

/**
 * The exposure multiplier the canvas asks for: the green channel's bright
 * percentile brought to the target, exactly as the CFA develop measures it from
 * the green sites.
 */
float autoExposure(const uint16_t* rgb16, int width, int height, const Params& p);

/**
 * Develops [rgb16] into eight-bit RGB at [out], which must hold width*height*3
 * bytes with rows [outStride] bytes apart. Sharpened in place afterwards.
 *
 * When [release] is set the canvas's pages are returned to the kernel strip by
 * strip as they are read, so the input is destroyed and the peak footprint is
 * the canvas alone rather than the canvas plus its output. The pointer must
 * then be to page-aligned anonymous memory, which the canvas's mapping is.
 */
bool developToRgb8(uint16_t* rgb16, int width, int height, const Params& p,
                   uint8_t* out, size_t outStride, bool release, Stats* stats);

/**
 * The whole path: develop, sharpen, encode, and hand the JPEG to [sink] in
 * pieces. The eight-bit image lives in a mapping this function owns and
 * unmaps before returning; the canvas is consumed as above.
 */
bool developToJpeg(uint16_t* rgb16, int width, int height, const Params& p, int quality,
                   const std::function<bool(const unsigned char*, size_t)>& sink, Stats* stats);

}  // namespace linear
}  // namespace multiframe
