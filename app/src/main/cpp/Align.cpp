// Tiled alignment search.
//
// A straight port of the Kotlin `Aligner`, which remains the reference
// implementation and stays under unit test. This exists because alignment
// turned out to be 83% of the merge -- measured on a Pixel 9 Pro XL, 2858 ms of
// a 3427 ms merge across five frames -- while the native accumulation it was
// grouped with was 295 ms. The plan of record had been to move the *merge* to
// the GPU, which would have attacked the 9%.
//
// The inner loop is a sum of absolute differences over an 8-bit plane, which is
// the one kernel ARM has a dedicated instruction family for: UABD and UABAL do
// sixteen bytes at a time.
//
// Every decision the search makes has to match the Kotlin exactly, including
// which candidate wins a tie, so the iteration order here is the same: the
// inherited offset, then the left neighbour, then the one above, then zero,
// then the square around the seed, with strict improvement only.

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <vector>

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>
#define MF_HAS_NEON 1
#endif

namespace {

constexpr int kLevels = 4;
constexpr int kSearchRadius = 4;
constexpr int kMinTile = 8;

/**
 * Bytes allocated past the last pixel of every plane.
 *
 * A sixteen-lane load reads sixteen bytes, and the de-interleaving one used for
 * the finest level reads thirty-two to gather sixteen even-indexed samples --
 * the last of which is discarded, but is still *read*. When the final sample of
 * a row is also the final byte of the buffer, that discarded read is one past
 * the allocation.
 *
 * It cost a crash to find. The luma proxy is 2040x1536, which is 3,133,440
 * bytes -- exactly 765 pages -- so the allocation ends flush against a page
 * boundary and the overread lands on an unmapped one. The parity test's smaller
 * plane had slack after it and passed happily while this was live.
 *
 * The slack is never summed: every loop is bounded by the sample count, and the
 * discarded lanes are discarded. It exists only so the load is legal.
 */
constexpr size_t kSlack = 32;

struct Plane {
    int w = 0;
    int h = 0;
    std::vector<uint8_t> data;

