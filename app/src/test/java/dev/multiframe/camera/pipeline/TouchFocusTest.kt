package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tap-to-focus coordinate mapping.
 *
 * Worth testing precisely because getting it wrong does not crash: it focuses
 * somewhere else in the frame, which a user reads as unreliable autofocus
 * rather than as a bug.
 */
class TouchFocusTest {

    private val fullArray = SensorRect(0, 0, 4080, 3072)

    /** The common case: sensor mounted landscape, phone held portrait. */
    private val portraitPhone = 90

    @Test
    fun `the centre of the view is the centre of the sensor`() {
        val (x, y) = TouchFocus.toSensorPoint(0.5f, 0.5f, portraitPhone, fullArray)

        assertThat(x).isWithin(2).of(2040)
        assertThat(y).isWithin(2).of(1536)
    }

    @Test
    fun `a tap near the top of a portrait view maps to the sensor's right`() {
        // With a 90 degree sensor orientation, the top of the upright image is
        // the right-hand edge of the sensor's own frame. Getting this backwards
        // focuses on the opposite side of the picture.
        val (x, _) = TouchFocus.toSensorPoint(0.5f, 0.05f, portraitPhone, fullArray)

        assertThat(x).isLessThan(fullArray.width / 4)
    }

    @Test
    fun `every orientation maps the centre to the centre`() {
        // Whatever the rotation, the middle of the view is the middle of the
        // sensor. A mapping that failed this would be wrong for all taps.
        for (orientation in listOf(0, 90, 180, 270)) {
            val (x, y) = TouchFocus.toSensorPoint(0.5f, 0.5f, orientation, fullArray)
            assertThat(x).isWithin(2).of(2040)
            assertThat(y).isWithin(2).of(1536)
        }
    }

    @Test
    fun `orientations are inverses of each other`() {
        // 90 and 270 must send a corner to opposite places, or one of them is
        // silently the wrong way round.
        val at90 = TouchFocus.toSensorPoint(0.2f, 0.2f, 90, fullArray)
        val at270 = TouchFocus.toSensorPoint(0.2f, 0.2f, 270, fullArray)

        assertThat(at90).isNotEqualTo(at270)
        assertThat(at90.first + at270.first).isWithin(4).of(fullArray.width)
        assertThat(at90.second + at270.second).isWithin(4).of(fullArray.height)
    }

    @Test
    fun `an unusual orientation value is handled rather than ignored`() {
        val negative = TouchFocus.toSensorPoint(0.5f, 0.5f, -90, fullArray)
        val wrapped = TouchFocus.toSensorPoint(0.5f, 0.5f, 270, fullArray)

        assertThat(negative).isEqualTo(wrapped)
    }

    // ------------------------------------------------------------------

    @Test
    fun `a metering region sits inside the sensor and around the tap`() {
        val region = TouchFocus.regionAt(0.5f, 0.5f, portraitPhone, fullArray)

        assertThat(region.left).isAtLeast(0)
        assertThat(region.top).isAtLeast(0)
        assertThat(region.right).isAtMost(fullArray.width)
        assertThat(region.bottom).isAtMost(fullArray.height)
        assertThat(region.width).isGreaterThan(0)
    }

    @Test
    fun `a tap in the corner still produces a full-size region`() {
        // Clipping at the edge would meter off a sliver, which behaves
        // erratically. Sliding the box inside keeps it the size it should be.
        val corner = TouchFocus.regionAt(0f, 0f, portraitPhone, fullArray)
        val centre = TouchFocus.regionAt(0.5f, 0.5f, portraitPhone, fullArray)

        assertThat(corner.width).isEqualTo(centre.width)
        assertThat(corner.height).isEqualTo(centre.height)
        assertThat(corner.left).isAtLeast(0)
        assertThat(corner.top).isAtLeast(0)
    }

    @Test
    fun `every corner of the view produces a valid region`() {
        for (x in listOf(0f, 0.5f, 1f)) {
            for (y in listOf(0f, 0.5f, 1f)) {
                for (orientation in listOf(0, 90, 180, 270)) {
                    val r = TouchFocus.regionAt(x, y, orientation, fullArray)
                    assertThat(r.width).isGreaterThan(0)
                    assertThat(r.height).isGreaterThan(0)
                    assertThat(r.left).isAtLeast(fullArray.left)
                    assertThat(r.top).isAtLeast(fullArray.top)
                    assertThat(r.right).isAtMost(fullArray.right)
                    assertThat(r.bottom).isAtMost(fullArray.bottom)
                }
            }
        }
    }

    @Test
    fun `a tap is metered inside the zoomed crop, not the whole sensor`() {
        // At zoom the sensor reads a sub-rectangle, so a tap refers to a point
        // inside that. Metering against the full array would focus on whatever
        // is outside the visible frame.
        val crop = TouchFocus.cropForZoom(fullArray, zoom = 2f, maxZoom = 8f)

        val region = TouchFocus.regionAt(0.5f, 0.5f, portraitPhone, crop)

        assertThat(region.left).isAtLeast(crop.left)
        assertThat(region.right).isAtMost(crop.right)
        assertThat(region.top).isAtLeast(crop.top)
        assertThat(region.bottom).isAtMost(crop.bottom)
    }

    // ------------------------------------------------------------------

    @Test
    fun `zoom crops a centred rectangle`() {
        val crop = TouchFocus.cropForZoom(fullArray, zoom = 2f, maxZoom = 8f)

        assertThat(crop.width).isWithin(2).of(fullArray.width / 2)
        assertThat(crop.height).isWithin(2).of(fullArray.height / 2)
        assertThat(crop.centerX).isWithin(2).of(fullArray.centerX)
        assertThat(crop.centerY).isWithin(2).of(fullArray.centerY)
    }

    @Test
    fun `zoom of one is the whole sensor`() {
        val crop = TouchFocus.cropForZoom(fullArray, zoom = 1f, maxZoom = 8f)

        assertThat(crop).isEqualTo(fullArray)
    }

    @Test
    fun `zoom is clamped to what the device supports`() {
        // Requesting more than the hardware offers would produce a crop the
        // camera rejects, losing the whole repeating request.
        val beyond = TouchFocus.cropForZoom(fullArray, zoom = 50f, maxZoom = 4f)
        val atMax = TouchFocus.cropForZoom(fullArray, zoom = 4f, maxZoom = 4f)

        assertThat(beyond).isEqualTo(atMax)

        val below = TouchFocus.cropForZoom(fullArray, zoom = 0.2f, maxZoom = 4f)
        assertThat(below).isEqualTo(fullArray)
    }

    @Test
    fun `the crop preserves aspect ratio`() {
        // A crop that did not would stretch the picture.
        val crop = TouchFocus.cropForZoom(fullArray, zoom = 3f, maxZoom = 8f)

        val original = fullArray.width.toFloat() / fullArray.height
        val cropped = crop.width.toFloat() / crop.height
        assertThat(cropped).isWithin(0.01f).of(original)
    }
}
