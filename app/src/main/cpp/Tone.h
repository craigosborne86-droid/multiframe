// The tone chain and the helpers around it, shared between the develops.
//
// These lived in bayer_merge.cpp for as long as there was one develop. There
// are now two: the CFA develop that demosaics a merged frame, and the linear
// develop that takes the sub-pixel canvas -- already full colour at twice the
// resolution -- and needs everything from the colour matrix onward but nothing
// before it. Both must render the same tone from the same parameters, because
// a 2x photograph and its ordinary sibling of the same scene should differ in
// resolution and in nothing else. One copy of the curve is how that is kept
// true; the parity tests hold each develop to ToneCurve.kt, and this header is
// what makes them the same arithmetic.
//
// Everything here is header-only. The functions are small and inline, the
// tables are built once per develop on the stack, and the one global -- the
// gamma table -- is an inline variable so every translation unit sees the
// single instance.

#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <functional>
#include <thread>
#include <vector>

namespace multiframe {
namespace tone {

/**
 * Runs [body] over horizontal bands of the image, in parallel.
 *
 * Work is claimed dynamically rather than divided up front, which matters on a
 * phone. Android CPUs are heterogeneous: a Pixel has a few fast cores and
 * several slow ones, and a fast core can be several times the speed of a slow
 * one. Splitting the image into one equal band per thread means the pass cannot
 * finish until the slowest core has ground through its share, while the fast
 * cores sit idle having finished theirs -- so the whole image runs at the speed
 * of the slowest core, whatever the others could have done.
 *
 * Cutting the image into far more bands than there are threads and letting each
 * thread take the next one as it becomes free lets the fast cores simply do
 * more of them. The bands still have to be large enough that claiming one costs
 * nothing next to doing it.
 */
/** Microseconds on a monotonic clock, for stage timing. */
inline int64_t nowMicros() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
}

inline void parallelBands(int height, const std::function<void(int, int)>& body) {
    const unsigned hw = std::thread::hardware_concurrency();
    int threads = static_cast<int>(std::max(2u, hw));
    threads = std::min(threads, std::max(1, height));

    // Several bands per thread, so a fast core can take more of them, but not
    // so many that the atomic claim is a measurable cost.
    constexpr int kBandsPerThread = 6;
    constexpr int kMinBandRows = 8;
    int bandRows = std::max(kMinBandRows,
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
    // The calling thread takes bands too rather than waiting on the others.
    worker();
    for (auto& th : pool) th.join();
}

constexpr int kGammaLutSize = 4096;
inline float gGammaLut[kGammaLutSize];
inline bool gGammaReady = false;

inline void ensureGammaLut() {
    if (gGammaReady) return;
    for (int i = 0; i < kGammaLutSize; ++i) {
        float v = static_cast<float>(i) / (kGammaLutSize - 1);
        float e = (v <= 0.0031308f) ? v * 12.92f
                                    : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
        gGammaLut[i] = e;
    }
    gGammaReady = true;
}

inline float encodeSrgb(float v) {
    v = std::clamp(v, 0.0f, 1.0f);
    return gGammaLut[static_cast<int>(v * (kGammaLutSize - 1))];
}

inline uint8_t toByte(float v) {
    return static_cast<uint8_t>(std::clamp(v * 255.0f + 0.5f, 0.0f, 255.0f));
}

/**
 * The highlight roll-off.
 *
 * `RealExp` is false only for the ablation harness. It swaps the exponential
 * for a reciprocal -- a saturating curve of the same shape, and not free, since
 * it is still a divide -- keeping the branch and everything around it. So what
 * that variant saves is not the cost of the call but the cost of the call *less
 * the cost of replacing it*, which is the number worth having: it is what a
 * fast approximation could hope to recover. The false instantiation renders a
 * wrong picture on purpose and is never reachable from a capture.
 */
template <bool RealExp>
inline float shoulderCurveParts(float x, float knee) {
    if (x <= 0.0f) return 0.0f;
    if (x <= knee) return x;
    float headroom = 1.0f - knee;
    if (headroom <= 0.0f) return knee;
    const float z = (x - knee) / headroom;
    if constexpr (RealExp) {
        return knee + headroom * (1.0f - std::exp(-z));
    } else {
        return knee + headroom * (1.0f - 1.0f / (1.0f + z));
    }
}

inline float shoulderCurve(float x, float knee) {
    return shoulderCurveParts<true>(x, knee);
}

/**
 * Rendering curve. Mirrors ToneCurve.kt, which is where the tests are; the
 * instrumentation parity test pins the two together.
 */
/** One pixel's three reconstructed channels, on the way out of the demosaic. */
struct Rgb {
    float r, g, b;
};

struct ToneParams {
    float exposureGain = 1.0f;
    float knee = 0.70f;
    float contrast = 0.30f;
    float desatStrength = 1.0f;
    float desatStart = 1.0f;
    float blackPoint = 0.012f;
};


/**
 * The highlight roll-off as a table on the scene peak.
 *
 * `shoulderCurve(p, knee) / p` is a pure function of one float, evaluated once
 * per pixel behind `if (p > knee)`, and the ablation harness put that branch at
 * 1.44 to 1.6x of the whole `demosaic+tone` pass -- the largest single item in a
 * capture. Not the exponential inside it, which is worth 1.15 to 1.21x on its
 * own: the whole body, since the same pass over a scene with 1% of the frame
 * above the knee could not be separated from one that skipped the roll-off
 * entirely. So the body becomes a lookup and the branch that guards it stays --
 * see `renderLinearParts` for what happened when it did not.
 *
 * **This is the first approximation in the render, and the difference from
 * DisplayLut is the point.** That one collapses the same kind of chain into a
 * table and is *exact*, because `encodeSrgb` had already quantised its input to
 * the very index the table is addressed by; its comment says that is the only
 * reason it was worth doing. There is no such argument here. The scene peak is a
 * continuous float and a table on it is a rounding of the curve, so what can be
 * offered instead of exactness is a measured bound:
 * `ToneAblationDeviceTest` renders twelve and a half megapixels both ways and
 * requires every byte to agree.
 *
 * Interpolated between entries, which pays for the table being small: at a
 * thousand cells across the useful range a truncated lookup would be nowhere
 * near good enough, and interpolating makes the error second order in the cell
 * instead of first. Written without it first -- on the argument that at 4096 cells two neighbours differ by well
 * under a display code, so a lerp would buy accuracy the 8-bit output could not
 * carry. Measured, that was wrong: nearest-entry put 1.8% of the bytes of a
 * twelve-megapixel render one code away from the arithmetic, and pushed the
 * native-versus-Kotlin parity from two codes to three.
 *
 * The reason is `DisplayLut` on the other side. It bins the *linear* value into
 * 4096, so a scale that is off by a few ten-thousandths moves a bright pixel
 * across a bin boundary, and the truncation error of a table on a curve is
 * first order in the cell -- around 6e-4 here. Interpolating makes it second
 * order, some 2e-7, which is three orders below a bin. One extra load off the
 * same cache line and one fma, for a render that is identical byte for byte.
 *
 * Above `pMax` the exponential has saturated -- `knee + headroom` to within a
 * float's last place -- and the curve is a plain reciprocal. That is computed
 * rather than tabulated, because it is unbounded above and because a specular
 * highlight is rare enough that the branch predicts.
 */
struct ShoulderLut {
    // A thousand entries, not four thousand, and indexed from the knee rather
    // than from zero. Both are about cache rather than accuracy: the caller only
    // reaches this behind `if (p > knee)`, so everything below the knee was a
    // twelfth of the table that could never be read, and the four thousand
    // floats that remained were 16 KB of L1 held for a lookup that a dark frame
    // does a hundred thousand times against twelve million pixels of plane. Its
    // own test caught that -- the table cost such a frame 5 to 13%, 46 rounds of
    // 64 against it -- and 4 KB is what fixed it.
    static constexpr int kSize = 2048;
    float scale[kSize];
    float knee = 0.0f;
    float pMax = 1.0f;
    float indexScale = 0.0f;

    float operator()(float p) const {
        if (p >= pMax) return shoulderCurve(p, knee) / p;
        const float x = std::max((p - knee) * indexScale, 0.0f);
        const int i = static_cast<int>(x);
        // p < pMax puts x below kSize - 1, so i + 1 is always in bounds.
        return scale[i] + (scale[i + 1] - scale[i]) * (x - static_cast<float>(i));
    }
};

inline void buildShoulderLut(ShoulderLut& lut, const ToneParams& t) {
    const float headroom = 1.0f - t.knee;
    // Sixteen headrooms past the knee, where exp(-16) is 1.1e-7 and the curve
    // has reached its limit to within the last place of a float. Floored at 1
    // so that a degenerate knee cannot produce an empty or inverted table; the
    // reciprocal branch stays exact whatever the parameters are, so the only
    // cost of getting this wrong would be speed.
    lut.knee = t.knee;
    lut.pMax = std::max(t.knee + 16.0f * std::max(headroom, 0.0f), t.knee + 1.0f);
    lut.indexScale = static_cast<float>(ShoulderLut::kSize - 1) / (lut.pMax - t.knee);
    for (int i = 0; i < ShoulderLut::kSize; ++i) {
        const float p = t.knee + static_cast<float>(i) / lut.indexScale;
        lut.scale[i] = (p > t.knee && p > 0.0f) ? shoulderCurve(p, t.knee) / p : 1.0f;
    }
}

/**
 * Hue-preserving roll-off, then highlight desaturation, in linear light.
 *
 * A template only so that the ablation harness can leave one of the two halves
 * out, or reach the roll-off the slow way; `renderLinear` below is the
 * instantiation that ships, and ToneCurve.kt is the reference the develop parity
 * test holds it to -- within a tolerance now rather than exactly, because of the
 * table above.
 */
template <bool Shoulder, bool Desat, bool RealExp = true, bool Tabulated = true,
          bool RealScale = true>
inline void renderLinearParts(float& r, float& g, float& b, const ToneParams& t,
                              const ShoulderLut& shoulder) {
    r = std::max(r, 0.0f);
    g = std::max(g, 0.0f);
    b = std::max(b, 0.0f);

    // Measured before compression: afterwards a bright sky and the sun both
    // sit at 1 and nothing can tell them apart. The peak drives the roll-off;
    // the luminance drives the desaturation -- a saturated red three times
    // over in its own channel is not three times overexposed, and keying the
    // desaturation on the peak rendered it pink. Mirrors ToneCurve.kt.
    const float scenePeak = std::max(r, std::max(g, b));
    const float sceneLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b;

    if constexpr (Shoulder) {
        // The branch stays. The first version of this dropped it -- the table
        // answers 1 below the knee, so it can be read unconditionally -- on the
        // reasoning that the ablations had shown the cost to be the branch being
        // taken. Measured, that was reading it backwards: what is expensive is
        // the *body*, and skipping it is why a frame with 1% above the knee had
        // been indistinguishable from one that skipped the roll-off entirely.
        // Branchless cost such a frame 0.89x, 14 rounds of 16 against it.
        if (scenePeak > t.knee) {
            // RealScale is false only for the harness. It keeps the branch and
            // the three multiplies and drops the lookup entirely, which is what
            // separates the cost of *reading* the curve from the cost of
            // applying it. Renders a wrong picture on purpose.
            const float scale = !RealScale
                ? 0.9f
                : (Tabulated ? shoulder(scenePeak)
                             : shoulderCurveParts<RealExp>(scenePeak, t.knee) / scenePeak);
            r *= scale; g *= scale; b *= scale;
        }
    }

    if constexpr (Desat) {
        if (t.desatStrength > 0.0f && sceneLuma > t.desatStart) {
            const float k = 1.0f - t.desatStart / sceneLuma;
            const float mix = std::clamp(k * k * t.desatStrength, 0.0f, 1.0f);
            const float level = std::max(r, std::max(g, b));
            r += (level - r) * mix;
            g += (level - g) * mix;
            b += (level - b) * mix;
        }
    }
}

inline void renderLinear(float& r, float& g, float& b, const ToneParams& t,
                         const ShoulderLut& shoulder) {
    renderLinearParts<true, true>(r, g, b, t, shoulder);
}

/**
 * Bilinearly interpolated lens shading gain.
 *
 * The grid is coarse -- around 17 by 13 cells across 4080 pixels, so each cell
 * covers 240 -- and sampling it as nearest neighbour would put a visible
 * brightness step across the sky every 240 pixels. Mirrors ShadingMap.kt.
 */
inline float shadingGain(const float* gains, int columns, int rows,
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
        return gains[(static_cast<size_t>(r) * columns + c) * 4 + channel];
    };
    const float top = cell(x0, y0) * (1.0f - tx) + cell(x1, y0) * tx;
    const float bottom = cell(x0, y1) * (1.0f - tx) + cell(x1, y1) * tx;
    return top * (1.0f - ty) + bottom * ty;
}

/**
 * White balance gain per CFA site.
 *
 * The two greens are separate sites and the profile carries a gain for each, so
 * which one a site takes depends on whether its row is even -- and sites 0 and 1
 * are the even rows by construction, which is why no y is needed here.
 */
inline void balanceBySite(const int* cfa, const float* gains, float* wb) {
    for (int site = 0; site < 4; ++site) {
        const int c = cfa[site];
        wb[site] = (c == 0) ? gains[0] : (c == 2) ? gains[3]
                                                  : (site < 2 ? gains[1] : gains[2]);
    }
}


/** Smootherstep blended with identity: monotonic for any amount in 0..1. */
inline float sCurve(float x, float amount) {
    if (amount <= 0.0f) return x;
    const float c = std::clamp(x, 0.0f, 1.0f);
    const float s = c * c * c * (c * (c * 6.0f - 15.0f) + 10.0f);
    return c + amount * (s - c);
}

/**
 * The whole display chain as one table.
 *
 * `toByte(renderDisplay(encodeSrgb(v)))` is a pure function of a single float,
 * and `encodeSrgb` already quantises its input to one of [kGammaLutSize]
 * indices. So the gamma lookup, black point, S-curve, scale and round collapse
 * into a single byte lookup on that same index.
 *
 * This is exact rather than approximate, which is the only reason it is worth
 * doing: the entry is built by evaluating the identical functions at the
 * identical point the old path would have evaluated them at, so every input
 * produces the byte it produced before. It cannot drift from the Kotlin the
 * parity test holds it to, because it has not changed the arithmetic -- only
 * how many times it is performed. Four thousand evaluations instead of thirty
 * seven million.
 */
struct DisplayLut {
    uint8_t bytes[kGammaLutSize];

