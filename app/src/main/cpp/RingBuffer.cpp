#include "RingBuffer.h"

#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
#include <chrono>
#include <algorithm>
#include <cstring>
#include <new>

#define LOG_TAG "MultiframeRing"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace multiframe {

namespace {
/** A gap longer than this multiple of the nominal frame interval lost a frame. */
constexpr double kGapFactor = 1.5;

/**
 * The pool may not reserve more than this share of physical memory.
 *
 * Linux happily overcommits: a mapping far larger than the machine has will
 * succeed and only fail when the pages are touched, by which point the
 * out-of-memory killer takes the whole process. Refusing here turns that into
 * a null return the caller can fall back from.
 */
constexpr double kMaxShareOfPhysicalRam = 0.5;

int64_t physicalRamBytes() {
    const long pages = sysconf(_SC_PHYS_PAGES);
    const long pageSize = sysconf(_SC_PAGE_SIZE);
    if (pages <= 0 || pageSize <= 0) return 0;
    return static_cast<int64_t>(pages) * static_cast<int64_t>(pageSize);
}

/**
 * Touches every page so the pool is warm before the first frame arrives.
 *
 * Lazy commit is the right default for most mappings, but not this one: a
 * zero-shutter-lag ring fills completely within its first second, so every page
 * gets faulted anyway -- just spread across the frames that can least afford
 * it. Measured on a Pixel 9 Pro XL, a cold 25 MB slot costs about 35 ms to fill
 * against 2.4 ms warm, which is a dropped frame at 30 fps. Paying it once up
 * front, while the session is still being configured, removes that entirely.
 */
void prefault(uint8_t* base, size_t bytes) {
    const size_t pageSize = static_cast<size_t>(sysconf(_SC_PAGE_SIZE));
    if (pageSize == 0) return;
    const auto start = std::chrono::steady_clock::now();
    for (size_t offset = 0; offset < bytes; offset += pageSize) {
        base[offset] = 0;
    }
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
    LOGI("pool prefaulted %.1f MB in %lld ms", bytes / (1024.0 * 1024.0),
         static_cast<long long>(ms));
}
/**
 * Copies one frame into a slot.
 *
 * Deliberately single threaded. Splitting the copy across four threads was
 * measured on a Pixel 9 Pro XL and made the average marginally better (8.2 ms
 * to 7.1 ms) while making the worst case much worse (18.1 ms to 30.7 ms),
 * because creating threads on a frame path that wakes 30 times a second pays
 * scheduler latency every time. Dropped frames are decided by the worst case,
 * not the average, so the predictable version wins.
 */
void copyFrame(uint8_t* dst, const uint8_t* src, int height,
               size_t rowBytes, size_t srcStride) {
    if (srcStride == rowBytes) {
        std::memcpy(dst, src, rowBytes * static_cast<size_t>(height));
        return;
    }
    // Cameras may pad rows; the pool stores them packed.
    for (int y = 0; y < height; ++y) {
        std::memcpy(dst + static_cast<size_t>(y) * rowBytes,
                    src + static_cast<size_t>(y) * srcStride, rowBytes);
    }
}
}  // namespace

RawRing* RawRing::Create(int width, int height, int capacity, int64_t nominalIntervalNs) {
    if (width <= 0 || height <= 0 || capacity <= 0) return nullptr;

    const size_t slotBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 2u;
    const size_t total = slotBytes * static_cast<size_t>(capacity);
    // Guards against an overflow turning a huge request into a small mapping.
    if (slotBytes == 0 || total / slotBytes != static_cast<size_t>(capacity)) return nullptr;

    const int64_t physical = physicalRamBytes();
    if (physical > 0 &&
        static_cast<double>(total) > kMaxShareOfPhysicalRam * static_cast<double>(physical)) {
        LOGW("refusing %.1f MB pool: device has %.1f MB of RAM",
             total / (1024.0 * 1024.0), physical / (1024.0 * 1024.0));
        return nullptr;
    }

    void* mem = mmap(nullptr, total, PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        LOGW("mmap of %.1f MB failed for %d slots", total / (1024.0 * 1024.0), capacity);
        return nullptr;
    }

    auto* ring = new (std::nothrow) RawRing();
    if (ring == nullptr) {
        munmap(mem, total);
        return nullptr;
    }
    ring->base_ = static_cast<uint8_t*>(mem);
    ring->mappedBytes_ = total;
    ring->mapped_ = true;
    ring->width_ = width;
    ring->height_ = height;
    ring->capacity_ = capacity;
    ring->slotBytes_ = slotBytes;
    ring->nominalIntervalNs_ = nominalIntervalNs;
    ring->slots_.assign(static_cast<size_t>(capacity), Slot{});

    LOGI("ring: %d slots of %dx%d, %.1f MB reserved (%.1f MB per slot)",
         capacity, width, height, total / (1024.0 * 1024.0),
         slotBytes / (1024.0 * 1024.0));
    prefault(ring->base_, total);
    return ring;
}

