package dev.multiframe.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BayerResolveQuantisationTest {
    @Test fun constantRadianceHasNoSystematicNegativePedestalAfterFusion() {
        val w = 64; val h = 64
        val profile = SensorProfile(intArrayOf(256, 256, 256, 256), 4095, intArrayOf(1, 2, 0, 1))
        val field = AlignmentField(1, 1, intArrayOf(0), intArrayOf(0))
        val a = BayerAccumulator(w, h, profile)
        val frames = List(4) { k -> BayerFrame(w, h, ShortArray(w * h) { i ->
            // Equal populations at true 260.25 and 260.75 DN, all differences
            // within the existing noise envelope. Average radiance is 260.5.
            (260 + if (i % 4 < 2) { if (k == 0) 1 else 0 } else { if (k == 0) 0 else 1 }).toShort()
        }) }
        a.setReference(frames[0])
        for (f in frames.drop(1)) a.add(f, field)
        val (result, _) = a.finish()
        var error = 0.0
        for (i in result.data.indices) {
            val truth = frames.sumOf { it.data[i].toInt() and 65535 } / 4.0
            val delta = (result.data[i].toInt() and 65535) - truth
            assertTrue("nearest-code error is at most half a DN", abs(delta) <= 0.5)
            error += delta
        }
        assertEquals("mean radiance preserved across quantisation phases", 0.0, error / (w * h), 1e-6)
    }

    @Test fun aSingleRawRemainsIdentical() {
        val w = 64; val h = 64; val profile = SensorProfile.DEFAULT
        val source = BayerFrame(w, h, ShortArray(w * h) { (it % (profile.whiteLevel + 1)).toShort() })
        val a = BayerAccumulator(w, h, profile); a.setReference(source)
        assertTrue(source.data.contentEquals(a.finish().first.data))
    }
}
