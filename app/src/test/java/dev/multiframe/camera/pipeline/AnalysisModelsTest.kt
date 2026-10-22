package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayOutputStream
import kotlin.math.ln

/**
 * The analysis models' shared plumbing and the deterministic halves of each
 * task: what the phone saves to train on, what it feeds a model, and the
 * defaults that do the same jobs without one.
 */
@RunWith(JUnit4::class)
class AnalysisModelsTest {

    @Test fun aTrainingPairReadsBackAsWritten() {
        val task = AnalysisTask.FRAME
        val input = FloatArray(task.input.size) { it * 1e-4f }
        val out = ByteArrayOutputStream()
        TrainingPair.write(out, task, input, floatArrayOf(1.75f))
        val (t, i, l) = TrainingPair.read(out.toByteArray())
        assertThat(t).isEqualTo(task)
        assertThat(i.toList()).isEqualTo(input.toList())
        assertThat(l.toList()).containsExactly(1.75f)
    }

    @Test fun everyTaskNamesItsRoleAndNoneClaimsToMakePixels() {
        for (task in AnalysisTask.entries) {
            assertThat(task.role).isNotEmpty()
            assertThat(task.asset).endsWith(".tflite")
        }
        // The tasks the phone cannot label say so.
        assertThat(AnalysisTask.PERSON.collectable).isFalse()
        assertThat(AnalysisTask.REGIONS.collectable).isFalse()
        assertThat(AnalysisTask.AWB.collectable).isTrue()
    }

    // --- White balance ---------------------------------------------------

    private fun scene(gainR: Float, gainB: Float): FloatArray {
        // Grey and coloured patches under an illuminant: camera RGB = reflectance / gain.
        val rnd = java.util.Random(4)
        return FloatArray(AwbContract.THUMB_W * AwbContract.THUMB_H * 3).also { c ->
            for (i in 0 until c.size / 3) {
                val level = 0.05f + 0.5f * rnd.nextFloat()
                val tint = if (i % 5 == 0) 1.3f else 1f
                c[i * 3] = level * tint / gainR; c[i * 3 + 1] = level; c[i * 3 + 2] = level / tint / gainB
            }
        }
    }

    @Test fun aChangeOfLightTranslatesTheHistogram() {
        val a = AwbContract.histogram(scene(2f, 1.5f), AwbContract.THUMB_W, AwbContract.THUMB_H)
        val b = AwbContract.histogram(scene(2f * kotlin.math.exp(4 * AwbContract.BIN_WIDTH), 1.5f), AwbContract.THUMB_W, AwbContract.THUMB_H)
        fun peakU(h: FloatArray): Int = (0 until AwbContract.BINS * AwbContract.BINS).maxBy { h[it * 2] } % AwbContract.BINS
        // Four bins redder in gain is four bins further along u.
        assertThat(peakU(b) - peakU(a)).isEqualTo(4)
        assertThat((0 until AwbContract.BINS * AwbContract.BINS).sumOf { a[it * 2].toDouble() }).isWithin(1e-4).of(1.0)
    }

    @Test fun theLabelAndTheNeutralAreInverses() {
        val neutral = floatArrayOf(0.5f, 1f, 0.66f)
        val uv = AwbContract.label(neutral)
        assertThat(uv[0]).isWithin(1e-5f).of(ln(2f))
        val back = AwbContract.neutral(uv)
        assertThat(back[0]).isWithin(1e-5f).of(0.5f)
        assertThat(back[2]).isWithin(1e-5f).of(0.66f)
        // A model that runs away is held inside the histogram's span.
        assertThat(AwbContract.neutral(floatArrayOf(40f, -40f))[0]).isGreaterThan(0f)
    }

    // --- Region tone -----------------------------------------------------

    @Test fun aRegionMayDeclineItsLiftButNeverTakeMore() {
        assertThat(RegionTone.scale(0f, 0f, 0f)).isEqualTo(1f)
        assertThat(RegionTone.scale(1f, 0f, 0f)).isWithin(1e-6f).of(0.4f)
        assertThat(RegionTone.scale(0f, 0f, 1f)).isWithin(1e-6f).of(0.75f)
        assertThat(RegionTone.scale(1f, 1f, 1f)).isEqualTo(1f) // skin keeps all it earned
        for (s in 0..10) for (k in 0..10) assertThat(RegionTone.scale(s / 10f, 0f, k / 10f)).isIn(com.google.common.collect.Range.closed(0.4f, 1f))
    }