    uint8_t operator()(float v) const {
        v = std::clamp(v, 0.0f, 1.0f);
        return bytes[static_cast<int>(v * (kGammaLutSize - 1))];
    }
};

/** Black point then S-curve, applied after the gamma encode. */
inline float renderDisplay(float encoded, const ToneParams& t) {
    float v = encoded;
    if (t.blackPoint > 0.0f) {
        v = std::max((v - t.blackPoint) / (1.0f - t.blackPoint), 0.0f);
    }
    return std::clamp(sCurve(v, t.contrast), 0.0f, 1.0f);
}

inline void buildDisplayLut(DisplayLut& lut, const ToneParams& t) {
    for (int i = 0; i < kGammaLutSize; ++i) {
        lut.bytes[i] = toByte(renderDisplay(gGammaLut[i], t));
    }
}

/**
 * A bilateral grid over log luminance: the local tone operator of
 * REFERENCE.md §3.5.
 *
 * ### What it does
 *
 * A global curve compresses the whole picture's range with one function of
 * brightness, so a bright window and the dim room around it are pulled toward
 * each other everywhere at once, and the room's own contrast goes with it. A
 * local operator asks, for each pixel, how bright its *neighbourhood* is, and
 * compresses that instead -- the base -- while leaving the pixel's difference
 * from its base, the detail, exactly as it was. The window comes down, the
 * room comes up, and the texture in each is untouched.
 *
 * ### Why a grid, and why the third axis
 *
 * "Neighbourhood brightness" as a spatial blur produces halos: at the edge of
 * the window the blur mixes window and wall, the base is wrong on both sides,
 * and a bright fringe appears along the wall. The bilateral grid (Chen, Paris
 * and Durand, 2007) adds luminance as a third axis, so a wall pixel's base is
 * averaged only with pixels near it in space *and in brightness* -- the wall,
 * not the window. The grid is coarse in all three axes, which is what makes
 * it cheap: a cell is sixteen pixels square and three quarters of a stop
 * deep, the whole grid for a twelve-megapixel frame is a few hundred
 * kilobytes, and slicing it is one trilinear read per pixel.
 *
 * ### The arithmetic
 *
 * Splat: each pixel adds its log2 luminance and a count to the cell at
 * (x / 16, y / 16, bin(log2 L)). Blur: a separable [1 4 6 4 1] over all three
 * axes, once. Slice: trilinear at the pixel's own position and luminance gives
 * the base b. The operator scales the pixel by `2 ^ (-strength * (b - pivot))`,
 * where the pivot is the count-weighted mean base of the grid -- so the base
 * layer's range about its mean shrinks by the strength and the detail layer,
 * `log2 L - b`, is carried through unchanged. Everything downstream -- the
 * shoulder, the desaturation, the display curve -- then sees a picture whose
 * range has been brought in locally rather than globally.
 *
 * Mirrors BilateralGrid.kt, which is the reference. Constants here and there
 * are the same numbers.
 */
struct BilateralGrid {
    static constexpr int kCell = 16;
    static constexpr int kBins = 16;
    static constexpr float kLogMin = -10.0f;
    static constexpr float kLogMax = 2.0f;
    static constexpr float kMinLuminance = 1e-6f;

