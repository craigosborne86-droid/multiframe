// Chrominance smoothing, shared by both develops.
//
// Mirrors ChromaSmooth.kt, which is where the tests are: a joint bilateral on
// the two colour differences Cb = B - Y and Cr = R - Y, guided by luminance,
// at half resolution -- the picture's Y, Cb and Cr box-averaged two by two,
// smoothed there in two separable passes of 2r+1 taps, and read back for
// every full-resolution pixel from the four half-pixels around it, weighted
// by the bilinear distance and by luminance likeness both. The tap weight is
// a clamped quadratic in luminance distance, `max(0, 1 - (dY / 2.5 sigma)^2)^2`.
// Y is written back exactly, at full resolution, so no brightness moves;
// only the coloured speckle of a high-ISO frame is averaged away, and never
// across a brightness edge.
//
// ### Memory, and why the bands are sequential
//
// The filter reads untouched neighbours and writes in place. A band written
// in parallel with its neighbours would have those neighbours reading rows it
// had already changed, so bands are taken in order from the top, and each
// keeps the *original* of the full-resolution rows the next band's half-planes
// will read as their upper halo, copied before they are overwritten. Rows
// below a band are untouched by construction. The half-planes of a band and
// its halo are a few megabytes; nothing the size of the image is allocated,
// which is what lets the same code run on the fifty-megapixel 2x canvas.
// Within a band the rows are independent and run across every core.
//
// ### The vector path
//
// The weight is arithmetic -- a subtract, two multiplies, a max, a multiply
// -- and not a table, which is the point: a table is a gather, and a gather
// is what a NEON lane cannot do. Four half-pixels of a row go through each
// pass together; the taps are performed in the scalar loop's order,
// lane-wise, so the two agree to the last bit and a row's tail finishes in
// the scalar loop.
//
// ### The pixel layouts
//
// The CFA develop's bitmap is RGBA8888 with red at byte 0; the 2x develop's
// output is three-byte RGB. Both are "red, green, blue at consecutive bytes"
// with a stride between pixels of 4 or 3, so one template serves both.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#include "Tone.h"

