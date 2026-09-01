// Bayer-domain burst merge.
//
// Exists to escape the Dalvik heap. This device caps an app at 256 MB of Java
// heap on 15 GB of physical RAM, and a single raw frame is 25 MB, so an
// Indigo-scale burst simply cannot be held on the managed heap. Everything
// here allocates natively, and frames are read straight out of the camera's
// own direct buffers with no copy into Java at all.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <functional>
#include <mutex>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <thread>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#define LOG_TAG "MultiframeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kNoiseBins = 16;
constexpr int kSampleStride = 8;
constexpr float kMadToSigma = 1.4826f;

struct Accumulator {
    int width = 0;
    int height = 0;
    int cfa[4] = {1, 2, 0, 1};
    int black[4] = {64, 64, 64, 64};
    int white = 1023;
    float noiseTolerance = 3.0f;
    float minNoiseSigma = 0.5f;

    // Native allocations: outside the Java heap entirely.
    std::vector<float> sum;
    std::vector<float> weight;
    std::vector<uint16_t> reference;

    float noiseVar[kNoiseBins] = {0};
    bool noiseReady = false;

    double contributionSum = 0.0;
    long long contributionCount = 0;
    int merged = 0;

    int blackAt(int x, int y) const { return black[(y & 1) * 2 + (x & 1)]; }
    int range() const {
        int lo = std::min(std::min(black[0], black[1]), std::min(black[2], black[3]));
        return std::max(1, white - lo);
    }
};

inline int binOf(float value, int white) {
    int b = static_cast<int>((value / static_cast<float>(white + 1)) * kNoiseBins);
    return std::clamp(b, 0, kNoiseBins - 1);
}

// Reads one 16-bit sample honouring row stride, which the camera may set
// larger than width * 2.
inline uint16_t sampleAt(const uint8_t* base, int rowStrideBytes, int x, int y) {
    const uint8_t* p = base + static_cast<size_t>(y) * rowStrideBytes + static_cast<size_t>(x) * 2;
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

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

void parallelBands(int height, const std::function<void(int, int)>& body) {
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

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nCreate(
        JNIEnv* env, jobject, jint width, jint height,
        jintArray cfa, jintArray black, jint white,
        jfloat tolerance, jfloat minSigma) {
    auto* acc = new Accumulator();
    acc->width = width;
    acc->height = height;
    acc->white = white;
    acc->noiseTolerance = tolerance;
    acc->minNoiseSigma = minSigma;

    env->GetIntArrayRegion(cfa, 0, 4, acc->cfa);
    env->GetIntArrayRegion(black, 0, 4, acc->black);

    const size_t n = static_cast<size_t>(width) * height;
    acc->sum.assign(n, 0.0f);
    acc->weight.assign(n, 0.0f);
    acc->reference.assign(n, 0);

    LOGI("created %dx%d accumulator, %.1f MB native",
         width, height,
         (n * (sizeof(float) * 2 + sizeof(uint16_t))) / (1024.0 * 1024.0));
    return reinterpret_cast<jlong>(acc);
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Accumulator*>(handle);
}

/** Half-resolution luma proxy: the mean of each 2x2 CFA cell. */
JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nLumaProxy(
        JNIEnv* env, jobject, jlong handle, jobject buffer, jint rowStride, jbyteArray out) {
    auto* acc = reinterpret_cast<Accumulator*>(handle);
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) return;

    const int hw = acc->width / 2;
    const int hh = acc->height / 2;
    std::vector<int8_t> tmp(static_cast<size_t>(hw) * hh);
    const float range = static_cast<float>(acc->range()) * 4.0f;

    parallelBands(hh, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            for (int x = 0; x < hw; ++x) {
                int sx = x * 2, sy = y * 2;
                int s = sampleAt(base, rowStride, sx, sy) +
                        sampleAt(base, rowStride, sx + 1, sy) +
                        sampleAt(base, rowStride, sx, sy + 1) +
                        sampleAt(base, rowStride, sx + 1, sy + 1);
                int blk = acc->blackAt(sx, sy) * 4;
                float norm = std::clamp((s - blk) / range, 0.0f, 1.0f);
                tmp[static_cast<size_t>(y) * hw + x] =
                        static_cast<int8_t>(static_cast<int>(norm * 255.0f) & 0xFF);
            }
        }
    });
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(tmp.size()),
                            reinterpret_cast<const jbyte*>(tmp.data()));
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nSetReference(
        JNIEnv* env, jobject, jlong handle, jobject buffer, jint rowStride) {
    auto* acc = reinterpret_cast<Accumulator*>(handle);
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) return;

    const int w = acc->width;
    parallelBands(acc->height, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            // One row pointer instead of recomputing the stride offset per
            // pixel. The camera's rows are 16-bit and may be padded, so the
            // pointer is stepped in bytes and read as pairs.
            const uint8_t* row = base + static_cast<size_t>(y) * rowStride;
            size_t i = static_cast<size_t>(y) * w;
            for (int x = 0; x < w; ++x, ++i) {
                const uint16_t v = static_cast<uint16_t>(row[x * 2] | (row[x * 2 + 1] << 8));
                acc->reference[i] = v;
                acc->sum[i] = static_cast<float>(v);
                acc->weight[i] = 1.0f;
            }
        }
    });
    acc->merged = 1;
}

/**
 * Estimates noise against signal level from the difference between the
 * reference and one aligned frame. Frame-to-frame differences in a static
 * scene are noise; a median per brightness bin is robust to what moved.
 */
static void estimateNoise(Accumulator* acc, const uint8_t* base, int rowStride,
                          const int* dx, const int* dy, int tilesX, int tilesY) {
    std::vector<std::vector<float>> samples(kNoiseBins);
    const int w = acc->width, h = acc->height;
    const float tileW = static_cast<float>(w / 2) / tilesX;
    const float tileH = static_cast<float>(h / 2) / tilesY;

    for (int y = 2; y < h - 2; y += kSampleStride) {
        int ty = std::clamp(static_cast<int>((y / 2) / tileH), 0, tilesY - 1);
        for (int x = 2; x < w - 2; x += kSampleStride) {
            int tx = std::clamp(static_cast<int>((x / 2) / tileW), 0, tilesX - 1);
            int idx = ty * tilesX + tx;
            int sx = x + dx[idx] * 2;
            int sy = y + dy[idx] * 2;
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
            float refV = static_cast<float>(acc->reference[static_cast<size_t>(y) * w + x]);
            float altV = static_cast<float>(sampleAt(base, rowStride, sx, sy));
            int b = binOf(refV, acc->white);
            if (samples[b].size() < 3000) samples[b].push_back(std::fabs(altV - refV));
        }
    }

    for (int b = 0; b < kNoiseBins; ++b) {
        float sigma;
        if (samples[b].size() < 16) {
            sigma = acc->minNoiseSigma;
        } else {
            auto& v = samples[b];
            std::sort(v.begin(), v.end());
            sigma = std::max(v[v.size() / 2] * kMadToSigma / 1.4142f, acc->minNoiseSigma);
        }
        float tol = acc->noiseTolerance * sigma;
        acc->noiseVar[b] = tol * tol;
    }
    for (int b = 0; b < kNoiseBins; ++b) {
        if (samples[b].size() >= 16) continue;
        for (int o = 1; o < kNoiseBins; ++o) {
            if (b - o >= 0 && samples[b - o].size() >= 16) { acc->noiseVar[b] = acc->noiseVar[b - o]; break; }
            if (b + o < kNoiseBins && samples[b + o].size() >= 16) { acc->noiseVar[b] = acc->noiseVar[b + o]; break; }
        }
    }
    acc->noiseReady = true;
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nAddFrame(
        JNIEnv* env, jobject, jlong handle, jobject buffer, jint rowStride,
        jintArray jdx, jintArray jdy, jint tilesX, jint tilesY) {
    auto* acc = reinterpret_cast<Accumulator*>(handle);
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) return;

    const int tiles = tilesX * tilesY;
    std::vector<int> dx(tiles), dy(tiles);
    env->GetIntArrayRegion(jdx, 0, tiles, dx.data());
    env->GetIntArrayRegion(jdy, 0, tiles, dy.data());

    if (!acc->noiseReady) estimateNoise(acc, base, rowStride, dx.data(), dy.data(), tilesX, tilesY);

    const int w = acc->width, h = acc->height;
    const float tileW = static_cast<float>(w / 2) / tilesX;
    const float tileH = static_cast<float>(h / 2) / tilesY;

    // Accumulated under a lock at the end of each band rather than into a
    // per-thread slot. The slot scheme indexed a fixed array of 64 by an
    // incrementing counter, which was safe only while each thread ran exactly
    // one band; now that bands are claimed dynamically a thread runs several,
    // and a sweep of more than 64 bands would have two of them sharing a slot
    // and racing. A few dozen lock acquisitions per frame cost nothing beside
    // the twelve million pixels they follow.
    double totalContrib = 0.0;
    long long totalCount = 0;
    std::mutex totals;

    // The alignment is per tile, not per pixel, so a row crosses only sixty-odd
    // displacements on the way across an image four thousand pixels wide. The
    // loop below walks those runs rather than the pixels, because everything
    // the displacement decides is decided once for the whole run:
    //
    //  - the source row. `sy` depends on the tile's vertical shift alone, so
    //    the row address -- which was costing a 64-bit multiply by the stride
    //    at every pixel -- is computed once, and a run that lands above or
    //    below the frame is skipped whole rather than pixel by pixel.
    //  - the horizontal bounds. `sx = x + shift` is in the frame exactly while
    //    x is in `[-shift, w - shift)`, so intersecting that with the run turns
    //    four compares and a branch per pixel into two clamps per run.
    //  - the count of contributing pixels, which is then the length of what is
    //    left rather than an increment per pixel.
    //
    // The tile column is a run boundary rather than a table now. It was being
    // memoised into `txForX` and looked up per pixel; grouping the equal values
    // it held gives the same answers and stops looking them up at all.
    struct Run {
        int x0;
        int x1;
        int tx;
    };
    std::vector<Run> runs;
    for (int x = 0; x < w; ++x) {
        const int tx = std::clamp(static_cast<int>((x / 2) / tileW), 0, tilesX - 1);
        if (!runs.empty() && runs.back().tx == tx) {
            runs.back().x1 = x + 1;
        } else {
            runs.push_back({x, x + 1, tx});
        }
    }

    // The noise variance depends only on the reference value at the site,
    // through a bin index that costs a float division, so it is a table built
    // once a frame. Not an approximation: each entry is the identical
    // expression evaluated at the identical input.
    const int white = acc->white;
    const int noiseSpan = white + 1;
    std::vector<float> noiseForValue(static_cast<size_t>(noiseSpan));
    for (int v = 0; v < noiseSpan; ++v) {
        noiseForValue[static_cast<size_t>(v)] =
            acc->noiseVar[binOf(static_cast<float>(v), white)];
    }

    const float* noise = noiseForValue.data();
    const uint16_t* refPlane = acc->reference.data();
    float* sumPlane = acc->sum.data();
    float* weightPlane = acc->weight.data();

    parallelBands(h, [&](int y0, int y1) {
        double localContrib = 0.0;
        long long localCount = 0;
#if defined(__ARM_NEON)
        // The vector path keeps four running totals of the contributed weight
        // and folds them in once the band is done. That is a different
        // summation order from the scalar loop's single running total, and the
        // only figure it reaches is the mean contribution -- a diagnostic,
        // reported to four decimals, where reordering a double sum of twelve
        // million values in [0, 1] moves the twelfth.
        float64x2_t contribLo = vdupq_n_f64(0.0);
        float64x2_t contribHi = vdupq_n_f64(0.0);
#endif

        for (int y = y0; y < y1; ++y) {
            const int ty = std::clamp(static_cast<int>((y / 2) / tileH), 0, tilesY - 1);
            const size_t rowBase = static_cast<size_t>(y) * w;
            const int tyBase = ty * tilesX;
            const uint16_t* refRow = refPlane + rowBase;
            float* sumRow = sumPlane + rowBase;
            float* weightRow = weightPlane + rowBase;

            for (const Run& run : runs) {
                const int idx = tyBase + run.tx;
                // Doubling a proxy offset always yields an even shift, keeping
                // every sample on its own colour plane.
                const int shiftX = dx[idx] * 2;
                const int sy = y + dy[idx] * 2;
                if (sy < 0 || sy >= h) continue;

                const int xEnd = std::min(run.x1, w - shiftX);
                const int xStart = std::max(run.x0, -shiftX);
                if (xStart >= xEnd) continue;

                const uint8_t* src = base + static_cast<size_t>(sy) * rowStride +
                                     static_cast<size_t>(xStart + shiftX) * 2;
                int x = xStart;

#if defined(__ARM_NEON)
                // Four pixels at a time.
                //
                // The compiler cannot do this one itself, and the reason is the
                // noise lookup: `noise[refRaw]` is a load whose address depends
                // on the data, and NEON has no gather. So that one term stays
                // scalar -- four loads straight into lanes, no round trip
                // through the stack -- and everything either side of it goes
                // four wide.
                //
                // The arithmetic is the same arithmetic, and it is written
                // the same way -- a multiply and then an add, which under
                // -ffast-math the compiler contracts into a fused multiply-add
                // here exactly as it already did in the scalar loop below. That
                // is not a liberty this change took: both paths emit `fmla`,
                // and what settles it either way is the parity test, which
                // holds both against the Kotlin reference pixel for pixel.
                //
                // This path reads the source with a 16-bit vector load, where
                // the scalar one assembles each sample from two bytes and is
                // therefore endian-neutral. Every ABI this builds for is
                // little-endian, and the scalar loop below is what would run
                // anywhere else.
                for (; x + 4 <= xEnd; x += 4, src += 8) {
                    const uint16x4_t refRaw4 = vld1_u16(refRow + x);
                    const float32x4_t refV =
                        vcvtq_f32_u32(vmovl_u16(refRaw4));
                    const float32x4_t altV = vcvtq_f32_u32(vmovl_u16(
                        vld1_u16(reinterpret_cast<const uint16_t*>(src))));

                    const float32x4_t d = vsubq_f32(altV, refV);
                    const float32x4_t d2 = vmulq_f32(d, d);

                    // Values above white clamp to the top bin, which is what
                    // binOf did with them, so the last entry answers for them.
                    float32x4_t n2 = vdupq_n_f32(0.0f);
                    n2 = vld1q_lane_f32(
                        noise + std::min<int>(refRow[x + 0], white), n2, 0);
                    n2 = vld1q_lane_f32(
                        noise + std::min<int>(refRow[x + 1], white), n2, 1);
                    n2 = vld1q_lane_f32(
                        noise + std::min<int>(refRow[x + 2], white), n2, 2);
                    n2 = vld1q_lane_f32(
                        noise + std::min<int>(refRow[x + 3], white), n2, 3);

                    // Both arms are evaluated and one is selected. The divide
                    // is the arm that is almost never taken -- a frame agrees
                    // with its reference to within tolerance nearly everywhere
                    // -- and a branch that mispredicts a few percent of twelve
                    // million times costs more than a divide that is thrown
                    // away. Where d2 is zero the divide yields an infinity and
                    // the select discards it.
                    const float32x4_t wgt = vbslq_f32(
                        vcleq_f32(d2, n2), vdupq_n_f32(1.0f), vdivq_f32(n2, d2));

                    vst1q_f32(sumRow + x, vaddq_f32(vld1q_f32(sumRow + x),
                                                    vmulq_f32(altV, wgt)));
                    vst1q_f32(weightRow + x,
                              vaddq_f32(vld1q_f32(weightRow + x), wgt));

                    // Widened to double before accumulating. Twelve million
                    // weights summed in single precision would stop moving the
                    // total long before the end of the image.
                    contribLo = vaddq_f64(contribLo, vcvt_f64_f32(vget_low_f32(wgt)));
                    contribHi = vaddq_f64(contribHi, vcvt_high_f64_f32(wgt));
                }
#endif

                for (; x < xEnd; ++x, src += 2) {
                    const uint16_t refRaw = refRow[x];
                    const float refV = static_cast<float>(refRaw);
                    const float altV = static_cast<float>(
                        static_cast<uint16_t>(src[0] | (src[1] << 8)));
                    const float d = altV - refV;
                    const float d2 = d * d;
                    // Values above white clamp to the top bin, which is what
                    // binOf did with them, so the last entry answers for them.
                    const float n2 = noise[static_cast<size_t>(std::min<int>(refRaw, white))];
                    const float wgt = (d2 <= n2) ? 1.0f : n2 / d2;

                    sumRow[x] += altV * wgt;
                    weightRow[x] += wgt;
                    localContrib += wgt;
                }
                localCount += xEnd - xStart;
            }
        }
#if defined(__ARM_NEON)
        localContrib += vaddvq_f64(contribLo) + vaddvq_f64(contribHi);
#endif
        std::lock_guard<std::mutex> guard(totals);
        totalContrib += localContrib;
        totalCount += localCount;
    });

    acc->contributionSum += totalContrib;
    acc->contributionCount += totalCount;
    acc->merged++;
}

