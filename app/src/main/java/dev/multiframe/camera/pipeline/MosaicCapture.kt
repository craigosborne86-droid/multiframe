package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "Multiframe"

/** How a sweep ended. */
data class MosaicResult(
    val tiles: Int,
    val rejected: Int,
    val coverage: Float,
    val megapixels: Double,
    val savedName: String?,
    val elapsedMillis: Long,
    val message: String,
)

/**
 * Drives a telephoto sweep.
 *
 * The user pans; this decides which frames are worth keeping, registers them,
 * develops only the ones it kept, and composites as it goes.
 *
 * ### Why only accepted frames are developed
 *
 * A sweep produces frames at thirty a second and needs about fifty in total, so
 * the overwhelming majority are discarded. Developing a full-resolution tile
 * costs around half a second; doing it before knowing whether the frame is
 * wanted would mean a sweep that runs sixty times slower than the user is
 * moving. Registration runs on a small proxy first, and only a frame that earns
 * a place is developed.
 *
 * ### Why the proxy is so small
 *
 * Corner detection scans every pixel, so registering on a half-resolution proxy
 * of a twelve-megapixel frame would cost seconds. Three further halvings bring
 * a 4080-wide frame to about 255 pixels across, which costs tens of
 * milliseconds and loses nothing: a homography is fitted to dozens of
 * correspondences, so its accuracy comes from their number rather than from any
 * one of them being precise.
 */
object MosaicCapture {

    /** Halvings below the half-resolution luma proxy the merge already builds. */
    private const val PROXY_HALVINGS = 3

    /** How often to look for a new frame. Faster than a person can pan. */
    private const val POLL_MILLIS = 90L

    /**
     * Runs a sweep until it is complete, cancelled, or gives up.
     *
     * [shouldContinue] is polled so the user can stop early and keep what they
     * have, which is the difference between a partial mosaic and a lost one.
     */
    suspend fun run(
        context: Context,
        stream: ZslRawStream,
        session: MosaicSession,
        settings: ManualSettings,
        caps: CameraCapabilities,
        onProgress: (MosaicProgress) -> Unit,
        shouldContinue: () -> Boolean,
    ): MosaicResult {
        val started = System.currentTimeMillis()
        val profile = SensorProfile.from(stream.characteristics)

        // Everything pinned for the duration. Auto-exposure drifting between
        // tiles leaves each a different brightness, and feathering turns a hard
        // seam into a soft gradient rather than removing it.
        stream.lockForSweep(settings, caps)

        var lastTimestamp = -1L
        var developed = 0

        try {
            while (shouldContinue() && !session.progress.complete) {
                val burst = stream.snapshot(1)
                if (burst.count == 0) {
                    burst.close()
                    delay(POLL_MILLIS)
                    continue
                }

                // The ring hands out the newest frame each time; without this
                // the same frame would be evaluated repeatedly while the user
                // is between positions.
                val timestamp = burst.frames.timestampsNs.firstOrNull() ?: -1L
                if (timestamp == lastTimestamp) {
                    burst.close()
                    delay(POLL_MILLIS)
                    continue
                }
                lastTimestamp = timestamp

                val outcome = runCatching {
                    consider(burst, session, profile, stream.config, stream.characteristics)
                }.onFailure { Log.w(TAG, "mosaic frame failed", it) }.getOrNull()

                burst.close()
                if (outcome == true) developed++
                onProgress(session.progress)
                delay(POLL_MILLIS)
            }
        } finally {
            stream.unlock(settings, caps)
        }

        val progress = session.progress
        val name = if (progress.placed > 0) {
            session.save(context, "MF_${stamp()}_mosaic_${progress.placed}t.jpg")
        } else null

        val elapsed = System.currentTimeMillis() - started
        return MosaicResult(
            tiles = progress.placed,
            rejected = progress.rejected,
            coverage = progress.coverage,
            megapixels = session.plan.megapixels,
            savedName = name,
            elapsedMillis = elapsed,
            message = when {
                progress.placed == 0 -> "no frames could be registered"
                name == null -> "swept ${progress.placed} tiles but could not save"
                progress.complete -> "mosaic complete: ${progress.placed} tiles"
                else -> "partial mosaic: ${progress.placed} tiles, " +
                    "%.0f%% covered".format(progress.coverage * 100)
            },
        )
    }

    /**
     * Evaluates one frame and, if it earns a place, develops and composites it.
     *
     * Returns true when a tile was actually added.
     */
    private fun consider(
        burst: ZslBurst,
        session: MosaicSession,
        profile: SensorProfile,
        config: RawStreamConfig,
        characteristics: android.hardware.camera2.CameraCharacteristics,
    ): Boolean {
        val buffer = burst.frames.buffer(0) ?: return false
        val stride = burst.frames.rowStride

        val merger = NativeMerge.create(config.width, config.height, profile) ?: return false

        return merger.use { m ->
            val small = proxyFor(m, buffer, stride)
            if (small.width == 0) return@use false
            val scale = m.width.toFloat() / small.width

            when (val decision = session.evaluate(small, scale)) {
                is OfferResult.Rejected -> false
                is OfferResult.Placed -> {
                    // Only now is it worth the half second.
                    m.setReference(buffer, stride)
                    val (merged, _) = m.finish()
                    val color = ColorProfile.calibrated(characteristics, burst.referenceResult)
                    val shading = ShadingMap.from(burst.referenceResult)
                    val tile = m.develop(merged, color, shading = shading)
                    if (tile == null) {
                        false
                    } else {
                        val placed = session.composite(tile, decision.placement)
                        tile.recycle()
                        placed
                    }
                }
            }
        }
    }

    /** Successively halved luma, small enough for corner detection to be quick. */
    private fun proxyFor(m: NativeMerge, buffer: java.nio.ByteBuffer, stride: Int): Plane {
        var plane = m.lumaProxy(buffer, stride)
        repeat(PROXY_HALVINGS) {
            if (plane.width > 64 && plane.height > 64) {
                plane = Aligner.downsampleByTwo(plane)
            }
        }
        return plane
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
}