namespace chroma {

constexpr float kReachSigmas = 2.5f;

inline float lumaOf(int r, int g, int b) {
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

inline uint8_t code(float v) {
    return static_cast<uint8_t>(std::clamp(static_cast<int>(std::lround(v)), 0, 255));
}

/** The weight of a neighbour whose luminance differs by `delta`, with the reach's reciprocal taken. */
inline float weightOf(float delta, float invReach) {
    const float t = delta * invReach;
    const float q = 1.0f - t * t;
    return q <= 0.0f ? 0.0f : q * q;
}

#if defined(__ARM_NEON)
inline float32x4_t weightOf(float32x4_t delta, float32x4_t invReach) {
    const float32x4_t t = vmulq_f32(delta, invReach);
    const float32x4_t q = vmaxq_f32(vsubq_f32(vdupq_n_f32(1.0f), vmulq_f32(t, t)), vdupq_n_f32(0.0f));
    return vmulq_f32(q, q);
}
#endif

/**
 * Pass one for one half-resolution row: the row-smoothed Cb and Cr, from the
 * row's Y, Cb, Cr. The taps run dx = -r..r in order, clamped at the ends.
 */
inline void smoothRow(const float* y, const float* cb, const float* cr, int width, int r,
                      float invReach, float* cbOut, float* crOut) {
    auto scalar = [&](int x) {
        const float centre = y[x];
        float sumW = 0.0f, sumCb = 0.0f, sumCr = 0.0f;
        for (int dx = -r; dx <= r; ++dx) {
            const int nx = std::clamp(x + dx, 0, width - 1);
            const float w = weightOf(y[nx] - centre, invReach);
            sumW += w;
            sumCb += w * cb[nx];
            sumCr += w * cr[nx];
        }
        cbOut[x] = sumCb / sumW;
        crOut[x] = sumCr / sumW;
    };

    int x = 0;
    for (; x < std::min(r, width); ++x) scalar(x);
#if defined(__ARM_NEON)
    const float32x4_t vInv = vdupq_n_f32(invReach);
    for (; x + 3 < width - r; x += 4) {
        const float32x4_t centre = vld1q_f32(y + x);
        float32x4_t sumW = vdupq_n_f32(0.0f), sumCb = vdupq_n_f32(0.0f), sumCr = vdupq_n_f32(0.0f);
        for (int dx = -r; dx <= r; ++dx) {
            const float32x4_t w = weightOf(vsubq_f32(vld1q_f32(y + x + dx), centre), vInv);
            sumW = vaddq_f32(sumW, w);
            sumCb = vmlaq_f32(sumCb, w, vld1q_f32(cb + x + dx));
            sumCr = vmlaq_f32(sumCr, w, vld1q_f32(cr + x + dx));
        }
        vst1q_f32(cbOut + x, vdivq_f32(sumCb, sumW));
        vst1q_f32(crOut + x, vdivq_f32(sumCr, sumW));
    }
#endif
    for (; x < width; ++x) scalar(x);
}

/**
 * Smooths the chroma of an image in place.
 *
 * [base] is the top-left pixel; [PixelBytes] is 4 for RGBA8888 and 3 for
 * RGB8, with red at byte 0 either way; [stride] is the row pitch in bytes.
 */
template <int PixelBytes>
void smooth(uint8_t* base, int width, int height, size_t stride,
            int radius, float sigma, float amount) {
    if (amount <= 0.0f || radius <= 0 || width < 4 || height < 4) return;
    const int r = radius;
    const float invReach = 1.0f / (kReachSigmas * sigma);
    const int hw = (width + 1) / 2;
    const int hh = (height + 1) / 2;

    // Full-resolution bands, even in height so they align with the half
    // planes; the half rows a band reads are its own, r either side for the
    // filter, and one more for the read-back's bilinear taps.
    constexpr int kBandRows = 512;
    const int haloHalf = r + 1 + r;  // the column pass's taps around the read-back's taps
    const size_t planeRow = static_cast<size_t>(hw);

    // The originals of the full rows above the current band that its half
    // planes need, saved before the band above overwrote them.
    const int haloFull = 2 * haloHalf;
    std::vector<uint8_t> haloAbove(static_cast<size_t>(haloFull) * stride);
    int haloRows = 0;

    std::vector<float> Y, Cb, Cr, CbH, CrH, CbS, CrS;

    for (int bandStart = 0; bandStart < height; bandStart += kBandRows) {
        const int bandEnd = std::min(height, bandStart + kBandRows);
        // Half rows this band's pixels read back from, then the filter's
        // reach around those.
        const int hb0 = bandStart / 2;
        const int hb1 = (bandEnd + 1) / 2;  // exclusive
        const int planeTop = std::max(0, hb0 - haloHalf);
        const int planeBottom = std::min(hh, hb1 + haloHalf);
        const int planeRows = planeBottom - planeTop;
        const size_t planeSize = static_cast<size_t>(planeRows) * planeRow;
        if (Y.size() < planeSize) {
            Y.resize(planeSize); Cb.resize(planeSize); Cr.resize(planeSize);
            CbH.resize(planeSize); CrH.resize(planeSize); CbS.resize(planeSize); CrS.resize(planeSize);
        }

        // A full row's bytes: from the saved originals above the band, else the image.
        auto fullRow = [&](int y) -> const uint8_t* {
            if (y < bandStart) {
                const int k = y - (bandStart - haloRows);
                return haloAbove.data() + static_cast<size_t>(k) * stride;
            }
            return base + static_cast<size_t>(y) * stride;
        };

        // The half planes for the band and its halo, and pass one along each
        // half row.
        multiframe::tone::parallelBands(planeRows, [&](int j0, int j1) {
            for (int j = j0; j < j1; ++j) {
                const int hj = planeTop + j;
                const uint8_t* row0 = fullRow(hj * 2);
                const uint8_t* row1 = (hj * 2 + 1 < height) ? fullRow(hj * 2 + 1) : nullptr;
                float* yp = Y.data() + static_cast<size_t>(j) * planeRow;
                float* cbp = Cb.data() + static_cast<size_t>(j) * planeRow;
                float* crp = Cr.data() + static_cast<size_t>(j) * planeRow;
                // One half-pixel: the box mean of its two-by-two, with a
                // missing row or column at the edge left out of the mean.
                auto scalarBox = [&](int i) {
                    float sumY = 0.0f, sumCb = 0.0f, sumCr = 0.0f;
                    int n = 0;
                    for (int dy = 0; dy < 2; ++dy) {
                        const uint8_t* row = dy == 0 ? row0 : row1;
                        if (row == nullptr) continue;
                        for (int dx = 0; dx < 2; ++dx) {
                            const int px = i * 2 + dx;
                            if (px >= width) continue;
                            const uint8_t* q = row + static_cast<size_t>(px) * PixelBytes;
                            const float l = lumaOf(q[0], q[1], q[2]);
                            sumY += l;
                            sumCb += q[2] - l;
                            sumCr += q[0] - l;
                            ++n;
                        }
                    }
                    yp[i] = sumY / n;
                    cbp[i] = sumCb / n;
                    crp[i] = sumCr / n;
                };
                int i = 0;
#if defined(__ARM_NEON)
                // Eight full pixels -- four half-pixels -- at a time, where
                // both rows exist and the eight are in the frame: the bytes
                // deinterleaved by channel, widened to float, the luminance
                // taken, and the two-by-two summed by pairing lanes. The sums
                // are taken in the scalar order -- top row left, top row
                // right, bottom left, bottom right -- so the two agree.
                if (row1 != nullptr) {
                    const float32x4_t vWr = vdupq_n_f32(0.2126f), vWg = vdupq_n_f32(0.7152f), vWb = vdupq_n_f32(0.0722f);
                    const float32x4_t quarter = vdupq_n_f32(0.25f);
                    for (; (i + 3) * 2 + 1 < width; i += 4) {
                        // Channels of eight pixels from each row, as u8x8.
                        uint8x8_t r0, g0, b0, r1, g1, b1;
                        const uint8_t* q0 = row0 + static_cast<size_t>(i * 2) * PixelBytes;
                        const uint8_t* q1 = row1 + static_cast<size_t>(i * 2) * PixelBytes;
                        if constexpr (PixelBytes == 4) {
                            const uint8x8x4_t p0 = vld4_u8(q0);
                            const uint8x8x4_t p1 = vld4_u8(q1);
                            r0 = p0.val[0]; g0 = p0.val[1]; b0 = p0.val[2];
                            r1 = p1.val[0]; g1 = p1.val[1]; b1 = p1.val[2];
                        } else {
                            const uint8x8x3_t p0 = vld3_u8(q0);
                            const uint8x8x3_t p1 = vld3_u8(q1);
                            r0 = p0.val[0]; g0 = p0.val[1]; b0 = p0.val[2];
                            r1 = p1.val[0]; g1 = p1.val[1]; b1 = p1.val[2];
                        }
                        // Widen to floats, eight per channel per row.
                        auto widen = [](uint8x8_t v, float32x4_t& lo, float32x4_t& hi) {
                            const uint16x8_t w = vmovl_u8(v);
                            lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(w)));
                            hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(w)));
                        };
                        float32x4_t r0lo, r0hi, g0lo, g0hi, b0lo, b0hi, r1lo, r1hi, g1lo, g1hi, b1lo, b1hi;
                        widen(r0, r0lo, r0hi); widen(g0, g0lo, g0hi); widen(b0, b0lo, b0hi);
                        widen(r1, r1lo, r1hi); widen(g1, g1lo, g1hi); widen(b1, b1lo, b1hi);
                        auto luma = [&](float32x4_t r, float32x4_t g, float32x4_t b) {
                            return vmlaq_f32(vmlaq_f32(vmulq_f32(vWr, r), vWg, g), vWb, b);
                        };
                        const float32x4_t l0lo = luma(r0lo, g0lo, b0lo), l0hi = luma(r0hi, g0hi, b0hi);
                        const float32x4_t l1lo = luma(r1lo, g1lo, b1lo), l1hi = luma(r1hi, g1hi, b1hi);
                        // Pair the lanes: even pixels and odd pixels of each row.
                        auto pairs = [](float32x4_t lo, float32x4_t hi, float32x4_t& even, float32x4_t& odd) {
                            even = vuzp1q_f32(lo, hi);
                            odd = vuzp2q_f32(lo, hi);
                        };
                        float32x4_t l0e, l0o, l1e, l1o, r0e, r0o, r1e, r1o, b0e, b0o, b1e, b1o;
                        pairs(l0lo, l0hi, l0e, l0o); pairs(l1lo, l1hi, l1e, l1o);
                        pairs(r0lo, r0hi, r0e, r0o); pairs(r1lo, r1hi, r1e, r1o);
                        pairs(b0lo, b0hi, b0e, b0o); pairs(b1lo, b1hi, b1e, b1o);
                        // Top-left, top-right, bottom-left, bottom-right, in that order.
                        const float32x4_t sumY = vaddq_f32(vaddq_f32(vaddq_f32(l0e, l0o), l1e), l1o);
                        const float32x4_t sumCb = vaddq_f32(vaddq_f32(vaddq_f32(
                            vsubq_f32(b0e, l0e), vsubq_f32(b0o, l0o)), vsubq_f32(b1e, l1e)), vsubq_f32(b1o, l1o));
                        const float32x4_t sumCr = vaddq_f32(vaddq_f32(vaddq_f32(
                            vsubq_f32(r0e, l0e), vsubq_f32(r0o, l0o)), vsubq_f32(r1e, l1e)), vsubq_f32(r1o, l1o));
                        vst1q_f32(yp + i, vmulq_f32(sumY, quarter));
                        vst1q_f32(cbp + i, vmulq_f32(sumCb, quarter));
                        vst1q_f32(crp + i, vmulq_f32(sumCr, quarter));
                    }
                }
#endif
                for (; i < hw; ++i) scalarBox(i);
                smoothRow(yp, cbp, crp, hw, r, invReach,
                          CbH.data() + static_cast<size_t>(j) * planeRow,
                          CrH.data() + static_cast<size_t>(j) * planeRow);
            }
        });

        // Pass two, down the half columns, for every half row the read-back
        // needs: those of the band and one either side.
        const int s0 = std::max(planeTop, hb0 - 1);
        const int s1 = std::min(planeBottom, hb1 + 1);  // exclusive
        multiframe::tone::parallelBands(s1 - s0, [&](int k0, int k1) {
            for (int k = k0; k < k1; ++k) {
                const int hj = s0 + k;
                const int j = hj - planeTop;
                const float* yc = Y.data() + static_cast<size_t>(j) * planeRow;
                float* cbOut = CbS.data() + static_cast<size_t>(j) * planeRow;
                float* crOut = CrS.data() + static_cast<size_t>(j) * planeRow;
                const int taps = 2 * r + 1;
                const float* tapY[16];
                const float* tapCb[16];
                const float* tapCr[16];
                for (int dy = -r; dy <= r; ++dy) {
                    const int nj = std::clamp(hj + dy, 0, hh - 1) - planeTop;
                    const int cj = std::clamp(nj, 0, planeRows - 1);
                    tapY[dy + r] = Y.data() + static_cast<size_t>(cj) * planeRow;
                    tapCb[dy + r] = CbH.data() + static_cast<size_t>(cj) * planeRow;
                    tapCr[dy + r] = CrH.data() + static_cast<size_t>(cj) * planeRow;
                }
                int i = 0;
#if defined(__ARM_NEON)
                const float32x4_t vInv = vdupq_n_f32(invReach);
                for (; i + 3 < hw; i += 4) {
                    const float32x4_t centre = vld1q_f32(yc + i);
                    float32x4_t sumW = vdupq_n_f32(0.0f), sumCb = vdupq_n_f32(0.0f), sumCr = vdupq_n_f32(0.0f);
                    for (int t = 0; t < taps; ++t) {
                        const float32x4_t w = weightOf(vsubq_f32(vld1q_f32(tapY[t] + i), centre), vInv);
                        sumW = vaddq_f32(sumW, w);
                        sumCb = vmlaq_f32(sumCb, w, vld1q_f32(tapCb[t] + i));
                        sumCr = vmlaq_f32(sumCr, w, vld1q_f32(tapCr[t] + i));
                    }
                    vst1q_f32(cbOut + i, vdivq_f32(sumCb, sumW));
                    vst1q_f32(crOut + i, vdivq_f32(sumCr, sumW));
                }
#endif
                for (; i < hw; ++i) {
                    const float centre = yc[i];
                    float sumW = 0.0f, sumCb = 0.0f, sumCr = 0.0f;
                    for (int t = 0; t < taps; ++t) {
                        const float w = weightOf(tapY[t][i] - centre, invReach);
                        sumW += w;
                        sumCb += w * tapCb[t][i];
                        sumCr += w * tapCr[t][i];
                    }
                    cbOut[i] = sumCb / sumW;
                    crOut[i] = sumCr / sumW;
                }
            }
        });

        // Save the originals of the full rows the next band's planes will
        // read above it, before they are written.
        const int saveFrom = std::max(bandStart, bandEnd - haloFull);
        haloRows = bandEnd - saveFrom;
        for (int k = 0; k < haloRows; ++k) {
            std::memcpy(haloAbove.data() + static_cast<size_t>(k) * stride,
                        base + static_cast<size_t>(saveFrom + k) * stride, stride);
        }

        // The read-back, over the band's full rows: each pixel's smoothed
        // chroma from the four half-pixels around it, weighted by bilinear
        // distance and by luminance likeness, and the pixel rebuilt around
        // its own luminance. A pixel with no tap of like brightness keeps its
        // own chroma.
        multiframe::tone::parallelBands(bandEnd - bandStart, [&](int i0, int i1) {
            for (int i = i0; i < i1; ++i) {
                const int y = bandStart + i;
                uint8_t* row = base + static_cast<size_t>(y) * stride;
                const float fy = std::clamp((y + 0.5f) / 2.0f - 0.5f, 0.0f, static_cast<float>(hh - 1));
                const int j0 = std::min(static_cast<int>(fy), hh - 1);
                const int j1 = std::min(j0 + 1, hh - 1);
                const float ty = fy - j0;
                const float* yA = Y.data() + static_cast<size_t>(j0 - planeTop) * planeRow;
                const float* yB = Y.data() + static_cast<size_t>(j1 - planeTop) * planeRow;
                const float* cbA = CbS.data() + static_cast<size_t>(j0 - planeTop) * planeRow;
                const float* cbB = CbS.data() + static_cast<size_t>(j1 - planeTop) * planeRow;
                const float* crA = CrS.data() + static_cast<size_t>(j0 - planeTop) * planeRow;
                const float* crB = CrS.data() + static_cast<size_t>(j1 - planeTop) * planeRow;
                int x = 0;
#if defined(__ARM_NEON)
                // Four pixels at a time, from an even x, so the four read the
                // half planes at m-1, m, m+1, m+2 with m = x/2: pixel 2m sits
                // between half-pixels m-1 and m at three quarters, 2m+1
                // between m and m+1 at a quarter, and so on. One load of the
                // four half values and two permutations give every lane its
                // pair; the edges, where m-1 or m+2 would fall off the plane,
                // are left to the scalar loop.
                {
                    const float32x4_t vInv = vdupq_n_f32(invReach);
                    const float32x4_t vTx = {0.75f, 0.25f, 0.75f, 0.25f};
                    const float32x4_t vOneMinusTx = vsubq_f32(vdupq_n_f32(1.0f), vTx);
                    const float32x4_t vTy = vdupq_n_f32(ty);
                    const float32x4_t vOneMinusTy = vdupq_n_f32(1.0f - ty);
                    const float32x4_t vAmount = vdupq_n_f32(amount);
                    const float32x4_t vWr = vdupq_n_f32(0.2126f), vWg = vdupq_n_f32(0.7152f), vWb = vdupq_n_f32(0.0722f);
                    const float32x4_t vInvG = vdupq_n_f32(1.0f / 0.7152f);
                    // The first even x whose m-1 is on the plane is 2; the last
                    // whose m+2 is on it needs (x+3)/2 + 2 <= hw - 1.
                    for (x = 2; x + 7 < width && (x + 3) / 2 + 2 <= hw - 1; x += 4) {
                        const int m = x / 2;
                        auto pairs = [&](const float* plane, float32x4_t& a, float32x4_t& b) {
                            const float32x4_t h = vld1q_f32(plane + m - 1);
                            const float32x4_t h1 = vextq_f32(h, h, 1);
                            const float32x4_t h2 = vextq_f32(h, h, 2);
                            a = vzip1q_f32(h, h1);   // h0 h1 h1 h2
                            b = vzip1q_f32(h1, h2);  // h1 h2 h2 h3
                        };
                        float32x4_t yA0, yA1, yB0, yB1, cbA0, cbA1, cbB0, cbB1, crA0, crA1, crB0, crB1;
                        pairs(yA, yA0, yA1); pairs(yB, yB0, yB1);
                        pairs(cbA, cbA0, cbA1); pairs(cbB, cbB0, cbB1);
                        pairs(crA, crA0, crA1); pairs(crB, crB0, crB1);

                        // The four pixels' own R, G, B and luminance: the bytes
                        // deinterleaved by channel and widened.
                        uint8_t* q4 = row + static_cast<size_t>(x) * PixelBytes;
                        uint8x8_t pr, pg, pb, pa = vdup_n_u8(255);
                        if constexpr (PixelBytes == 4) {
                            // vld4 wants eight pixels; the four beyond are read
                            // and not written back. Every x here has x + 7 in
                            // the row or the loop bound would have stopped it.
                            const uint8x8x4_t p = vld4_u8(q4);
                            pr = p.val[0]; pg = p.val[1]; pb = p.val[2]; pa = p.val[3];
                        } else {
                            const uint8x8x3_t p = vld3_u8(q4);
                            pr = p.val[0]; pg = p.val[1]; pb = p.val[2];
                        }
                        const float32x4_t vR = vcvtq_f32_u32(vmovl_u16(vget_low_u16(vmovl_u8(pr))));
                        const float32x4_t vG = vcvtq_f32_u32(vmovl_u16(vget_low_u16(vmovl_u8(pg))));
                        const float32x4_t vB = vcvtq_f32_u32(vmovl_u16(vget_low_u16(vmovl_u8(pb))));
                        const float32x4_t vL = vmlaq_f32(vmlaq_f32(vmulq_f32(vWr, vR), vWg, vG), vWb, vB);
                        const float32x4_t ownCb = vsubq_f32(vB, vL);
                        const float32x4_t ownCr = vsubq_f32(vR, vL);

                        float32x4_t sumW = vdupq_n_f32(0.0f), sumCb = vdupq_n_f32(0.0f), sumCr = vdupq_n_f32(0.0f);
                        auto tap = [&](float32x4_t yT, float32x4_t cbT, float32x4_t crT, float32x4_t bilinear) {
                            const float32x4_t w = vmulq_f32(bilinear, weightOf(vsubq_f32(yT, vL), vInv));
                            sumW = vaddq_f32(sumW, w);
                            sumCb = vmlaq_f32(sumCb, w, cbT);
                            sumCr = vmlaq_f32(sumCr, w, crT);
                        };
                        tap(yA0, cbA0, crA0, vmulq_f32(vOneMinusTx, vOneMinusTy));
                        tap(yA1, cbA1, crA1, vmulq_f32(vTx, vOneMinusTy));
                        tap(yB0, cbB0, crB0, vmulq_f32(vOneMinusTx, vTy));
                        tap(yB1, cbB1, crB1, vmulq_f32(vTx, vTy));

                        // A lane with no tap of like brightness keeps its own
                        // chroma: its smoothed value is set to its own.
                        const uint32x4_t none = vcleq_f32(sumW, vdupq_n_f32(1e-6f));
                        const float32x4_t safeW = vbslq_f32(none, vdupq_n_f32(1.0f), sumW);
                        const float32x4_t sCb = vbslq_f32(none, ownCb, vdivq_f32(sumCb, safeW));
                        const float32x4_t sCr = vbslq_f32(none, ownCr, vdivq_f32(sumCr, safeW));
                        const float32x4_t cb = vmlaq_f32(ownCb, vsubq_f32(sCb, ownCb), vAmount);
                        const float32x4_t cr = vmlaq_f32(ownCr, vsubq_f32(sCr, ownCr), vAmount);
                        const float32x4_t red = vaddq_f32(vL, cr);
                        const float32x4_t blue = vaddq_f32(vL, cb);
                        const float32x4_t green = vmulq_f32(vsubq_f32(vsubq_f32(vL, vmulq_f32(vWr, red)), vmulq_f32(vWb, blue)), vInvG);
                        // Rounded to nearest, ties away, as lround does.
                        const int32x4_t iR = vcvtaq_s32_f32(red), iG = vcvtaq_s32_f32(green), iB = vcvtaq_s32_f32(blue);
                        // Narrowed with saturation to bytes -- the clamp -- and
                        // written back interleaved, four pixels of twelve bytes,
                        // the alpha of an RGBA pixel carried through.
                        const uint16x4_t nR = vqmovun_s32(iR), nG = vqmovun_s32(iG), nB = vqmovun_s32(iB);
                        const uint8x8_t bR = vqmovn_u16(vcombine_u16(nR, nR));
                        const uint8x8_t bG = vqmovn_u16(vcombine_u16(nG, nG));
                        const uint8x8_t bB = vqmovn_u16(vcombine_u16(nB, nB));
                        uint8_t outR[8], outG[8], outB[8], outA[8];
                        vst1_u8(outR, bR); vst1_u8(outG, bG); vst1_u8(outB, bB); vst1_u8(outA, pa);
                        for (int k = 0; k < 4; ++k) {
                            uint8_t* q = row + static_cast<size_t>(x + k) * PixelBytes;
                            q[0] = outR[k]; q[1] = outG[k]; q[2] = outB[k];
                        }
                        (void)outA;
                    }
                    // Pixels 0 and 1, which the vector loop skipped, are done
                    // by the scalar loop below along with the tail.
                }
#else
                x = 2;
#endif
                const int vectorEnd = x;
                auto scalarPixel = [&](int px) {
                    const float fx = std::clamp((px + 0.5f) / 2.0f - 0.5f, 0.0f, static_cast<float>(hw - 1));
                    const int i0h = std::min(static_cast<int>(fx), hw - 1);
                    const int i1h = std::min(i0h + 1, hw - 1);
                    const float tx = fx - i0h;
                    uint8_t* q = row + static_cast<size_t>(px) * PixelBytes;
                    const float l = lumaOf(q[0], q[1], q[2]);
                    const float ownCb = q[2] - l;
                    const float ownCr = q[0] - l;
                    float sumW = 0.0f, sumCb = 0.0f, sumCr = 0.0f;
                    auto tap = [&](const float* yr, const float* cbr, const float* crr, int idx, float bilinear) {
                        const float w = bilinear * weightOf(yr[idx] - l, invReach);
                        sumW += w;
                        sumCb += w * cbr[idx];
                        sumCr += w * crr[idx];
                    };
                    tap(yA, cbA, crA, i0h, (1.0f - tx) * (1.0f - ty));
                    tap(yA, cbA, crA, i1h, tx * (1.0f - ty));
                    tap(yB, cbB, crB, i0h, (1.0f - tx) * ty);
                    tap(yB, cbB, crB, i1h, tx * ty);
                    if (sumW <= 1e-6f) return;
                    const float cb = ownCb + (sumCb / sumW - ownCb) * amount;
                    const float cr = ownCr + (sumCr / sumW - ownCr) * amount;
                    const float red = l + cr;
                    const float blue = l + cb;
                    const float green = (l - 0.2126f * red - 0.0722f * blue) / 0.7152f;
                    q[0] = code(red); q[1] = code(green); q[2] = code(blue);
                };
                for (int px = 0; px < std::min(2, width); ++px) scalarPixel(px);
                for (int px = std::max(vectorEnd, std::min(2, width)); px < width; ++px) scalarPixel(px);
            }
        });
    }
}

}  // namespace chroma
