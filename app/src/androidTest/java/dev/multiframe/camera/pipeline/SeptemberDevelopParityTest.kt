package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.bench.DngFile
import dev.multiframe.camera.bench.BenchLenses
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/** Opt-in real-fixture parity; adb stages selected DNGs in the app's external files/parity. */
@RunWith(AndroidJUnit4::class)
class SeptemberDevelopParityTest {
    @Test fun septemberDngsMatchThroughProductionOrdering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(context.getExternalFilesDir(null), "parity").listFiles { f -> f.name.startsWith("MF_202609") && f.extension == "dng" }
        assumeTrue("stage September DNGs in external files/parity", files != null && files.isNotEmpty())
        for (file in files!!.sortedBy { it.name }) {
            val whole = DngFile.read(file)
            val dng = whole.decimated()
            val w = dng.frame.width; val h = dng.frame.height
            val params = DevelopParams(localTone = .15f, shadowLift = 1f,
                meteredGain = 2f.pow(dng.baselineExposure)).adaptedTo(dng.iso)
            val original = dng.frame.data.copyOf()
            val expected = RawDeveloper.develop(dng.frame, dng.sensor, dng.color, params, dng.shading)
            val buf = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
            buf.asShortBuffer().put(original)
            val bitmap = NativeMerge.create(w, h, dng.sensor)!!.use { it.develop(buf, dng.color, params, shading = dng.shading) }!!
            val actual = IntArray(w * h)
            bitmap.getPixels(actual, 0, w, 0, 0, w, h)
            if (dng.iso > 800) {
                val cfg = params.copy(chroma = params.chroma.copy(amount = 0f), sharpen = params.sharpen.copy(amount = 0f))
                val ref = RawDeveloper.develop(BayerFrame(w, h, original.copyOf()), dng.sensor, dng.color, cfg, dng.shading)
                val bm = NativeMerge.create(w, h, dng.sensor)!!.use { it.develop(buf, dng.color, cfg, shading = dng.shading) }!!
                val nat = IntArray(w*h); bm.getPixels(nat, 0, w, 0, 0, w, h); bm.recycle()
                compare(ref, nat, file.name + " core")
            }
            compare(expected, actual, file.name + " develop")
            // The reference's whole §3.1, optics and crop last; production's
            // native develop, then Optics.applyCropped on its bitmap.
            val optics = Optics.Params(distortion = BenchLenses.modelFor(whole, w, h), lensId = null)
            val reference = RawDeveloper.developCropped(BayerFrame(w, h, original.copyOf()), dng.sensor, dng.color,
                params.copy(optics = optics), dng.shading)
            // One measured CA model for both implementations; this tests the gather, not estimator noise.
            val corrected = Optics.applyCropped(bitmap, optics.copy(aberration = reference.scale))
            assertThat(corrected.inset).isEqualTo(reference.inset)
            val cw = corrected.bitmap.width; val ch = corrected.bitmap.height
            assertThat(cw).isEqualTo(reference.width)
            assertThat(ch).isEqualTo(reference.height)
            val result = IntArray(cw * ch)
            corrected.bitmap.getPixels(result, 0, cw, 0, 0, cw, ch)
            compare(reference.pixels, result, file.name + " optics/crop")
            if (corrected.bitmap !== bitmap) corrected.bitmap.recycle()
            bitmap.recycle()
        }
    }

    private fun compare(a: IntArray, b: IntArray, label: String) {
        var worst = 0; var total = 0L; var overTwo = 0
        for (i in a.indices) for (shift in intArrayOf(16, 8, 0)) {
            val delta = abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255))
            worst = maxOf(worst, delta); total += delta
            if (delta > 2) overTwo++
        }
        val mean = total.toDouble() / (a.size * 3)
        val rare = overTwo.toDouble() / (a.size * 3)
        Log.i("SeptemberParity", "$label: max=$worst mean=$mean fraction>2=$rare")
        // Existing sharpened parity allowance: threshold flips can add two codes.
        assertThat(worst).isAtMost(4)
        assertThat(mean).isLessThan(.25)
        assertThat(rare).isLessThan(.005)
    }

    @Test fun clippedSourceIsWhiteInBothNativeDevelops() {
        for (levels in listOf(floatArrayOf(1f, 1f, 1f), floatArrayOf(.75f, 1f, .66f))) {
            val profile = SensorProfile.DEFAULT
            val frame = BayerFrame(32, 32, ShortArray(1024) { i ->
                val x = i % 32; val y = i / 32
                val black = profile.blackAt(x, y)
                val c = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
                (black + levels[c] * (profile.whiteLevel - black)).toInt().toShort()
            })
            val buf = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder())
            buf.asShortBuffer().put(frame.data)
            val colour = ColorProfile(floatArrayOf(2.2f, 1f, 1f, 1.7f),
                floatArrayOf(1.6f, -.5f, -.1f, -.2f, 1.5f, -.3f, .1f, -.5f, 1.4f))
            val params = DevelopParams(exposureGain = 1f, localTone = .15f, shadowLift = 1f)
            NativeMerge.create(32, 32, profile)!!.use { merger ->
                val bitmap = merger.develop(buf, colour, params)!!
                assertThat(bitmap.getPixel(16, 16)).isEqualTo(-1)
                bitmap.recycle()
            }
            NativeSubPixel.create(32, 32, profile)!!.use { canvas ->
                assertThat(canvas.setReference(buf, 64)).isTrue()
                val result = canvas.finish()!!
                val black = LinearDeveloper.channelBlack(profile)
                result.rgb16.asShortBuffer().put(ShortArray(result.width * result.height * 3) { i ->
                    val c = i % 3
                    (black[c] + levels[c] * (profile.whiteLevel - black[c])).toInt().toShort()
                })
                val out = canvas.developToArray(profile, colour, params)!!
                val centre = (32 * result.width + 32) * 3
                for (c in 0..2) assertThat(out[centre + c].toInt() and 255).isEqualTo(255)
            }
        }
    }
}