    @Test fun theColourClassifierFindsSkyFoliageAndSkin() {
        val ow = 3; val oh = 3
        // Top row: bright blue sky. Middle: green leaves. Bottom: skin.
        val px = floatArrayOf(
            0.35f, 0.55f, 0.95f, 0.35f, 0.55f, 0.95f, 0.35f, 0.55f, 0.95f,
            0.10f, 0.30f, 0.06f, 0.10f, 0.30f, 0.06f, 0.10f, 0.30f, 0.06f,
            0.60f, 0.38f, 0.28f, 0.60f, 0.38f, 0.28f, 0.60f, 0.38f, 0.28f,
        )
        val m = RegionTone.classify(px, ow, oh)
        assertThat(m[0]).isGreaterThan(0.5f)       // sky
        assertThat(m[3 * 3 + 2]).isGreaterThan(0.5f) // foliage
        assertThat(m[6 * 3 + 1]).isGreaterThan(0.5f) // skin
        assertThat(m[6 * 3]).isLessThan(0.1f)        // skin is not sky
    }

    @Test fun theScaleMapIsMadeForTheDevelopGridAndAOneMapChangesNothing() {
        val w = 640; val h = 480
        val map = RegionTone.liftScale(FloatArray(16 * 12 * 3), 16, 12, w, h)
        val grid = BilateralGrid.forImage(w, h)
        assertThat(map.cellsX).isEqualTo(grid.cellsX)
        assertThat(map.cellsY).isEqualTo(grid.cellsY)
        assertThat(map.values.all { it == 1f }).isTrue()
        // A map for other cells is not taken.
        grid.liftScale = LiftScaleMap(1, 1, floatArrayOf(0.5f))
        assertThat(grid.liftScale).isNull()
    }

    @Test fun aHalfShareLiftsLessAndLeavesTheBrightEnd() {
        val frame = BayerFrame(128, 96, ShortArray(128 * 96) { i ->
            val x = i % 128; val y = i / 128
            (64 + (if (x > 96 && y < 48) 900 else 10) + (x * 7 + y * 3) % 5).toShort()
        })
        val sensor = SensorProfile(intArrayOf(64, 64, 64, 64), 1023, intArrayOf(0, 1, 1, 2))
        val params = DevelopParams(exposureGain = 1f, shadowLift = 1f, shadowLiftMax = 3f)
        val whole = BilateralGrid.fromFrame(frame, sensor, ColorProfile.NEUTRAL, params)
        val grid = BilateralGrid.forImage(128, 96)
        val halfGrid = BilateralGrid.fromFrame(frame, sensor, ColorProfile.NEUTRAL,
            params.copy(liftScale = LiftScaleMap(grid.cellsX, grid.cellsY, FloatArray(grid.cellsX * grid.cellsY) { 0.5f })))
        val dim = 0.01f
        val liftWhole = ln(whole.gain(20, 80, dim, 0f, 1f, 3f))
        val liftHalf = ln(halfGrid.gain(20, 80, dim, 0f, 1f, 3f))
        assertThat(liftWhole).isGreaterThan(0f)
        // Less lift, never more than half the ceiling the scene earned.
        assertThat(liftHalf).isGreaterThan(0f)
        assertThat(liftHalf).isLessThan(liftWhole)
        assertThat(liftHalf / ln(2f)).isAtMost(whole.ceiling(1f, 3f) * 0.5f + 1e-4f)
        // And the bright end does not move under either.
        val bright = 1.2f
        assertThat(halfGrid.gain(120, 10, bright, 0f, 1f, 3f)).isWithin(1e-3f).of(whole.gain(120, 10, bright, 0f, 1f, 3f))
    }

    // --- Reference frame -------------------------------------------------

