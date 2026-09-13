// Sub-pixel accumulation onto a double-resolution canvas, in strips.
//
// The Kotlin SubPixelAccumulator is the reference and does the whole canvas at
// once, which is fine for a test image and impossible for a photograph: a
// 12.5 MP sensor gives a 50 M-cell canvas, and three channels of float
// accumulator and float weight is 24 bytes a cell -- 1.2 GB against a heap
// MemoryBudget sizes at 256 MB. So this does what MosaicCanvas does: the output
// lives in an anonymous mmap, guarded against half of physical memory, and the
// accumulators exist only for one horizontal strip at a time. A 256-row strip
// of an 8160-wide canvas is 50 MB, and when it is resolved into the output it
// is zeroed and reused for the next.
//
// The cost of strips is that every frame is swept once per strip rather than
// once. A frame is 25 MB and there are about 24 strips, so about 600 MB of
// sequential reads per frame -- but only the sensor rows that can land in the
// strip are touched, which is the strip's own height plus the alignment's
// reach, so in practice a frame is read a little more than once in total.
//
// Everything about the arithmetic matches the Kotlin: the same kernel, the
// same gradient-aware tolerance, the same fill. SubPixelParityTest holds this
// to it within a bound the -ffast-math flags permit.

#include <jni.h>
#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SubPixel", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "SubPixel", __VA_ARGS__)

