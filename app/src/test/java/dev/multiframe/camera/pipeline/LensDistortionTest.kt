package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Undistortion, checked against the properties the physics guarantees.
 *
 * The useful tests here are not "does it produce these numbers" but the
 * invariants a real lens model must satisfy whatever its coefficients: the
 * optical axis does not move, radial distortion is radial, barrel and pincushion
 * pull in opposite directions, and a lens with no distortion is left alone.
 */
class LensDistortionTest {

    private val width = 4080
    private val height = 3064

    /** A plausible wide-angle lens: mild barrel, no tangential component. */
    private fun barrel(k1: Float = -0.05f) = LensDistortion.Model(
        fx = 3200f, fy = 3200f,
        cx = width / 2f, cy = height / 2f,
        skew = 0f,
        k1 = k1, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f,
    )

    private fun sourceOf(model: LensDistortion.Model, x: Float, y: Float): Pair<Float, Float> {
        val out = FloatArray(2)
        LensDistortion.sourceFor(model, x, y, out)
        return out[0] to out[1]
    }

    // --- invariants --------------------------------------------------------

    /**
     * The optical axis is the one point no radial model can move: at r = 0 every
     * radial term vanishes. If this drifts, the principal point is being applied
     * inconsistently between the normalise and the un-normalise.
     */
    @Test
    fun theOpticalAxisDoesNotMove() {
        val model = barrel()
        val (x, y) = sourceOf(model, model.cx, model.cy)
        assertThat(x).isWithin(1e-3f).of(model.cx)
        assertThat(y).isWithin(1e-3f).of(model.cy)
    }

    @Test
    fun aLensWithNoCoefficientsMovesNothing() {
        val model = barrel(k1 = 0f)
        assertThat(model.isIdentity).isTrue()

        for ((px, py) in listOf(0f to 0f, 1000f to 500f, 4079f to 3063f)) {
            val (x, y) = sourceOf(model, px, py)
            assertThat(x).isWithin(1e-3f).of(px)
            assertThat(y).isWithin(1e-3f).of(py)
        }
        assertThat(LensDistortion.peakDisplacement(model, width, height))
            .isWithin(1e-3f).of(0f)
    }

    /**
     * A purely radial model moves points along the line from the centre and
     * nowhere else. Checked as an angle, since the magnitude is allowed to
     * change and the direction is not.
     */
    @Test
    fun purelyRadialDistortionMovesPointsRadially() {
        val model = barrel()
        for ((px, py) in listOf(3000f to 2000f, 500f to 400f, 4000f to 600f)) {
            val (sx, sy) = sourceOf(model, px, py)

            val outAngle = kotlin.math.atan2(py - model.cy, px - model.cx)
            val srcAngle = kotlin.math.atan2(sy - model.cy, sx - model.cx)
            assertThat(srcAngle).isWithin(1e-4f).of(outAngle)
        }
    }

    /**
     * Barrel and pincushion are opposite signs of k1 and must pull opposite
     * ways. This is the test that catches a sign error, which is otherwise
     * invisible: a picture corrected with the sign inverted looks distorted in
     * the other direction and quite plausible until measured.
     */
    @Test
    fun barrelAndPincushionPullOppositeWays() {
        val corner = 4000f to 3000f

        val (bx, by) = sourceOf(barrel(k1 = -0.05f), corner.first, corner.second)
        val (px, py) = sourceOf(barrel(k1 = +0.05f), corner.first, corner.second)

        val centre = barrel()
        val outR = kotlin.math.hypot(corner.first - centre.cx, corner.second - centre.cy)
        val barrelR = kotlin.math.hypot(bx - centre.cx, by - centre.cy)
        val pincushionR = kotlin.math.hypot(px - centre.cx, py - centre.cy)

        // Negative k1 pulls the source radius in, positive pushes it out.
        assertThat(barrelR).isLessThan(outR)
        assertThat(pincushionR).isGreaterThan(outR)
    }

    /** Distortion grows with radius. A term in r^2 cannot do otherwise. */
    @Test
    fun displacementGrowsWithDistanceFromTheAxis() {
        val model = barrel()
        var previous = -1f
        for (radius in listOf(200f, 600f, 1200f, 1800f)) {
            val (sx, sy) = sourceOf(model, model.cx + radius, model.cy)
            val moved = kotlin.math.abs(sx - (model.cx + radius))
            assertThat(moved).isGreaterThan(previous)
            previous = moved
            assertThat(sy).isWithin(1e-3f).of(model.cy)
        }
    }