    int cellsX = 0;
    int cellsY = 0;
    std::vector<float> sum;
    std::vector<float> count;
    float pivot = 0.0f;
    bool ready = false;

    void init(int width, int height) {
        cellsX = (width + kCell - 1) / kCell;
        cellsY = (height + kCell - 1) / kCell;
        sum.assign(static_cast<size_t>(cellsX) * cellsY * kBins, 0.0f);
        count.assign(sum.size(), 0.0f);
        ready = false;
    }

    static inline float logOf(float luminance) {
        return std::log2(std::max(luminance, kMinLuminance));
    }

    static inline float binOf(float logL) {
        return std::clamp((logL - kLogMin) / (kLogMax - kLogMin) * (kBins - 1), 0.0f, static_cast<float>(kBins - 1));
    }

    inline size_t index(int cx, int cy, int b) const {
        return (static_cast<size_t>(cy) * cellsX + cx) * kBins + b;
    }

    /** Adds one luminance at a pixel position. Not thread-safe; splat on one thread or per band with a merge. */
    inline void splat(int x, int y, float luminance) {
        const int cx = std::min(x / kCell, cellsX - 1);
        const int cy = std::min(y / kCell, cellsY - 1);
        const float logL = logOf(luminance);
        const int b = static_cast<int>(binOf(logL) + 0.5f);
        const size_t i = index(cx, cy, std::min(b, kBins - 1));
        sum[i] += logL;
        count[i] += 1.0f;
    }