namespace {

constexpr int kChannels = 3;
constexpr int kNoiseBins = 16;
constexpr int kSampleStride = 7;
constexpr int kTileTarget = 32;
constexpr float kMadToSigma = 1.4826f;
constexpr float kSigma = 0.5f;
constexpr float kRadius = 1.0f;
constexpr float kInvTwoSigma2 = 1.0f / (2.0f * kSigma * kSigma);
constexpr float kHoleThreshold = 0.05f;
constexpr int kFillRadius = 3;
constexpr float kFlatForNoise = 20.0f;
constexpr int kInteriorMargin = 4;

/**
 * Marks a resolved cell that had no sample. Not NaN.
 *
 * The build has -ffast-math, which includes -ffinite-math-only: the compiler
 * is told NaN never occurs and folds std::isnan() to false. The first version
 * of this used NaN as the hole marker, exactly as the Kotlin reference does,
 * and the native pass reported zero holes on every canvas while the Kotlin
 * found a fifth -- every hole was written out as whatever 0/0 produced. Raw
 * sensor counts are never negative, so a negative sentinel is unambiguous and
 * survives the flags.
 */
constexpr float kHole = -1.0f;
inline bool isHole(float v) { return v < 0.0f; }

/** Rows per strip. 256 of an 8160-wide canvas is 50 MB of accumulator. */
constexpr int kStripRows = 256;

/**
 * Rows of accumulator either side of the strip proper. The kernel reaches one
 * cell; the fill reaches three. Resolved but not output, so a fill at the
 * strip's edge has neighbours to look at.
 */
constexpr int kHalo = kFillRadius;

struct Frame {
    const uint8_t* data;
    int strideBytes;
    float invGain;
    // Alignment, in proxy pixels: integer and fraction per tile.
    std::vector<int> dx, dy;
    std::vector<float> fracDx, fracDy;
    // How far, in sensor rows, this frame's samples can land from where they
    // sit. Bounds which sensor rows a strip needs to sweep.
    int maxRowReach;
};

inline uint16_t at(const uint8_t* base, int strideBytes, int x, int y) {
    const uint8_t* p = base + static_cast<size_t>(y) * strideBytes + static_cast<size_t>(x) * 2;
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

class SubPixelCanvas {
public:
    static SubPixelCanvas* Create(int sensorW, int sensorH, const int* cfa, const int* black,
                                  int blackCount, int white, float noiseTolerance, float minSigma);
    ~SubPixelCanvas();

    void SetReference(const uint8_t* data, int strideBytes);
    void AddFrame(const uint8_t* data, int strideBytes, const int* dx, const int* dy,
                  const float* fracDx, const float* fracDy, float gainScale);
    bool Finish();

    int width() const { return width_; }
    int height() const { return height_; }
    const uint16_t* output() const { return out_; }
    size_t outputBytes() const { return outBytes_; }
    float holeFraction() const { return holeFraction_; }
    float interiorHoleFraction() const { return interiorHoleFraction_; }
    float meanContribution() const { return contributionCount_ > 0 ? static_cast<float>(contributionSum_ / contributionCount_) : 0.0f; }
    int framesMerged() const { return 1 + static_cast<int>(alts_.size()); }

private:
    SubPixelCanvas() = default;

    int colourAt(int x, int y) const { return cfa_[(y & 1) * 2 + (x & 1)]; }
    int binOf(float v) const {
        int b = static_cast<int>((v / static_cast<float>(white_ + 1)) * kNoiseBins);
        return std::min(std::max(b, 0), kNoiseBins - 1);
    }

    struct RefSample { float value; float uncertainty; };
    void sampleReference(int ax, int ay, float fx, float fy, RefSample& out) const;
    void estimateNoise(const Frame& f);
    void accumulateStrip(int stripY0, int stripY1, std::vector<float>& acc, std::vector<float>& wsum);
    void splat(std::vector<float>& acc, std::vector<float>& wsum, int stripBase, int stripRows,
               int channel, float u, float v, float value, float robustness);
    void resolveStrip(int stripY0, int stripY1, const std::vector<float>& acc,
                      const std::vector<float>& wsum, std::vector<float>& resolved);

    int sensorW_ = 0, sensorH_ = 0;
    int width_ = 0, height_ = 0;
    int cfa_[4] = {0, 1, 1, 2};
    std::vector<int> black_;
    int white_ = 1023;
    float noiseTolerance_ = 3.0f;
    float minSigma_ = 0.5f;

    int tilesX_ = 1, tilesY_ = 1, tileW_ = 1, tileH_ = 1;

    const uint8_t* ref_ = nullptr;
    int refStride_ = 0;
    std::vector<Frame> alts_;
    float noiseVar_[kNoiseBins];
    bool noiseEstimated_ = false;

    uint16_t* out_ = nullptr;
    size_t outBytes_ = 0;

    double contributionSum_ = 0.0;
    int64_t contributionCount_ = 0;
    int64_t holes_ = 0;
    int64_t interiorHoles_ = 0;
    float holeFraction_ = 0.0f;
    float interiorHoleFraction_ = 0.0f;
};

SubPixelCanvas* SubPixelCanvas::Create(int sensorW, int sensorH, const int* cfa, const int* black,
                                       int blackCount, int white, float noiseTolerance, float minSigma) {
    if (sensorW <= 0 || sensorH <= 0) return nullptr;
    const int w = sensorW * 2;
    const int h = sensorH * 2;
    const size_t cells = static_cast<size_t>(w) * static_cast<size_t>(h);
    const size_t bytes = cells * kChannels * sizeof(uint16_t);

    // The same guard MosaicCanvas applies: not more than half of physical
    // memory, because the merge that fed this still has its own buffers live.
    const long pages = sysconf(_SC_PHYS_PAGES);
    const long pageSize = sysconf(_SC_PAGE_SIZE);
    if (pages > 0 && pageSize > 0) {
        const size_t physical = static_cast<size_t>(pages) * static_cast<size_t>(pageSize);
        if (bytes > physical / 2) {
            LOGW("refusing %.0f MB canvas on a %.0f MB device", bytes / 1048576.0, physical / 1048576.0);
            return nullptr;
        }
    }

    void* mem = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        LOGW("mmap of %.0f MB canvas failed", bytes / 1048576.0);
        return nullptr;
    }

    auto* c = new (std::nothrow) SubPixelCanvas();
    if (c == nullptr) { munmap(mem, bytes); return nullptr; }
    c->sensorW_ = sensorW;
    c->sensorH_ = sensorH;
    c->width_ = w;
    c->height_ = h;
    for (int i = 0; i < 4; ++i) c->cfa_[i] = cfa[i];
    c->black_.assign(black, black + std::max(1, blackCount));
    c->white_ = white;
    c->noiseTolerance_ = noiseTolerance;
    c->minSigma_ = minSigma;
    c->out_ = static_cast<uint16_t*>(mem);
    c->outBytes_ = bytes;

    // The tile grid, derived exactly as the aligner derives it.
    c->tilesX_ = std::max(1, (sensorW / 2) / kTileTarget);
    c->tilesY_ = std::max(1, (sensorH / 2) / kTileTarget);
    c->tileW_ = std::max(1, (sensorW / 2) / c->tilesX_);
    c->tileH_ = std::max(1, (sensorH / 2) / c->tilesY_);

    LOGI("sub-pixel canvas %dx%d, %.0f MB output, %d-row strips of %.0f MB",
         w, h, bytes / 1048576.0, kStripRows,
         static_cast<double>(w) * (kStripRows + 2 * kHalo) * kChannels * 2 * sizeof(float) / 1048576.0);
    return c;
}

SubPixelCanvas::~SubPixelCanvas() {
    if (out_ != nullptr) munmap(out_, outBytes_);
}

void SubPixelCanvas::SetReference(const uint8_t* data, int strideBytes) {
    ref_ = data;
    refStride_ = strideBytes;
}

void SubPixelCanvas::AddFrame(const uint8_t* data, int strideBytes, const int* dx, const int* dy,
                              const float* fracDx, const float* fracDy, float gainScale) {
    Frame f;
    f.data = data;
    f.strideBytes = strideBytes;
    f.invGain = 1.0f / gainScale;
    const int n = tilesX_ * tilesY_;
    f.dx.assign(dx, dx + n);
    f.dy.assign(dy, dy + n);
    f.fracDx.assign(fracDx, fracDx + n);
    f.fracDy.assign(fracDy, fracDy + n);
    // The furthest any tile moves a sample, in sensor rows, rounded up.
    float reach = 0.0f;
    for (int i = 0; i < n; ++i) {
        reach = std::max(reach, std::fabs((dy[i] + fracDy[i]) * 2.0f));
    }
    f.maxRowReach = static_cast<int>(std::ceil(reach)) + 1;
    alts_.push_back(std::move(f));
}

void SubPixelCanvas::sampleReference(int ax, int ay, float fx, float fy, RefSample& out) const {
    const int px = ax & 1;
    const int py = ay & 1;
    int x0 = static_cast<int>(std::floor(fx));
    if ((x0 & 1) != px) x0--;
    int y0 = static_cast<int>(std::floor(fy));
    if ((y0 & 1) != py) y0--;
    const int x1 = x0 + 2;
    const int y1 = y0 + 2;
    const float tx = (fx - x0) / 2.0f;
    const float ty = (fy - y0) / 2.0f;

    const int maxX = sensorW_ - 1 - ((sensorW_ - 1 - px) & 1);
    const int maxY = sensorH_ - 1 - ((sensorH_ - 1 - py) & 1);
    auto sample = [&](int x, int y) -> float {
        const int cx = std::min(std::max(x, px), maxX);
        const int cy = std::min(std::max(y, py), maxY);
        return static_cast<float>(at(ref_, refStride_, cx, cy));
    };
    const float v00 = sample(x0, y0);
    const float v10 = sample(x1, y0);
    const float v01 = sample(x0, y1);
    const float v11 = sample(x1, y1);

    const float top = v00 + (v10 - v00) * tx;
    const float bottom = v01 + (v11 - v01) * tx;
    out.value = top + (bottom - top) * ty;

    const float varX = std::max(std::fabs(v10 - v00), std::fabs(v11 - v01));
    const float varY = std::max(std::fabs(v01 - v00), std::fabs(v11 - v10));
    out.uncertainty = 0.5f * std::max(varX, varY);
}

void SubPixelCanvas::estimateNoise(const Frame& f) {
    std::vector<std::vector<float>> samples(kNoiseBins);
    for (auto& s : samples) s.reserve(256);

    RefSample rs;
    for (int ay = 2; ay < sensorH_ - 2; ay += kSampleStride) {
        const int ty = std::min(std::max((ay / 2) / tileH_, 0), tilesY_ - 1);
        for (int ax = 2; ax < sensorW_ - 2; ax += kSampleStride) {
            const int tx = std::min(std::max((ax / 2) / tileW_, 0), tilesX_ - 1);
            const int idx = ty * tilesX_ + tx;
            const float fx = ax - (f.dx[idx] + f.fracDx[idx]) * 2.0f;
            const float fy = ay - (f.dy[idx] + f.fracDy[idx]) * 2.0f;
            if (fx < 1.0f || fy < 1.0f || fx > sensorW_ - 2.0f || fy > sensorH_ - 2.0f) continue;
            const int rawAlt = at(f.data, f.strideBytes, ax, ay);
            if (rawAlt >= white_) continue;
            sampleReference(ax, ay, fx, fy, rs);
            if (rs.uncertainty > kFlatForNoise) continue;
            const float altV = static_cast<float>(rawAlt) * f.invGain;
            const int b = binOf(rs.value);
            if (samples[b].size() < 3000) samples[b].push_back(std::fabs(altV - rs.value));
        }
    }

    for (int b = 0; b < kNoiseBins; ++b) {
        auto& list = samples[b];
        float sigma;
        if (list.size() < 16) {
            sigma = minSigma_;
        } else {
            std::nth_element(list.begin(), list.begin() + list.size() / 2, list.end());
            sigma = std::max(list[list.size() / 2] * kMadToSigma / 1.4142f, minSigma_);
        }
        const float tol = noiseTolerance_ * sigma;
        noiseVar_[b] = tol * tol;
    }
    for (int b = 0; b < kNoiseBins; ++b) {
        if (samples[b].size() >= 16) continue;
        for (int o = 1; o < kNoiseBins; ++o) {
            if (b - o >= 0 && samples[b - o].size() >= 16) { noiseVar_[b] = noiseVar_[b - o]; break; }
            if (b + o < kNoiseBins && samples[b + o].size() >= 16) { noiseVar_[b] = noiseVar_[b + o]; break; }
        }
    }
    noiseEstimated_ = true;
}

void SubPixelCanvas::splat(std::vector<float>& acc, std::vector<float>& wsum, int stripBase, int stripRows,
                           int channel, float u, float v, float value, float robustness) {
    const int i0 = std::max(static_cast<int>(std::floor(u - kRadius)), 0);
    const int i1 = std::min(static_cast<int>(std::ceil(u + kRadius)), width_ - 1);
    const int j0 = std::max(static_cast<int>(std::floor(v - kRadius)), stripBase);
    const int j1 = std::min(static_cast<int>(std::ceil(v + kRadius)), stripBase + stripRows - 1);
    if (j0 > j1 || i0 > i1) return;

    float* a = acc.data() + static_cast<size_t>(channel) * width_ * stripRows;
    float* w = wsum.data() + static_cast<size_t>(channel) * width_ * stripRows;

    for (int j = j0; j <= j1; ++j) {
        const float dy = j - v;
        const size_t row = static_cast<size_t>(j - stripBase) * width_;
        for (int i = i0; i <= i1; ++i) {
            const float dx = i - u;
            const float d2 = dx * dx + dy * dy;
            if (d2 > kRadius * kRadius) continue;
            const float k = std::exp(-d2 * kInvTwoSigma2);
            const float wt = k * robustness;
            a[row + i] += wt * value;
            w[row + i] += wt;
        }
    }
}

void SubPixelCanvas::accumulateStrip(int stripY0, int stripY1, std::vector<float>& acc, std::vector<float>& wsum) {
    // The accumulator covers [stripY0 - halo, stripY1 + halo).
    const int base = stripY0 - kHalo;
    const int rows = (stripY1 - stripY0) + 2 * kHalo;
    std::fill(acc.begin(), acc.end(), 0.0f);
    std::fill(wsum.begin(), wsum.end(), 0.0f);

    // The reference: every sample at twice its position, weight one. Sensor
    // rows whose doubled position could reach the accumulator.
    {
        const int sy0 = std::max(0, (base - 2) / 2);
        const int sy1 = std::min(sensorH_ - 1, (base + rows + 2) / 2);
        for (int y = sy0; y <= sy1; ++y) {
            for (int x = 0; x < sensorW_; ++x) {
                const int c = colourAt(x, y);
                const float v = static_cast<float>(at(ref_, refStride_, x, y));
                splat(acc, wsum, base, rows, c, 2.0f * x, 2.0f * y, v, 1.0f);
            }
        }
    }

    RefSample rs;
    for (const Frame& f : alts_) {
        // Sensor rows that can land in this accumulator, given how far this
        // frame's alignment moves them.
        const int sy0 = std::max(0, (base - 2) / 2 - f.maxRowReach);
        const int sy1 = std::min(sensorH_ - 1, (base + rows + 2) / 2 + f.maxRowReach);

        for (int ay = sy0; ay <= sy1; ++ay) {
            const int ty = std::min(std::max((ay / 2) / tileH_, 0), tilesY_ - 1);
            for (int ax = 0; ax < sensorW_; ++ax) {
                const int tx = std::min(std::max((ax / 2) / tileW_, 0), tilesX_ - 1);
                const int idx = ty * tilesX_ + tx;

                const float dispX = (f.dx[idx] + f.fracDx[idx]) * 2.0f;
                const float dispY = (f.dy[idx] + f.fracDy[idx]) * 2.0f;
                const float fx = ax - dispX;
                const float fy = ay - dispY;
                if (fx < 0.0f || fy < 0.0f || fx > sensorW_ - 1.0f || fy > sensorH_ - 1.0f) continue;

                // Only bother if the canvas position can reach this strip.
                const float v = 2.0f * fy;
                if (v + kRadius < base || v - kRadius > base + rows - 1) continue;

                const int rawAlt = at(f.data, f.strideBytes, ax, ay);
                if (f.invGain < 1.0f && rawAlt >= white_) continue;
                const float altV = static_cast<float>(rawAlt) * f.invGain;

                sampleReference(ax, ay, fx, fy, rs);
                const float d = altV - rs.value;
                const float d2 = d * d;
                const float n2 = noiseVar_[binOf(rs.value)] + rs.uncertainty * rs.uncertainty;
                const float r = d2 <= n2 ? 1.0f : n2 / d2;

                const int c = colourAt(ax, ay);
                splat(acc, wsum, base, rows, c, 2.0f * fx, v, altV, r);

                // Counted once per sample, on the strip that owns its centre,
                // so the mean is over samples and not over strip overlaps.
                const int centreRow = static_cast<int>(std::floor(v));
                if (centreRow >= stripY0 && centreRow < stripY1) {
                    contributionSum_ += r;
                    contributionCount_++;
                }
            }
        }
    }
}

void SubPixelCanvas::resolveStrip(int stripY0, int stripY1, const std::vector<float>& acc,
                                  const std::vector<float>& wsum, std::vector<float>& resolved) {
    const int base = stripY0 - kHalo;
    const int rows = (stripY1 - stripY0) + 2 * kHalo;
    const size_t plane = static_cast<size_t>(width_) * rows;

    // Resolve the whole haloed accumulator; kHole marks a hole.
    for (int c = 0; c < kChannels; ++c) {
        const float* a = acc.data() + c * plane;
        const float* w = wsum.data() + c * plane;
        float* r = resolved.data() + c * plane;
        for (size_t i = 0; i < plane; ++i) {
            r[i] = w[i] >= kHoleThreshold ? a[i] / w[i] : kHole;
        }
    }

    const float floorValue = static_cast<float>(*std::min_element(black_.begin(), black_.end()));

    // Fill and write the strip proper. Holes are counted only in the strip's
    // own rows, once.
    for (int j = stripY0; j < stripY1; ++j) {
        const int lj = j - base;
        const bool insideRow = j >= kInteriorMargin && j < height_ - kInteriorMargin;
        uint16_t* outRow = out_ + static_cast<size_t>(j) * width_ * kChannels;
        for (int i = 0; i < width_; ++i) {
            const bool inside = insideRow && i >= kInteriorMargin && i < width_ - kInteriorMargin;
            for (int c = 0; c < kChannels; ++c) {
                const float* r = resolved.data() + c * plane;
                float v = r[static_cast<size_t>(lj) * width_ + i];
                if (isHole(v)) {
                    holes_++;
                    if (inside) interiorHoles_++;
                    // Nearest resolved cell of this channel, in rings, within
                    // the haloed accumulator.
                    v = floorValue;
                    bool found = false;
                    for (int rad = 1; rad <= kFillRadius && !found; ++rad) {
                        for (int dj = -rad; dj <= rad && !found; ++dj) {
                            const int jj = lj + dj;
                            if (jj < 0 || jj >= rows) continue;
                            for (int di = -rad; di <= rad; ++di) {
                                if (std::abs(di) != rad && std::abs(dj) != rad) continue;
                                const int ii = i + di;
                                if (ii < 0 || ii >= width_) continue;
                                const float cand = r[static_cast<size_t>(jj) * width_ + ii];
                                if (!isHole(cand)) { v = cand; found = true; break; }
                            }
                        }
                    }
                }
                const long q = std::lround(v);
                outRow[static_cast<size_t>(i) * kChannels + c] =
                    static_cast<uint16_t>(std::min(std::max(q, 0L), 65535L));
            }
        }
    }
}

bool SubPixelCanvas::Finish() {
    if (ref_ == nullptr || out_ == nullptr) return false;
    if (!alts_.empty() && !noiseEstimated_) estimateNoise(alts_.front());
    if (alts_.empty()) {
        // No alternates: the tolerance is never consulted, but leave it sane.
        for (float& v : noiseVar_) v = minSigma_ * minSigma_ * noiseTolerance_ * noiseTolerance_;
    }

    const int rows = kStripRows + 2 * kHalo;
    const size_t plane = static_cast<size_t>(width_) * rows;
    std::vector<float> acc(plane * kChannels);
    std::vector<float> wsum(plane * kChannels);
    std::vector<float> resolved(plane * kChannels);

    holes_ = 0;
    interiorHoles_ = 0;
    contributionSum_ = 0.0;
    contributionCount_ = 0;

    for (int y0 = 0; y0 < height_; y0 += kStripRows) {
        const int y1 = std::min(y0 + kStripRows, height_);
        accumulateStrip(y0, y1, acc, wsum);
        resolveStrip(y0, y1, acc, wsum, resolved);
    }

    const double cells = static_cast<double>(width_) * height_;
    const double interior = std::max(0.0, static_cast<double>(width_ - 2 * kInteriorMargin)) *
                            std::max(0.0, static_cast<double>(height_ - 2 * kInteriorMargin));
    holeFraction_ = static_cast<float>(holes_ / (cells * kChannels));
    interiorHoleFraction_ = interior > 0 ? static_cast<float>(interiorHoles_ / (interior * kChannels)) : 0.0f;
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_multiframe_camera_pipeline_NativeSubPixel_nCreate(
        JNIEnv* env, jobject, jint sensorW, jint sensorH, jintArray jcfa, jintArray jblack,
        jint white, jfloat noiseTolerance, jfloat minSigma) {
    jint cfa[4] = {0, 1, 1, 2};
    if (env->GetArrayLength(jcfa) >= 4) env->GetIntArrayRegion(jcfa, 0, 4, cfa);
    const jsize blackN = env->GetArrayLength(jblack);
    std::vector<jint> black(std::max<jsize>(1, blackN), 0);
    if (blackN > 0) env->GetIntArrayRegion(jblack, 0, blackN, black.data());
    auto* c = SubPixelCanvas::Create(sensorW, sensorH, cfa, black.data(), static_cast<int>(black.size()),
                                     white, noiseTolerance, minSigma);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_NativeSubPixel_nDestroy(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<SubPixelCanvas*>(h);
}

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_NativeSubPixel_nSetReference(
        JNIEnv* env, jobject, jlong h, jobject buffer, jint strideBytes) {
    auto* c = reinterpret_cast<SubPixelCanvas*>(h);
    if (c == nullptr) return JNI_FALSE;
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr) return JNI_FALSE;
    c->SetReference(data, strideBytes);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_NativeSubPixel_nAddFrame(
        JNIEnv* env, jobject, jlong h, jobject buffer, jint strideBytes,
        jintArray jdx, jintArray jdy, jfloatArray jfracDx, jfloatArray jfracDy, jfloat gainScale) {
    auto* c = reinterpret_cast<SubPixelCanvas*>(h);
    if (c == nullptr) return JNI_FALSE;
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr) return JNI_FALSE;
    const jsize n = env->GetArrayLength(jdx);
    if (env->GetArrayLength(jdy) < n || env->GetArrayLength(jfracDx) < n || env->GetArrayLength(jfracDy) < n) {
        return JNI_FALSE;
    }
    std::vector<jint> dx(n), dy(n);
    std::vector<jfloat> fdx(n), fdy(n);
    env->GetIntArrayRegion(jdx, 0, n, dx.data());
    env->GetIntArrayRegion(jdy, 0, n, dy.data());
    env->GetFloatArrayRegion(jfracDx, 0, n, fdx.data());
    env->GetFloatArrayRegion(jfracDy, 0, n, fdy.data());
    c->AddFrame(data, strideBytes, dx.data(), dy.data(), fdx.data(), fdy.data(), gainScale);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_NativeSubPixel_nFinish(JNIEnv*, jobject, jlong h) {
    auto* c = reinterpret_cast<SubPixelCanvas*>(h);
    return (c != nullptr && c->Finish()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_dev_multiframe_camera_pipeline_NativeSubPixel_nOutput(JNIEnv* env, jobject, jlong h) {
    auto* c = reinterpret_cast<SubPixelCanvas*>(h);
    if (c == nullptr || c->output() == nullptr) return nullptr;
    return env->NewDirectByteBuffer(const_cast<uint16_t*>(c->output()), static_cast<jlong>(c->outputBytes()));
}

JNIEXPORT jfloatArray JNICALL
Java_dev_multiframe_camera_pipeline_NativeSubPixel_nStats(JNIEnv* env, jobject, jlong h) {
    auto* c = reinterpret_cast<SubPixelCanvas*>(h);
    if (c == nullptr) return nullptr;
    jfloat stats[4] = {c->holeFraction(), c->interiorHoleFraction(), c->meanContribution(),
                       static_cast<jfloat>(c->framesMerged())};
    jfloatArray out = env->NewFloatArray(4);
    env->SetFloatArrayRegion(out, 0, 4, stats);
    return out;
}

}  // extern "C"