    /**
     * The tangential terms are the ones that are not radial, so they must move
     * a point off its own radius. Without this, `p1` and `p2` could be silently
     * dropped and every radial test above would still pass.
     */
    @Test
    fun tangentialTermsMovePointsOffTheirRadius() {
        val model = barrel(k1 = 0f).copy(p1 = 0.01f, p2 = 0.01f)
        assertThat(model.isIdentity).isFalse()

        val px = 3000f
        val py = 2000f
        val (sx, sy) = sourceOf(model, px, py)

        val outAngle = kotlin.math.atan2(py - model.cy, px - model.cx)
        val srcAngle = kotlin.math.atan2(sy - model.cy, sx - model.cx)
        assertThat(kotlin.math.abs(srcAngle - outAngle)).isGreaterThan(1e-4f)
    }

    // --- scaling -----------------------------------------------------------

    /**
     * Intrinsics are published against the active array. Applied unscaled to a
     * capture of a different size they put the optical axis somewhere it is
     * not, which turns the correction into a distortion of its own.
     */
    @Test
    fun theModelRescalesToTheImageItIsCorrecting() {
        val full = barrel()
        val half = full.scaledTo(width, height, width / 2, height / 2)

        assertThat(half.fx).isWithin(1e-3f).of(full.fx / 2f)
        assertThat(half.fy).isWithin(1e-3f).of(full.fy / 2f)
        assertThat(half.cx).isWithin(1e-3f).of(full.cx / 2f)
        assertThat(half.cy).isWithin(1e-3f).of(full.cy / 2f)
    }

    /** The coefficients act on normalised coordinates, so scaling leaves them. */
    @Test
    fun rescalingDoesNotTouchTheCoefficients() {
        val full = barrel().copy(k2 = 0.01f, k3 = -0.002f, p1 = 0.001f, p2 = 0.003f)
        val half = full.scaledTo(width, height, width / 2, height / 2)

        assertThat(half.k1).isEqualTo(full.k1)
        assertThat(half.k2).isEqualTo(full.k2)
        assertThat(half.k3).isEqualTo(full.k3)
        assertThat(half.p1).isEqualTo(full.p1)
        assertThat(half.p2).isEqualTo(full.p2)
    }

    /**
     * The same physical point maps to the same *relative* place whatever
     * resolution the image is at. This is the property rescaling exists for,
     * and it is stronger than checking the fields individually.
     */
    @Test
    fun correctionIsResolutionIndependent() {
        val full = barrel()
        val half = full.scaledTo(width, height, width / 2, height / 2)

        val (fx, fy) = sourceOf(full, 3000f, 2000f)
        val (hx, hy) = sourceOf(half, 1500f, 1000f)

        assertThat(hx * 2f).isWithin(0.01f).of(fx)
        assertThat(hy * 2f).isWithin(0.01f).of(fy)
    }

    @Test
    fun aDegenerateActiveArrayLeavesTheModelAlone() {
        val full = barrel()
        assertThat(full.scaledTo(0, 0, width, height)).isEqualTo(full)
    }

    // --- peak displacement -------------------------------------------------

    @Test
    fun peakDisplacementFindsTheCornersOnARadialLens() {
        val peak = LensDistortion.peakDisplacement(barrel(), width, height)
        // A 5% k1 on a lens this long moves the corners by tens of pixels --
        // far past the threshold at which correcting is worth a resample.
        assertThat(peak).isGreaterThan(LensDistortion.NEGLIGIBLE_PX)
        assertThat(peak).isGreaterThan(10f)
    }

    @Test
    fun aGentleLensFallsUnderTheThreshold() {
        val gentle = barrel(k1 = -1e-5f)
        assertThat(LensDistortion.peakDisplacement(gentle, width, height))
            .isLessThan(LensDistortion.NEGLIGIBLE_PX)
    }

    // --- model construction ------------------------------------------------