    /** Blurs the grid in all three axes and fixes the pivot. */
    void finish() {
        for (int axis = 0; axis < 3; ++axis) blurAxis(axis);
        double ws = 0.0, wc = 0.0;
        for (size_t i = 0; i < sum.size(); ++i) {
            ws += sum[i];
            wc += count[i];
        }
        pivot = wc > 0.0 ? static_cast<float>(ws / wc) : 0.0f;
        ready = true;
    }

    /** One separable pass of [1 4 6 4 1] / 16 along one axis, clamped at the ends. */
    void blurAxis(int axis) {
        static const float kTaps[5] = {1.0f / 16, 4.0f / 16, 6.0f / 16, 4.0f / 16, 1.0f / 16};
        std::vector<float> s2(sum.size());
        std::vector<float> c2(count.size());
        for (int cy = 0; cy < cellsY; ++cy) {
            for (int cx = 0; cx < cellsX; ++cx) {
                for (int b = 0; b < kBins; ++b) {
                    float s = 0.0f, c = 0.0f;
                    for (int t = -2; t <= 2; ++t) {
                        const int xx = axis == 0 ? std::clamp(cx + t, 0, cellsX - 1) : cx;
                        const int yy = axis == 1 ? std::clamp(cy + t, 0, cellsY - 1) : cy;
                        const int bb = axis == 2 ? std::clamp(b + t, 0, kBins - 1) : b;
                        const size_t i = index(xx, yy, bb);
                        s += kTaps[t + 2] * sum[i];
                        c += kTaps[t + 2] * count[i];
                    }
                    s2[index(cx, cy, b)] = s;
                    c2[index(cx, cy, b)] = c;
                }
            }
        }
        sum.swap(s2);
        count.swap(c2);
    }

