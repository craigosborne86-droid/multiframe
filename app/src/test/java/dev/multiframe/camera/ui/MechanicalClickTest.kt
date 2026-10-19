package dev.multiframe.camera.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MechanicalClickTest {
    @Test fun synthesizedReleaseHasTwoSeparatedNoiseImpulses() {
        val samples = MechanicalClick.synthesize(seed = 42L)
        val rate = MechanicalClick.SAMPLE_RATE
        fun energy(startMs: Int, endMs: Int): Long =
            samples.slice((rate * startMs / 1000) until (rate * endMs / 1000))
                .sumOf { it.toLong() * it.toLong() }

        assertThat(energy(0, 14)).isGreaterThan(0L)
        assertThat(energy(14, 52)).isEqualTo(0L)
        assertThat(energy(52, 77)).isGreaterThan(0L)
        assertThat(energy(77, 110)).isEqualTo(0L)
    }
}