    private fun texture(blur: Int): Plane {
        val w = 200; val h = 150
        val rnd = java.util.Random(9)
        val base = FloatArray(w * h) { 40f + 160f * rnd.nextFloat() }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (d in -blur..blur) { s += base[y * w + (x + d).coerceIn(0, w - 1)]; n++ }
            out[y * w + x] = s / n
        }
        return Plane(w, h, ByteArray(w * h) { out[it].toInt().toByte() })
    }

    @Test fun theSharpestOfTheNewestWinsOnlyWhenClearlySharper() {
        val sharp = ReferenceChooser.sharpness(texture(0))
        val blurred = ReferenceChooser.sharpness(texture(3))
        assertThat(sharp).isGreaterThan(blurred * 2)
        // The newest blurred, an older one sharp: the older is chosen.
        assertThat(ReferenceChooser.bySharpness(floatArrayOf(blurred, sharp, blurred))).isEqualTo(1)
        // Equal, or barely sharper: the newest stays.
        assertThat(ReferenceChooser.bySharpness(floatArrayOf(sharp, sharp * 1.05f, sharp))).isEqualTo(0)
        assertThat(ReferenceChooser.byBlur(floatArrayOf(2f, 0.4f, 1.9f))).isEqualTo(1)
        assertThat(ReferenceChooser.byBlur(floatArrayOf(1f, 0.8f, 0.9f))).isEqualTo(0)
        assertThat(ReferenceChooser.FrameContract.input(texture(0)).size).isEqualTo(AnalysisTask.FRAME.input.size)
    }

    // --- Person mask -----------------------------------------------------

    @Test fun peopleComeFromTheReferenceOnlyWhereTheyMoved() {
        val n = PersonMask.WIDTH * PersonMask.HEIGHT
        val ref = FloatArray(n * 3) { 0.3f }
        val merged = ref.copyOf().also { for (k in 0 until 3) it[k] = 0.5f } // cell 0 moved
        val person = FloatArray(n) { 1f }
        val w = PersonMask.mergeWeights(person, ref, merged)
        assertThat(w[0]).isWithin(1e-6f).of(1f)
        assertThat(w[1]).isEqualTo(0f) // a person who stood still is merged
        val noPerson = PersonMask.mergeWeights(FloatArray(n), ref, merged)
        assertThat(noPerson[0]).isEqualTo(0f)
    }

    @Test fun theBlendTakesTheReferenceWhereTheWeightIsOneAndLeavesTheMergeElsewhere() {
        val w = 512; val h = 384; val stride = w * 2 + 64
        val merged = java.nio.ByteBuffer.allocateDirect(w * h * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until w * h) merged.putShort(i * 2, 500)
        val ref = java.nio.ByteBuffer.allocateDirect(stride * h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) for (x in 0 until w) ref.putShort(y * stride + x * 2, 900)
        val weights = FloatArray(PersonMask.WIDTH * PersonMask.HEIGHT) { if (it % PersonMask.WIDTH < PersonMask.WIDTH / 2) 1f else 0f }
        PersonMask.blendTowardReference(merged, ref, stride, w, h, weights)
        assertThat(merged.getShort((100 * w + 10) * 2).toInt()).isEqualTo(900)
        assertThat(merged.getShort((100 * w + w - 10) * 2).toInt()).isEqualTo(500)
        val packed = PersonMask.copyPacked(ref, stride, w, h)
        assertThat(packed.capacity()).isEqualTo(w * h * 2)
        assertThat(packed.order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort((h - 1) * w * 2 + (w - 1) * 2).toInt()).isEqualTo(900)
    }

    @Test fun thePortraitBlurAveragesMeasuredValuesAndInventsNone() {
        val w = 64; val h = 48
        val flat = FloatArray(w * h) { 120f }
        PortraitBlur.blurChannel(flat, w, h, 3)
        assertThat(flat.all { kotlin.math.abs(it - 120f) < 1e-3f }).isTrue()
        val edge = FloatArray(w * h) { if (it % w < w / 2) 0f else 200f }
        PortraitBlur.blurChannel(edge, w, h, 3)
        // Spread across the edge, and never outside the values it averaged.
        assertThat(edge[10 * w + w / 2 - 1]).isGreaterThan(0f)
        assertThat(edge.all { it in 0f..200f }).isTrue()
    }

    // --- Subject ---------------------------------------------------------

    @Test fun aTapLabelPeaksWhereTheTapWasAndAFaintHeatmapMovesNothing() {
        val label = SubjectFocus.label(0.25f, 0.75f)
        val at = SubjectFocus.peak(label)!!
        assertThat(at.first).isWithin(1f / SubjectFocus.OUT_W).of(0.25f)
        assertThat(at.second).isWithin(1f / SubjectFocus.OUT_H).of(0.75f)
        assertThat(SubjectFocus.peak(FloatArray(SubjectFocus.OUT_W * SubjectFocus.OUT_H) { 0.2f })).isNull()
    }
}
