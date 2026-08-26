// JPEG encoding, in strips, at once.
//
// `Bitmap.compress` reaches Skia's copy of libjpeg-turbo through an interface
// with no handle on it: one call, one thread. Twelve and a half megapixels cost
// about 240 ms there, which is the largest single item in a capture -- and the
// phone has eight cores sitting idle for all of it.
//
// A JPEG's entropy-coded data is a single serial dependency: each block's DC
// coefficient is stored as a difference from the one before it, so the encoder
// cannot start halfway down the image without knowing what came above. Restart
// markers are the escape. They divide the scan into intervals that reset the DC
// predictor, and intervals are therefore independent of one another -- which
// means they can be produced in any order, or at the same time.
//
// So the image is cut into horizontal strips, each strip is encoded on its own
// thread as though it were an image in its own right, and the results are
// stitched into one file: the first strip's header, then every strip's entropy
// data with a restart marker between, then the end-of-image. Two details make
// the seam invisible to a decoder. Each strip is given a restart interval equal
// to its own length in MCUs, so it emits no restart markers internally and its
// data is exactly one interval. And the height in the stitched header is
// patched to the height of the whole image rather than the first strip's.
//
// The result is an ordinary baseline JPEG. Nothing here invents format.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <csetjmp>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <functional>
#include <thread>
#include <vector>
#include <unistd.h>

extern "C" {
#include <jpeglib.h>
}

#define LOG_TAG "MultiframeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

/** Chroma is subsampled 2x2, so a minimum coded unit is sixteen pixels square. */
constexpr int kMcu = 16;

/**
 * libjpeg's own error handler calls exit(), which would take the whole app
 * with it. This one jumps back to the caller instead.
 */
struct ErrorMgr {
    struct jpeg_error_mgr pub;
    jmp_buf jump;
};

void onError(j_common_ptr cinfo) {
    char message[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, message);
    LOGW("libjpeg: %s", message);
    longjmp(reinterpret_cast<ErrorMgr*>(cinfo->err)->jump, 1);
}

struct Strip {
    const uint8_t* first = nullptr;  // first row of this strip in the bitmap
    int height = 0;
    unsigned char* out = nullptr;    // malloc'd by libjpeg
    unsigned long size = 0;
    bool ok = false;
};

/** Encodes one strip as a standalone JPEG in memory. */
void encodeStrip(Strip& strip, int width, size_t stride, int quality,
                 unsigned int restartInterval) {
    jpeg_compress_struct cinfo{};
    ErrorMgr err{};
    cinfo.err = jpeg_std_error(&err.pub);
    err.pub.error_exit = onError;
    if (setjmp(err.jump)) {
        jpeg_destroy_compress(&cinfo);
        strip.ok = false;
        return;
    }

    jpeg_create_compress(&cinfo);
    jpeg_mem_dest(&cinfo, &strip.out, &strip.size);

    cinfo.image_width = static_cast<JDIMENSION>(width);
    cinfo.image_height = static_cast<JDIMENSION>(strip.height);
    // Straight from the Bitmap's own pixels. libjpeg-turbo converts RGBA to
    // YCbCr itself, with NEON, so nothing has to repack the image first.
    cinfo.input_components = 4;
    cinfo.in_color_space = JCS_EXT_RGBA;

    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality, TRUE);
    // Exactly one interval per strip: no restart marker is emitted inside a
    // strip, and the ones between strips are added when they are stitched.
    cinfo.restart_interval = restartInterval;

    jpeg_start_compress(&cinfo, TRUE);
    // A batch at a time rather than a row at a time. libjpeg buffers whole MCU
    // rows anyway, and handing it one row per call is all call overhead.
    std::vector<JSAMPROW> rows(kMcu);
    while (cinfo.next_scanline < cinfo.image_height) {
        const JDIMENSION remaining = cinfo.image_height - cinfo.next_scanline;
        const JDIMENSION batch = std::min<JDIMENSION>(kMcu, remaining);
        for (JDIMENSION i = 0; i < batch; ++i) {
            rows[i] = const_cast<JSAMPROW>(
                strip.first + static_cast<size_t>(cinfo.next_scanline + i) * stride);
        }
        jpeg_write_scanlines(&cinfo, rows.data(), batch);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    strip.ok = true;
}

