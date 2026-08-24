// Native ring buffer pool for zero-shutter-lag raw capture.
//
// A 4080x3072 raw frame is 25 MB. A 16-deep ring is 400 MB and a 32-deep ring
// is 800 MB, which cannot exist on the managed heap at all -- this process is
// capped at 256 MB of Java heap. The pool therefore lives entirely in native
// memory, and camera frames are copied into it straight from the ImageReader's
// own direct buffer, so no Java array is ever allocated in the frame path.
//
// Backing store is mmap rather than std::vector. Two reasons:
//
//  * std::vector value-initialises, which would touch and commit every page of
//    an 800 MB pool up front. Anonymous mmap commits lazily, so a ring that is
//    only ever partly filled only costs what it actually holds.
//  * Pages can be returned to the kernel on close without waiting for the
//    allocator to decide to trim.
//
// AHardwareBuffer would be the choice if the merge ran on the GPU, because it
// can be imported into Vulkan without a copy. The merge is CPU-side today, so
// a hardware buffer would only add a lock/unlock round trip per frame for no
// benefit. That trade changes in the Vulkan phase, not this one.

#pragma once

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

namespace multiframe {

/**
 * Slot lifecycle.
 *
 * kWriting and kLocked both mean "the pool may not recycle this", but for
 * opposite reasons: one has a producer writing into it, the other has the
 * merge reading out of it.
 */
enum class SlotState : uint8_t {
    kFree = 0,
    kWriting = 1,
    kReady = 2,
    kLocked = 3,
};

struct RingStats {
    int64_t pushed = 0;
    /**
     * Frames refused while nothing was locked. This is the number that must be
     * zero: it means the ring could not keep up with the sensor on its own.
     */
    int64_t droppedNoSlot = 0;
    /**
     * Frames refused because a merge held the slots. Expected and harmless --
     * a merge outlasts the pool by design -- but counted apart from
     * droppedNoSlot so it can never be mistaken for a streaming failure.
     */
    int64_t droppedWhileLocked = 0;
    /** Ready frames recycled before anyone read them. Normal for a rolling ring. */
    int64_t recycled = 0;
    /**
     * Inter-arrival gaps longer than 1.5x nominal: a frame the camera produced
     * and something upstream lost. Distinct from droppedNoSlot, which is ours.
     */
    int64_t cameraGaps = 0;
    int64_t maxIntervalNs = 0;
    int64_t lastIntervalNs = 0;
    int64_t intervalSumNs = 0;
    int64_t intervalCount = 0;
};

class RawRing {
public:
    /**
     * Allocates the pool. Returns nullptr if the mapping fails, which lets the
     * caller retry with a smaller capacity rather than dying.
     */
    static RawRing* Create(int width, int height, int capacity, int64_t nominalIntervalNs);

    ~RawRing();

    RawRing(const RawRing&) = delete;
    RawRing& operator=(const RawRing&) = delete;

    int width() const { return width_; }
    int height() const { return height_; }
    int capacity() const { return capacity_; }
    size_t slotBytes() const { return slotBytes_; }

    /**
     * Reserves the oldest recyclable slot for writing, or -1 when every slot is
     * spoken for. Returns without blocking: the camera callback must never wait
     * on the merge.
     */
    int BeginWrite();

    /** Publishes a written slot as the newest frame. */
    void CommitWrite(int slot, int64_t timestampNs, int64_t frameNumber);

    /** Releases a reserved slot without publishing it, e.g. a short read. */
    void AbortWrite(int slot);

    uint16_t* SlotData(int slot);

    /**
     * Locks the [count] newest ready frames, newest first, and returns how many
     * were actually locked. Locked slots are excluded from recycling until
     * Unlock, so the merge can read them while the camera keeps streaming.
     *
     * This is the shutter path: it copies nothing and touches no pixels, so it
     * costs a mutex and a scan of at most `capacity` entries.
     */
    int LockNewest(int count, int* outSlots, int64_t* outTimestamps, int64_t* outFrameNumbers);

    /**
     * Releases every locked slot. The frames are discarded rather than
     * returned to the ready pool: a snapshot consumes what it locked.
     */
    void Unlock();

    int lockedCount() const;

    /** Frames available to a snapshot right now. */
    int readyCount() const;

    /**
     * Histograms the newest ready frame without consuming it.
     *
     * Needed because highlight protection has to run *while* streaming: by the
     * time the shutter is pressed the frames already exist, so an exposure
     * decision taken then is a decision about the next photograph rather than
     * this one. Taking a snapshot to measure would consume frames the shutter
     * is meant to use, so this reads in place under the lock instead.
     *
     * Returns false when the ring holds nothing yet.
     */
    bool HistogramNewest(int* bins, int binCount, int stride,
                         const int* black, int white, const int* cfa) const;

    RingStats stats() const;
    void ResetStats();

private:
    RawRing() = default;

    struct Slot {
        SlotState state = SlotState::kFree;
        int64_t sequence = -1;
        int64_t timestampNs = 0;
        int64_t frameNumber = -1;
    };

    mutable std::mutex mutex_;
    uint8_t* base_ = nullptr;
    size_t mappedBytes_ = 0;
    bool mapped_ = false;

    int width_ = 0;
    int height_ = 0;
    int capacity_ = 0;
    size_t slotBytes_ = 0;
    int64_t nominalIntervalNs_ = 0;

    std::vector<Slot> slots_;
    int64_t nextSequence_ = 0;
    int64_t lastTimestampNs_ = 0;
    int lockedCount_ = 0;

    RingStats stats_;
};

}  // namespace multiframe