    private fun profile(
        distortion: FloatArray? = floatArrayOf(-0.05f, 0.01f, 0f, 0f, 0f),
        intrinsics: FloatArray? = floatArrayOf(3200f, 3200f, 2040f, 1532f, 0f),
    ) = LensProfile(
        cameraId = "0", logicalId = null,
        focalLengthMm = 6.9f, equivalent35mm = 24, apertureF = 1.7f,
        facingBack = true,
        sensorWidthMm = 9.8f, sensorHeightMm = 7.3f,
        activeArrayWidth = width, activeArrayHeight = height,
        supportsRaw = true, supportsManualSensor = true, supportsManualFocus = true,
        hasOis = true, isoMin = 24, isoMax = 6023,
        exposureMinNs = 1000L, exposureMaxNs = 16_000_000_000L,
        distortion = distortion, intrinsics = intrinsics,
        dcgThresholdIso = null,
    )

    @Test
    fun aFullyDescribedLensProducesAModel() {
        val model = LensDistortion.modelFor(profile(), width, height)
        assertThat(model).isNotNull()
        assertThat(model!!.k1).isWithin(1e-6f).of(-0.05f)
        assertThat(model.cx).isWithin(1e-3f).of(2040f)
    }

    /**
     * Missing data produces no model rather than a guessed one. Both keys are
     * optional in the Camera2 specification, and coefficients without
     * intrinsics cannot be applied at all -- there is nothing to normalise
     * against. Inventing a principal point would warp the picture.
     */
    @Test
    fun anIncompletelyDescribedLensIsNotCorrected() {
        assertThat(LensDistortion.modelFor(profile(distortion = null), width, height))
            .isNull()
        assertThat(LensDistortion.modelFor(profile(intrinsics = null), width, height))
            .isNull()
        assertThat(
            LensDistortion.modelFor(
                profile(distortion = floatArrayOf(1f, 2f)), width, height,
            )
        ).isNull()
        assertThat(
            LensDistortion.modelFor(
                profile(intrinsics = floatArrayOf(0f, 0f, 0f, 0f, 0f)), width, height,
            )
        ).isNull()
    }

    // --- orientation -------------------------------------------------------

    /**
     * The bug this exists for, caught on the phone rather than here.
     *
     * Optics were being corrected after the frame was rotated, so a 3064x4080
     * image was being matched against a 4080x3064 array. `scaledTo` then
     * divided fx by 1.33 and multiplied fy by it, and the 24mm main lens --
     * which is close to rectilinear -- reported a peak displacement of 75.5px.
     * That was not distortion, it was the correction being computed against a
     * frame the model does not describe.
     */
    @Test
    fun aRotatedFrameIsRefusedRatherThanRescaled() {
        // Portrait image, landscape array: a quarter turn.
        assertThat(LensDistortion.modelFor(profile(), height, width)).isNull()
    }

    @Test
    fun aFrameInTheArraysOwnOrientationIsAccepted() {
        assertThat(LensDistortion.modelFor(profile(), width, height)).isNotNull()
    }

    /**
     * A capture may legitimately be a different size -- a crop, a binned
     * readout -- and only a transposed one is the error. The tolerance has to
     * admit the first without admitting the second.
     */
    @Test
    fun aCroppedFrameIsStillTheSameWayRound() {
        assertThat(
            LensDistortion.matchesArrayOrientation(4080, 3064, 4080, 3064)
        ).isTrue()
        // 16:9 crop of a 4:3 array: a real capture mode, wider but not turned.
        assertThat(
            LensDistortion.matchesArrayOrientation(4080, 3064, 4080, 2296)
        ).isTrue()
        // Half resolution, same shape.
        assertThat(
            LensDistortion.matchesArrayOrientation(4080, 3064, 2040, 1532)
        ).isTrue()
    }

    @Test
    fun aTransposedFrameNeverPassesWhateverItsSize() {
        assertThat(
            LensDistortion.matchesArrayOrientation(4080, 3064, 3064, 4080)
        ).isFalse()
        assertThat(
            LensDistortion.matchesArrayOrientation(4080, 3064, 1532, 2040)
        ).isFalse()
        assertThat(
            LensDistortion.matchesArrayOrientation(4080, 3064, 2296, 4080)
        ).isFalse()
    }

    /** Nothing to compare against is not a reason to refuse. */
    @Test
    fun anUnknownArraySizeDoesNotBlockTheCorrection() {
        assertThat(LensDistortion.matchesArrayOrientation(0, 0, 4080, 3064)).isTrue()
    }

    // --- resampling --------------------------------------------------------

