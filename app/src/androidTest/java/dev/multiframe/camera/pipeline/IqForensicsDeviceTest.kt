package dev.multiframe.camera.pipeline

import android.content.Context
import android.app.KeyguardManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Opt-in physical RAW archive and repeated-capture audit; never part of the routine suite. */
@RunWith(AndroidJUnit4::class)
class IqForensicsDeviceTest {
    @get:Rule val cameraPermission = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private companion object {
        const val PREVIEW_EXPOSURE_CAP_NS = 33_333_333L
    }

    @Test fun freezeRawBurstsAndMeasureRepeatedCaptures() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val preflightOnly = args.getString("iqPreflightOnly") == "true"
        val requireComparable = args.getString("iqRequireComparable") == "true"
        assumeTrue("opt in with -e iqForensics true or -e iqPreflightOnly true",
            args.getString("iqForensics") == "true" || preflightOnly)
        assertTrue("Physical Pixel 11 Pro required", !DeviceKind.isEmulator && Build.MODEL == "Pixel 11 Pro")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val screenLost = AtomicBoolean(false)
        fun assertScreenReady() {
            assertTrue("Screen slept or keyguard appeared; unlock and keep the screen awake before capture",
                !screenLost.get() && power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked)
        }
        assertScreenReady()
        val manager = context.getSystemService(CameraManager::class.java)
        val lens = requireNotNull(LensCatalog.default(LensCatalog.enumerate(manager)))
        val ch = manager.getCameraCharacteristics(lens.cameraId)
        val caps = CameraCapabilities(true, null, null, null, null, 0f, listOf(1), emptyList(),
            emptyList(), emptyList(), 0, 0, 0f, 90, true, emptySet()).forLens(ch)
        val decision = ZslPolicy.evaluate(lens.supportsRaw, ZslRawStream.rawConfigs(ch))
        assertTrue("RAW streaming required", decision is ZslDecision.Stream)
        val config = (decision as ZslDecision.Stream).config
        val preview = ZslRawStream.previewSizeFor(ch, config)
        val readerThread = HandlerThread("iq-preview-drain").apply { start() }
        val reader = ImageReader.newInstance(preview.width, preview.height, ImageFormat.YUV_420_888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(readerThread.looper))
        val frames = (args.getString("iqFrames")?.toInt() ?: 16).coerceIn(2, 28)
        val repeats = (args.getString("iqRepeats")?.toInt() ?: 3).coerceIn(1, 10)
        val archive = args.getString("iqArchive") != "false"
        val requireExact = args.getString("iqRequireExact") == "true"
        val phase = args.getString("iqPhase") ?: "baseline"
        require(phase.matches(Regex("[a-zA-Z0-9_-]+")))
        val root = File(context.getExternalFilesDir(null), "iq-forensics/${phase}-${System.currentTimeMillis()}")
        check(root.mkdirs())
        val running = AtomicBoolean(true)
        val sampler = thread(name = "iq-telemetry") {
            File(root, "telemetry.csv").bufferedWriter().use { out ->
                out.appendLine("elapsedMs,thermalStatus,vmRssKb,vmHwmKb,nativeAllocatedBytes,screenInteractive,keyguardLocked,deviceLocked")
                while (running.get()) {
                    val interactive = power.isInteractive
                    val locked = keyguard.isKeyguardLocked
                    val deviceLocked = keyguard.isDeviceLocked
                    if (!interactive || locked || deviceLocked) screenLost.set(true)
                    val status = File("/proc/self/status").readLines()
                    fun kb(key: String) = status.firstOrNull { it.startsWith("$key:") }
                        ?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
                    out.appendLine("${SystemClock.elapsedRealtime()},${power.currentThermalStatus},${kb("VmRSS")},${kb("VmHWM")},${android.os.Debug.getNativeHeapAllocatedSize()},$interactive,$locked,$deviceLocked")
                    out.flush()
                    Thread.sleep(1000)
                }
            }
        }
        var stream: ZslRawStream? = null
        var foreground: ActivityScenario<ComponentActivity>? = null
        try {
            val activity = ActivityScenario.launch(ComponentActivity::class.java)
            foreground = activity
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            waitUntil {
                assertScreenReady()
                var focused = false
                activity.onActivity { focused = it.hasWindowFocus() }
                focused
            }
            assertScreenReady()
            stream = withTimeout(20_000) { ZslRawStream.open(manager, lens, ch, reader.surface, config,
                frames + 4, ManualSettings(), caps) }
            val s = requireNotNull(stream)
            waitUntil { assertScreenReady(); s.readyFrames() >= frames && s.latestResult() != null }
            // Let the actual auto solution settle before holding focus/WB/exposure.
            SystemClock.sleep(2000)
            assertScreenReady()
            val initial = requireNotNull(s.latestResult())
            val autoIso = requireNotNull(initial.get(CaptureResult.SENSOR_SENSITIVITY))
            val autoExposureNs = requireNotNull(initial.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            val autoProduct = Math.multiplyExact(autoIso.toLong(), autoExposureNs)
            val product = autoIso.toDouble() * autoExposureNs
            val range = requireNotNull(ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))
            val exposureRange = requireNotNull(ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE))
            val minimumExposureNs = exposureRange.lower
            val maximumPreviewExposureNs = minOf(exposureRange.upper, PREVIEW_EXPOSURE_CAP_NS)
            val high = range.upper
            val regimes = (args.getString("iqIsos")?.split(',')?.map { it.toInt() }
                ?: listOf(range.lower, sqrt(range.lower.toDouble() * high).toInt(), high)).distinct()
            require(regimes.all { it in range.lower..range.upper })
            val perIsoBounds = JSONArray()
            val commonMinimum = regimes.maxOf { requestedIso ->
                Math.multiplyExact(requestedIso.toLong(), minimumExposureNs)
            }
            val commonMaximum = regimes.minOf { requestedIso ->
                Math.multiplyExact(requestedIso.toLong(), maximumPreviewExposureNs)
            }
            regimes.forEach { requestedIso ->
                perIsoBounds.put(JSONObject().put("requestedIso", requestedIso)
                    .put("minimumProductIsoNs", Math.multiplyExact(requestedIso.toLong(), minimumExposureNs))
                    .put("maximumProductIsoNs", Math.multiplyExact(requestedIso.toLong(), maximumPreviewExposureNs)))
            }
            val commonNonempty = minimumExposureNs <= maximumPreviewExposureNs && commonMinimum <= commonMaximum
            val comparable = commonNonempty && autoProduct in commonMinimum..commonMaximum
            val preflight = JSONObject()
                .put("model", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
                .put("cameraId", lens.cameraId).put("phase", phase)
                .put("rawWidth", config.width).put("rawHeight", config.height)
                .put("isoRange", JSONArray(listOf(range.lower, range.upper)))
                .put("sensorExposureRangeNs", JSONArray(listOf(exposureRange.lower, exposureRange.upper)))
                .put("sensorMinimumExposureNs", minimumExposureNs)
                .put("sensorMaximumExposureNs", exposureRange.upper)
                .put("previewExposureCapNs", PREVIEW_EXPOSURE_CAP_NS)
                .put("effectivePreviewMaximumExposureNs", maximumPreviewExposureNs)
                .put("autoIso", autoIso).put("autoExposureNs", autoExposureNs)
                .put("autoProductIsoNs", autoProduct)
                .put("requestedIsos", JSONArray(regimes))
                .put("perIsoFeasibleProductIntervals", perIsoBounds)
                .put("commonFeasibleProductInterval", JSONObject()
                    .put("minimumProductIsoNs", commonMinimum)
                    .put("maximumProductIsoNs", commonMaximum)
                    .put("nonempty", commonNonempty))
                .put("autoProductComparable", comparable)
                .put("preflightOnly", preflightOnly).put("requireComparable", requireComparable)
            val preflightFile = File(root, "preflight.json")
            preflightFile.writeText(preflight.toString(2))
            Log.i("IqForensics", "PREFLIGHT ${preflightFile.absolutePath} ${preflight}")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "IQ preflight ${preflightFile.absolutePath}: " +
                    "autoProduct=$autoProduct common=[$commonMinimum,$commonMaximum] " +
                    "nonempty=$commonNonempty comparable=$comparable\n")
            })
            if (preflightOnly) return@runBlocking
            if (requireComparable) check(comparable) {
                "Auto ISO×time product $autoProduct is outside the common feasible interval " +
                    "[$commonMinimum,$commonMaximum] for requested ISOs $regimes; see ${preflightFile.absolutePath}"
            }
            val manifest = JSONObject().put("model", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
                .put("cameraId", lens.cameraId).put("width", config.width).put("height", config.height)
                .put("phase", phase).put("frames", frames).put("repeats", repeats)
                .put("staticBlack", JSONArray(SensorProfile.from(ch).blackLevel.toList()))
                .put("staticWhite", SensorProfile.from(ch).whiteLevel)
                .put("cfa", JSONArray(SensorProfile.from(ch).cfaPattern.toList()))
                .put("isoRange", JSONArray(listOf(range.lower, range.upper)))
                .put("requestedRegimes", JSONArray(regimes))
                .put("activeArray", ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE).toString())
                .put("opticalBlackRegions", ch.get(CameraCharacteristics.SENSOR_OPTICAL_BLACK_REGIONS)?.joinToString())
                .put("archiveNote", "Raw I/O occurs before develop; timings exclude archive I/O; ring hold-offs during archive are diagnostic overhead.")
            val shots = JSONArray(); manifest.put("shots", shots)
            File(root, "manifest.json").writeText(manifest.toString(2))
            for ((regime, requestedIso) in regimes.withIndex()) {
                assertScreenReady()
                val exposure = (product / requestedIso).toLong().coerceIn(caps.exposureMinNs ?: 1L,
                    minOf(caps.exposureMaxNs ?: PREVIEW_EXPOSURE_CAP_NS, PREVIEW_EXPOSURE_CAP_NS))
                val settings = ManualSettings(manualExposure = true, iso = requestedIso,
                    exposureTimeNs = exposure, manualFocus = caps.hasManualFocus,
                    focusDiopters = initial.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                    manualColor = true)
                s.applySettings(settings, caps)
                // Camera2 settings have pipeline latency. Waiting merely for newer
                // images can retain frames made by the previous request/regime.
                waitUntil { assertScreenReady(); s.latestResult()?.request?.get(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY) == settings.effectiveIso(caps) }
                val prior = s.newestFrameTimestampNs()
                waitUntil { assertScreenReady(); s.readyFramesSince(prior + 1) >= frames }
                repeat(repeats) { repeat ->
                    assertScreenReady()
                    if (repeat > 0) {
                        val after = s.newestFrameTimestampNs()
                        waitUntil { assertScreenReady(); s.readyFramesSince(after + 1) >= frames }
                    }
                    val shot = ZslCapture.take(s, frames)
                    try {
                        assertEquals(frames, shot.count)
                        val dir = File(root, "regime${regime}-repeat${repeat}").apply { mkdirs() }
                        val frameMetadata = JSONArray()
                        // Reflection permits this diagnostic APK to run against the preserved
                        // baseline APK (whose getter returns an array) and the candidate (List).
                        val rawResults = shot.burst.javaClass.getMethod("getResults").invoke(shot.burst)
                        val selected = when (rawResults) {
                            is Array<*> -> rawResults.toList()
                            is List<*> -> rawResults
                            else -> error("unknown result container")
                        }
                        // With archival disabled, start production develop immediately.
                        // Do not let diagnostic metadata polling hide the callback race.
                        val earlyOutput = if (!archive) ZslCapture.develop(context, s, shot, 0,
                            develop = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON,
                                shadowLift = DevelopParams.SHADOW_LIFT_ON), outputTag = "iq_$phase") else null
                        val resolved = if (requireExact) shot.burst.results else emptyList()
                        if (requireExact && !archive) {
                            assertEquals(shot.count, resolved.size)
                            for (i in resolved.indices) assertEquals("Exact result after production develop",
                                shot.burst.frames.timestampsNs[i], resolved[i]?.get(CaptureResult.SENSOR_TIMESTAMP))
                        }
                        val exact = s.javaClass.getDeclaredMethod("exactResult", java.lang.Long.TYPE).apply { isAccessible = true }
                        var mismatches = 0
                        var pending = 0
                        for (i in 0 until shot.count) {
                            val stamp = shot.burst.frames.timestampsNs[i]
                            val supplied = selected[i] as? TotalCaptureResult
                            if (supplied == null) pending++
                            else if (supplied.get(CaptureResult.SENSOR_TIMESTAMP) != stamp) mismatches++
                            var correct = resolved.getOrNull(i)?.takeIf { it.get(CaptureResult.SENSOR_TIMESTAMP) == stamp }
                                ?: supplied?.takeIf { it.get(CaptureResult.SENSOR_TIMESTAMP) == stamp }
                                ?: (exact.invoke(s, stamp) as? TotalCaptureResult)
                            val deadline = SystemClock.elapsedRealtime() + 2000
                            while (correct == null && SystemClock.elapsedRealtime() < deadline) {
                                SystemClock.sleep(5); correct = exact.invoke(s, stamp) as? TotalCaptureResult
                            }
                            assertNotNull("No exact metadata for sensor timestamp $stamp", correct)
                            val entry = metadata(correct!!).put("imageTimestamp", stamp)
                                .put("snapshotResultTimestamp", supplied?.get(CaptureResult.SENSOR_TIMESTAMP))
                            if (archive && repeat == 0) {
                                val file = File(dir, "frame-${i.toString().padStart(2, '0')}.raw16")
                                FileOutputStream(file).channel.use { channel ->
                                    val data = requireNotNull(shot.burst.frames.buffer(i)).duplicate()
                                    data.clear(); data.limit(config.width * config.height * 2)
                                    while (data.hasRemaining()) channel.write(data)
                                }
                                entry.put("raw", file.name).put("sha256", sha256(file))
                            }
                            frameMetadata.put(entry)
                        }
                        File(dir, "frames.json").writeText(frameMetadata.toString(2))
                        if (requireExact) assertEquals("Wrong-frame metadata must never be substituted", 0, mismatches)
                        val output = earlyOutput ?: ZslCapture.develop(context, s, shot, 0,
                            develop = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON,
                                shadowLift = DevelopParams.SHADOW_LIFT_ON), outputTag = "iq_$phase")
                        val row = JSONObject().put("directory", dir.name).put("requestedIso", requestedIso)
                            .put("requestedExposureNs", exposure).put("snapshotMismatches", mismatches)
                            .put("snapshotPending", pending)
                            .put("actualIso", (frameMetadata.getJSONObject(0)).opt("iso"))
                            .put("mergedFrames", output.framesCaptured).put("mergeMillis", output.mergeMillis)
                            .put("developMillis", output.developMillis).put("jpeg", output.savedJpeg)
                            .put("dng", output.savedDng).put("message", output.message)
                            .put("thermal", power.currentThermalStatus).put("stream", s.stats().toString())
                        shots.put(row); File(root, "manifest.json").writeText(manifest.toString(2))
                        Log.i("IqForensics", row.toString())
                        assertNotNull("JPEG failed: $output", output.savedJpeg)
                        assertScreenReady()
                    } finally { shot.close() }
                }
            }
            Log.i("IqForensics", "ARCHIVE ${root.absolutePath}")
        } finally {
            stream?.close(); reader.close(); readerThread.quitSafely()
            running.set(false); sampler.join(2000)
            foreground?.close()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Camera readiness timeout", condition())
    }

    private fun metadata(r: TotalCaptureResult): JSONObject {
        val g = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val shading = r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        return JSONObject().put("resultTimestamp", r.get(CaptureResult.SENSOR_TIMESTAMP))
            .put("frameNumber", r.frameNumber).put("iso", r.get(CaptureResult.SENSOR_SENSITIVITY))
            .put("exposureNs", r.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            .put("frameDurationNs", r.get(CaptureResult.SENSOR_FRAME_DURATION))
            .put("focusDiopters", r.get(CaptureResult.LENS_FOCUS_DISTANCE))
            .put("aeMode", r.get(CaptureResult.CONTROL_AE_MODE)).put("aeState", r.get(CaptureResult.CONTROL_AE_STATE))
            .put("awbMode", r.get(CaptureResult.CONTROL_AWB_MODE)).put("awbState", r.get(CaptureResult.CONTROL_AWB_STATE))
            .put("afMode", r.get(CaptureResult.CONTROL_AF_MODE)).put("afState", r.get(CaptureResult.CONTROL_AF_STATE))
            .put("lensState", r.get(CaptureResult.LENS_STATE))
            .put("wbRggb", g?.let { JSONArray(listOf(it.red, it.greenEven, it.greenOdd, it.blue)) })
            .put("neutral", r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { a -> JSONArray(a.map { it.toDouble() }) })
            .put("black", r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { JSONArray(it.toList()) })
            .put("white", r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL))
            .put("greenSplit", r.get(CaptureResult.SENSOR_GREEN_SPLIT))
            .put("noiseProfile", r.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { a -> JSONArray(a.map { listOf(it.first, it.second) }) })
            .put("pixelMode", r.get(CaptureResult.SENSOR_PIXEL_MODE))
            .put("postRawBoost", r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST))
            .put("shadingMode", r.get(CaptureResult.SHADING_MODE))
            .put("rawCrop", r.get(CaptureResult.SCALER_RAW_CROP_REGION)?.toShortString())
            .put("shadingColumns", shading?.columnCount).put("shadingRows", shading?.rowCount)
            .put("shadingRggb", shading?.let { m ->
                val a = FloatArray(m.gainFactorCount); m.copyGainFactors(a, 0); JSONArray(a.toList())
            })
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(1 shl 20)
            while (true) { val n = input.read(block); if (n < 0) break; digest.update(block, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