    void allocate(int width, int height) {
        w = width;
        h = height;
        data.assign(static_cast<size_t>(width) * height + kSlack, 0);
    }
};

/** 2x2 box downsample, truncating like the Kotlin's `sum shr 2`. */
Plane downsampleByTwo(const Plane& src) {
    Plane out;
    out.allocate(src.w / 2, src.h / 2);

    for (int row = 0; row < out.h; ++row) {
        const uint8_t* r0 = src.data.data() + static_cast<size_t>(row * 2) * src.w;
        const uint8_t* r1 = r0 + src.w;
        uint8_t* o = out.data.data() + static_cast<size_t>(row) * out.w;
        for (int col = 0; col < out.w; ++col) {
            const int c0 = col * 2;
            const int sum = static_cast<int>(r0[c0]) + r0[c0 + 1] +
                            r1[c0] + r1[c0 + 1];
            o[col] = static_cast<uint8_t>(sum >> 2);
        }
    }
    return out;
}

std::vector<Plane> buildPyramid(Plane base) {
    std::vector<Plane> pyramid;
    pyramid.reserve(kLevels);
    pyramid.push_back(std::move(base));
    for (int i = 1; i < kLevels; ++i) {
        const Plane& current = pyramid.back();
        if (current.w < 32 || current.h < 32) break;
        pyramid.push_back(downsampleByTwo(current));
    }
    return pyramid;
}

/**
 * Sum of absolute differences between one row segment of each plane.
 *
 * [step] of 2 samples every other byte, which is what the finest level does.
 * `vld2q_u8` de-interleaves exactly that, so the stride costs nothing.
 */
inline int64_t rowSad(const uint8_t* ref, const uint8_t* alt, int count, int step) {
    int64_t sum = 0;
    int i = 0;

#ifdef MF_HAS_NEON
    if (step == 1) {
        uint32x4_t acc = vdupq_n_u32(0);
        for (; i + 16 <= count; i += 16) {
            const uint8x16_t d = vabdq_u8(vld1q_u8(ref + i), vld1q_u8(alt + i));
            acc = vpadalq_u16(acc, vpaddlq_u8(d));
        }
        sum += vaddvq_u32(acc);
        ref += i;
        alt += i;
        count -= i;
        i = 0;
        for (int k = 0; k < count; ++k) {
            sum += std::abs(static_cast<int>(ref[k]) - static_cast<int>(alt[k]));
        }
        return sum;
    }
    if (step == 2) {
        uint32x4_t acc = vdupq_n_u32(0);
        int done = 0;
        for (; done + 16 <= count; done += 16) {
            const uint8x16_t r = vld2q_u8(ref + done * 2).val[0];
            const uint8x16_t a = vld2q_u8(alt + done * 2).val[0];
            acc = vpadalq_u16(acc, vpaddlq_u8(vabdq_u8(r, a)));
        }
        sum += vaddvq_u32(acc);
        for (int k = done; k < count; ++k) {
            sum += std::abs(static_cast<int>(ref[k * 2]) - static_cast<int>(alt[k * 2]));
        }
        return sum;
    }
#endif

    for (; i < count; ++i) {
        sum += std::abs(static_cast<int>(ref[i * step]) - static_cast<int>(alt[i * step]));
    }
    return sum;
}

/**
 * Cost of one candidate offset, abandoning once [ceiling] is passed.
 *
 * The abandoned value is whatever has accumulated so far, exactly as the Kotlin
 * does: the caller only ever asks whether it is smaller than the best, and an
 * abandoned cost is by definition not.
 */
int64_t tileCost(const Plane& ref, const Plane& alt, int x0, int y0,
                 int tileW, int tileH, int offX, int offY, int step,
                 int64_t ceiling) {
    int64_t cost = 0;
    const int aw = alt.w;
    const int ah = alt.h;

    for (int yy = 0; yy < tileH; yy += step) {
        const int ry = y0 + yy;
        int ay = ry + offY;
        if (ay < 0) ay = 0; else if (ay >= ah) ay = ah - 1;

        const uint8_t* rRow = ref.data.data() + static_cast<size_t>(ry) * ref.w;
        const uint8_t* aRow = alt.data.data() + static_cast<size_t>(ay) * aw;

        // Where the offset stays inside the alt plane, the sample positions
        // advance together and the row vectorises. Outside it the read clamps
        // to the edge pixel, so those runs are counted separately rather than
        // vectorised against a moving pointer that is not moving.
        int interiorFirst = 0;
        const int lowLimit = -offX - x0;
        if (lowLimit > 0) interiorFirst = ((lowLimit + step - 1) / step) * step;
        int interiorLast = aw - 1 - offX - x0;
        if (interiorLast > tileW - 1) interiorLast = tileW - 1;

        for (int xx = 0; xx < interiorFirst && xx < tileW; xx += step) {
            cost += std::abs(static_cast<int>(rRow[x0 + xx]) - static_cast<int>(aRow[0]));
        }

        if (interiorLast >= interiorFirst) {
            const int count = (interiorLast - interiorFirst) / step + 1;
            cost += rowSad(rRow + x0 + interiorFirst,
                           aRow + x0 + interiorFirst + offX, count, step);
        }

        int suffixFirst = interiorLast + 1;
        if (suffixFirst < interiorFirst) suffixFirst = interiorFirst;
        if (suffixFirst % step != 0) suffixFirst += step - (suffixFirst % step);
        for (int xx = suffixFirst; xx < tileW; xx += step) {
            cost += std::abs(static_cast<int>(rRow[x0 + xx]) -
                             static_cast<int>(aRow[aw - 1]));
        }

        if (cost >= ceiling) return cost;
    }
    return cost;
}

// --- Sub-pixel refinement --------------------------------------------------
//
// Inverse-compositional Lucas-Kanade on the proxy, translation only, from the
// integer winner. The Kotlin `Aligner.refineSubPixel` is the reference and
// carries the derivation; this is the same arithmetic. The warp's fraction is
// constant over a tile, so its bilinear weights are four integers in 1/256,
// and every sum -- the Hessian, sum g*e, sum e^2 -- is an exact integer here
// and there whatever order the lanes add in. The 2x2 solve is the only
// floating point, written as the Kotlin writes it.

constexpr int kLkIterations = 3;
constexpr int kLkOne = 256;
constexpr double kLkMinGradient = 0.25;
constexpr double kLkMinSnr = 1.0;

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

struct LkScratch {
    std::vector<int32_t> gx, gy, t;
};

/** Sums over one row of the warp: sum gx*e, gy*e, e*e. The caller has checked the row is inside the plane. */
inline void warpRow(const uint8_t* rowA, const uint8_t* rowB, const int32_t* gx, const int32_t* gy,
                    const int32_t* t, int count, int wx, int wy,
                    int64_t& bx, int64_t& by, int64_t& ee) {
    int i = 0;
#ifdef MF_HAS_NEON
    const uint16_t wx0 = static_cast<uint16_t>(kLkOne - wx);
    const uint16_t wx1 = static_cast<uint16_t>(wx);
    const uint32_t wy0 = static_cast<uint32_t>(kLkOne - wy);
    const uint32_t wy1 = static_cast<uint32_t>(wy);
    int32x4_t accX = vdupq_n_s32(0);
    int32x4_t accY = vdupq_n_s32(0);
    int64x2_t accE = vdupq_n_s64(0);
    const uint32x4_t half = vdupq_n_u32(kLkOne / 2);
    for (; i + 8 <= count; i += 8) {
        const uint16x8_t a0 = vmovl_u8(vld1_u8(rowA + i));
        const uint16x8_t a1 = vmovl_u8(vld1_u8(rowA + i + 1));
        const uint16x8_t b0 = vmovl_u8(vld1_u8(rowB + i));
        const uint16x8_t b1 = vmovl_u8(vld1_u8(rowB + i + 1));
        // Horizontal: at most 255 * 256 = 65280 per value.
        const uint32x4_t h0lo = vmlal_n_u16(vmull_n_u16(vget_low_u16(a0), wx0), vget_low_u16(a1), wx1);
        const uint32x4_t h0hi = vmlal_n_u16(vmull_n_u16(vget_high_u16(a0), wx0), vget_high_u16(a1), wx1);
        const uint32x4_t h1lo = vmlal_n_u16(vmull_n_u16(vget_low_u16(b0), wx0), vget_low_u16(b1), wx1);
        const uint32x4_t h1hi = vmlal_n_u16(vmull_n_u16(vget_high_u16(b0), wx0), vget_high_u16(b1), wx1);
        // Vertical, rounded back to 1/256 of a code: at most 65280 * 256 + 128.
        const uint32x4_t vlo = vshrq_n_u32(vaddq_u32(vmlaq_n_u32(vmulq_n_u32(h0lo, wy0), h1lo, wy1), half), 8);
        const uint32x4_t vhi = vshrq_n_u32(vaddq_u32(vmlaq_n_u32(vmulq_n_u32(h0hi, wy0), h1hi, wy1), half), 8);
        const int32x4_t elo = vsubq_s32(vreinterpretq_s32_u32(vlo), vld1q_s32(t + i));
        const int32x4_t ehi = vsubq_s32(vreinterpretq_s32_u32(vhi), vld1q_s32(t + i + 4));
        // |g| <= 255 and |e| <= 65280: a product under 1.7e7, and a lane
        // sums at most a quarter of a row's -- sixteen of them on the widest
        // tile the proxy makes -- before it is widened below.
        accX = vmlaq_s32(vmlaq_s32(accX, vld1q_s32(gx + i), elo), vld1q_s32(gx + i + 4), ehi);
        accY = vmlaq_s32(vmlaq_s32(accY, vld1q_s32(gy + i), elo), vld1q_s32(gy + i + 4), ehi);
        accE = vaddq_s64(accE, vmull_s32(vget_low_s32(elo), vget_low_s32(elo)));
        accE = vaddq_s64(accE, vmull_s32(vget_high_s32(elo), vget_high_s32(elo)));
        accE = vaddq_s64(accE, vmull_s32(vget_low_s32(ehi), vget_low_s32(ehi)));
        accE = vaddq_s64(accE, vmull_s32(vget_high_s32(ehi), vget_high_s32(ehi)));
    }
    bx += vaddlvq_s32(accX);
    by += vaddlvq_s32(accY);
    ee += vaddvq_s64(accE);
#endif
    for (; i < count; ++i) {
        const int h0 = rowA[i] * (kLkOne - wx) + rowA[i + 1] * wx;
        const int h1 = rowB[i] * (kLkOne - wx) + rowB[i + 1] * wx;
        const int v = (h0 * (kLkOne - wy) + h1 * wy + kLkOne / 2) >> 8;
        const int e = v - t[i];
        bx += static_cast<int64_t>(gx[i]) * e;
        by += static_cast<int64_t>(gy[i]) * e;
        ee += static_cast<int64_t>(e) * e;
    }
}

/** See `Aligner.refineSubPixel`. Writes zero to both where the fit declines. */
void refineSubPixel(const Plane& ref, const Plane& alt, int x0, int y0, int tileW, int tileH,
                    int dx, int dy, LkScratch& s, float& outX, float& outY) {
    outX = 0.0f;
    outY = 0.0f;
    const int n = tileW * tileH;
    if (n <= 0) return;
    s.gx.resize(static_cast<size_t>(n));
    s.gy.resize(static_cast<size_t>(n));
    s.t.resize(static_cast<size_t>(n));

    const int rw = ref.w, rh = ref.h;
    const uint8_t* rd = ref.data.data();
    auto r = [&](int x, int y) -> int {
        return rd[static_cast<size_t>(clampi(y, 0, rh - 1)) * rw + clampi(x, 0, rw - 1)];
    };
    int64_t hxx = 0, hxy = 0, hyy = 0;
    for (int yy = 0; yy < tileH; ++yy) {
        const int y = y0 + yy;
        for (int xx = 0; xx < tileW; ++xx) {
            const int x = x0 + xx;
            const int i = yy * tileW + xx;
            const int a = r(x + 1, y) - r(x - 1, y);
            const int b = r(x, y + 1) - r(x, y - 1);
            s.gx[i] = a;
            s.gy[i] = b;
            s.t[i] = r(x, y) * kLkOne;
            hxx += static_cast<int64_t>(a) * a;
            hxy += static_cast<int64_t>(a) * b;
            hyy += static_cast<int64_t>(b) * b;
        }
    }
    const double det = static_cast<double>(hxx) * static_cast<double>(hyy) -
                       static_cast<double>(hxy) * static_cast<double>(hxy);
    if (det <= 0.0) return;
    const double trace = static_cast<double>(hxx) + static_cast<double>(hyy);
    const double diff = static_cast<double>(hxx) - static_cast<double>(hyy);
    const double root = std::sqrt(diff * diff + 4.0 * static_cast<double>(hxy) * static_cast<double>(hxy));
    const double lambdaMin = 0.5 * (trace - root);
    if (lambdaMin / (4.0 * n) < kLkMinGradient) return;

    const int aw = alt.w, ah = alt.h;
    const uint8_t* ad = alt.data.data();
    double px = dx, py = dy;
    int64_t ee = 0;
    for (int iteration = 0; iteration < kLkIterations; ++iteration) {
        int ix = static_cast<int>(std::floor(px));
        int wx = static_cast<int>(std::floor((px - ix) * kLkOne + 0.5));
        if (wx == kLkOne) { ++ix; wx = 0; }
        int iy = static_cast<int>(std::floor(py));
        int wy = static_cast<int>(std::floor((py - iy) * kLkOne + 0.5));
        if (wy == kLkOne) { ++iy; wy = 0; }

        int64_t bx = 0, by = 0;
        ee = 0;
        const int sx0 = x0 + ix;
        const bool insideX = sx0 >= 0 && sx0 + tileW < aw;
        for (int yy = 0; yy < tileH; ++yy) {
            const int sy = y0 + yy + iy;
            const int i0 = yy * tileW;
            if (insideX && sy >= 0 && sy + 1 < ah) {
                const uint8_t* rowA = ad + static_cast<size_t>(sy) * aw + sx0;
                warpRow(rowA, rowA + aw, s.gx.data() + i0, s.gy.data() + i0, s.t.data() + i0,
                        tileW, wx, wy, bx, by, ee);
                continue;
            }
            // The plane's edge: read clamped, as the reference does.
            const int ya = clampi(sy, 0, ah - 1), yb = clampi(sy + 1, 0, ah - 1);
            for (int xx = 0; xx < tileW; ++xx) {
                const int sx = sx0 + xx;
                const int xa = clampi(sx, 0, aw - 1), xb = clampi(sx + 1, 0, aw - 1);
                const int h0 = ad[static_cast<size_t>(ya) * aw + xa] * (kLkOne - wx) + ad[static_cast<size_t>(ya) * aw + xb] * wx;
                const int h1 = ad[static_cast<size_t>(yb) * aw + xa] * (kLkOne - wx) + ad[static_cast<size_t>(yb) * aw + xb] * wx;
                const int v = (h0 * (kLkOne - wy) + h1 * wy + kLkOne / 2) >> 8;
                const int e = v - s.t[i0 + xx];
                bx += static_cast<int64_t>(s.gx[i0 + xx]) * e;
                by += static_cast<int64_t>(s.gy[i0 + xx]) * e;
                ee += static_cast<int64_t>(e) * e;
            }
        }
        const double scale = 2.0 / kLkOne / det;
        const double stepX = scale * (static_cast<double>(hyy) * static_cast<double>(bx) -
                                      static_cast<double>(hxy) * static_cast<double>(by));
        const double stepY = scale * (static_cast<double>(hxx) * static_cast<double>(by) -
                                      static_cast<double>(hxy) * static_cast<double>(bx));
        px -= stepX;
        py -= stepY;
        if (std::fabs(px - dx) > 1.0 || std::fabs(py - dy) > 1.0) return;
    }
    const double residual = static_cast<double>(ee) / n / (static_cast<double>(kLkOne) * kLkOne);
    if (lambdaMin / (4.0 * n) < kLkMinSnr * residual) return;
    outX = static_cast<float>(px - dx);
    outY = static_cast<float>(py - dy);
}

void alignPyramids(const std::vector<Plane>& refPyr, const std::vector<Plane>& altPyr,
                   int tilesX, int tilesY, int* dx, int* dy,
                   float* fracDx, float* fracDy) {
    const int count = tilesX * tilesY;
    const int levels = static_cast<int>(std::min(refPyr.size(), altPyr.size()));
    LkScratch scratch;

    for (int level = levels - 1; level >= 0; --level) {
        if (level < levels - 1) {
            for (int i = 0; i < count; ++i) {
                dx[i] *= 2;
                dy[i] *= 2;
            }
        }

        const Plane& ref = refPyr[level];
        const Plane& alt = altPyr[level];
        const int step = (level == 0) ? 2 : 1;

        const int tileW = ref.w / tilesX;
        const int tileH = ref.h / tilesY;
        if (tileW < kMinTile || tileH < kMinTile) continue;

        for (int ty = 0; ty < tilesY; ++ty) {
            for (int tx = 0; tx < tilesX; ++tx) {
                const int idx = ty * tilesX + tx;
                const int x0 = tx * tileW;
                const int y0 = ty * tileH;

                int seedDx = dx[idx];
                int seedDy = dy[idx];
                int64_t seedCost = tileCost(ref, alt, x0, y0, tileW, tileH,
                                            seedDx, seedDy, step, INT64_MAX);

                auto consider = [&](int cdx, int cdy) {
                    if (cdx == seedDx && cdy == seedDy) return;
                    const int64_t c = tileCost(ref, alt, x0, y0, tileW, tileH,
                                               cdx, cdy, step, seedCost);
                    if (c < seedCost) {
                        seedCost = c;
                        seedDx = cdx;
                        seedDy = cdy;
                    }
                };

                if (tx > 0) consider(dx[idx - 1], dy[idx - 1]);
                if (ty > 0) consider(dx[idx - tilesX], dy[idx - tilesX]);
                consider(0, 0);

                int bestDx = seedDx;
                int bestDy = seedDy;
                int64_t bestCost = seedCost;

                for (int cy = -kSearchRadius; cy <= kSearchRadius; ++cy) {
                    for (int cx = -kSearchRadius; cx <= kSearchRadius; ++cx) {
                        const int tryDx = seedDx + cx;
                        const int tryDy = seedDy + cy;
                        const int64_t cost = tileCost(ref, alt, x0, y0, tileW, tileH,
                                                      tryDx, tryDy, step, bestCost);
                        if (cost < bestCost) {
                            bestCost = cost;
                            bestDx = tryDx;
                            bestDy = tryDy;
                        }
                    }
                }
                dx[idx] = bestDx;
                dy[idx] = bestDy;

                // Sub-pixel refinement at the finest level only. Higher up it
                // would be doubled into the next level's integer search and
                // lost.
                if (level == 0) {
                    refineSubPixel(ref, alt, x0, y0, tileW, tileH, bestDx, bestDy, scratch,
                                   fracDx[idx], fracDy[idx]);
                }
            }
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_Aligner_nAlign(
        JNIEnv* env, jobject, jbyteArray jref, jbyteArray jalt,
        jint width, jint height, jint tilesX, jint tilesY,
        jintArray joutDx, jintArray joutDy,
        jfloatArray joutFracDx, jfloatArray joutFracDy) {
    if (width <= 0 || height <= 0 || tilesX <= 0 || tilesY <= 0) return JNI_FALSE;

    const size_t pixels = static_cast<size_t>(width) * height;
    if (env->GetArrayLength(jref) < static_cast<jsize>(pixels)) return JNI_FALSE;
    if (env->GetArrayLength(jalt) < static_cast<jsize>(pixels)) return JNI_FALSE;

    Plane refBase;
    refBase.allocate(width, height);
    Plane altBase;
    altBase.allocate(width, height);

    env->GetByteArrayRegion(jref, 0, static_cast<jsize>(pixels),
                            reinterpret_cast<jbyte*>(refBase.data.data()));
    env->GetByteArrayRegion(jalt, 0, static_cast<jsize>(pixels),
                            reinterpret_cast<jbyte*>(altBase.data.data()));

    const std::vector<Plane> refPyr = buildPyramid(std::move(refBase));
    const std::vector<Plane> altPyr = buildPyramid(std::move(altBase));

    const int count = tilesX * tilesY;
    std::vector<int> dx(static_cast<size_t>(count), 0);
    std::vector<int> dy(static_cast<size_t>(count), 0);
    std::vector<float> fracDx(static_cast<size_t>(count), 0.0f);
    std::vector<float> fracDy(static_cast<size_t>(count), 0.0f);

    alignPyramids(refPyr, altPyr, tilesX, tilesY, dx.data(), dy.data(),
                  fracDx.data(), fracDy.data());

    env->SetIntArrayRegion(joutDx, 0, count, dx.data());
    env->SetIntArrayRegion(joutDy, 0, count, dy.data());
    env->SetFloatArrayRegion(joutFracDx, 0, count, fracDx.data());
    env->SetFloatArrayRegion(joutFracDy, 0, count, fracDy.data());
    return JNI_TRUE;
}

}  // extern "C"