RawRing::~RawRing() {
    if (mapped_ && base_ != nullptr) {
        munmap(base_, mappedBytes_);
    }
    base_ = nullptr;
}

int RawRing::BeginWrite() {
    std::lock_guard<std::mutex> guard(mutex_);

    // Oldest recyclable slot wins: free slots first (sequence -1), then the
    // least recently published ready slot. Locked and in-flight slots are
    // invisible here, which is what keeps a snapshot stable under the merge.
    int best = -1;
    int64_t bestSequence = 0;
    for (int i = 0; i < capacity_; ++i) {
        const Slot& s = slots_[static_cast<size_t>(i)];
        if (s.state != SlotState::kFree && s.state != SlotState::kReady) continue;
        if (best == -1 || s.sequence < bestSequence) {
            best = i;
            bestSequence = s.sequence;
        }
    }

    if (best == -1) {
        // Being out of slots means something different depending on why: a
        // merge holding the ring is by design, the sensor outrunning an idle
        // ring is a defect.
        if (lockedCount_ > 0) {
            stats_.droppedWhileLocked++;
        } else {
            stats_.droppedNoSlot++;
        }
        return -1;
    }
    if (slots_[static_cast<size_t>(best)].state == SlotState::kReady) {
        stats_.recycled++;
    }
    slots_[static_cast<size_t>(best)].state = SlotState::kWriting;
    return best;
}

void RawRing::CommitWrite(int slot, int64_t timestampNs, int64_t frameNumber) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (slot < 0 || slot >= capacity_) return;

    Slot& s = slots_[static_cast<size_t>(slot)];
    s.state = SlotState::kReady;
    s.sequence = nextSequence_++;
    s.timestampNs = timestampNs;
    s.frameNumber = frameNumber;

    stats_.pushed++;
    if (lastTimestampNs_ != 0 && timestampNs > lastTimestampNs_) {
        const int64_t dt = timestampNs - lastTimestampNs_;
        stats_.lastIntervalNs = dt;
        stats_.intervalSumNs += dt;
        stats_.intervalCount++;
        stats_.maxIntervalNs = std::max(stats_.maxIntervalNs, dt);
        if (nominalIntervalNs_ > 0 &&
            static_cast<double>(dt) > kGapFactor * static_cast<double>(nominalIntervalNs_)) {
            stats_.cameraGaps++;
        }
    }
    if (timestampNs > lastTimestampNs_) lastTimestampNs_ = timestampNs;
}

void RawRing::AbortWrite(int slot) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (slot < 0 || slot >= capacity_) return;
    Slot& s = slots_[static_cast<size_t>(slot)];
    if (s.state == SlotState::kWriting) {
        // Keep whatever sequence it had so it stays the oldest candidate.
        s.state = (s.sequence >= 0) ? SlotState::kReady : SlotState::kFree;
    }
}

uint16_t* RawRing::SlotData(int slot) {
    if (base_ == nullptr || slot < 0 || slot >= capacity_) return nullptr;
    return reinterpret_cast<uint16_t*>(base_ + static_cast<size_t>(slot) * slotBytes_);
}

int RawRing::LockNewest(int count, int* outSlots, int64_t* outTimestamps,
                        int64_t* outFrameNumbers) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (count <= 0) return 0;

    // Collect ready slots newest-first. Capacity is small (<= 32), so an
    // insertion pass is cheaper than allocating and sorting.
    std::vector<int> ordered;
    ordered.reserve(static_cast<size_t>(capacity_));
    for (int i = 0; i < capacity_; ++i) {
        if (slots_[static_cast<size_t>(i)].state != SlotState::kReady) continue;
        ordered.push_back(i);
    }
    std::sort(ordered.begin(), ordered.end(), [this](int a, int b) {
        return slots_[static_cast<size_t>(a)].sequence >
               slots_[static_cast<size_t>(b)].sequence;
    });

    const int n = std::min(count, static_cast<int>(ordered.size()));
    for (int i = 0; i < n; ++i) {
        const int slot = ordered[static_cast<size_t>(i)];
        Slot& s = slots_[static_cast<size_t>(slot)];
        s.state = SlotState::kLocked;
        if (outSlots != nullptr) outSlots[i] = slot;
        if (outTimestamps != nullptr) outTimestamps[i] = s.timestampNs;
        if (outFrameNumbers != nullptr) outFrameNumbers[i] = s.frameNumber;
    }
    lockedCount_ += n;
    return n;
}

