package dev.multiframe.camera.pipeline

import android.graphics.BitmapFactory
import android.util.Log
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "SuperResDevice"

/**
 * The 2x path end to end at the sensor's real size, on synthetic frames.
 *
 * ### What it checks
 *
 * That a burst of the size a shutter press produces -- eight frames of
 * 4080x3072 -- goes through alignment, the coverage prediction, the strip
 * accumulator and the linear develop to a JPEG of 8160x6144 that decodes,
 * without ever holding anything of image size on the Java heap; and what
 * that costs, in time and in resident memory, which is the figure the memory
 * budget has to be verified against rather than reasoned about.
 *
 * ### Why synthetic
 *
 * A real burst needs the camera and a hand, and its sub-pixel offsets are
 * whatever they happened to be. Frames rendered from one scene at known
 * offsets exercise the same code with the same sizes and let the prediction
 * be checked against the geometry it was made from. The camera path itself
 * is `ZslCapture`, which this mirrors stage by stage.
 */
@RunWith(AndroidJUnit4::class)
class SuperResDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val sensorW = 4080
    private val sensorH = 3072
    private val frames = 8

    private val profile = SensorProfile(
        blackLevel = intArrayOf(64, 64, 64, 64),
        whiteLevel = 1023,
        cfaPattern = intArrayOf(0, 1, 1, 2),
    )
    private val color = ColorProfile(
        gains = floatArrayOf(1.8f, 1f, 1f, 1.5f),
        matrix = floatArrayOf(1.6f, -0.5f, -0.1f, -0.2f, 1.5f, -0.3f, 0.0f, -0.5f, 1.5f),
    )

    /**
     * A scene with structure at every scale: a gradient, a few incommensurate
     * waves for the alignment to lock onto, a field of bars at the sensor's
     * Nyquist across the middle, and a bright patch to exercise the roll-off.
     * Evaluated analytically at a sub-pixel position, so a shifted frame is
     * the same scene seen from a shifted sensor.
     *
     * The waves are `sin(a x + b y)`, which is not separable -- but
     * `sin(ax) cos(by) + cos(ax) sin(by)` is, so a frame is rendered from four
     * tables per wave rather than five sines per pixel. The first version
     * spent forty-five seconds a burst in `sinf` and heated the phone enough
     * to throttle the very measurement the test exists to make.
     */
    private val waves = arrayOf(
        floatArrayOf(0.0173f, 0.0091f, 1.0f),
        floatArrayOf(0.0047f, -0.0212f, 1.0f),
        floatArrayOf(0.061f, 0.043f, 0.8f),
        floatArrayOf(0.29f, -0.17f, 0.8f),
        floatArrayOf(0.11f, 0.31f, 0.7f),
    )

    private class Tables(val sinX: Array<FloatArray>, val cosX: Array<FloatArray>, val sinY: Array<FloatArray>, val cosY: Array<FloatArray>)

    private fun tables(sx: Float, sy: Float): Tables {
        val n = waves.size
        return Tables(
            Array(n) { k -> FloatArray(sensorW) { x -> kotlin.math.sin(waves[k][0] * (x + sx)) } },
            Array(n) { k -> FloatArray(sensorW) { x -> kotlin.math.cos(waves[k][0] * (x + sx)) } },
            Array(n) { k -> FloatArray(sensorH) { y -> kotlin.math.sin(waves[k][1] * (y + sy)) } },
            Array(n) { k -> FloatArray(sensorH) { y -> kotlin.math.cos(waves[k][1] * (y + sy)) } },
        )
    }

    private fun sceneAt(c: Int, x: Int, y: Int, sx: Float, sy: Float, t: Tables): Float {
        val fx = x + sx
        val fy = y + sy
        val gx = fx / sensorW
        val gy = fy / sensorH
        var texture = 0f
        for (k in waves.indices) {
            texture += waves[k][2] * (t.sinX[k][x] * t.cosY[k][y] + t.cosX[k][x] * t.sinY[k][y])
        }
        texture *= 50f
        val bar = if (fy > sensorH / 3f && fy < 2 * sensorH / 3f) {
            // Bars two sensor pixels wide: a smooth-edged square wave. At the
            // sensor's Nyquist they are what the canvas exists to resolve and
            // what a 2x2-binned proxy cannot align by, so they ride on the
            // texture rather than replace it.
            val phase = ((fx / 2f) % 2f + 2f) % 2f
            if (phase < 1f) 90f else 0f
        } else 0f
        val patch = if (gx > 0.75f && gy < 0.2f) 350f else 0f
        val tint = when (c) { 0 -> 0.55f; 2 -> 0.65f; else -> 1f }
        return 64f + (260f + 300f * gx + 160f * gy + texture + bar + patch) * tint
    }

    private fun frame(sx: Float, sy: Float, seed: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(sensorW * sensorH * 2).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buf.asShortBuffer()
        val rnd = Random(seed)
        val t = tables(sx, sy)
        val row = ShortArray(sensorW)
        for (y in 0 until sensorH) {
            for (x in 0 until sensorW) {
                val c = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
                val v = sceneAt(c, x, y, sx, sy, t) + rnd.nextInt(-2, 3)
                row[x] = v.toInt().coerceIn(0, 1023).toShort()
            }
            shorts.put(row)
        }
        buf.rewind()
        return buf
    }

    private fun rssMb(): Long = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024) ?: -1
        }
    } catch (e: Exception) { -1 }

    @Test
    fun aFullSizeBurstBecomesATwoXJpeg() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        assertThat(MemoryBudget.canHoldCanvas(sensorW, sensorH)).isTrue()
        DeviceKind.warnIfNotAPhone(TAG)

        val rssStart = rssMb()
        val tFrames = System.currentTimeMillis()
        // Offsets a hand produces over a quarter of a second: a few pixels of
        // drift, whose fractions -- the part that matters to the canvas -- are
        // spread over the period. Under a pixel of total motion they would not
        // be, and red and blue would keep a third of their cells for the fill.
        val rnd = Random(3)
        val shifts = List(frames - 1) { rnd.nextFloat() * 6f - 3f to rnd.nextFloat() * 6f - 3f }
        val ref = frame(0f, 0f, 1)
        val alts = shifts.mapIndexed { i, (sx, sy) -> frame(sx, sy, 10 + i) }
        Log.i(TAG, "rendered $frames frames in ${System.currentTimeMillis() - tFrames}ms, rss ${rssMb()} MB")
        val stride = sensorW * 2

        // Alignment, as ZslCapture does it, through the merge's proxies.
        val tAlign = System.currentTimeMillis()
        val merger = NativeMerge.create(sensorW, sensorH, profile)
        assertThat(merger).isNotNull()
        val fields = merger!!.use { m ->
            m.setReference(ref, stride)
            val refProxy = m.lumaProxy(ref, stride)
            val tilesX = maxOf(1, m.proxyWidth / 32)
            val tilesY = maxOf(1, m.proxyHeight / 32)
            alts.map { alt ->
                val proxy = m.lumaProxy(alt, stride)
                Aligner.alignNative(refProxy, proxy, tilesX, tilesY)
                    ?: Aligner.align(Aligner.buildPyramid(refProxy), Aligner.buildPyramid(proxy), tilesX, tilesY)
            }
        }
        val alignMillis = System.currentTimeMillis() - tAlign

        // The alignment found the shifts it was given, fraction included.
        // The alignment found the shifts it was given, fraction included, in
        // a tile of plain texture. A tile under the bar field is logged and
        // not asserted: bars at the sensor's Nyquist are at the *proxy's*
        // Nyquist, where a shift changes their contrast rather than their
        // phase, and no SAD search can read a shift off that -- and a search
        // confused in x takes its y cross-section at the wrong x. The samples
        // a frame places there by a wrong offset disagree with the reference
        // and are rejected; the canvas under a pure grating is the
        // reference's, which is the honest answer and is why the fill exists.
        for ((i, f) in fields.withIndex()) {
            val (sx, sy) = shifts[i]
            val textured = f.tilesX / 2 + f.tilesX * (f.tilesY / 8)
            val bars = f.dx.size / 2
            for (t in intArrayOf(textured, bars)) {
                val foundX = (f.dx[t] + f.fracDx[t]) * 2f
                val foundY = (f.dy[t] + f.fracDy[t]) * 2f
                Log.i(TAG, "frame ${i + 1} tile $t (${if (t == bars) "bars" else "texture"}): shift (%.2f, %.2f) found (%.2f, %.2f)".format(
                    sx, sy, -foundX, -foundY))
                if (t == textured) {
                    assertThat(abs(-foundX - sx)).isLessThan(0.35f)
                    assertThat(abs(-foundY - sy)).isLessThan(0.35f)
                }
            }
        }

        val prediction = SubPixelCoverage.predict(fields, profile.cfaPattern)
        Log.i(TAG, "predicted holes: R %.1f%% G %.1f%% B %.1f%%".format(
            prediction.perChannel[0] * 100, prediction.perChannel[1] * 100, prediction.perChannel[2] * 100))
        assertThat(prediction.worthAttempting).isTrue()

        // The canvas.
        val tAcc = System.currentTimeMillis()
        val canvas = NativeSubPixel.create(sensorW, sensorH, profile)
        assertThat(canvas).isNotNull()
        val out = File(context.cacheDir, "superres_test.jpg")
        var developStats: NativeSubPixel.DevelopStats? = null
        var accMillis = 0L
        var jpegMillis = 0L
        var rssPeak = 0L
        canvas!!.use { n ->
            assertThat(n.setReference(ref, stride)).isTrue()
            for ((i, alt) in alts.withIndex()) assertThat(n.addFrame(alt, stride, fields[i])).isTrue()
            val r = n.finish()
            accMillis = System.currentTimeMillis() - tAcc
            assertThat(r).isNotNull()
            rssPeak = rssMb()
            Log.i(TAG, ("accumulated in %dms: holes R %.2f%% G %.2f%% B %.2f%% (interior %.2f%%), " +
                "contribution %.3f, rss %d MB").format(
                accMillis, r!!.interiorHolesPerChannel[0] * 100, r.interiorHolesPerChannel[1] * 100,
                r.interiorHolesPerChannel[2] * 100, r.interiorHoleFraction * 100, r.meanContribution, rssPeak))
            assertThat(r.width).isEqualTo(sensorW * 2)
            assertThat(r.height).isEqualTo(sensorH * 2)
            // Prediction and measurement agree on green within a point, and
            // the burst is one the path accepts.
            assertThat(abs(r.interiorHolesPerChannel[1] - prediction.perChannel[1])).isLessThan(0.01f)
            assertThat(r.acceptable).isTrue()

            val tJpeg = System.currentTimeMillis()
            // A descriptor, as the saver hands the capture path one.
            android.os.ParcelFileDescriptor.open(
                out,
                android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or
                    android.os.ParcelFileDescriptor.MODE_CREATE or
                    android.os.ParcelFileDescriptor.MODE_TRUNCATE,
            ).use { pfd ->
                developStats = n.developToJpeg(pfd.fd, profile, color, DevelopParams(), null, 95)
            }
            jpegMillis = System.currentTimeMillis() - tJpeg
            rssPeak = maxOf(rssPeak, rssMb())
        }
        assertThat(developStats).isNotNull()
        Log.i(TAG, ("developed: gain %.2f, exposure %.0fms, develop %.0fms, sharpen %.0fms, encode %.0fms; " +
            "%dms wall, file %.1f MB, rss after %d MB").format(
            developStats!!.exposureGain, developStats!!.exposureMillis, developStats!!.developMillis,
            developStats!!.sharpenMillis, developStats!!.encodeMillis, jpegMillis,
            out.length() / 1048576.0, rssMb()))

        // The file is a JPEG of the canvas's size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, bounds)
        assertThat(bounds.outWidth).isEqualTo(sensorW * 2)
        assertThat(bounds.outHeight).isEqualTo(sensorH * 2)
        assertThat(bounds.outMimeType).isEqualTo("image/jpeg")

        // And it is the picture: decoded small, the gradient runs the right
        // way, the bright patch is bright, and the bar field is neither black
        // nor flat.
        val small = BitmapFactory.decodeFile(out.path, BitmapFactory.Options().apply { inSampleSize = 16 })
        assertThat(small).isNotNull()
        val w = small!!.width
        val h = small.height
        fun luma(x: Int, y: Int): Int {
            val p = small.getPixel(x, y)
            return (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val topLeft = luma(w / 20, h / 20)
        val bottomRight = luma(w - w / 20, h - h / 20)
        val patch = luma(w * 9 / 10, h / 10)
        Log.i(TAG, "decoded %dx%d: top-left %d, bottom-right %d, patch %d".format(w, h, topLeft, bottomRight, patch))
        assertThat(bottomRight).isGreaterThan(topLeft)
        assertThat(patch).isGreaterThan(200)
        var barMin = 255
        var barMax = 0
        for (x in w / 4 until 3 * w / 4) {
            val l = luma(x, h / 2)
            barMin = minOf(barMin, l); barMax = maxOf(barMax, l)
        }
        assertThat(barMin).isGreaterThan(20)
        small.recycle()

        Log.i(TAG, ("2x path at %dx%d -> %dx%d: align %dms, accumulate %dms, develop+encode %dms; " +
            "rss start %d MB, peak seen %d MB").format(
            sensorW, sensorH, sensorW * 2, sensorH * 2, alignMillis, accMillis, jpegMillis, rssStart, rssPeak))
        out.delete()
    }

    /**
     * The strip sweep's tile size, compared on one burst, interleaved.
     *
     * A/B/A/B rather than A then B: the phone warms through the run, and a
     * configuration measured second is measured on a slower phone. Interleaving
     * puts each configuration on both sides of the drift. The frames are
     * rendered once and shared, at a quarter of the sensor's height to keep
     * the run short; the reach and the tile are in canvas cells and do not
     * care about the height.
     */
    @Test
    fun theTileSizeIsChosenByMeasurement() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val h = 768
        val rnd = Random(5)
        val shifts = List(frames - 1) { rnd.nextFloat() * 6f - 3f to rnd.nextFloat() * 6f - 3f }
        val ref = frameOfHeight(h, 0f, 0f, 1)
        val alts = shifts.mapIndexed { i, (sx, sy) -> frameOfHeight(h, sx, sy, 10 + i) }
        val stride = sensorW * 2
        val fields = NativeMerge.create(sensorW, h, profile)!!.use { m ->
            m.setReference(ref, stride)
            val refProxy = m.lumaProxy(ref, stride)
            alts.map { alt ->
                Aligner.alignNative(refProxy, m.lumaProxy(alt, stride), maxOf(1, m.proxyWidth / 32), maxOf(1, m.proxyHeight / 32))!!
            }
        }

        fun timeWith(rows: Int, cols: Int): Long {
            NativeSubPixel.setTile(rows, cols)
            val canvas = NativeSubPixel.create(sensorW, h, profile)!!
            return canvas.use { n ->
                n.setReference(ref, stride)
                for ((i, alt) in alts.withIndex()) n.addFrame(alt, stride, fields[i])
                val t0 = System.nanoTime()
                assertThat(n.finish()).isNotNull()
                (System.nanoTime() - t0) / 1_000_000
            }
        }

        // Row bands the width of the canvas, against tiles that fit a cache.
        val configs = listOf(8 to 100000, 32 to 100000, 64 to 512, 32 to 256, 128 to 1024)
        val rounds = 3
        val times = LinkedHashMap<Pair<Int, Int>, ArrayList<Long>>()
        for (round in 0 until rounds) {
            for (cfg in configs) times.getOrPut(cfg) { ArrayList() }.add(timeWith(cfg.first, cfg.second))
        }
        for ((cfg, t) in times) {
            Log.i(TAG, "tile %4d x %6d: %s ms, median %d".format(cfg.first, cfg.second, t.joinToString(" "), t.sorted()[t.size / 2]))
        }
        NativeSubPixel.setTile(NativeSubPixel.TILE_ROWS, NativeSubPixel.TILE_COLS)
        DeviceKind.warnIfNotAPhone(TAG)
    }

    /** [frame] at a different sensor height, for the harness. */
    private fun frameOfHeight(h: Int, sx: Float, sy: Float, seed: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(sensorW * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buf.asShortBuffer()
        val rnd = Random(seed)
        val t = tables(sx, sy)
        val row = ShortArray(sensorW)
        for (y in 0 until h) {
            for (x in 0 until sensorW) {
                val c = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
                val v = sceneAt(c, x, y, sx, sy, t) + rnd.nextInt(-2, 3)
                row[x] = v.toInt().coerceIn(0, 1023).toShort()
            }
            shorts.put(row)
        }
        buf.rewind()
        return buf
    }

    /** The orientation goes in the tag, not the pixels, and the tag has to read back. */
    @Test
    fun theOrientationTagSurvivesTheWriter() {
        val out = File(context.cacheDir, "orientation_test.jpg")
        // A tiny JPEG to carry the tag.
        val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()

        val exif = ExifInterface(out.path)
        ExifWriter.apply(
            exif,
            CaptureMetadata(
                exposureTimeNs = 8_000_000L, iso = 100, apertureF = 1.7f, focalLengthMm = 6.9f,
                equivalent35mm = 24, frames = 8, takenAtMillis = 0L, lensLabel = "24mm",
                kind = CaptureKind.SUPER_RES, rotationDegrees = 90,
            ),
        )
        exif.saveAttributes()

        val back = ExifInterface(out.path)
        assertThat(back.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            .isEqualTo(ExifInterface.ORIENTATION_ROTATE_90)
        assertThat(back.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)).contains("accumulated at 2x")
        out.delete()
    }
}