/** Writes the merged CFA into a direct buffer; returns mean contribution. */
JNIEXPORT jfloat JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nFinish(
        JNIEnv* env, jobject, jlong handle, jobject out) {
    auto* acc = reinterpret_cast<Accumulator*>(handle);
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(out));
    if (dst == nullptr) return 0.0f;

    const int w = acc->width, h = acc->height;
    const int ceiling = acc->white;
    parallelBands(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            for (int x = 0; x < w; ++x) {
                size_t i = static_cast<size_t>(y) * w + x;
                int v = static_cast<int>(acc->sum[i] / acc->weight[i]);
                v = std::clamp(v, 0, ceiling);
                dst[i * 2] = static_cast<uint8_t>(v & 0xFF);
                dst[i * 2 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            }
        }
    });

    // Free the heavy buffers immediately; only the handle remains.
    acc->sum.clear(); acc->sum.shrink_to_fit();
    acc->weight.clear(); acc->weight.shrink_to_fit();
    acc->reference.clear(); acc->reference.shrink_to_fit();

    return acc->contributionCount == 0
           ? 0.0f
           : static_cast<jfloat>(acc->contributionSum / acc->contributionCount);
}

JNIEXPORT jint JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nFramesMerged(JNIEnv*, jobject, jlong handle) {
    return reinterpret_cast<Accumulator*>(handle)->merged;
}

/**
 * Noise sigma the merge measured at mid brightness, in sensor codes.
 *
 * The merge already estimates this per brightness bin, from frame-to-frame
 * differences in the burst, and uses it to decide how much to trust each pixel.
 * Reporting it turns an internal quantity into the one number that says how
 * noisy the scene actually was, which is what makes the merge's own claims
 * about improvement checkable rather than asserted.
 *
 * The stored value is the squared rejection threshold, tolerance * sigma, so
 * the tolerance has to be divided back out.
 */
JNIEXPORT jfloat JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nEstimatedSigma(
        JNIEnv*, jobject, jlong handle) {
    auto* acc = reinterpret_cast<Accumulator*>(handle);
    if (acc == nullptr || !acc->noiseReady || acc->noiseTolerance <= 0.0f) return 0.0f;
    const float variance = acc->noiseVar[kNoiseBins / 2];
    if (variance <= 0.0f) return 0.0f;
    return std::sqrt(variance) / acc->noiseTolerance;
}

}  // extern "C"

// ---------------------------------------------------------------------------
// Develop: merged CFA -> sRGB, written directly into a Bitmap's pixels.
//
// Keeps the whole path off the Java heap. The merged data is already in a
// native buffer and the destination is the Bitmap's own pixel store, so no
// intermediate array exists at any point.
// ---------------------------------------------------------------------------

#include <android/bitmap.h>

namespace {

/**
 * Scratch buffers kept between captures.
 *
 * Develop allocated around 150 MB of working buffers every time it ran -- a
 * normalised plane, a luma plane and a copy of the output -- and every one of
 * those pages had to be faulted in on first touch. That cost was measured
 * elsewhere in this project at roughly 1.4 ms per megabyte, which puts a fifth
 * of a second of develop into the kernel zeroing pages that the previous
 * capture had just finished with.
 *
 * Holding them costs memory the app is already holding far more of, and the
 * pool is released when the system asks for memory back.
 */
class Scratch {
public:
    std::vector<float>& floats(size_t n) {
        if (floats_.size() < n) floats_.resize(n);
        return floats_;
    }

    std::vector<float>& floatsB(size_t n) {
        if (floatsB_.size() < n) floatsB_.resize(n);
        return floatsB_;
    }

    std::vector<float>& floatsC(size_t n) {
        if (floatsC_.size() < n) floatsC_.resize(n);
        return floatsC_;
    }

    std::vector<uint8_t>& bytes(size_t n) {
        if (bytes_.size() < n) bytes_.resize(n);
        return bytes_;
    }