/**
 * Walks the marker segments of a JPEG.
 *
 * [sofHeight] receives the offset of the two-byte height field in the frame
 * header. The return is the offset of the entropy-coded data, just past the
 * start-of-scan segment, or zero if the file is not shaped as expected.
 */
size_t scanStart(const unsigned char* p, size_t n, size_t* sofHeight) {
    *sofHeight = 0;
    if (n < 4 || p[0] != 0xFF || p[1] != 0xD8) return 0;
    size_t i = 2;
    while (i + 3 < n) {
        if (p[i] != 0xFF) return 0;
        const unsigned marker = p[i + 1];
        // Standalone markers carry no length.
        if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
            i += 2;
            continue;
        }
        const size_t len = (static_cast<size_t>(p[i + 2]) << 8) | p[i + 3];
        if (len < 2 || i + 2 + len > n) return 0;
        // Baseline or extended sequential frame header: length, precision, then
        // the height.
        if (marker == 0xC0 || marker == 0xC1) *sofHeight = i + 5;
        if (marker == 0xDA) return i + 2 + len;
        i += 2 + len;
    }
    return 0;
}

/** Every byte of [data], or false. */
bool writeAll(int fd, const unsigned char* data, size_t n) {
    size_t done = 0;
    while (done < n) {
        const ssize_t wrote = write(fd, data + done, n - done);
        if (wrote <= 0) return false;
        done += static_cast<size_t>(wrote);
    }
    return true;
}

void freeStrips(std::vector<Strip>& strips) {
    for (auto& s : strips) {
        if (s.out != nullptr) free(s.out);
        s.out = nullptr;
    }
}

/**
 * Encodes [pixels] and hands the finished file to [sink].
 *
 * The assembled JPEG is never copied: the header comes from the first strip and
 * each strip's entropy data is written straight out of the buffer libjpeg
 * produced it in.
 */
