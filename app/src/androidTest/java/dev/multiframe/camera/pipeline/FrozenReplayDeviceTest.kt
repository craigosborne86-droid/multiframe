package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Pixel execution of identical archived sensor samples, not a new live scene. */
@RunWith(AndroidJUnit4::class)
class FrozenReplayDeviceTest {
    @Test fun compareHostAndDeviceOnIdenticalRaw() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("iqReplay") == "true")
        assertTrue(!DeviceKind.isEmulator && Build.MODEL == "Pixel 11 Pro")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = File(context.getExternalFilesDir(null), "iq-forensics")
        val name = requireNotNull(args.getString("iqArchiveName"))
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        val root = File(base, name)
        val m = JSONObject(File(root, "manifest.json").readText())
        fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
        fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
        val width = m.getInt("width"); val height = m.getInt("height")
        val profile = SensorProfile(ints(m.getJSONArray("staticBlack")), m.getInt("staticWhite"), ints(m.getJSONArray("cfa")))
        val calibrationBytes = File(base, "calibration.dng").readBytes()
        val illuminants = requireNotNull(DngMetadata.readIlluminants(calibrationBytes))
        val calibration = requireNotNull(DngMetadata.readCalibration(calibrationBytes)).copy(
            illuminant1Kelvin = ColorScience.illuminantKelvin(illuminants.first),
            illuminant2Kelvin = ColorScience.illuminantKelvin(illuminants.second))
        val output = File(base, "replay-${System.currentTimeMillis()}").apply { mkdirs() }
        val runs = JSONArray()
        for (regime in 0 until m.getJSONArray("requestedRegimes").length()) {
            val dir = File(root, "regime$regime-repeat0")
            val meta = JSONArray(File(dir, "frames.json").readText())
            val first = meta.getJSONObject(0)
            val buffers = (0 until meta.length()).map { i ->
                RandomAccessFile(File(dir, meta.getJSONObject(i).getString("raw")), "r").use {
                    it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()).order(ByteOrder.LITTLE_ENDIAN)
                }
            }
            fun light(i: Int): Double {
                val f = meta.getJSONObject(i)
                return f.getDouble("iso") * f.getDouble("exposureNs")
            }
            val wb = floats(first.getJSONArray("wbRggb")); val neutral = floats(first.getJSONArray("neutral"))
            val color = ColorProfile(wb, ColorScience.balancedCameraToSrgb(calibration, neutral,
                ColorScience.solveBlend(calibration, neutral), wb).m)
            val cols = first.getInt("shadingColumns"); val rows = first.getInt("shadingRows")
            val rggb = floats(first.getJSONArray("shadingRggb"))
            val maps = listOf("legacy" to ShadingMap(cols, rows, rggb),
                "site" to ShadingMap.fromRggb(cols, rows, rggb, profile.cfaPattern))
            val dest = File(output, dir.name).apply { mkdirs() }
            requireNotNull(NativeMerge.create(width, height, profile)).use { merge ->
                val started = SystemClock.elapsedRealtime()
                merge.setReference(buffers[0], width * 2)
                val ref = merge.lumaProxy(buffers[0], width * 2)
                for (i in 1 until buffers.size) {
                    val gain = (light(i) / light(0)).toFloat()
                    val alt = merge.lumaProxy(buffers[i], width * 2, gain)
                    val field = requireNotNull(Aligner.alignNative(ref, alt, maxOf(1, ref.width / 32), maxOf(1, ref.height / 32)))
                    merge.addFrame(buffers[i], width * 2, field, gain)
                }
                val (raw, stats) = merge.finish()
                val elapsed = SystemClock.elapsedRealtime() - started
                File(dest, "native-exact.raw16").outputStream().channel.use { ch ->
                    val view = raw.duplicate().apply { clear() }
                    while (view.hasRemaining()) ch.write(view)
                }
                val params = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON,
                    shadowLift = DevelopParams.SHADOW_LIFT_ON).adaptedTo(first.getInt("iso")).copy(meteredGain = 1f)
                for ((label, map) in maps) {
                    val bitmap = requireNotNull(merge.develop(raw, color, params, map))
                    File(dest, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                runs.put(JSONObject().put("regime", regime).put("frames", stats.framesMerged)
                    .put("mergeMillis", elapsed).put("meanContribution", stats.meanContribution)
                    .put("nativeBytes", android.os.Debug.getNativeHeapAllocatedSize()))
            }
        }
        File(output, "summary.json").writeText(runs.toString(2))
        android.util.Log.i("IqForensics", "REPLAY ${output.absolutePath}")
    }
}
