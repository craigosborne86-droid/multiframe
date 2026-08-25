package dev.multiframe.camera.pipeline

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Handing memory back.
 *
 * The ring is up to 765 MB, more than most apps use in total. Holding it while
 * the system is short does not produce a warning, it produces a dead process,
 * and the user loses the viewfinder rather than losing zero shutter lag.
 */
class MemoryPressureTest {

    @Test
    fun `ordinary running pressure is ignored`() {
        // RUNNING_MODERATE fires routinely on a busy device. Responding to it
        // would mean surrendering the ring during normal use, which is the
        // whole feature.
        assertThat(MemoryPressure.responseTo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
            .isEqualTo(PressureResponse.NONE)
        assertThat(MemoryPressure.responseTo(0)).isEqualTo(PressureResponse.NONE)
    }

    @Test
    fun `real pressure gives up the ring but keeps the camera`() {
        // Degrading to sequential capture still takes photographs. Being killed
        // does not.
        assertThat(MemoryPressure.responseTo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
            .isEqualTo(PressureResponse.RELEASE_RING)
    }

    @Test
    fun `critical pressure gives up everything`() {
        assertThat(MemoryPressure.responseTo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
            .isEqualTo(PressureResponse.RELEASE_ALL)
    }

    @Test
    fun `going into the background releases the ring`() {
        // A backgrounded camera holding 765 MB is the first thing the system
        // will kill, and it needs none of it.
        assertThat(MemoryPressure.responseTo(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
            .isNotEqualTo(PressureResponse.NONE)
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )) {
            assertThat(MemoryPressure.responseTo(level)).isEqualTo(PressureResponse.RELEASE_ALL)
        }
    }

    @Test
    fun `the response never weakens as pressure rises`() {
        // A higher level asking for less would be a reading error that only
        // shows up when the device is already in trouble.
        val order = listOf(
            PressureResponse.NONE,
            PressureResponse.RELEASE_RING,
            PressureResponse.RELEASE_ALL,
        )
        var worst = 0
        for (level in 0..100) {
            val here = order.indexOf(MemoryPressure.responseTo(level))
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                assertThat(here).isAtLeast(1)
            }
            worst = maxOf(worst, here)
        }
        assertThat(worst).isEqualTo(2)
    }

    @Test
    fun `a ring is only opened with room to spare`() {
        // Checked before allocating, not only after failing: an allocation that
        // succeeds and then gets the process killed is worse than one never
        // attempted.
        val ring = 765L * 1024 * 1024

        assertThat(MemoryPressure.canAffordRing(3_000L * 1024 * 1024, ring)).isTrue()
        assertThat(MemoryPressure.canAffordRing(800L * 1024 * 1024, ring)).isFalse()
        // Exactly enough is not enough; the merge and the outputs come after.
        assertThat(MemoryPressure.canAffordRing(ring, ring)).isFalse()
    }
}