    /**
     * A flat field survives resampling unchanged, whatever the geometry does.
     * Interpolating between identical values must return that value, and the
     * border clamp must not introduce black at the edges.
     */
    @Test
    fun aUniformImageSurvivesTheResample() {
        val w = 64
        val h = 48
        val grey = (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80
        val src = IntArray(w * h) { grey }
        val dst = IntArray(w * h)

        val model = LensDistortion.Model(
            fx = 50f, fy = 50f, cx = w / 2f, cy = h / 2f, skew = 0f,
            k1 = -0.1f, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f,
        )
        LensDistortion.apply(src, dst, w, h, model)

        assertThat(dst.all { it == grey }).isTrue()
    }

    /** An identity model is a copy, to the last bit. */
    @Test
    fun anIdentityModelReproducesTheImageExactly() {
        val w = 32
        val h = 24
        val src = IntArray(w * h) { i ->
            (0xFF shl 24) or ((i * 7 and 0xFF) shl 16) or ((i * 3 and 0xFF) shl 8) or (i and 0xFF)
        }
        val dst = IntArray(w * h)

        val model = LensDistortion.Model(
            fx = 40f, fy = 40f, cx = w / 2f, cy = h / 2f, skew = 0f,
            k1 = 0f, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f,
        )
        LensDistortion.apply(src, dst, w, h, model)

        assertThat(dst).isEqualTo(src)
    }

    @Test
    fun theResampleRefusesBuffersTooSmallToHoldTheImage() {
        val model = barrel()
        try {
            LensDistortion.apply(IntArray(10), IntArray(64 * 48), 64, 48, model)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("source")
        }
        try {
            LensDistortion.apply(IntArray(64 * 48), IntArray(10), 64, 48, model)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("destination")
        }
    }

    /** Opacity is not a channel the geometry may touch. */
    @Test
    fun theAlphaChannelStaysOpaque() {
        val w = 40
        val h = 30
        val src = IntArray(w * h) { (0xFF shl 24) or 0x336699 }
        val dst = IntArray(w * h)
        LensDistortion.apply(
            src, dst, w, h,
            LensDistortion.Model(35f, 35f, w / 2f, h / 2f, 0f, -0.08f, 0f, 0f, 0f, 0f),
        )
        assertThat(dst.all { (it ushr 24) and 0xFF == 0xFF }).isTrue()
    }

    // --- the corner stretch --------------------------------------------------

    /**
     * Undistorting barrel magnifies the corners. A mild lens costs a few
     * percent, which the picture carries; a strong one is scaled back until
     * no corner is stretched past the limit, and the identity is one.
     */
    @Test
    fun `the corner stretch is measured, and a strong correction is scaled back to the limit`() {
        val none = barrel(k1 = 0f)
        assertThat(LensDistortion.peakStretch(none, width, height)).isWithin(1e-3f).of(1f)

        // A mild barrel: the corners are stretched a little, under the limit,
        // and the model is left alone.
        val mild = barrel(k1 = -0.02f)
        val mildStretch = LensDistortion.peakStretch(mild, width, height)
        assertThat(mildStretch).isGreaterThan(1.0f)
        assertThat(mildStretch).isLessThan(LensDistortion.MAX_CORNER_STRETCH)
        assertThat(LensDistortion.limitStretch(mild, width, height)).isSameInstanceAs(mild)

        // A strong one -- the field test's mush -- is over the limit and comes
        // back at it, with the same sign and a smaller coefficient: partly
        // straight, still straighter than the glass.
        val strong = barrel(k1 = -0.12f)
        assertThat(LensDistortion.peakStretch(strong, width, height)).isGreaterThan(LensDistortion.MAX_CORNER_STRETCH)
        val limited = LensDistortion.limitStretch(strong, width, height)
        val limitedStretch = LensDistortion.peakStretch(limited, width, height)
        assertThat(limitedStretch).isAtMost(LensDistortion.MAX_CORNER_STRETCH + 0.01f)
        assertThat(limitedStretch).isGreaterThan(LensDistortion.MAX_CORNER_STRETCH - 0.03f)
        assertThat(limited.k1).isLessThan(0f)
        assertThat(kotlin.math.abs(limited.k1)).isLessThan(kotlin.math.abs(strong.k1))
        // Pincushion compresses the corners and is never a stretch.
        assertThat(LensDistortion.peakStretch(barrel(k1 = 0.05f), width, height)).isWithin(1e-3f).of(1f)
    }

    // --- the border --------------------------------------------------------

    /**
     * A source position past the frame reads the frame as a mirror: nothing
     * repeated, and continuous with the frame at its edge.
     */
    @Test
    fun `the border is a mirror, never the edge pixel repeated`() {
        val max = 99
        // Inside: untouched, the edge rows included.
        for (v in listOf(0f, 0.5f, 1f, 50f, 98.5f, 99f)) {
            assertThat(LensDistortion.reflectIntoFrame(v, max)).isEqualTo(v)
        }
        // A pixel past the edge reads a pixel inside it: -3 reads 3, 104 reads 94.
        assertThat(LensDistortion.reflectIntoFrame(-3f, max)).isEqualTo(3f)
        assertThat(LensDistortion.reflectIntoFrame(-0.5f, max)).isEqualTo(0.5f)
        assertThat(LensDistortion.reflectIntoFrame(104f, max)).isEqualTo(94f)
        assertThat(LensDistortion.reflectIntoFrame(99.25f, max)).isEqualTo(98.75f)
        // No two positions past the edge read the same row: that is the rib.
        val reads = (1..20).map { LensDistortion.reflectIntoFrame(-it.toFloat(), max) }
        assertThat(reads.toSet()).hasSize(reads.size)
        // Past the mirror's reach: clamped, not reflected back out.
        assertThat(LensDistortion.reflectIntoFrame(-500f, max)).isEqualTo(0f)
        assertThat(LensDistortion.reflectIntoFrame(700f, max)).isEqualTo(max.toFloat())
    }

    /**
     * The field test's ribs: a correction that pulls from above the frame
     * along the top edge repeated the edge row -- which alternates in colour
     * column by column, as a demosaic's edge does -- down into every row it
     * overshot. Here the edge row is marked and the rows under it are flat;
     * a pincushion correction that reads from above the frame must never
     * carry the mark into the output.
     */
    @Test
    fun `a correction that reads past the top edge does not repeat the edge row into ribs`() {
        val w = 64
        val h = 48
        val src = IntArray(w * h) { i ->
            val y = i / w
            val x = i % w
            val v = when {
                // The edge rows: alternating columns, the demosaic's worst.
                y < 2 -> if (x % 2 == 0) 250 else 20
                else -> 128
            }
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        // Pincushion: the source of an output pixel lies further from the
        // centre than the pixel, so the top rows read from above the frame.
        val model = LensDistortion.Model(
            fx = 40f, fy = 40f, cx = (w - 1) / 2f, cy = (h - 1) / 2f, skew = 0f,
            k1 = 0.25f, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f,
        )
        val probe = FloatArray(2)
        LensDistortion.sourceFor(model, w / 2f, 0f, probe)
        assertThat(probe[1]).isLessThan(0f)

        val dst = IntArray(w * h)
        LensDistortion.apply(src, dst, w, h, model)
        // Measured over the middle columns: the radial term bends the band
        // the overshoot reaches, deeper at the corners than in the middle,
        // and the rows below are about the middle.
        fun spread(y: Int): Int {
            val row = (w / 4 until 3 * w / 4).map { x -> (dst[y * w + x] shr 8) and 0xFF }
            return row.max() - row.min()
        }
        // The top output row reads two pixels above the frame, which the
        // mirror reads as two pixels inside it: flat, not the edge row again.
        assertThat(spread(0)).isLessThan(8)
        // The marked rows appear where the mirror puts them and where they
        // are, and nowhere else: the source has two of them, so at most
        // three output rows carry the pattern, never a band of it. An edge
        // clamp would have carried it into every row that read above.
        val ribbed = (0 until 8).count { spread(it) > 100 }
        assertThat(ribbed).isAtMost(3)
        // And from the fourth row down, only the picture.
        for (y in 5 until 8) assertThat(spread(y)).isLessThan(8)
    }

    // --- The crop ---

    @Test
    fun `the inset takes exactly the border the correction would have had to invent`() {
        val w = 64
        val h = 48
        // Pincushion: the top and bottom rows read from above and below the
        // frame, the left and right columns from beyond its sides.
        val model = LensDistortion.Model(
            fx = 40f, fy = 40f, cx = (w - 1) / 2f, cy = (h - 1) / 2f, skew = 0f,
            k1 = 0.25f, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f,
        )
        val inset = LensDistortion.validInset(model, w, h)
        assertThat(inset.isZero).isFalse()
        // Every pixel kept reads from inside the frame ...
        val out = FloatArray(2)
        for (y in inset.top until h - inset.bottom) {
            for (x in inset.left until w - inset.right) {
                LensDistortion.sourceFor(model, x.toFloat(), y.toFloat(), out)
                assertThat(out[0]).isAtLeast(0f)
                assertThat(out[1]).isAtLeast(0f)
                assertThat(out[0]).isAtMost((w - 1).toFloat())
                assertThat(out[1]).isAtMost((h - 1).toFloat())
            }
        }
        // ... and the row just outside the crop did not, somewhere along
        // its kept length -- under pincushion at the corners, where the
        // radius is largest: the crop is as small as it can be, to the
        // bilinear neighbour it keeps.
        val outsideAbove = (inset.left until w - inset.right).any { x ->
            LensDistortion.sourceFor(model, x.toFloat(), (inset.top - 2).toFloat(), out)
            out[1] < 0f
        }
        assertThat(outsideAbove).isTrue()
        // Symmetric for a centred radial model.
        assertThat(inset.left).isEqualTo(inset.right)
        assertThat(inset.top).isEqualTo(inset.bottom)
        assertThat(inset.width(w)).isEqualTo(w - inset.left - inset.right)
    }

    @Test
    fun `barrel correction reads inward along the edges and needs no crop there`() {
        // The source of an edge-midpoint pixel lies inside the frame under
        // barrel; only the aberration's reach, if any, would crop.
        val model = barrel(-0.05f)
        val inset = LensDistortion.validInset(model, width, height)
        val out = FloatArray(2)
        LensDistortion.sourceFor(model, width / 2f, 0f, out)
        if (out[1] >= 0f) assertThat(inset.top).isEqualTo(0)
        // A wild model cannot crop the picture away.
        val wild = LensDistortion.Model(
            fx = 10f, fy = 10f, cx = width / 2f, cy = height / 2f, skew = 0f,
            k1 = 5f, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f,
        )
        val capped = LensDistortion.validInset(wild, width, height)
        assertThat(capped.left).isAtMost((width * LensDistortion.MAX_INSET_FRACTION).toInt() + 1)
        assertThat(capped.top).isAtMost((height * LensDistortion.MAX_INSET_FRACTION).toInt() + 1)
    }

    @Test
    fun `the aberration rescale adds its own reach to the crop`() {
        val none = LensDistortion.validInset(null, width, height)
        assertThat(none.isZero).isTrue()
        // Red a tenth of a percent larger: the corner's red is read from a
        // tenth of a percent further out than the frame has.
        val red = LensDistortion.validInset(null, width, height, ChromaticAberration.Scale(1.001f, 1f))
        assertThat(red.left).isAtLeast(1)
        assertThat(red.left).isAtMost(kotlin.math.ceil(0.001f * width / 2f).toInt() + 1)
        // Smaller than the frame reads inward: nothing to crop.
        assertThat(LensDistortion.validInset(null, width, height, ChromaticAberration.Scale(0.999f, 0.999f)).isZero).isTrue()
    }

    @Test
    fun `the reference writes a window of the corrected frame that matches the whole frame's pixels`() {
        val w = 48
        val h = 36
        val src = IntArray(w * h) { i ->
            val v = (i * 37) and 0xFF
            (0xFF shl 24) or (v shl 16) or (((i * 11) and 0xFF) shl 8) or ((i * 5) and 0xFF)
        }
        val model = LensDistortion.Model(
            fx = 30f, fy = 30f, cx = (w - 1) / 2f, cy = (h - 1) / 2f, skew = 0f,
            k1 = 0.2f, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f,
        )
        val whole = IntArray(w * h)
        Optics.applyReference(src, whole, w, h, model, ChromaticAberration.NONE)
        val inset = LensDistortion.validInset(model, w, h)
        val cw = inset.width(w)
        val ch = inset.height(h)
        val window = IntArray(cw * ch)
        Optics.applyReference(src, window, w, h, model, ChromaticAberration.NONE, cw, ch, inset.left, inset.top)
        for (y in 0 until ch) for (x in 0 until cw) {
            assertThat(window[y * cw + x]).isEqualTo(whole[(y + inset.top) * w + (x + inset.left)])
        }
    }
}