void RawRing::Unlock() {
    std::lock_guard<std::mutex> guard(mutex_);
    for (int i = 0; i < capacity_; ++i) {
        Slot& s = slots_[static_cast<size_t>(i)];
        if (s.state != SlotState::kLocked) continue;
        // Freed, not merely unlocked. A snapshot consumes its frames: they have
        // been merged, and by the time the merge finishes they are seconds old.
        // Returning them to the ready pool would let the next shutter press
        // merge them again alongside fresh ones -- frames seconds apart, which
        // is the exact failure a zero-shutter-lag ring exists to avoid. Freeing
        // them also makes these slots the first the camera refills.
        s.state = SlotState::kFree;
        s.sequence = -1;
        s.timestampNs = 0;
        s.frameNumber = -1;
    }
    lockedCount_ = 0;
}

int RawRing::lockedCount() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return lockedCount_;
}

int RawRing::readyCount() const {
    std::lock_guard<std::mutex> guard(mutex_);
    int n = 0;
    for (int i = 0; i < capacity_; ++i) {
        if (slots_[static_cast<size_t>(i)].state == SlotState::kReady) ++n;
    }
    return n;
}

bool RawRing::HistogramNewest(int* bins, int binCount, int stride,
                              const int* black, int white, const int* cfa) const {
    std::lock_guard<std::mutex> guard(mutex_);
    if (base_ == nullptr || bins == nullptr || binCount <= 0) return false;

    int newest = -1;
    int64_t newestSequence = -1;
    for (int i = 0; i < capacity_; ++i) {
        const Slot& s = slots_[static_cast<size_t>(i)];
        if (s.state != SlotState::kReady) continue;
        if (s.sequence > newestSequence) {
            newestSequence = s.sequence;
            newest = i;
        }
    }
    if (newest < 0) return false;

    const auto* src = reinterpret_cast<const uint16_t*>(
        base_ + static_cast<size_t>(newest) * slotBytes_);
    int lo = std::min(std::min(black[0], black[1]), std::min(black[2], black[3]));
    const float range = static_cast<float>(std::max(1, white - lo));
    const int step = std::max(1, stride);

    std::fill(bins, bins + binCount, 0);
    for (int y = 0; y < height_; y += step) {
        for (int x = 0; x < width_; x += step) {
            // Green sites only: half the sensor, and where the luminance is.
            if (cfa[(y & 1) * 2 + (x & 1)] != 1) continue;
            const float lin =
                (static_cast<float>(src[static_cast<size_t>(y) * width_ + x]) -
                 black[(y & 1) * 2 + (x & 1)]) / range;
            const int bin = std::clamp(
                static_cast<int>(std::clamp(lin, 0.0f, 1.0f) * (binCount - 1)),
                0, binCount - 1);
            bins[bin]++;
        }
    }
    return true;
}

RingStats RawRing::stats() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return stats_;
}

void RawRing::ResetStats() {
    std::lock_guard<std::mutex> guard(mutex_);
    stats_ = RingStats{};
    lastTimestampNs_ = 0;
}

}  // namespace multiframe

// ---------------------------------------------------------------------------
// JNI bridge.
//
// The copy out of the camera's ImageReader buffer happens here, deliberately
// outside the ring's mutex: BeginWrite reserves a slot, the memcpy runs
// unlocked, CommitWrite publishes it. A shutter press therefore never waits on
// a frame copy, and a frame copy never waits on the merge.
// ---------------------------------------------------------------------------

using multiframe::RawRing;
using multiframe::RingStats;