    void release() {
        floats_ = {}; floatsB_ = {}; floatsC_ = {}; bytes_ = {};
    }

private:
    std::vector<float> floats_, floatsB_, floatsC_;
    std::vector<uint8_t> bytes_;
};

// Develop is not reentrant -- one capture at a time -- so a single pool guarded
// by a lock is enough, and the lock is taken once per call rather than per
// pixel.
Scratch gScratch;
std::mutex gScratchLock;

constexpr int kGammaLutSize = 4096;
float gGammaLut[kGammaLutSize];
bool gGammaReady = false;

void ensureGammaLut() {
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

void buildShoulderLut(ShoulderLut& lut, const ToneParams& t) {
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
    // sit at 1 and nothing can tell them apart.
    const float scenePeak = std::max(r, std::max(g, b));

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
        if (t.desatStrength > 0.0f && scenePeak > t.desatStart) {
            const float k = 1.0f - t.desatStart / scenePeak;
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

/**
 * The x half of the bilinear weights, and the runs of pixels sharing a cell.
 *
 * Both depend only on x, and every one of the 3072 rows recomputes them
 * identically. At 4080 pixels across a 17-column grid there are sixteen runs of
 * about 240 pixels, so a run's four corner gains can be read once for the run
 * rather than once for each of its pixels.
 */
struct ShadingPlan {
    struct Run {
        int from, to;  // half-open range of x
        int x0, x1;    // the grid columns this run interpolates between
    };
    std::vector<float> tx;
    std::vector<Run> runs;
};

ShadingPlan buildShadingPlan(int columns, int width) {
    ShadingPlan plan;
    plan.tx.resize(static_cast<size_t>(width));
    for (int x = 0; x < width; ++x) {
        const float fx = (static_cast<float>(x) / (width - 1)) * (columns - 1);
        const int x0 = std::clamp(static_cast<int>(fx), 0, columns - 1);
        const int x1 = std::min(x0 + 1, columns - 1);
        plan.tx[static_cast<size_t>(x)] = std::clamp(fx - x0, 0.0f, 1.0f);
        // x0 is non-decreasing in x, so a changed value always opens a new run.
        if (plan.runs.empty() || plan.runs.back().x0 != x0) {
            plan.runs.push_back({x, x + 1, x0, x1});
        } else {
            plan.runs.back().to = x + 1;
        }
    }
    return plan;
}

/**
 * Shading and white balance, one pixel at a time.
 *
 * This is the form DevelopParityTest pinned to ShadingMap.kt. It is kept
 * because the fast path below is only permitted to be a rearrangement of it,
 * and something has to say what it is a rearrangement *of*.
 * ShadingSpeedDeviceTest runs the two against each other at 4080x3072 with a
 * 17x13 map, where a run is 240 pixels -- the case the fast path exists for,
 * and one the 64x48 parity fixture cannot reach, its cells being four pixels
 * wide.
 */
void applyShadingReference(float* plane, int width, int height,
                           const float* shadingPtr, int columns, int rows,
                           const float* wb) {
    parallelBands(height, [&](int yStart, int yEnd) {
        for (int y = yStart; y < yEnd; ++y) {
            const int rowParity = (y & 1) * 2;
            const size_t rowBase = static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                const int site = rowParity + (x & 1);
                plane[rowBase + x] *= shadingGain(shadingPtr, columns, rows,
                                                  x, y, width, height, site) * wb[site];
            }
        }
    });
}

/**
 * The same correction with everything that does not vary per pixel lifted out.
 *
 * Three things were being recomputed twelve and a half million times: the row's
 * position in the grid, which is constant along a row; the column's, which is
 * the same for every row; and the four corner gains, which hold for the whole
 * run. What is left inside the loop is one table read and the interpolation
 * itself, written in the same order and the same associations as the reference
 * above.
 *
 * That is deliberately not the same as saying the answers are identical. The
 * build compiles with -ffast-math, and the compiler reassociates and fuses the
 * two loops differently because their surroundings differ: about a fifth of the
 * plane comes back one unit in the last place away, some 1e-7 on values running
 * to 3.5. ShadingSpeedDeviceTest bounds that rather than asserting equality,
 * and says what the bound is there to rule out.
 *
 * The two sites of a row are carried side by side in `a0`..`b1` rather than
 * split into two loops, because splitting them would halve the sequential
 * access to the plane, which is the one thing this pass does well.
 */
void applyShadingHoisted(float* plane, int width, int height,
                         const float* shadingPtr, int columns, int rows,
                         const float* wb) {
    if (shadingPtr == nullptr || columns <= 0 || rows <= 0 || width <= 1 || height <= 1) {
        // shadingGain answers 1 to every one of these, so only balance is left.
        parallelBands(height, [&](int yStart, int yEnd) {
            for (int y = yStart; y < yEnd; ++y) {
                const int rowParity = (y & 1) * 2;
                const size_t rowBase = static_cast<size_t>(y) * width;
                for (int x = 0; x < width; ++x) {
                    plane[rowBase + x] *= wb[rowParity + (x & 1)];
                }
            }
        });
        return;
    }

    const ShadingPlan plan = buildShadingPlan(columns, width);

    parallelBands(height, [&](int yStart, int yEnd) {
        for (int y = yStart; y < yEnd; ++y) {
            const float fy = (static_cast<float>(y) / (height - 1)) * (rows - 1);
            const int y0 = std::clamp(static_cast<int>(fy), 0, rows - 1);
            const int y1 = std::min(y0 + 1, rows - 1);
            const float ty = std::clamp(fy - y0, 0.0f, 1.0f);
            const int rowParity = (y & 1) * 2;
            const size_t rowBase = static_cast<size_t>(y) * width;

            const float* upper = shadingPtr + static_cast<size_t>(y0) * columns * 4;
            const float* lower = shadingPtr + static_cast<size_t>(y1) * columns * 4;

            for (const ShadingPlan::Run& run : plan.runs) {
                float a0[2], a1[2], b0[2], b1[2];
                for (int p = 0; p < 2; ++p) {
                    const int channel = rowParity + p;
                    a0[p] = upper[static_cast<size_t>(run.x0) * 4 + channel];
                    a1[p] = upper[static_cast<size_t>(run.x1) * 4 + channel];
                    b0[p] = lower[static_cast<size_t>(run.x0) * 4 + channel];
                    b1[p] = lower[static_cast<size_t>(run.x1) * 4 + channel];
                }
                for (int x = run.from; x < run.to; ++x) {
                    const int p = x & 1;
                    const float tx = plan.tx[static_cast<size_t>(x)];
                    const float top = a0[p] * (1.0f - tx) + a1[p] * tx;
                    const float bottom = b0[p] * (1.0f - tx) + b1[p] * tx;
                    plane[rowBase + x] *=
                        (top * (1.0f - ty) + bottom * ty) * wb[rowParity + p];
                }
            }
        }
    });
}

/** Black level, normalise and clamp. The develop's first pass, on its own. */
void applyBlackLevel(const uint16_t* src, float* plane, int width, int height,
                     const int* black, float range) {
    parallelBands(height, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const int rowParity = (y & 1) * 2;
            const size_t rowBase = static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                const int site = rowParity + (x & 1);
                plane[rowBase + x] =
                    std::max((static_cast<float>(src[rowBase + x]) - black[site]) / range, 0.0f);
            }
        }
    });
}

/**
 * The first and third passes in one, for the harness.
 *
 * Both are pure per-pixel maps, so they fold trivially -- *if* nothing has to
 * happen between them. Something does: the hot pixel pass sits in the middle
 * and has to see values that are black-subtracted and not yet shaded. So this
 * is not a shipping path and it renders a wrong picture; it exists to price one
 * fewer sweep of a 50 MB plane before anyone builds the pipeline that would
 * fold all three honestly.
 */
void applyBlackAndShading(const uint16_t* src, float* plane, int width, int height,
                          const int* black, float range,
                          const float* shadingPtr, int columns, int rows,
                          const float* wb) {
    const bool tabulated =
        shadingPtr != nullptr && columns > 0 && rows > 0 && width > 1 && height > 1;
    ShadingPlan plan;
    if (tabulated) plan = buildShadingPlan(columns, width);

    parallelBands(height, [&](int yStart, int yEnd) {
        for (int y = yStart; y < yEnd; ++y) {
            const int rowParity = (y & 1) * 2;
            const size_t rowBase = static_cast<size_t>(y) * width;

            auto blackAt = [&](int x) {
                const int site = rowParity + (x & 1);
                return std::max(
                    (static_cast<float>(src[rowBase + x]) - black[site]) / range, 0.0f);
            };

            if (!tabulated) {
                for (int x = 0; x < width; ++x) {
                    plane[rowBase + x] = blackAt(x) * wb[rowParity + (x & 1)];
                }
                continue;
            }

            const float fy = (static_cast<float>(y) / (height - 1)) * (rows - 1);
            const int y0 = std::clamp(static_cast<int>(fy), 0, rows - 1);
            const int y1 = std::min(y0 + 1, rows - 1);
            const float ty = std::clamp(fy - y0, 0.0f, 1.0f);
            const float* upper = shadingPtr + static_cast<size_t>(y0) * columns * 4;
            const float* lower = shadingPtr + static_cast<size_t>(y1) * columns * 4;

            for (const ShadingPlan::Run& run : plan.runs) {
                float a0[2], a1[2], b0[2], b1[2];
                for (int p = 0; p < 2; ++p) {
                    const int channel = rowParity + p;
                    a0[p] = upper[static_cast<size_t>(run.x0) * 4 + channel];
                    a1[p] = upper[static_cast<size_t>(run.x1) * 4 + channel];
                    b0[p] = lower[static_cast<size_t>(run.x0) * 4 + channel];
                    b1[p] = lower[static_cast<size_t>(run.x1) * 4 + channel];
                }
                for (int x = run.from; x < run.to; ++x) {
                    const int p = x & 1;
                    const float tx = plan.tx[static_cast<size_t>(x)];
                    const float top = a0[p] * (1.0f - tx) + a1[p] * tx;
                    const float bottom = b0[p] * (1.0f - tx) + b1[p] * tx;
                    plane[rowBase + x] =
                        blackAt(x) * (top * (1.0f - ty) + bottom * ty) * wb[rowParity + p];
                }
            }
        }
    });
}

/**
 * Replaces defective sensor sites in a normalised CFA plane.
 *
 * Mirrors HotPixels.kt, where the tests are. Compares against the four sites two
 * pixels away, which on a Bayer grid are the nearest of the same colour, and
 * only replaces a site that lies outside the range of all four by a margin --
 * so a genuine highlight shared with a neighbour survives.
 *
 * The merge cannot do this job: it suppresses whatever varies between frames,
 * and a defective site is wrong identically in all of them. Cleaning up the
 * surrounding noise only makes the dots more obvious.
 */
