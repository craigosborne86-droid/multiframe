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
#include <cstdint>
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

void alignPyramids(const std::vector<Plane>& refPyr, const std::vector<Plane>& altPyr,
                   int tilesX, int tilesY, int* dx, int* dy) {
    const int count = tilesX * tilesY;
    const int levels = static_cast<int>(std::min(refPyr.size(), altPyr.size()));

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
        jintArray joutDx, jintArray joutDy) {
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

    alignPyramids(refPyr, altPyr, tilesX, tilesY, dx.data(), dy.data());

    env->SetIntArrayRegion(joutDx, 0, count, dx.data());
    env->SetIntArrayRegion(joutDy, 0, count, dy.data());
    return JNI_TRUE;
}

}  // extern "C"