bool encode(const uint8_t* pixels, int width, int height, size_t stride,
            int quality, int threadsWanted,
            const std::function<bool(const unsigned char*, size_t)>& sink) {
    if (width <= 0 || height <= 0) return false;

    const int mcuCols = (width + kMcu - 1) / kMcu;
    const int mcuRows = (height + kMcu - 1) / kMcu;

    int threads = threadsWanted > 0
                  ? threadsWanted
                  : static_cast<int>(std::thread::hardware_concurrency());
    threads = std::clamp(threads, 1, mcuRows);
    // Whole MCU rows per strip, so every strip but the last is the same size
    // and holds exactly one restart interval.
    const int mcuRowsPerStrip = (mcuRows + threads - 1) / threads;
    const int strips = (mcuRows + mcuRowsPerStrip - 1) / mcuRowsPerStrip;
    const unsigned int restartInterval =
        static_cast<unsigned int>(mcuCols) * static_cast<unsigned int>(mcuRowsPerStrip);

    std::vector<Strip> parts(static_cast<size_t>(strips));
    for (int k = 0; k < strips; ++k) {
        const int y0 = k * mcuRowsPerStrip * kMcu;
        parts[static_cast<size_t>(k)].first = pixels + static_cast<size_t>(y0) * stride;
        parts[static_cast<size_t>(k)].height =
            std::min(mcuRowsPerStrip * kMcu, height - y0);
    }

    if (strips == 1) {
        encodeStrip(parts[0], width, stride, quality, 0);
    } else {
        std::vector<std::thread> pool;
        pool.reserve(static_cast<size_t>(strips - 1));
        for (int k = 1; k < strips; ++k) {
            pool.emplace_back([&parts, k, width, stride, quality, restartInterval]() {
                encodeStrip(parts[static_cast<size_t>(k)], width, stride, quality,
                            restartInterval);
            });
        }
        // The calling thread takes the first strip rather than waiting.
        encodeStrip(parts[0], width, stride, quality, restartInterval);
        for (auto& t : pool) t.join();
    }

    for (const auto& s : parts) {
        if (!s.ok || s.out == nullptr || s.size < 4) {
            LOGW("jpeg: a strip failed to encode");
            freeStrips(parts);
            return false;
        }
    }

    size_t sofHeight = 0;
    const size_t headerEnd = scanStart(parts[0].out, parts[0].size, &sofHeight);
    if (headerEnd == 0 || sofHeight == 0 || sofHeight + 1 >= parts[0].size) {
        LOGW("jpeg: could not read back the header libjpeg just wrote");
        freeStrips(parts);
        return false;
    }
    // The first strip's header describes the first strip. The file describes
    // the whole image.
    parts[0].out[sofHeight] = static_cast<unsigned char>((height >> 8) & 0xFF);
    parts[0].out[sofHeight + 1] = static_cast<unsigned char>(height & 0xFF);

    bool ok = sink(parts[0].out, headerEnd);
    for (int k = 0; ok && k < strips; ++k) {
        auto& s = parts[static_cast<size_t>(k)];
        size_t ignored = 0;
        const size_t start = (k == 0) ? headerEnd : scanStart(s.out, s.size, &ignored);
        if (start == 0 || s.size < start + 2) {
            ok = false;
            break;
        }
        // Everything but the trailing end-of-image, which belongs to the file
        // rather than to the strip.
        ok = sink(s.out + start, s.size - 2 - start);
        if (ok && k + 1 < strips) {
            // Restart markers cycle RST0..RST7 in order of appearance.
            const unsigned char rst[2] = {0xFF, static_cast<unsigned char>(0xD0 + (k % 8))};
            ok = sink(rst, 2);
        }
    }
    if (ok) {
        const unsigned char eoi[2] = {0xFF, 0xD9};
        ok = sink(eoi, 2);
    }

    freeStrips(parts);
    return ok;
}

/** Locks a Bitmap's pixels and checks they are the eight-bit RGBA this expects. */
struct LockedBitmap {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    AndroidBitmapInfo info{};
    bool ok = false;

    LockedBitmap(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGW("jpeg: bitmap is not RGBA_8888, leaving it to the framework");
            return;
        }
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        ok = true;
    }
    ~LockedBitmap() {
        if (ok) AndroidBitmap_unlockPixels(env, bitmap);
    }
};

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_multiframe_camera_pipeline_NativeJpeg_nEncodeToFd(
        JNIEnv* env, jobject, jobject bitmap, jint quality, jint fd, jint threads) {
    LockedBitmap locked(env, bitmap);
    if (!locked.ok) return JNI_FALSE;

    const bool ok = encode(
        static_cast<const uint8_t*>(locked.pixels),
        static_cast<int>(locked.info.width), static_cast<int>(locked.info.height),
        locked.info.stride, quality, threads,
        [fd](const unsigned char* p, size_t n) { return writeAll(fd, p, n); });
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** The same encode, into a byte array. For tests, which have no descriptor. */
JNIEXPORT jbyteArray JNICALL
Java_dev_multiframe_camera_pipeline_NativeJpeg_nEncodeToArray(
        JNIEnv* env, jobject, jobject bitmap, jint quality, jint threads) {
    LockedBitmap locked(env, bitmap);
    if (!locked.ok) return nullptr;

    std::vector<unsigned char> buffer;
    buffer.reserve(1u << 21);
    const bool ok = encode(
        static_cast<const uint8_t*>(locked.pixels),
        static_cast<int>(locked.info.width), static_cast<int>(locked.info.height),
        locked.info.stride, quality, threads,
        [&buffer](const unsigned char* p, size_t n) {
            buffer.insert(buffer.end(), p, p + n);
            return true;
        });
    if (!ok) return nullptr;

    jbyteArray out = env->NewByteArray(static_cast<jsize>(buffer.size()));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(buffer.size()),
                            reinterpret_cast<const jbyte*>(buffer.data()));
    return out;
}

}  // extern "C"