long suppressHotPixels(float* plane, int width, int height, float threshold) {
    if (width < 5 || height < 5 || threshold <= 0.0f) return 0;
    std::atomic<long> replaced{0};

    parallelBands(height, [&](int y0, int y1) {
        const int from = std::max(y0, 2);
        const int to = std::min(y1, height - 2);
        long local = 0;
        for (int y = from; y < to; ++y) {
            const size_t row = static_cast<size_t>(y) * width;
            for (int x = 2; x < width - 2; ++x) {
                const float here = plane[row + x];
                const float a = plane[row + x - 2];
                const float b = plane[row + x + 2];
                const float c = plane[row - 2 * static_cast<size_t>(width) + x];
                const float d = plane[row + 2 * static_cast<size_t>(width) + x];

                const float highest = std::max(std::max(a, b), std::max(c, d));
                const float lowest = std::min(std::min(a, b), std::min(c, d));

                if (here > highest + threshold) {
                    plane[row + x] = highest;
                    ++local;
                } else if (here < lowest - threshold) {
                    plane[row + x] = lowest;
                    ++local;
                }
            }
        }
        replaced.fetch_add(local);
    });
    return replaced.load();
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

void buildDisplayLut(DisplayLut& lut, const ToneParams& t) {
    for (int i = 0; i < kGammaLutSize; ++i) {
        lut.bytes[i] = toByte(renderDisplay(gGammaLut[i], t));
    }
}

/**
 * Which part of the develop's last pass to leave out, for the ablation harness.
 *
 * The pass reports as one figure -- `demosaic+tone`, and the largest item in a
 * capture -- and this log's recurring lesson is that one figure is usually two.
 * Splitting it by running it in halves would not work: the halves are fused
 * precisely so that the demosaic's output never leaves the registers, and
 * writing it to an intermediate buffer would add 50 MB of traffic and measure
 * that instead. So each variant is the whole pass with one item removed, timed
 * against the whole pass, and the difference is what the item costs.
 *
 * Every variant still writes all four bytes of every pixel from values the
 * demosaic produced, so nothing can be deleted as dead.
 */
enum ToneAblation {
    kToneFull = 0,       // as shipped
    kToneNoMatrix = 1,   // the 3x3 colour matrix, nine multiplies and six adds
    kToneNoRender = 2,   // renderLinear: three maxima and two branches
    kToneNoDisplay = 3,  // the display table, replaced by a bare quantise
    kToneNone = 4,       // the demosaic on its own
    kToneNoShoulder = 5, // renderLinear without the highlight roll-off
    kToneNoDesat = 6,    // renderLinear without the highlight desaturation
    kToneNoExp = 7,      // the roll-off with its exponential taken out
    kToneExactShoulder = 8,  // the roll-off computed rather than tabulated
    kToneUnsplitDemosaic = 9,  // the demosaic dispatching per pixel on its site
    kToneScalarDemosaic = 10,  // the split loop, a pixel at a time
    kToneNoDisplayChain = 11,  // no clamp, no scale, no convert and no lookup
    kToneFlatShoulder = 12,    // the roll-off's branch and multiplies, no lookup
    kToneCensus = 13,    // as shipped, and counts which way the branches went
};

/**
 * A byte out of a float for the price of nothing, for the ablation harness.
 *
 * `kToneNoDisplay` swaps the display table for `toByte`, which is a clamp, a
 * multiply-add and a convert -- so all it could ever measure was whether a 4 KB
 * lookup costs more than one extra fma. Pooled over thirty runs it came back
 * 241 rounds of 480, settling that the *load* is free and saying nothing
 * whatever about the clamp, the scale and the convert that both sides pay.
 *
 * This takes the low byte of the float's own bits: no clamp, no scale, no
 * convert, no load, and the value is still fully consumed so nothing above it
 * can be deleted as dead. It renders noise on purpose. What `kToneFull` costs
 * against it is the whole display chain, which is 1.06 to 1.32x of the pass --
 * a fifth of it, invisible to the older probe.
 */
inline uint8_t rawByte(float v) {
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    return static_cast<uint8_t>(bits);
}

#if defined(__ARM_NEON)
/**
 * The six taps a row supplies to an octet of pixels.
 *
 * A row of the plane is read three times with a deinterleaving load, at x-2, x
 * and x+2, which yields the even and odd positions of each -- and those six
 * vectors are every horizontal tap both halves of the octet need. `em2` is
 * {x-2, x, x+2, x+4}, `o0` is {x+1, x+3, x+5, x+7}, and so on.
 *
 * Deinterleaving is what makes this work at all. The demosaic wants pixels two
 * apart, because that is how far apart two sites of the same colour are, and a
 * plain load gives four *adjacent* floats. `vld2q_f32` gives the evens and the
 * odds of eight, which is exactly the shape of a Bayer row.
 */
struct RowTaps {
    float32x4_t em2, om2, e0, o0, ep2, op2;
};

inline RowTaps rowTaps(const float* q) {
    const float32x4x2_t a = vld2q_f32(q - 2);
    const float32x4x2_t b = vld2q_f32(q);
    const float32x4x2_t c = vld2q_f32(q + 2);
    return {a.val[0], a.val[1], b.val[0], b.val[1], c.val[0], c.val[1]};
}

/** The thirteen taps of one lane-set, named as the scalar bodies name them. */
struct Taps {
    float32x4_t c0, nA, sA, eA, wA, nn, ss, ee, ww, diag;
};

/** Green site, four at a time. Mirrors `atGreen`, operation for operation. */
inline void greenVec(const Taps& t, bool red,
                     float32x4_t& r, float32x4_t& g, float32x4_t& b) {
    float32x4_t h = vmulq_n_f32(t.c0, 5.0f);
    h = vmlaq_n_f32(h, vaddq_f32(t.wA, t.eA), 4.0f);
    h = vsubq_f32(h, vaddq_f32(t.ww, t.ee));
    h = vsubq_f32(h, t.diag);
    h = vmlaq_n_f32(h, vaddq_f32(t.nn, t.ss), 0.5f);

    float32x4_t v = vmulq_n_f32(t.c0, 5.0f);
    v = vmlaq_n_f32(v, vaddq_f32(t.nA, t.sA), 4.0f);
    v = vsubq_f32(v, vaddq_f32(t.nn, t.ss));
    v = vsubq_f32(v, t.diag);
    v = vmlaq_n_f32(v, vaddq_f32(t.ww, t.ee), 0.5f);

    const float32x4_t zero = vdupq_n_f32(0.0f);
    r = vmaxq_f32(vmulq_n_f32(red ? h : v, 0.125f), zero);
    g = vmaxq_f32(t.c0, zero);
    b = vmaxq_f32(vmulq_n_f32(red ? v : h, 0.125f), zero);
}

/** Red or blue site, four at a time. Mirrors `atColour`. */
inline void colourVec(const Taps& t, bool red,
                      float32x4_t& r, float32x4_t& g, float32x4_t& b) {
    const float32x4_t axis =
        vaddq_f32(vaddq_f32(vaddq_f32(t.nA, t.sA), t.eA), t.wA);
    const float32x4_t axis2 =
        vaddq_f32(vaddq_f32(vaddq_f32(t.nn, t.ss), t.ee), t.ww);

    float32x4_t gv = vmulq_n_f32(t.c0, 4.0f);
    gv = vmlaq_n_f32(gv, axis, 2.0f);
    gv = vmulq_n_f32(vsubq_f32(gv, axis2), 0.125f);

    float32x4_t ov = vmulq_n_f32(t.c0, 6.0f);
    ov = vmlaq_n_f32(ov, t.diag, 2.0f);
    ov = vmlsq_n_f32(ov, axis2, 1.5f);
    ov = vmulq_n_f32(ov, 0.125f);

    const float32x4_t zero = vdupq_n_f32(0.0f);
    r = vmaxq_f32(red ? t.c0 : ov, zero);
    g = vmaxq_f32(gv, zero);
    b = vmaxq_f32(red ? ov : t.c0, zero);
}
#endif  // __ARM_NEON


/**
 * Demosaic, colour, tone and quantise, in one pass over the plane.
 *
 * A template rather than a copy: `nDevelop` runs the `kToneFull` instantiation
 * and the harness runs the others, so a measurement here is a measurement of
 * the code that ships. The alternative -- a second copy of the demosaic kept
 * beside the real one for benchmarking -- would drift within a session.
 */
template <int Ablation>
void demosaicAndTone(const float* plane, int width, int height,
                     uint8_t* dstBase, int stride,
                     const int* cfa, const float* m,
                     const ToneParams& tone, const DisplayLut& display,
                     const ShoulderLut& shoulder,
                     std::atomic<long>* aboveKnee = nullptr) {
    const int w1 = width;
    const int w2 = width * 2;

    // Gradient-corrected linear interpolation (Malvar, He and Cutler). The
    // measured sample at this site is kept exactly; only the two missing
    // colours are interpolated, with a second-derivative term carrying the
    // luminance gradient across channels so the planes agree about edge
    // position. Mirrors Demosaic.kt, where the tests are.
    //
    // Two functions of the site rather than one three-way branch inside the
    // loop, because a pixel's site is decided by its position in the 2x2 and by
    // nothing else -- so the caller can know it without asking.
    auto atGreen = [&](const float* p, bool redHorizontal) -> Rgb {
        const float c0 = p[0];
        const float nA = p[-w1], sA = p[w1];
        const float eA = p[1], wA = p[-1];
        const float nn = p[-w2], ss = p[w2];
        const float ee = p[2], ww = p[-2];
        const float diag = p[-w1 - 1] + p[-w1 + 1] + p[w1 - 1] + p[w1 + 1];
        const float alongH = 5.0f * c0 + 4.0f * (wA + eA) - (ww + ee) -
                             diag + 0.5f * (nn + ss);
        const float alongV = 5.0f * c0 + 4.0f * (nA + sA) - (nn + ss) -
                             diag + 0.5f * (ww + ee);
        // The correction extrapolates and can overshoot past black.
        return {
            std::max((redHorizontal ? alongH : alongV) * 0.125f, 0.0f),
            std::max(c0, 0.0f),
            std::max((redHorizontal ? alongV : alongH) * 0.125f, 0.0f),
        };
    };
    auto atColour = [&](const float* p, bool red) -> Rgb {
        const float c0 = p[0];
        const float diag = p[-w1 - 1] + p[-w1 + 1] + p[w1 - 1] + p[w1 + 1];
        const float axis = p[-w1] + p[w1] + p[1] + p[-1];
        const float axis2 = p[-w2] + p[w2] + p[2] + p[-2];
        const float g = (4.0f * c0 + 2.0f * axis - axis2) * 0.125f;
        const float o = (6.0f * c0 + 2.0f * diag - 1.5f * axis2) * 0.125f;
        return {
            std::max(red ? c0 : o, 0.0f),
            std::max(g, 0.0f),
            std::max(red ? o : c0, 0.0f),
        };
    };

    parallelBands(height, [&](int yStart, int yEnd) {
        float acc[3];
        int cnt[3];
        [[maybe_unused]] long overKnee = 0;
        for (int y = yStart; y < yEnd; ++y) {
            uint8_t* row = dstBase + static_cast<size_t>(y) * stride;
            const float* const p0 = plane + static_cast<size_t>(y) * width;

            // Split in two so that the vector path can reach the second half
            // directly: it has the colour matrix's inputs in registers already
            // and has no reason to spill them for nine scalar multiplies. What
            // is below the split is a table lookup and two data-dependent
            // branches, which is the part that does not want to be a vector.
            auto emitTone = [&](int x, float r, float g, float b) {
                uint8_t* q = row + static_cast<size_t>(x) * 4;
                if constexpr (Ablation == kToneCensus) {
                    if (std::max(r, std::max(g, b)) > tone.knee) ++overKnee;
                }
                if constexpr (Ablation == kToneNoShoulder) {
                    renderLinearParts<false, true>(r, g, b, tone, shoulder);
                } else if constexpr (Ablation == kToneNoDesat) {
                    renderLinearParts<true, false>(r, g, b, tone, shoulder);
                } else if constexpr (Ablation == kToneNoExp) {
                    renderLinearParts<true, true, false, false>(r, g, b, tone, shoulder);
                } else if constexpr (Ablation == kToneFlatShoulder) {
                    renderLinearParts<true, true, true, true, false>(r, g, b, tone,
                                                                     shoulder);
                } else if constexpr (Ablation == kToneExactShoulder) {
                    renderLinearParts<true, true, true, false>(r, g, b, tone, shoulder);
                } else if constexpr (Ablation != kToneNoRender) {
                    renderLinear(r, g, b, tone, shoulder);
                }

                // RGBA_8888 is byte order R,G,B,A in memory.
                if constexpr (Ablation == kToneNoDisplayChain) {
                    q[0] = rawByte(r);
                    q[1] = rawByte(g);
                    q[2] = rawByte(b);
                } else if constexpr (Ablation == kToneNoDisplay) {
                    q[0] = toByte(r);
                    q[1] = toByte(g);
                    q[2] = toByte(b);
                } else {
                    q[0] = display(r);
                    q[1] = display(g);
                    q[2] = display(b);
                }
                q[3] = 255;
            };

            auto emit = [&](int x, Rgb v) {
                if constexpr (Ablation == kToneNone) {
                    // Still three bytes out of three demosaiced values, so the
                    // pass above it cannot be deleted as unused.
                    uint8_t* q = row + static_cast<size_t>(x) * 4;
                    q[0] = toByte(v.r);
                    q[1] = toByte(v.g);
                    q[2] = toByte(v.b);
                    q[3] = 255;
                    return;
                }

                float r, g, b;
                if constexpr (Ablation == kToneNoMatrix) {
                    r = v.r; g = v.g; b = v.b;
                } else {
                    r = m[0] * v.r + m[1] * v.g + m[2] * v.b;
                    g = m[3] * v.r + m[4] * v.g + m[5] * v.b;
                    b = m[6] * v.r + m[7] * v.g + m[8] * v.b;
                }
                emitTone(x, r * tone.exposureGain, g * tone.exposureGain,
                         b * tone.exposureGain);
            };

#if defined(__ARM_NEON)
            auto matrixVec = [&](float32x4_t r0, float32x4_t g0, float32x4_t b0,
                                 float32x4_t& r, float32x4_t& g, float32x4_t& b) {
                if constexpr (Ablation == kToneNoMatrix) {
                    r = r0; g = g0; b = b0;
                } else {
                    r = vmulq_n_f32(r0, m[0]);
                    r = vmlaq_n_f32(r, g0, m[1]);
                    r = vmlaq_n_f32(r, b0, m[2]);
                    g = vmulq_n_f32(r0, m[3]);
                    g = vmlaq_n_f32(g, g0, m[4]);
                    g = vmlaq_n_f32(g, b0, m[5]);
                    b = vmulq_n_f32(r0, m[6]);
                    b = vmlaq_n_f32(b, g0, m[7]);
                    b = vmlaq_n_f32(b, b0, m[8]);
                }
                r = vmulq_n_f32(r, tone.exposureGain);
                g = vmulq_n_f32(g, tone.exposureGain);
                b = vmulq_n_f32(b, tone.exposureGain);
            };
#endif

            // Border: no 5x5 support, so the simple gather, with the bounds
            // checks that only these pixels need.
            auto borderPixel = [&](int x) {
                acc[0] = acc[1] = acc[2] = 0.0f;
                cnt[0] = cnt[1] = cnt[2] = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    const int sy = y + dy;
                    if (sy < 0 || sy >= height) continue;
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int sx = x + dx;
                        if (sx < 0 || sx >= width) continue;
                        const int c = cfa[(sy & 1) * 2 + (sx & 1)];
                        acc[c] += plane[static_cast<size_t>(sy) * width + sx];
                        cnt[c]++;
                    }
                }
                emit(x, {
                    cnt[0] ? acc[0] / cnt[0] : 0.0f,
                    cnt[1] ? acc[1] / cnt[1] : 0.0f,
                    cnt[2] ? acc[2] / cnt[2] : 0.0f,
                });
            };

            if constexpr (Ablation == kToneUnsplitDemosaic) {
                // The shape this replaced, kept so the restructuring can be
                // timed against it in one binary: every pixel asks whether it is
                // on the border, then reads its own site out of the CFA pattern
                // and dispatches on it. The arithmetic is the same two functions
                // above, so the two cannot drift apart.
                const bool interiorRow = (y >= 2 && y < height - 2);
                for (int x = 0; x < width; ++x) {
                    if (!interiorRow || x < 2 || x >= width - 2) {
                        borderPixel(x);
                    } else {
                        const int here = cfa[(y & 1) * 2 + (x & 1)];
                        if (here == 1) {
                            emit(x, atGreen(p0 + x,
                                            cfa[(y & 1) * 2 + ((x + 1) & 1)] == 0));
                        } else {
                            emit(x, atColour(p0 + x, here == 0));
                        }
                    }
                }
                continue;
            }

            if (y < 2 || y >= height - 2) {
                for (int x = 0; x < width; ++x) borderPixel(x);
                continue;
            }
            const int lead = std::min(2, width);
            const int trailFrom = std::max(2, width - 2);
            for (int x = 0; x < lead; ++x) borderPixel(x);
            for (int x = trailFrom; x < width; ++x) borderPixel(x);

            // **In a Bayer row the non-green sites are all one colour.** A row
            // is red-and-green or green-and-blue, never both, so `here`
            // alternates green / that colour with period two -- and the choice
            // `redHorizontal` makes, which is the colour of the horizontal
            // neighbour, is a property of the row rather than of the pixel.
            // Both come out of the loop, and with them the CFA reads and the
            // three-way branch.
            const int site = (y & 1) * 2;
            const bool greenFirst = (cfa[site] == 1);
            const bool red = ((greenFirst ? cfa[site + 1] : cfa[site]) == 0);

            // Two pixels at a time, so which of the two bodies a pixel needs is
            // settled by where it sits in the unrolled loop rather than by a
            // test. The interior starts at x = 2, but the parity is written out
            // rather than assumed.
            int x = 2;
#if defined(__ARM_NEON)
            // Eight at a time: four green sites and four of the row's colour,
            // out of one set of deinterleaving loads. Both halves want the same
            // six vectors per row, which is why the octet rather than the
            // quartet is the natural unit here.
            //
            // The loads reach x-2 to x+9 on five rows, so the guard is about the
            // widest tap and not about the last output pixel; what it cannot
            // cover falls through to the pair loop below.
            if constexpr (Ablation != kToneScalarDemosaic) {
                for (; x + 9 < width && x + 7 < trailFrom; x += 8) {
                    const float* const q = p0 + x;
                    const RowTaps R0 = rowTaps(q - w2);
                    const RowTaps R1 = rowTaps(q - w1);
                    const RowTaps R2 = rowTaps(q);
                    const RowTaps R3 = rowTaps(q + w1);
                    const RowTaps R4 = rowTaps(q + w2);

                    Taps ev;
                    ev.c0 = R2.e0;  ev.wA = R2.om2; ev.eA = R2.o0;
                    ev.ww = R2.em2; ev.ee = R2.ep2;
                    ev.nA = R1.e0;  ev.sA = R3.e0;
                    ev.nn = R0.e0;  ev.ss = R4.e0;
                    ev.diag = vaddq_f32(vaddq_f32(vaddq_f32(R1.om2, R1.o0), R3.om2),
                                        R3.o0);

                    Taps od;
                    od.c0 = R2.o0;  od.wA = R2.e0;  od.eA = R2.ep2;
                    od.ww = R2.om2; od.ee = R2.op2;
                    od.nA = R1.o0;  od.sA = R3.o0;
                    od.nn = R0.o0;  od.ss = R4.o0;
                    od.diag = vaddq_f32(vaddq_f32(vaddq_f32(R1.e0, R1.ep2), R3.e0),
                                        R3.ep2);

                    // Named by position, not by site: which of the two bodies a
                    // position gets is the row's business, settled once above.
                    float32x4_t er, eg, eb, orr, og, ob;
                    if (greenFirst) {
                        greenVec(ev, red, er, eg, eb);
                        colourVec(od, red, orr, og, ob);
                    } else {
                        colourVec(ev, red, er, eg, eb);
                        greenVec(od, red, orr, og, ob);
                    }

                    float ar[4], ag[4], ab[4], br[4], bg[4], bb[4];
                    if constexpr (Ablation == kToneNone) {
                        vst1q_f32(ar, er);  vst1q_f32(ag, eg);  vst1q_f32(ab, eb);
                        vst1q_f32(br, orr); vst1q_f32(bg, og);  vst1q_f32(bb, ob);
                        for (int i = 0; i < 4; ++i) {
                            emit(x + 2 * i, Rgb{ar[i], ag[i], ab[i]});
                            emit(x + 2 * i + 1, Rgb{br[i], bg[i], bb[i]});
                        }
                        continue;
                    }

                    // The colour matrix and the exposure gain stay in the
                    // vectors. They were scalar in the first version of this and
                    // it was waste: nine multiplies and six adds a pixel, on
                    // values already sitting in registers, spilled to the stack
                    // to be done one lane at a time. The harness had the matrix
                    // at 1.09 to 1.25x of the pass by then -- it had risen above
                    // the resolution floor precisely because the demosaic around
                    // it got faster.
                    float32x4_t ER, EG, EB, OR, OG, OB;
                    matrixVec(er, eg, eb, ER, EG, EB);
                    matrixVec(orr, og, ob, OR, OG, OB);

                    // Back to scalar here, and no further down: what is left is
                    // a table lookup and two data-dependent branches. Through
                    // the stack, which is L1.
                    vst1q_f32(ar, ER); vst1q_f32(ag, EG); vst1q_f32(ab, EB);
                    vst1q_f32(br, OR); vst1q_f32(bg, OG); vst1q_f32(bb, OB);
                    for (int i = 0; i < 4; ++i) {
                        emitTone(x + 2 * i, ar[i], ag[i], ab[i]);
                        emitTone(x + 2 * i + 1, br[i], bg[i], bb[i]);
                    }
                }
            }
#endif

            if (greenFirst == ((x & 1) == 0)) {
                for (; x + 1 < trailFrom; x += 2) {
                    emit(x, atGreen(p0 + x, red));
                    emit(x + 1, atColour(p0 + x + 1, red));
                }
            } else {
                for (; x + 1 < trailFrom; x += 2) {
                    emit(x, atColour(p0 + x, red));
                    emit(x + 1, atGreen(p0 + x + 1, red));
                }
            }
            for (; x < trailFrom; ++x) {
                emit(x, greenFirst == ((x & 1) == 0) ? atGreen(p0 + x, red)
                                                     : atColour(p0 + x, red));
            }
        }
        if constexpr (Ablation == kToneCensus) {
            if (aboveKnee != nullptr) aboveKnee->fetch_add(overKnee);
        }
    });
}


