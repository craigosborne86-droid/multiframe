package dev.multiframe.camera.pipeline

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs

class RawNoiseModelTest {
    @Test fun dnVarianceEqualsNormalisedVarianceTimesRangeSquaredForEverySite() {
        val black = floatArrayOf(63.75f, 64.5f, 65f, 63.25f)
        val p = doubleArrayOf(.00001, .00000001, .00002, .00000003,
            .00003, .00000002, .00004, .00000004)
        val m = requireNotNull(RawNoiseModel.fromNormalized(p, black, 4095))
        for (c in 0..3) for (level in listOf(-.01, 0.0, .001, .02, .18, .5, 1.0)) {
            val range = 4095.0 - black[c]
            val raw = (black[c] + level * range).toFloat()
            val expected = (p[2*c] * level.coerceAtLeast(0.0) + p[2*c+1]) * range * range
            assertEquals(expected, m.variance(c, raw).toDouble(), 0.0001)
        }
    }

    @Test fun varianceUsesSquaredExposureGainAndNeverScalesTheBlackPedestal() {
        val black = floatArrayOf(256f, 257f, 258f, 259f)
        val p = doubleArrayOf(.0001, .000001, .0002, .000002, .0003, .000003, .0004, .000004)
        val m = requireNotNull(RawNoiseModel.fromNormalized(p, black, 4095))
        for (site in 0..3) for (gain in listOf(.5f, 1f, 2f, 4f)) {
            val ref = black[site] + 100f; val alt = black[site] + 100f * gain
            assertEquals(m.variance(site, ref) + m.variance(site, alt) / (gain * gain),
                RawNoiseModel.residualVariance(m, m, site, ref, alt, gain), 1e-5f)
        }
    }

    @Test fun malformedOrAbsentModelsAreRejectedRatherThanInvented() {
        val black = FloatArray(4) { 64f }; val p = DoubleArray(8) { .0001 }
        assertNull(RawNoiseModel.fromNormalized(DoubleArray(0), black, 1023))
        assertNull(RawNoiseModel.fromNormalized(DoubleArray(8), black, 1023))
        assertNull(RawNoiseModel.fromNormalized(p.copyOf().apply { this[2] = Double.NaN }, black, 1023))
        assertNull(RawNoiseModel.fromNormalized(p.copyOf().apply { this[3] = -1.0 }, black, 1023))
        assertNull(RawNoiseModel.fromNormalized(p, black, 64))
    }

    @Test fun nativeAndKotlinAgreeAcrossCfaSitesUnequalNoiseGainAndEdgeTaps() {
        assumeTrue("opt-in ARM64 host JNI / physical native library", NativeMerge.isAvailable())
        val w = 130; val h = 98
        val black = intArrayOf(256, 257, 258, 259)
        val cfas = listOf(intArrayOf(0,1,1,2), intArrayOf(1,0,2,1), intArrayOf(1,2,0,1), intArrayOf(2,1,1,0))
        fun model(scale: Double) = requireNotNull(RawNoiseModel.fromNormalized(
            DoubleArray(8) { j -> if (j % 2 == 0) (j / 2 + 1) * .00008 * scale else .0000002 * scale },
            FloatArray(4) { black[it].toFloat() }, 4095))
        fun buffer(frame: BayerFrame) = ByteBuffer.allocateDirect(w*h*2).order(ByteOrder.LITTLE_ENDIAN).apply {
            asShortBuffer().put(frame.data)
        }
        for (cfa in cfas) {
            val profile = SensorProfile(black,4095,cfa); val random = Random(321)
            val frames = List(5) { k -> BayerFrame(w,h,ShortArray(w*h) { i ->
                val c=(i/w and 1)*2+(i%w and 1)
                (black[c]+80+c*90+random.nextInt(65)+(if (k==2 && i%w<40) 900 else 0)).toShort()
            }) }
            val kotlin = BayerAccumulator(w,h,profile)
            requireNotNull(NativeMerge.create(w,h,profile)).use { native ->
                kotlin.setReference(frames[0], model(1.0)); native.setReference(buffer(frames[0]),w*2,model(1.0))
                for (k in 1..4) {
                    val field = AlignmentField(2,1,intArrayOf(if(k==1) -1 else 1,0),intArrayOf(1,-1))
                    val gain = listOf(1f,1f,1.7f,.7f,1f)[k]
                    // Missing last-frame model exercises the empirical fallback after metadata.
                    val noise = if (k==4) null else model(1.0+k*.3)
                    kotlin.add(frames[k],field,gain,noise)
                    native.addFrame(buffer(frames[k]),w*2,field,gain,noise)
                }
                val expected = kotlin.finish().first.data
                val actual = ShortArray(w*h); native.finish().first.asShortBuffer().get(actual)
                for (i in actual.indices) assertTrue("CFA=${cfa.joinToString()} sample=$i",
                    abs((actual[i].toInt() and 65535)-(expected[i].toInt() and 65535)) <= 1)
            }
        }
    }
}
