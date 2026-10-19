package dev.multiframe.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ShadingCfaOrderTest {
    private val patterns = listOf(intArrayOf(0, 1, 1, 2), intArrayOf(1, 0, 2, 1),
        intArrayOf(1, 2, 0, 1), intArrayOf(2, 1, 1, 0))

    @Test fun cameraChannelsBecomeSensorSitesForEveryBayerPattern() {
        val rggb = floatArrayOf(2f, 1f, 1.5f, 4f)
        val expected = listOf(floatArrayOf(2f, 1f, 1.5f, 4f), floatArrayOf(1f, 2f, 4f, 1.5f),
            floatArrayOf(1f, 4f, 2f, 1.5f), floatArrayOf(4f, 1f, 1.5f, 2f))
        for ((i, cfa) in patterns.withIndex()) {
            val map = ShadingMap.fromRggb(1, 1, rggb, cfa)
            assertArrayEquals(expected[i], map.gains, 0f)
            assertArrayEquals(floatArrayOf(2f, 1.25f, 4f), map.rgbGrid(cfa), 0f)
            // A neutral flat field with colour-dependent falloff must recover
            // the same linear value at R, G-even, G-odd and B sites.
            val sensor = SensorProfile(intArrayOf(64, 64, 64, 64), 16383, cfa)
            val raw = BayerFrame(16, 16, ShortArray(256) { at ->
                val site = (at / 16 % 2) * 2 + (at % 2)
                (64 + 6000 / expected[i][site]).toInt().toShort()
            })
            for (y in 6..7) for (x in 6..7) assertEquals(
                6000f / sensor.range, Demosaic.sample(raw, sensor, ColorProfile.NEUTRAL, x, y, map), 1e-5f)
        }
    }

    @Test fun everyGridCellIsConvertedWithoutChangingSpatialCoordinates() {
        val source = FloatArray(3 * 2 * 4) { 1f + it * 0.1f }
        val cfa = intArrayOf(1, 2, 0, 1)
        val map = ShadingMap.fromRggb(3, 2, source, cfa)
        for (row in 0..1) for (col in 0..2) for (site in 0..3)
            assertEquals(source[(row * 3 + col) * 4 + intArrayOf(1, 3, 0, 2)[site]],
                map.cell(col, row, site), 0f)
    }
}