    /** The base at a pixel: the neighbourhood's mean log luminance near this luminance. */
    inline float slice(int x, int y, float logL) const {
        const float fx = std::clamp((x + 0.5f) / kCell - 0.5f, 0.0f, static_cast<float>(cellsX - 1));
        const float fy = std::clamp((y + 0.5f) / kCell - 0.5f, 0.0f, static_cast<float>(cellsY - 1));
        const float fb = binOf(logL);
        const int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy), b0 = static_cast<int>(fb);
        const int x1 = std::min(x0 + 1, cellsX - 1), y1 = std::min(y0 + 1, cellsY - 1), b1 = std::min(b0 + 1, kBins - 1);
        const float tx = fx - x0, ty = fy - y0, tb = fb - b0;
        float s = 0.0f, c = 0.0f;
        const float wts[8] = {
            (1 - tx) * (1 - ty) * (1 - tb), tx * (1 - ty) * (1 - tb), (1 - tx) * ty * (1 - tb), tx * ty * (1 - tb),
            (1 - tx) * (1 - ty) * tb,       tx * (1 - ty) * tb,       (1 - tx) * ty * tb,       tx * ty * tb,
        };
        const int xs[8] = {x0, x1, x0, x1, x0, x1, x0, x1};
        const int ys[8] = {y0, y0, y1, y1, y0, y0, y1, y1};
        const int bs[8] = {b0, b0, b0, b0, b1, b1, b1, b1};
        for (int k = 0; k < 8; ++k) {
            const size_t i = index(xs[k], ys[k], bs[k]);
            s += wts[k] * sum[i];
            c += wts[k] * count[i];
        }
        // Nothing near this luminance in this neighbourhood: the pixel is its own base.
        return c > 1e-4f ? s / c : logL;
    }

    /** The scale to apply to a pixel of [luminance] at (x, y). */
    inline float gain(int x, int y, float luminance, float strength) const {
        if (!ready || strength <= 0.0f || luminance <= kMinLuminance) return 1.0f;
        const float logL = logOf(luminance);
        const float base = slice(x, y, logL);
        return std::exp2(-strength * (base - pivot));
    }
};

}  // namespace tone
}  // namespace multiframe
