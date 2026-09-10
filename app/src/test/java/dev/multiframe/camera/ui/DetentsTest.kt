package dev.multiframe.camera.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where the dials click.
 *
 * A slider on glass has no mechanical stops, so these are the positions the
 * haptic tick is fired at. Testable here rather than on a phone for the reason
 * the rest of `ControlBar` is: it is arithmetic, and what it decides is which
 * of a photographer's familiar numbers the finger is currently on.
 */
class DetentsTest {

    @Test
    fun theIsoSeriesIsTheFullStopOne() {
        // Every entry is double the one before, which is what a full stop means
        // and what an ISO dial has always clicked at.
        val stops = Detents.ISO_STOPS
        for (i in 1 until stops.size) {
            assertThat(stops[i]).isWithin(0.01f).of(stops[i - 1] * 2f)
        }
    }

    @Test
    fun onlyTheStopsTheHardwareCanReachAreOffered() {
        val stops = Detents.isoStops(min = 100, max = 3200)
        assertThat(stops).containsExactly(100f, 200f, 400f, 800f, 1600f, 3200f).inOrder()
    }

    @Test
    fun anExoticRangeSimplyYieldsFewerStops() {
        assertThat(Detents.isoStops(min = 55, max = 90)).isEmpty()
        assertThat(Detents.isoStops(min = 6400, max = 25600))
            .containsExactly(6400f, 12800f, 25600f).inOrder()
    }

    @Test
    fun aValueSittingOnAStopIsAtThatStop() {
        val stops = Detents.isoStops(50, 12800)
        assertThat(Detents.nearest(400f, stops)).isEqualTo(400f)
        assertThat(Detents.nearest(1600f, stops)).isEqualTo(1600f)
    }

    @Test
    fun aValueBetweenStopsIsAtNone() {
        val stops = Detents.isoStops(50, 12800)
        assertThat(Detents.nearest(300f, stops)).isNull()
        assertThat(Detents.nearest(1000f, stops)).isNull()
    }

    /**
     * The window is proportional, which is the whole reason it is not a
     * constant.
     *
     * The ISO series is geometric: the gap from 100 to 200 is a hundred units
     * and the gap from 6400 to 12800 is six thousand. A fixed tolerance would
     * make the top of the dial frictionless and the bottom one long detent.
     */
    @Test
    fun theWindowScalesWithTheStopItSurrounds() {
        val stops = Detents.isoStops(50, 12800)

        // Six percent of 100 is 6, so 104 is inside and 110 is not.
        assertThat(Detents.nearest(104f, stops)).isEqualTo(100f)
        assertThat(Detents.nearest(110f, stops)).isNull()

        // Six percent of 6400 is 384, so 6600 is comfortably inside -- a gap
        // that a fixed six-unit window would have called "between stops".
        assertThat(Detents.nearest(6600f, stops)).isEqualTo(6400f)
        assertThat(Detents.nearest(8000f, stops)).isNull()
    }

    /**
     * Exposure compensation has exactly one stop, and it is the one everybody
     * navigates back to.
     */
    @Test
    fun exposureCompensationClicksOnlyAtZero() {
        val stops = Detents.evStops()
        assertThat(stops).containsExactly(0f)
        assertThat(Detents.nearest(0f, stops)).isEqualTo(0f)
        assertThat(Detents.nearest(3f, stops)).isNull()
        assertThat(Detents.nearest(-2f, stops)).isNull()
    }

    /**
     * Zero needs an absolute floor, because six percent of zero is zero and a
     * detent of no width can never be landed on.
     */
    @Test
    fun theStopAtZeroHasWidthDespiteBeingZero() {
        val stops = Detents.evStops()
        // The EV slider steps in whole indices, so half a unit either side is
        // enough to catch it and narrow enough not to catch index 1.
        assertThat(Detents.nearest(0.4f, stops)).isEqualTo(0f)
        assertThat(Detents.nearest(-0.4f, stops)).isEqualTo(0f)
        assertThat(Detents.nearest(1f, stops)).isNull()
    }

    @Test
    fun noStopsMeansNoDetentRatherThanACrash() {
        assertThat(Detents.nearest(400f, emptyList())).isNull()
    }
}