void runToneVariant(int variant, const float* plane, int width, int height,
                    uint8_t* dst, int stride, const int* cfa, const float* m,
                    const ToneParams& tone, const DisplayLut& display,
                    const ShoulderLut& shoulder, std::atomic<long>* aboveKnee) {
    switch (variant) {
        case kToneNoMatrix:
            demosaicAndTone<kToneNoMatrix>(plane, width, height, dst, stride,
                                           cfa, m, tone, display, shoulder); break;
        case kToneNoRender:
            demosaicAndTone<kToneNoRender>(plane, width, height, dst, stride,
                                           cfa, m, tone, display, shoulder); break;
        case kToneNoDisplay:
            demosaicAndTone<kToneNoDisplay>(plane, width, height, dst, stride,
                                            cfa, m, tone, display, shoulder); break;
        case kToneNone:
            demosaicAndTone<kToneNone>(plane, width, height, dst, stride,
                                       cfa, m, tone, display, shoulder); break;
        case kToneNoShoulder:
            demosaicAndTone<kToneNoShoulder>(plane, width, height, dst, stride,
                                             cfa, m, tone, display, shoulder); break;
        case kToneNoDesat:
            demosaicAndTone<kToneNoDesat>(plane, width, height, dst, stride,
                                          cfa, m, tone, display, shoulder); break;
        case kToneNoExp:
            demosaicAndTone<kToneNoExp>(plane, width, height, dst, stride,
                                        cfa, m, tone, display, shoulder); break;
        case kToneExactShoulder:
            demosaicAndTone<kToneExactShoulder>(plane, width, height, dst, stride,
                                                cfa, m, tone, display, shoulder); break;
        case kToneUnsplitDemosaic:
            demosaicAndTone<kToneUnsplitDemosaic>(plane, width, height, dst, stride,
                                                  cfa, m, tone, display, shoulder); break;
        case kToneScalarDemosaic:
            demosaicAndTone<kToneScalarDemosaic>(plane, width, height, dst, stride,
                                                 cfa, m, tone, display, shoulder); break;
        case kToneNoDisplayChain:
            demosaicAndTone<kToneNoDisplayChain>(plane, width, height, dst, stride,
                                                 cfa, m, tone, display, shoulder); break;
        case kToneFlatShoulder:
            demosaicAndTone<kToneFlatShoulder>(plane, width, height, dst, stride,
                                               cfa, m, tone, display, shoulder); break;
        case kToneCensus:
            demosaicAndTone<kToneCensus>(plane, width, height, dst, stride,
                                         cfa, m, tone, display, shoulder, aboveKnee); break;
        default:
            demosaicAndTone<kToneFull>(plane, width, height, dst, stride,
                                       cfa, m, tone, display, shoulder); break;
    }
}

/**
 * A normalised CFA plane with something for every branch to do.
 *
 * The scene matters more than it looks, and here it decides the answer rather
 * than shading it. `renderLinear` has two data-dependent branches, so a plane
 * that never reaches the knee would report the rendering curve as nearly free;
 * one that clips everywhere would report it as the whole cost. So this ramps
 * across the frame, carries a per-site tint because a CFA plane is white
 * balanced by the time this pass sees it, and has fine detail on top so the
 * demosaic's gradient terms are not multiplying zeros. The harness counts how
 * many pixels ended up over the knee and the test prints it, so the figure
 * comes with the scene it describes.
 */
void fillScenePlane(float* plane, int width, int height) {
    for (int y = 0; y < height; ++y) {
        const float v = static_cast<float>(y) / height;
        for (int x = 0; x < width; ++x) {
            const float u = static_cast<float>(x) / width;
            const float base = 0.04f + 0.50f * (0.35f * u + 0.65f * v);
            const float detail = 0.03f * std::sin(x * 0.21f) * std::sin(y * 0.17f) +
                                 0.06f * std::sin(u * 31.0f + v * 17.0f);
            // Sites 0 and 3 are the red and blue corners of the 2x2; a white
            // balanced plane has them lifted relative to the two greens.
            const int site = (y & 1) * 2 + (x & 1);
            const float tint = (site == 0) ? 1.18f : (site == 3) ? 1.09f : 1.0f;
            plane[static_cast<size_t>(y) * width + x] =
                std::max((base + detail) * tint, 0.0f);
        }
    }
}

}  // namespace