namespace {
inline RawRing* ringOf(jlong handle) { return reinterpret_cast<RawRing*>(handle); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nCreate(
        JNIEnv*, jobject, jint width, jint height, jint capacity, jlong nominalIntervalNs) {
    return reinterpret_cast<jlong>(RawRing::Create(width, height, capacity, nominalIntervalNs));
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nDestroy(JNIEnv*, jobject, jlong handle) {
    delete ringOf(handle);
}

/**
 * Copies one raw frame into the pool. Returns the slot index, or -1 if the ring
 * had no room, which the caller reports rather than swallowing.
 */
JNIEXPORT jint JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nPush(
        JNIEnv* env, jobject, jlong handle, jobject buffer, jint rowStride,
        jlong timestampNs, jlong frameNumber) {
    RawRing* ring = ringOf(handle);
    if (ring == nullptr) return -1;

    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (src == nullptr) return -1;
    const jlong available = env->GetDirectBufferCapacity(buffer);
    if (available <= 0) return -1;

    const int slot = ring->BeginWrite();
    if (slot < 0) return -1;

    auto* dst = reinterpret_cast<uint8_t*>(ring->SlotData(slot));
    if (dst == nullptr) {
        ring->AbortWrite(slot);
        return -1;
    }

    const int width = ring->width();
    const int height = ring->height();
    const size_t rowBytes = static_cast<size_t>(width) * 2u;
    const size_t stride = static_cast<size_t>(rowStride);

    const size_t needed = (stride == rowBytes)
            ? rowBytes * static_cast<size_t>(height)
            : stride * static_cast<size_t>(height - 1) + rowBytes;
    if (static_cast<size_t>(available) < needed) {
        ring->AbortWrite(slot);
        return -1;
    }
    multiframe::copyFrame(dst, src, height, rowBytes, stride);

    ring->CommitWrite(slot, timestampNs, frameNumber);
    return slot;
}

JNIEXPORT jint JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nLockNewest(
        JNIEnv* env, jobject, jlong handle, jint count,
        jintArray outSlots, jlongArray outTimestamps, jlongArray outFrameNumbers) {
    RawRing* ring = ringOf(handle);
    if (ring == nullptr) return 0;

    std::vector<int> slots(static_cast<size_t>(std::max(count, 0)));
    std::vector<int64_t> stamps(slots.size());
    std::vector<int64_t> frames(slots.size());

    const int n = ring->LockNewest(count, slots.data(), stamps.data(), frames.data());
    if (n > 0) {
        env->SetIntArrayRegion(outSlots, 0, n, slots.data());
        env->SetLongArrayRegion(outTimestamps, 0, n,
                                reinterpret_cast<const jlong*>(stamps.data()));
        env->SetLongArrayRegion(outFrameNumbers, 0, n,
                                reinterpret_cast<const jlong*>(frames.data()));
    }
    return n;
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nUnlock(JNIEnv*, jobject, jlong handle) {
    RawRing* ring = ringOf(handle);
    if (ring != nullptr) ring->Unlock();
}

/**
 * Hands the merge a direct ByteBuffer over the slot's own pixels.
 *
 * This is the whole point of the ring: the existing native merge already reads
 * from a direct buffer, so a locked slot feeds straight into it with no copy
 * and no new merge code.
 */
JNIEXPORT jobject JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nSlotBuffer(
        JNIEnv* env, jobject, jlong handle, jint slot) {
    RawRing* ring = ringOf(handle);
    if (ring == nullptr) return nullptr;
    void* data = ring->SlotData(slot);
    if (data == nullptr) return nullptr;
    return env->NewDirectByteBuffer(data, static_cast<jlong>(ring->slotBytes()));
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nStats(
        JNIEnv* env, jobject, jlong handle, jlongArray out) {
    RawRing* ring = ringOf(handle);
    if (ring == nullptr) return;
    const RingStats s = ring->stats();
    const jlong values[9] = {
        s.pushed, s.droppedNoSlot, s.recycled, s.cameraGaps,
        s.maxIntervalNs, s.lastIntervalNs, s.intervalSumNs, s.intervalCount,
        s.droppedWhileLocked,
    };
    const jsize n = std::min(static_cast<jsize>(9), env->GetArrayLength(out));
    env->SetLongArrayRegion(out, 0, n, values);
}

JNIEXPORT void JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nResetStats(JNIEnv*, jobject, jlong handle) {
    RawRing* ring = ringOf(handle);
    if (ring != nullptr) ring->ResetStats();
}

JNIEXPORT jint JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nLockedCount(JNIEnv*, jobject, jlong handle) {
    RawRing* ring = ringOf(handle);
    return ring == nullptr ? 0 : ring->lockedCount();
}

JNIEXPORT jint JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nReadyCount(JNIEnv*, jobject, jlong handle) {
    RawRing* ring = ringOf(handle);
    return ring == nullptr ? 0 : ring->readyCount();
}

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_RawRing_nHistogramNewest(
        JNIEnv* env, jobject, jlong handle, jintArray outBins, jint stride,
        jintArray jblack, jint white, jintArray jcfa) {
    RawRing* ring = ringOf(handle);
    if (ring == nullptr) return JNI_FALSE;

    const jsize binCount = env->GetArrayLength(outBins);
    if (binCount <= 0) return JNI_FALSE;

    int black[4], cfa[4];
    env->GetIntArrayRegion(jblack, 0, 4, black);
    env->GetIntArrayRegion(jcfa, 0, 4, cfa);

    std::vector<int> bins(static_cast<size_t>(binCount));
    if (!ring->HistogramNewest(bins.data(), binCount, stride, black, white, cfa)) {
        return JNI_FALSE;
    }
    env->SetIntArrayRegion(outBins, 0, binCount, bins.data());
    return JNI_TRUE;
}

}  // extern "C"
