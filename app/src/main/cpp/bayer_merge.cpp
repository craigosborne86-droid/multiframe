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
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

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

void parallelBands(int height, const std::function<void(int, int)>& body) {
    unsigned hw = std::thread::hardware_concurrency();
    int threads = static_cast<int>(std::max(2u, hw));
    threads = std::min(threads, std::max(1, height));
    int band = (height + threads - 1) / threads;

    std::vector<std::thread> pool;
    pool.reserve(threads);
    for (int t = 0; t < threads; ++t) {
        int start = t * band;
        int end = std::min(start + band, height);
        if (start >= end) break;
        pool.emplace_back([&body, start, end] { body(start, end); });
    }
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
            for (int x = 0; x < w; ++x) {
                uint16_t v = sampleAt(base, rowStride, x, y);
                size_t i = static_cast<size_t>(y) * w + x;
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

    std::vector<double> contrib(64, 0.0);
    std::vector<long long> counts(64, 0);
    std::atomic<int> slot{0};

    parallelBands(h, [&](int y0, int y1) {
        int me = slot.fetch_add(1) % 64;
        double localContrib = 0.0;
        long long localCount = 0;

        for (int y = y0; y < y1; ++y) {
            int ty = std::clamp(static_cast<int>((y / 2) / tileH), 0, tilesY - 1);
            const size_t rowBase = static_cast<size_t>(y) * w;
            for (int x = 0; x < w; ++x) {
                int tx = std::clamp(static_cast<int>((x / 2) / tileW), 0, tilesX - 1);
                int idx = ty * tilesX + tx;
                // Doubling a proxy offset always yields an even shift, keeping
                // every sample on its own colour plane.
                int sx = x + dx[idx] * 2;
                int sy = y + dy[idx] * 2;
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;

                float refV = static_cast<float>(acc->reference[rowBase + x]);
                float altV = static_cast<float>(sampleAt(base, rowStride, sx, sy));
                float d = altV - refV;
                float d2 = d * d;
                float n2 = acc->noiseVar[binOf(refV, acc->white)];
                float wgt = (d2 <= n2) ? 1.0f : n2 / d2;

                acc->sum[rowBase + x] += altV * wgt;
                acc->weight[rowBase + x] += wgt;
                localContrib += wgt;
                ++localCount;
            }
        }
        contrib[me] += localContrib;
        counts[me] += localCount;
    });

    for (int i = 0; i < 64; ++i) {
        acc->contributionSum += contrib[i];
        acc->contributionCount += counts[i];
    }
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

inline uint8_t encodeSrgb(float v) {
    v = std::clamp(v, 0.0f, 1.0f);
    float e = gGammaLut[static_cast<int>(v * (kGammaLutSize - 1))];
    return static_cast<uint8_t>(std::clamp(e * 255.0f + 0.5f, 0.0f, 255.0f));
}

inline float shoulderCurve(float x, float knee) {
    if (x <= 0.0f) return 0.0f;
    if (x <= knee) return x;
    float headroom = 1.0f - knee;
    return knee + headroom * (1.0f - std::exp(-(x - knee) / headroom));
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
        jfloat exposureGain, jfloat knee) {
    ensureGammaLut();

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

    parallelBands(height, [&](int y0, int y1) {
        float acc[3];
        int cnt[3];
        for (int y = y0; y < y1; ++y) {
            uint8_t* row = dstBase + static_cast<size_t>(y) * stride;
            for (int x = 0; x < width; ++x) {
                acc[0] = acc[1] = acc[2] = 0.0f;
                cnt[0] = cnt[1] = cnt[2] = 0;

                // A 3x3 window around any Bayer pixel contains all three
                // colours, whatever the arrangement.
                for (int dy = -1; dy <= 1; ++dy) {
                    int sy = y + dy;
                    if (sy < 0 || sy >= height) continue;
                    for (int dx = -1; dx <= 1; ++dx) {
                        int sx = x + dx;
                        if (sx < 0 || sx >= width) continue;
                        int c = cfa[(sy & 1) * 2 + (sx & 1)];
                        float lin = (static_cast<float>(src[static_cast<size_t>(sy) * width + sx]) -
                                     black[(sy & 1) * 2 + (sx & 1)]) / range;
                        float g = (c == 0) ? gains[0] : (c == 2) ? gains[3]
                                                                 : ((sy & 1) == 0 ? gains[1] : gains[2]);
                        acc[c] += std::max(lin, 0.0f) * g;
                        cnt[c]++;
                    }
                }

                float r0 = cnt[0] ? acc[0] / cnt[0] : 0.0f;
                float g0 = cnt[1] ? acc[1] / cnt[1] : 0.0f;
                float b0 = cnt[2] ? acc[2] / cnt[2] : 0.0f;

                float r = m[0] * r0 + m[1] * g0 + m[2] * b0;
                float g = m[3] * r0 + m[4] * g0 + m[5] * b0;
                float b = m[6] * r0 + m[7] * g0 + m[8] * b0;

                uint8_t* p = row + static_cast<size_t>(x) * 4;
                // RGBA_8888 is byte order R,G,B,A in memory.
                p[0] = encodeSrgb(shoulderCurve(r * exposureGain, knee));
                p[1] = encodeSrgb(shoulderCurve(g * exposureGain, knee));
                p[2] = encodeSrgb(shoulderCurve(b * exposureGain, knee));
                p[3] = 255;
            }
        }
    });

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

}  // extern "C"
