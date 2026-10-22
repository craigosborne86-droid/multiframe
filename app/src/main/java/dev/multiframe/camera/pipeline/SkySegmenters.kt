package dev.multiframe.camera.pipeline

import android.content.Context

/**
 * The opt-in sky segmentation model (Labs) and the data that will train it.
 *
 * ### Provenance
 *
 * The model is Multiframe's own, trained on pairs this app produces: the
 * reference frame of a tripod astro capture and the *measured* sky mask
 * ([SkyMask]) the stack itself derived. No third-party dataset, so no licence
 * question; and the model learns to reproduce what the sensor proved, from one
 * frame, which is what lets it help where the measured mask is weak (a
 * featureless foreground, a short span). Pairs are collected only when the
 * user switches collection on in Labs; they stay in the app's own external
 * folder until copied off by hand. `tools/analysis_models/` trains and
 * exports ([AnalysisModels], task [AnalysisTask.SKY]).
 *
 * ### The contract ([SkyModelContract])
 *
 * Input: one 256×192 single-channel float tensor, the reference proxy
 * area-averaged down and stretched so its 99th percentile is 1. Output: the
 * same shape, sky probability 0..1. The export and the inference share the
 * preprocessing below, so the model sees on the phone exactly what it was
 * trained on.
 */
object SkySegmenters {
    fun available(context: Context): Boolean = AnalysisModels.available(context, AnalysisTask.SKY)

    fun obtain(context: Context): SkySegmenter? = AnalysisModels.obtain(context, AnalysisTask.SKY)?.let { model ->
        object : SkySegmenter {
            override val name = model.name
            override fun skyMask(proxy: Plane): FloatArray =
                SkyModelContract.upscale(model.run(SkyModelContract.preprocess(proxy)), proxy.width, proxy.height)
        }
    }

    /** Saves one training pair (preprocessed input, measured mask) where collection is on. */
    fun collect(context: Context, proxy: Plane, measured: FloatArray) = AnalysisModels.collect(
        context, AnalysisTask.SKY,
        SkyModelContract.preprocess(proxy),
        SkyModelContract.downscaleMask(measured, proxy.width, proxy.height),
    )
}

/** What the model eats and returns; shared by inference and the training export. */
object SkyModelContract {
    const val WIDTH = 256
    const val HEIGHT = 192

    /** The proxy area-averaged to WIDTH×HEIGHT and stretched so its 99th percentile is 1. */
    fun preprocess(proxy: Plane): FloatArray = ModelImage.luma(proxy, WIDTH, HEIGHT)

    fun downscaleMask(mask: FloatArray, width: Int, height: Int): FloatArray = ModelImage.area(mask, width, height, WIDTH, HEIGHT)

    /** The model's WIDTH×HEIGHT output, bilinearly back to the proxy's size. */
    fun upscale(small: FloatArray, width: Int, height: Int): FloatArray = ModelImage.upscale(small, WIDTH, HEIGHT, width, height)
}
