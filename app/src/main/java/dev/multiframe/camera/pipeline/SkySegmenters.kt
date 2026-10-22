package dev.multiframe.camera.pipeline

import android.content.Context

/**
 * Where an opt-in sky segmentation model comes from (Labs). None ships yet:
 * the model and its on-device runtime are chosen separately (REFERENCE
 * §3.13), and until one is, the Labs row is absent and astro uses the
 * measured mask -- the deterministic default the charter requires anyway.
 */
object SkySegmenters {
    fun available(context: Context): Boolean = false
    fun obtain(context: Context): SkySegmenter? = null
}
