package dev.multiframe.camera.bench

/**
 * The geometric models of the two development devices' main lenses, from the
 * probe records in REFERENCE.md §2.4 -- the DNG does not carry the model in
 * the form `LensDistortion.Model` takes -- picked by the DNG's focal length
 * and scaled to the frame being developed. Null for a lens not on record,
 * which develops without the geometry (the tone is the same; edges keep the
 * demosaic's texture the resample would have smoothed).
 */
object BenchLenses {
    private class Record(val focalMm: Float, val arrayW: Int, val arrayH: Int, val intrinsics: FloatArray, val distortion: FloatArray)

    private val records = listOf(
        // Pixel 11 Pro telephoto, September fixture EXIF focal length and REFERENCE §2.4.
        Record(19.52f, 4032, 3024, floatArrayOf(12355f, 12355f, 1982.3f, 1517.1f, 0f), floatArrayOf(.09758f, 13.658f, -187.50f, 0f, 0f)),
        // Pixel 11 Pro 24mm.
        Record(6.9f, 4080, 3064, floatArrayOf(2833.28f, 2833.28f, 2048.94f, 1538.04f, 0f), floatArrayOf(0.09295f, -0.15067f, 0.09918f, 0f, 0f)),
        // Pixel 9 Pro XL 24mm.
        Record(6.9f, 4080, 3072, floatArrayOf(2852.81f, 2852.81f, 2044.85f, 1537.71f, 0f), floatArrayOf(0.09004f, -0.15054f, 0.10187f, 0f, 0f)),
    )

    fun modelFor(dng: DngFile, width: Int, height: Int): dev.multiframe.camera.pipeline.LensDistortion.Model? {
        // [dng] is the whole frame, so its size is the array's.
        val r = records.firstOrNull {
            kotlin.math.abs(it.focalMm - dng.focalMm) < 0.2f && it.arrayW == dng.frame.width && it.arrayH == dng.frame.height
        } ?: return null
        val model = dev.multiframe.camera.pipeline.LensDistortion.Model(
            fx = r.intrinsics[0], fy = r.intrinsics[1], cx = r.intrinsics[2], cy = r.intrinsics[3], skew = r.intrinsics[4],
            k1 = r.distortion[0], k2 = r.distortion[1], k3 = r.distortion[2], p1 = r.distortion[3], p2 = r.distortion[4],
        ).scaledTo(r.arrayW, r.arrayH, width, height)
        return dev.multiframe.camera.pipeline.LensDistortion.limitStretch(model, width, height)
    }
}
