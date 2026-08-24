package dev.multiframe.camera.pipeline

/**
 * Gradient-corrected linear demosaicing (Malvar, He and Cutler, ICASSP 2004).
 *
 * ### Why not the obvious thing
 *
 * The straightforward demosaic averages every sample of a colour in a 3x3
 * neighbourhood. It has one fatal property: at a pixel whose own colour is
 * being asked for, it averages that pixel *with its neighbours*. A green site
 * sits at the centre of five greens in a 3x3 window, so the green the sensor
 * actually measured there is blurred together with four others before it ever
 * reaches the image. Half the pixels on the sensor are green; throwing away
 * their sharpness throws away most of the luminance detail in the picture.
 *
 * Here a known sample is used exactly as measured, and only the two missing
 * colours are interpolated.
 *
 * ### The gradient correction
 *
 * Interpolating each colour plane independently is what produces the coloured
 * fringes along edges that give demosaiced images their tell-tale look: the
 * planes are sampled at different positions, so they disagree about where an
 * edge is. The correction exploits the fact that the *luminance* gradient at a
 * point is shared by all three channels. The second-derivative term in each
 * kernel measures how the known channel is bending at that point and applies
 * the same correction to the interpolated one, so the planes agree about edge
 * position.
 *
 * This costs a 5x5 filter instead of a 3x3 gather and is still purely linear,
 * with no thresholds to tune and no direction-choosing heuristic to get wrong.
 *
 * ### Order of operations
 *
 * Runs on white-balanced values. The gradient correction carries a correction
 * measured on one channel across to another, which is only sound if the
 * channels are on a common scale; on raw sensor values, where green can be
 * twice red, it would inject the white balance error into every edge. This is
 * the same order dcraw uses, scaling colours before interpolating.
 *
 * Deliberately not adaptive. Direction-sensing demosaics resolve slightly more
 * detail on clean data and produce zippering and maze artefacts when they guess
 * wrong, which is exactly what a merged burst should not be spending its
 * cleanliness on.
 */
object Demosaic {

    /** Colour index of the CFA site at (x, y): 0 red, 1 green, 2 blue. */
    fun colourAt(sensor: SensorProfile, x: Int, y: Int): Int =
        sensor.cfaPattern[(y and 1) * 2 + (x and 1)]

    /**
     * White-balanced linear sample, edge-clamped.
     *
     * Gains are applied per site colour, so this returns a value already on a
     * common scale between channels.
     */
    fun sample(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        x: Int,
        y: Int,
    ): Float {
        val cx = x.coerceIn(0, frame.width - 1)
        val cy = y.coerceIn(0, frame.height - 1)
        val raw = frame.data[cy * frame.width + cx].toInt() and 0xFFFF
        val lin = (raw - sensor.blackAt(cx, cy)).toFloat() / sensor.range.toFloat()
        return lin.coerceAtLeast(0f) * color.gainFor(colourAt(sensor, cx, cy), cy)
    }

    /**
     * Full-colour value at (x, y), written into [out] as red, green, blue.
     *
     * Within two pixels of the border the 5x5 support does not exist, so those
     * pixels fall back to the simple gather. Clamping instead would fold the
     * CFA phase back on itself and give the border the wrong colours.
     */
    fun pixel(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        x: Int,
        y: Int,
        out: FloatArray,
    ) {
        if (x < 2 || y < 2 || x >= frame.width - 2 || y >= frame.height - 2) {
            simpleGather(frame, sensor, color, x, y, out)
            return
        }

        fun s(dx: Int, dy: Int) = sample(frame, sensor, color, x + dx, y + dy)

        val c = s(0, 0)
        val n = s(0, -1); val e = s(1, 0); val w = s(-1, 0); val so = s(0, 1)
        val nn = s(0, -2); val ee = s(2, 0); val ww = s(-2, 0); val ss = s(0, 2)
        val nw = s(-1, -1); val ne = s(1, -1); val sw = s(-1, 1); val se = s(1, 1)

        val axis = n + so + e + w
        val axis2 = nn + ss + ee + ww
        val diag = nw + ne + sw + se

        when (colourAt(sensor, x, y)) {
            1 -> {
                // Green site: green is measured, so it is kept exactly. Red and
                // blue each lie along one axis and are recovered from it, with
                // the other axis supplying the correction.
                val redIsHorizontal = colourAt(sensor, x + 1, y) == 0
                val alongH = 5f * c + 4f * (w + e) - (ww + ee) - diag + 0.5f * (nn + ss)
                val alongV = 5f * c + 4f * (n + so) - (nn + ss) - diag + 0.5f * (ww + ee)
                out[0] = (if (redIsHorizontal) alongH else alongV) / 8f
                out[1] = c
                out[2] = (if (redIsHorizontal) alongV else alongH) / 8f
            }
            0 -> {
                out[0] = c
                out[1] = (4f * c + 2f * axis - axis2) / 8f
                out[2] = (6f * c + 2f * diag - 1.5f * axis2) / 8f
            }
            else -> {
                out[0] = (6f * c + 2f * diag - 1.5f * axis2) / 8f
                out[1] = (4f * c + 2f * axis - axis2) / 8f
                out[2] = c
            }
        }

        // The correction is an extrapolation and can overshoot past black on a
        // hard edge. Negative light is not a thing.
        if (out[0] < 0f) out[0] = 0f
        if (out[1] < 0f) out[1] = 0f
        if (out[2] < 0f) out[2] = 0f
    }

    /**
     * Simple 3x3 gather: every sample of a colour in the neighbourhood averaged.
     *
     * Used for the two-pixel border, where the 5x5 support does not exist. It
     * is also the algorithm the whole pipeline used before, kept reachable so
     * the tests can measure the new one against it rather than asserting an
     * improvement in the abstract.
     */
    internal fun simpleGather(
        frame: BayerFrame,
        sensor: SensorProfile,
        color: ColorProfile,
        x: Int,
        y: Int,
        out: FloatArray,
    ) {
        var r = 0f; var g = 0f; var b = 0f
        var rn = 0; var gn = 0; var bn = 0
        for (dy in -1..1) {
            val sy = y + dy
            if (sy < 0 || sy >= frame.height) continue
            for (dx in -1..1) {
                val sx = x + dx
                if (sx < 0 || sx >= frame.width) continue
                val v = sample(frame, sensor, color, sx, sy)
                when (colourAt(sensor, sx, sy)) {
                    0 -> { r += v; rn++ }
                    1 -> { g += v; gn++ }
                    else -> { b += v; bn++ }
                }
            }
        }
        out[0] = if (rn > 0) r / rn else 0f
        out[1] = if (gn > 0) g / gn else 0f
        out[2] = if (bn > 0) b / bn else 0f
    }
}