extern "C" {

/**
 * Measures a global exposure multiplier from the green sites of the merged
 * frame. One number for the whole image: no local tone mapping.
 */
JNIEXPORT jfloat JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nAutoExposure(
        JNIEnv* env, jobject, jobject merged, jint width, jint height,
        jintArray jcfa, jintArray jblack, jint white,
        jfloatArray jgains, jfloat percentile, jfloat target) {
    auto* src = static_cast<const uint16_t*>(env->GetDirectBufferAddress(merged));
    if (src == nullptr) return 1.0f;

    int cfa[4], black[4];
    float gains[4];
    env->GetIntArrayRegion(jcfa, 0, 4, cfa);
    env->GetIntArrayRegion(jblack, 0, 4, black);
    env->GetFloatArrayRegion(jgains, 0, 4, gains);

    int lo = std::min(std::min(black[0], black[1]), std::min(black[2], black[3]));
    float range = static_cast<float>(std::max(1, white - lo));

    std::vector<float> samples;
    samples.reserve(8192);
    int stepY = std::max(1, height / 400);
    int stepX = std::max(1, width / 400);
    for (int y = 0; y < height; y += stepY) {
        for (int x = 0; x < width; x += stepX) {
            if (cfa[(y & 1) * 2 + (x & 1)] != 1) continue;
            float lin = (static_cast<float>(src[static_cast<size_t>(y) * width + x]) -
                         black[(y & 1) * 2 + (x & 1)]) / range;
            float g = (y & 1) == 0 ? gains[1] : gains[2];
            samples.push_back(std::max(lin, 0.0f) * g);
        }
    }
    if (samples.size() < 32) return 1.0f;

    std::sort(samples.begin(), samples.end());
    float bright = samples[static_cast<size_t>((samples.size() - 1) * percentile)];
    if (bright <= 1e-5f) return 64.0f;
    return std::clamp(target / bright, 0.25f, 64.0f);
}

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nDevelop(
        JNIEnv* env, jobject, jobject merged, jobject bitmap,
        jint width, jint height,
        jintArray jcfa, jintArray jblack, jint white,
        jfloatArray jgains, jfloatArray jmatrix,
        jfloat exposureGain, jfloat knee,
        jfloat contrast, jfloat desatStrength, jfloat desatStart, jfloat blackPoint,
        jfloatArray jshading, jint shadingColumns, jint shadingRows,
        jfloat hotPixelThreshold) {
    ensureGammaLut();

    // Stage clocks, declared here so they outlive the blocks they are taken in.
    int64_t tBlack = 0, tHotPixels = 0, tShading = 0, tDemosaic = 0;

    // Lens shading, when the camera reported a map for this capture. Raw is
    // defined as uncorrected, so without this every frame carries a stop and a
    // half of corner falloff that the camera's own JPEG path removes.
    std::vector<float> shading;
    const float* shadingPtr = nullptr;
    if (jshading != nullptr && shadingColumns > 0 && shadingRows > 0) {
        const jsize count = env->GetArrayLength(jshading);
        if (count == shadingColumns * shadingRows * 4) {
            shading.resize(static_cast<size_t>(count));
            env->GetFloatArrayRegion(jshading, 0, count, shading.data());
            shadingPtr = shading.data();
        }
    }

    ToneParams tone;
    tone.exposureGain = exposureGain;
    tone.knee = knee;
    tone.contrast = contrast;
    tone.desatStrength = desatStrength;
    tone.desatStart = desatStart;
    tone.blackPoint = blackPoint;

    auto* src = static_cast<const uint16_t*>(env->GetDirectBufferAddress(merged));
    if (src == nullptr) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }

    int cfa[4], black[4];
    float gains[4], m[9];
    env->GetIntArrayRegion(jcfa, 0, 4, cfa);
    env->GetIntArrayRegion(jblack, 0, 4, black);
    env->GetFloatArrayRegion(jgains, 0, 4, gains);
    env->GetFloatArrayRegion(jmatrix, 0, 9, m);

    int lo = std::min(std::min(black[0], black[1]), std::min(black[2], black[3]));
    const float range = static_cast<float>(std::max(1, white - lo));
    auto* dstBase = static_cast<uint8_t*>(pixels);
    const int stride = static_cast<int>(info.stride);

    // One pass to normalise, then a second to demosaic.
    //
    // The demosaic reads thirteen neighbours per pixel. Doing the black-level
    // subtraction, shading correction and white balance inside that read meant
    // repeating the work thirteen times over, through a lambda that clamped
    // both coordinates on every call -- for the interior, where nothing needs
    // clamping. Normalising once into a float plane turns those thirteen calls
    // into thirteen array reads. It costs one float per pixel, which is 50 MB
    // at twelve megapixels and native memory rather than managed heap.
    std::lock_guard<std::mutex> scratchGuard(gScratchLock);
    std::vector<float>& plane = gScratch.floats(static_cast<size_t>(width) * height);

    // Black level only, first. Defective sites are found before shading and
    // white balance are applied, so the comparison happens in the sensor's own
    // domain -- which is what lets the Kotlin fallback, which has no plane to
    // work on and corrects the CFA in place, arrive at the same answer.
    tBlack = nowMicros();
    applyBlackLevel(src, plane.data(), width, height, black, range);

    tHotPixels = nowMicros();
    if (hotPixelThreshold > 0.0f) {
        const long replaced = suppressHotPixels(plane.data(), width, height, hotPixelThreshold);
        if (replaced > 0) {
            LOGI("replaced %ld defective sites (%.4f%% of the sensor)",
                 replaced, 100.0 * replaced / (static_cast<double>(width) * height));
        }
    }

    // Then shading and white balance, on the corrected values.
    tShading = nowMicros();
    float wb[4];
    balanceBySite(cfa, gains, wb);
    applyShadingHoisted(plane.data(), width, height, shadingPtr,
                        shadingColumns, shadingRows, wb);

    // Built once for this capture's tone parameters, then read by every band.
    // On the stack rather than in a global: two develops on different threads
    // would otherwise be writing the same table while reading it.
    DisplayLut display;
    buildDisplayLut(display, tone);
    ShoulderLut shoulder;
    buildShoulderLut(shoulder, tone);

    tDemosaic = nowMicros();
    demosaicAndTone<kToneFull>(plane.data(), width, height, dstBase, stride,
                               cfa, m, tone, display, shoulder);

    const int64_t tEnd = nowMicros();
    LOGI("develop: black %lldms, hotpixels %lldms, shading %lldms, demosaic+tone %lldms, total %lldms",
         (tHotPixels - tBlack) / 1000, (tShading - tHotPixels) / 1000,
         (tDemosaic - tShading) / 1000, (tEnd - tDemosaic) / 1000, (tEnd - tBlack) / 1000);

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

/**
 * What the develop's three preparatory passes cost, and what folding one saves.
 *
 * `black`, `hotpixels` and `shading` are three separate sweeps of a 50 MB plane
 * and the obvious question is whether they can be one. The first and third are
 * pure per-pixel maps and fold trivially; the second sits between them and has
 * to see values that are black-subtracted and not yet shaded, so an honest fold
 * needs a software pipeline with a two-row lag and a halo at every band edge.
 *
 * That is a lot to build on a guess. This prices the guess: slot A runs the
 * three as they ship, slot B runs black-and-shading folded with hot pixels
 * after. B renders the wrong picture -- the order is wrong on purpose -- and
 * what it measures is one fewer sweep, which is the whole prize.
 */
JNIEXPORT jlongArray JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nPrepassBench(
        JNIEnv* env, jobject, jint width, jint height,
        jintArray jcfa, jintArray jblack, jint white, jfloatArray jgains,
        jfloatArray jshading, jint columns, jint rows,
        jfloat hotPixelThreshold, jint roundCount) {
    if (width < 8 || height < 8 || roundCount <= 0) return nullptr;

    int cfa[4], black[4];
    float gains[4], wb[4];
    env->GetIntArrayRegion(jcfa, 0, 4, cfa);
    env->GetIntArrayRegion(jblack, 0, 4, black);
    env->GetFloatArrayRegion(jgains, 0, 4, gains);
    balanceBySite(cfa, gains, wb);

    std::vector<float> map;
    const float* shadingPtr = nullptr;
    if (jshading != nullptr && columns > 0 && rows > 0) {
        const jsize count = env->GetArrayLength(jshading);
        if (count == columns * rows * 4) {
            map.resize(static_cast<size_t>(count));
            env->GetFloatArrayRegion(jshading, 0, count, map.data());
            shadingPtr = map.data();
        }
    }

    const int lo = std::min(std::min(black[0], black[1]), std::min(black[2], black[3]));
    const float range = static_cast<float>(std::max(1, white - lo));

    const size_t pixels = static_cast<size_t>(width) * height;
    std::vector<uint16_t> src(pixels);
    std::vector<float> plane(pixels);
    // A merged frame's shape: a ramp with detail on it, sites tinted as a white
    // balanced sensor's are, and one site in ten thousand stuck high so the hot
    // pixel pass has something to find and to branch on.
    for (size_t i = 0; i < pixels; ++i) {
        const int x = static_cast<int>(i % static_cast<size_t>(width));
        const int y = static_cast<int>(i / static_cast<size_t>(width));
        const int site = (y & 1) * 2 + (x & 1);
        const float tint = (site == 0) ? 1.18f : (site == 3) ? 1.09f : 1.0f;
        float v = 64.0f + 700.0f * (0.35f * x / width + 0.65f * y / height);
        v += 40.0f * std::sin(x * 0.21f) * std::sin(y * 0.17f);
        v *= tint;
        if ((i * 2654435761u % 10000u) == 0u) v += 600.0f;
        src[i] = static_cast<uint16_t>(std::clamp(v, 0.0f, 1023.0f));
    }

    auto three = [&]() {
        applyBlackLevel(src.data(), plane.data(), width, height, black, range);
        if (hotPixelThreshold > 0.0f) {
            suppressHotPixels(plane.data(), width, height, hotPixelThreshold);
        }
        applyShadingHoisted(plane.data(), width, height, shadingPtr, columns, rows, wb);
    };
    auto two = [&]() {
        applyBlackAndShading(src.data(), plane.data(), width, height, black, range,
                             shadingPtr, columns, rows, wb);
        if (hotPixelThreshold > 0.0f) {
            suppressHotPixels(plane.data(), width, height, hotPixelThreshold);
        }
    };

    // Untimed: faults the pages in and lets the cores come up to clock.
    three();
    two();

    std::vector<jlong> out(static_cast<size_t>(roundCount) * 2, 0);
    for (int r = 0; r < roundCount; ++r) {
        int64_t tA = 0, tB = 0;
        auto slotA = [&]() { const int64_t t = nowMicros(); three(); tA = nowMicros() - t; };
        auto slotB = [&]() { const int64_t t = nowMicros(); two();   tB = nowMicros() - t; };
        if ((r & 1) == 0) { slotA(); slotB(); } else { slotB(); slotA(); }
        out[static_cast<size_t>(r) * 2] = tA;
        out[static_cast<size_t>(r) * 2 + 1] = tB;
    }

    jlongArray result = env->NewLongArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

/**
 * Times the shading pass against the per-pixel form it replaced, in one binary.
 *
 * Every A/B in this project until now compared two installs, and the develop
 * harness measured against itself that way separates a binary from itself by
 * two and a half times -- the reinstall is where the variance lives. Both
 * implementations are present here, so a round times one straight after the
 * other on the same cores at the same temperature and nothing has to be
 * installed twice.
 *
 * `selfCheck` runs the fast path in both slots. That is the A/A this project
 * decided it would demand before believing any A/B, and it is cheap enough that
 * the test always runs it first.
 *
 * The result is `[a0, b0, a1, b1, ...]` in microseconds, then the number of
 * values on which the two runs disagreed, then the largest such disagreement in
 * nano-units. The order of the two within a round alternates, so a drift over
 * the measurement cannot land on one of them.
 */
JNIEXPORT jlongArray JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nShadingBench(
        JNIEnv* env, jobject, jint width, jint height,
        jintArray jcfa, jfloatArray jgains,
        jfloatArray jshading, jint columns, jint rows,
        jint roundCount, jboolean selfCheck) {
    if (width <= 1 || height <= 1 || roundCount <= 0) return nullptr;

    int cfa[4];
    float gains[4], wb[4];
    env->GetIntArrayRegion(jcfa, 0, 4, cfa);
    env->GetFloatArrayRegion(jgains, 0, 4, gains);
    balanceBySite(cfa, gains, wb);

    std::vector<float> map;
    const float* shadingPtr = nullptr;
    if (jshading != nullptr && columns > 0 && rows > 0) {
        const jsize count = env->GetArrayLength(jshading);
        if (count == columns * rows * 4) {
            map.resize(static_cast<size_t>(count));
            env->GetFloatArrayRegion(jshading, 0, count, map.data());
            shadingPtr = map.data();
        }
    }
    if (shadingPtr == nullptr) return nullptr;

    const size_t n = static_cast<size_t>(width) * height;
    std::vector<float> a(n), b(n);

    // Not a flat plane: a constant would multiply to a constant and let a
    // wrong gain pass unnoticed. Knuth's multiplicative hash gives every
    // pixel its own value for the price of one multiply.
    auto fill = [&](std::vector<float>& v) {
        for (size_t i = 0; i < n; ++i) {
            v[i] = 0.05f + 0.9f *
                static_cast<float>((i * 2654435761u) & 0xffffu) / 65535.0f;
        }
    };

    // Untimed, and not a formality: the first pass over 100 MB of freshly
    // allocated vector faults every page in, and the big cores are still at
    // their idle clock. Both costs landed on whichever variant went first.
    fill(a);
    fill(b);
    for (int w = 0; w < 2; ++w) {
        applyShadingReference(a.data(), width, height, shadingPtr, columns, rows, wb);
        applyShadingHoisted(b.data(), width, height, shadingPtr, columns, rows, wb);
    }

    std::vector<jlong> out(static_cast<size_t>(roundCount) * 2 + 2, 0);
    long differing = 0;
    double worst = 0.0;

    for (int r = 0; r < roundCount; ++r) {
        fill(a);
        fill(b);
        int64_t tA = 0, tB = 0;
        auto slotA = [&]() {
            const int64_t t = nowMicros();
            if (selfCheck) {
                applyShadingHoisted(a.data(), width, height, shadingPtr, columns, rows, wb);
            } else {
                applyShadingReference(a.data(), width, height, shadingPtr, columns, rows, wb);
            }
            tA = nowMicros() - t;
        };
        auto slotB = [&]() {
            const int64_t t = nowMicros();
            applyShadingHoisted(b.data(), width, height, shadingPtr, columns, rows, wb);
            tB = nowMicros() - t;
        };
        if ((r & 1) == 0) { slotA(); slotB(); } else { slotB(); slotA(); }

        out[static_cast<size_t>(r) * 2] = tA;
        out[static_cast<size_t>(r) * 2 + 1] = tB;
        for (size_t i = 0; i < n; ++i) {
            if (a[i] != b[i]) {
                ++differing;
                worst = std::max(worst, std::fabs(static_cast<double>(a[i]) - b[i]));
            }
        }
    }
    out[static_cast<size_t>(roundCount) * 2] = differing;
    out[static_cast<size_t>(roundCount) * 2 + 1] = static_cast<jlong>(worst * 1e9);

    jlongArray result = env->NewLongArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

/**
 * What `demosaic+tone` is made of, measured by leaving one piece out.
 *
 * The pass is the largest item in a capture and reports as one number. It
 * cannot be split by running it in halves -- the halves are fused so that the
 * demosaic's output never leaves the registers, and an intermediate buffer
 * would add 50 MB of traffic and measure that. So the harness runs the whole
 * pass with one item removed and compares it against the whole pass, in the
 * same process, paired within a round, the way `nShadingBench` does.
 *
 * Ablation differences are subtractions and subtractions do not have to add up.
 * Removing an item lets the compiler and the machine rearrange what is left, so
 * a part's cost measured this way is what removing it saves, which is the
 * useful quantity but not the same as what it would cost on its own.
 *
 * `[a0, b0, a1, b1, ...]` in microseconds, then the bytes on which the two
 * outputs disagreed, then how many pixels the census found above the knee, then
 * the largest of those disagreements. The last is what matters when the two
 * variants are two ways of computing the same picture rather than an ablation:
 * it is the claim about what the faster way costs the photograph.
 */
JNIEXPORT jlongArray JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nToneBench(
        JNIEnv* env, jobject, jint width, jint height,
        jintArray jcfa, jfloatArray jmatrix,
        jfloat exposureGain, jfloat knee, jfloat contrast,
        jfloat desatStrength, jfloat desatStart, jfloat blackPoint,
        jint variantA, jint variantB, jint roundCount) {
    if (width < 8 || height < 8 || roundCount <= 0) return nullptr;
    ensureGammaLut();

    int cfa[4];
    float m[9];
    env->GetIntArrayRegion(jcfa, 0, 4, cfa);
    env->GetFloatArrayRegion(jmatrix, 0, 9, m);

    ToneParams tone;
    tone.exposureGain = exposureGain;
    tone.knee = knee;
    tone.contrast = contrast;
    tone.desatStrength = desatStrength;
    tone.desatStart = desatStart;
    tone.blackPoint = blackPoint;

    DisplayLut display;
    buildDisplayLut(display, tone);
    ShoulderLut shoulder;
    buildShoulderLut(shoulder, tone);

    const size_t pixels = static_cast<size_t>(width) * height;
    const int stride = width * 4;
    std::vector<float> plane(pixels);
    std::vector<uint8_t> dstA(pixels * 4), dstB(pixels * 4);
    fillScenePlane(plane.data(), width, height);

    // Untimed: faults in 150 MB of fresh pages and lets the cores come up to
    // clock, both of which otherwise land on whichever variant went first.
    std::atomic<long> aboveKnee{0};
    runToneVariant(kToneCensus, plane.data(), width, height, dstA.data(), stride,
                   cfa, m, tone, display, shoulder, &aboveKnee);
    runToneVariant(variantA, plane.data(), width, height, dstA.data(), stride,
                   cfa, m, tone, display, shoulder, nullptr);
    runToneVariant(variantB, plane.data(), width, height, dstB.data(), stride,
                   cfa, m, tone, display, shoulder, nullptr);

    std::vector<jlong> out(static_cast<size_t>(roundCount) * 2 + 3, 0);
    long differing = 0;
    int worstByte = 0;

    for (int r = 0; r < roundCount; ++r) {
        int64_t tA = 0, tB = 0;
        auto slotA = [&]() {
            const int64_t t = nowMicros();
            runToneVariant(variantA, plane.data(), width, height, dstA.data(),
                           stride, cfa, m, tone, display, shoulder, nullptr);
            tA = nowMicros() - t;
        };
        auto slotB = [&]() {
            const int64_t t = nowMicros();
            runToneVariant(variantB, plane.data(), width, height, dstB.data(),
                           stride, cfa, m, tone, display, shoulder, nullptr);
            tB = nowMicros() - t;
        };
        if ((r & 1) == 0) { slotA(); slotB(); } else { slotB(); slotA(); }

        out[static_cast<size_t>(r) * 2] = tA;
        out[static_cast<size_t>(r) * 2 + 1] = tB;
        if (r == 0) {
            for (size_t i = 0; i < dstA.size(); ++i) {
                if (dstA[i] != dstB[i]) {
                    ++differing;
                    worstByte = std::max(worstByte, std::abs(dstA[i] - dstB[i]));
                }
            }
        }
    }
    out[static_cast<size_t>(roundCount) * 2] = differing;
    out[static_cast<size_t>(roundCount) * 2 + 1] = aboveKnee.load();
    out[static_cast<size_t>(roundCount) * 2 + 2] = worstByte;

    jlongArray result = env->NewLongArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

}  // extern "C"


// ---------------------------------------------------------------------------
// Capture sharpening.
//
// Mirrors Sharpen.kt, which is where the tests are. A Bayer sensor measures one
// colour per site and the demosaic reconstructs the other two, so even a
// perfect reconstruction delivers less acutance than the lens projected. This
// restores it rather than adding an effect.
//
// Safe here in a way it is not on a single frame: a merged burst has already
// had its noise reduced by the square root of the frame count, so the same
// sharpening lands on a much cleaner signal.
// ---------------------------------------------------------------------------

namespace {

inline float lumaOf(int r, int g, int b) {
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

inline uint8_t shiftChannel(int value, float shift) {
    const int moved = static_cast<int>(std::lround(value + shift));
    return static_cast<uint8_t>(std::clamp(moved, 0, 255));
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nReleaseScratch(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(gScratchLock);
    gScratch.release();
}

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nSharpen(
        JNIEnv* env, jobject, jobject bitmap,
        jfloat amount, jfloat threshold, jfloat maxShift) {
    if (amount <= 0.0f) return JNI_TRUE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    const int width = static_cast<int>(info.width);
    const int height = static_cast<int>(info.height);
    if (width < 3 || height < 3) return JNI_TRUE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    auto* base = static_cast<uint8_t*>(pixels);
    const int stride = static_cast<int>(info.stride);

    // Luminance once for the whole image: the mask needs each pixel's
    // neighbours, and recomputing it per neighbour would do the work nine
    // times over. At twelve megapixels that is 50 MB, which is native memory
    // and not the managed heap.
    std::lock_guard<std::mutex> scratchGuard(gScratchLock);
    std::vector<float>& luma = gScratch.floats(static_cast<size_t>(width) * height);
    const int64_t tLuma = nowMicros();
    parallelBands(height, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint8_t* row = base + static_cast<size_t>(y) * stride;
            for (int x = 0; x < width; ++x) {
                const uint8_t* p = row + static_cast<size_t>(x) * 4;
                luma[static_cast<size_t>(y) * width + x] = lumaOf(p[0], p[1], p[2]);
            }
        }
    });

    // Written in place, over the pixels being read.
    //
    // This used to go through a full copy of the image -- fifty megabytes out
    // and fifty back -- on the grounds that a pixel's neighbours must be the
    // original values rather than already-sharpened ones. That is true, and it
    // is already guaranteed by something else: every neighbour this loop reads
    // comes from `luma`, which was computed above from the untouched image and
    // is never written again. The only pixel whose colour is read is the one
    // being written. So the copy was guarding a hazard that cannot occur, and
    // it was measured costing eleven milliseconds a capture to do it.
    const int64_t tSharpen = nowMicros();
    parallelBands(height, [&](int y0, int y1) {
        const int from = std::max(y0, 1);
        const int to = std::min(y1, height - 1);
        for (int y = from; y < to; ++y) {
            uint8_t* row = base + static_cast<size_t>(y) * stride;
            const float* centre = luma.data() + static_cast<size_t>(y) * width;
            int x = 1;

#if defined(__ARM_NEON)
            // Four pixels at a time.
            //
            // Bit-identical to the scalar loop below, which is what makes it
            // safe to run instead of that loop rather than beside it. Every
            // vector add is lane-wise, so lane j performs exactly the sequence
            // of nine additions the scalar code performs for x+j, in the same
            // order -- and float addition being non-associative is the whole
            // reason that order had to be preserved rather than rearranged
            // into row sums, which is the obvious way to write this and would
            // have produced a different picture.
            //
            // `vcvtaq_s32_f32` rounds to nearest with ties away from zero,
            // which is precisely what `std::lround` does, so the rounding
            // agrees as well.
            const float32x4_t vThreshold = vdupq_n_f32(threshold);
            const float32x4_t vAmount = vdupq_n_f32(amount);
            const float32x4_t vMaxShift = vdupq_n_f32(maxShift);
            const float32x4_t vMinShift = vdupq_n_f32(-maxShift);
            const float32x4_t vNine = vdupq_n_f32(9.0f);
            // Alpha is not a colour channel and takes no shift. Its lane is
            // held back rather than shifted and repaired, because 255 plus a
            // negative shift does not clamp back to 255.
            const uint8x16_t alphaLanes = vreinterpretq_u8_u32(vdupq_n_u32(0xFF000000u));

            for (; x + 3 <= width - 2; x += 4) {
                const float* l = centre + x;
                float32x4_t sum = vld1q_f32(l - width - 1);
                sum = vaddq_f32(sum, vld1q_f32(l - width));
                sum = vaddq_f32(sum, vld1q_f32(l - width + 1));
                sum = vaddq_f32(sum, vld1q_f32(l - 1));
                const float32x4_t here = vld1q_f32(l);
                sum = vaddq_f32(sum, here);
                sum = vaddq_f32(sum, vld1q_f32(l + 1));
                sum = vaddq_f32(sum, vld1q_f32(l + width - 1));
                sum = vaddq_f32(sum, vld1q_f32(l + width));
                sum = vaddq_f32(sum, vld1q_f32(l + width + 1));

                const float32x4_t detail = vsubq_f32(here, vdivq_f32(sum, vNine));
                // Below the threshold this is noise or texture the merge just
                // finished cleaning up. Those lanes keep their original bytes.
                const uint32x4_t keep = vcltq_f32(vabsq_f32(detail), vThreshold);
                const float32x4_t shift =
                    vminq_f32(vmaxq_f32(vmulq_f32(detail, vAmount), vMinShift), vMaxShift);

                uint8_t* q = row + static_cast<size_t>(x) * 4;
                const uint8x16_t px = vld1q_u8(q);
                const uint16x8_t lo = vmovl_u8(vget_low_u8(px));
                const uint16x8_t hi = vmovl_u8(vget_high_u8(px));

                // One float vector per pixel: its four channels, with that
                // pixel's shift broadcast across them.
                const float32x4_t p0 = vaddq_f32(
                    vcvtq_f32_u32(vmovl_u16(vget_low_u16(lo))), vdupq_laneq_f32(shift, 0));
                const float32x4_t p1 = vaddq_f32(
                    vcvtq_f32_u32(vmovl_u16(vget_high_u16(lo))), vdupq_laneq_f32(shift, 1));
                const float32x4_t p2 = vaddq_f32(
                    vcvtq_f32_u32(vmovl_u16(vget_low_u16(hi))), vdupq_laneq_f32(shift, 2));
                const float32x4_t p3 = vaddq_f32(
                    vcvtq_f32_u32(vmovl_u16(vget_high_u16(hi))), vdupq_laneq_f32(shift, 3));

                // The saturating narrows do the clamp to 0..255 on the way down.
                const int16x8_t n0 = vcombine_s16(vqmovn_s32(vcvtaq_s32_f32(p0)),
                                                  vqmovn_s32(vcvtaq_s32_f32(p1)));
                const int16x8_t n1 = vcombine_s16(vqmovn_s32(vcvtaq_s32_f32(p2)),
                                                  vqmovn_s32(vcvtaq_s32_f32(p3)));
                const uint8x16_t shifted =
                    vcombine_u8(vqmovun_s16(n0), vqmovun_s16(n1));

                // A lane's compare result is 32 bits wide and a pixel is four
                // bytes, so the mask lines up with the pixels with no widening
                // at all: one comparison covers exactly one pixel's bytes.
                const uint8x16_t original =
                    vorrq_u8(vreinterpretq_u8_u32(keep), alphaLanes);
                vst1q_u8(q, vbslq_u8(original, px, shifted));
            }
#endif

            for (; x < width - 1; ++x) {
                float sum = 0.0f;
                for (int dy = -1; dy <= 1; ++dy) {
                    const size_t r = static_cast<size_t>(y + dy) * width;
                    for (int dx = -1; dx <= 1; ++dx) {
                        sum += luma[r + x + dx];
                    }
                }
                const float blurred = sum / 9.0f;
                const float detail = centre[x] - blurred;

                // Below the threshold this is noise or texture the merge just
                // finished cleaning up.
                if (std::fabs(detail) < threshold) continue;

                const float shift = std::clamp(detail * amount, -maxShift, maxShift);
                uint8_t* q = row + static_cast<size_t>(x) * 4;
                // The same shift in all three channels moves brightness without
                // moving hue; scaling per channel is what puts coloured
                // speckle along every edge.
                q[0] = shiftChannel(q[0], shift);
                q[1] = shiftChannel(q[1], shift);
                q[2] = shiftChannel(q[2], shift);
            }
        }
    });

    // Sharpening is its own JNI call, so it reports its own passes. The point
    // of splitting these at all is that "develop 900ms" says nothing about
    // which pass to spend effort on -- the merge spent a phase aimed at the
    // wrong half for exactly that reason, and this pass had never been timed
    // at all until it turned out to be a third of the native develop.
    const int64_t tEnd = nowMicros();
    LOGI("sharpen: luma %lldms, sharpen %lldms, total %lldms",
         (tSharpen - tLuma) / 1000, (tEnd - tSharpen) / 1000, (tEnd - tLuma) / 1000);

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

}  // extern "C"


// ---------------------------------------------------------------------------
// Defringing.
//
// Mirrors Defringe.kt, where the tests are. A lens does not focus every
// wavelength at the same scale, so along a high-contrast edge the colour planes
// are slightly misregistered and show a coloured rim. The ISP corrects it for
// its own JPEG; a raw pipeline inherits it.
//
// A fringe exists only at the edge, while a genuinely coloured object carries
// its colour away from its own edges too, so chroma at an edge is limited to
// the range found among nearby pixels that are not on one.
// ---------------------------------------------------------------------------

namespace {

constexpr int kFringeWindow = 2;
// One more than the window: each neighbour is itself tested for being an edge,
// and that test needs its own neighbours.
constexpr int kFringeMargin = kFringeWindow + 1;

inline float clampToward(float value, float low, float high,
                         float tolerance, float strength) {
    const float ceiling = high + tolerance;
    const float floor = low - tolerance;
    if (value > ceiling) return value - (value - ceiling) * strength;
    if (value < floor) return value + (floor - value) * strength;
    return value;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_dev_multiframe_camera_pipeline_NativeMerge_nDefringe(
        JNIEnv* env, jobject, jobject bitmap,
        jfloat edgeThreshold, jfloat tolerance, jfloat strength, jfloat minChroma,
        jfloat innerRadius) {
    if (strength <= 0.0f) return 0;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 0;

    const int width = static_cast<int>(info.width);
    const int height = static_cast<int>(info.height);
    if (width <= kFringeMargin * 2 || height <= kFringeMargin * 2) return 0;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return 0;
    }
    auto* base = static_cast<uint8_t*>(pixels);
    const int stride = static_cast<int>(info.stride);
    const size_t n = static_cast<size_t>(width) * height;

    // Luma and the two chroma differences once, since the window needs its
    // neighbours' values and recomputing them would do the work 25 times over.
    std::lock_guard<std::mutex> scratchGuard(gScratchLock);
    std::vector<float>& luma = gScratch.floats(n);
    std::vector<float>& cr = gScratch.floatsB(n);
    std::vector<float>& cb = gScratch.floatsC(n);
    parallelBands(height, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint8_t* row = base + static_cast<size_t>(y) * stride;
            for (int x = 0; x < width; ++x) {
                const uint8_t* p = row + static_cast<size_t>(x) * 4;
                const size_t i = static_cast<size_t>(y) * width + x;
                luma[i] = 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2];
                // Against green: a Bayer sensor has most of its samples there
                // and least of its own error, so it is the reference.
                cr[i] = static_cast<float>(p[0]) - p[1];
                cb[i] = static_cast<float>(p[2]) - p[1];
            }
        }
    });

    std::atomic<int> altered{0};
    const size_t outputBytes = static_cast<size_t>(height) * stride;
    std::vector<uint8_t>& output = gScratch.bytes(outputBytes);
    std::memcpy(output.data(), base, outputBytes);

    // Squared radii, so the per-pixel test needs no square root. Lateral colour
    // error is zero at the optical centre by definition and grows toward the
    // corners, so correcting the middle would be wrong as well as wasteful.
    const float centreX = (width - 1) * 0.5f;
    const float centreY = (height - 1) * 0.5f;
    const float cornerSquared = centreX * centreX + centreY * centreY;
    const float innerSquared = cornerSquared * innerRadius * innerRadius;

    parallelBands(height, [&](int y0, int y1) {
        const int from = std::max(y0, kFringeMargin);
        const int to = std::min(y1, height - kFringeMargin);
        int local = 0;
        for (int y = from; y < to; ++y) {
            const float dyc = y - centreY;
            for (int x = kFringeMargin; x < width - kFringeMargin; ++x) {
                const float dxc = x - centreX;
                if (dxc * dxc + dyc * dyc < innerSquared) continue;
                const size_t index = static_cast<size_t>(y) * width + x;
                // Cheapest test first: most pixels carry no real chroma and
                // never need the window scan. Without it the rule fires on
                // ordinary chroma noise and becomes noise reduction applied to
                // half the frame.
                if (std::max(std::fabs(cr[index]), std::fabs(cb[index])) < minChroma) continue;

                const float gx = std::fabs(luma[index + 1] - luma[index - 1]);
                const float gy = std::fabs(luma[index + width] - luma[index - width]);
                if (std::max(gx, gy) < edgeThreshold) continue;

                float crLow = 1e9f, crHigh = -1e9f, cbLow = 1e9f, cbHigh = -1e9f;
                int found = 0;
                for (int dy = -kFringeWindow; dy <= kFringeWindow; ++dy) {
                    const size_t row = index + static_cast<size_t>(dy) * width;
                    for (int dx = -kFringeWindow; dx <= kFringeWindow; ++dx) {
                        const size_t at = row + dx;
                        const float ngx = std::fabs(luma[at + 1] - luma[at - 1]);
                        const float ngy = std::fabs(luma[at + width] - luma[at - width]);
                        if (std::max(ngx, ngy) >= edgeThreshold) continue;
                        crLow = std::min(crLow, cr[at]); crHigh = std::max(crHigh, cr[at]);
                        cbLow = std::min(cbLow, cb[at]); cbHigh = std::max(cbHigh, cb[at]);
                        ++found;
                    }
                }
                // No non-edge neighbour means no evidence about what colour
                // belongs here, and altering it would be a guess.
                if (found == 0) continue;

                const float newCr = clampToward(cr[index], crLow, crHigh, tolerance, strength);
                const float newCb = clampToward(cb[index], cbLow, cbHigh, tolerance, strength);
                if (newCr == cr[index] && newCb == cb[index]) continue;

                uint8_t* q = output.data() + static_cast<size_t>(y) * stride +
                             static_cast<size_t>(x) * 4;
                const float g = static_cast<float>(q[1]);
                q[0] = static_cast<uint8_t>(std::clamp(std::lround(g + newCr), 0L, 255L));
                q[2] = static_cast<uint8_t>(std::clamp(std::lround(g + newCb), 0L, 255L));
                ++local;
            }
        }
        altered.fetch_add(local);
    });

    std::memcpy(base, output.data(), outputBytes);
    AndroidBitmap_unlockPixels(env, bitmap);
    return altered.load();
}

}  // extern "C"
