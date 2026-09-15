package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Shutter priority under the camera's own meter: when the cap takes the
 * exposure, how it holds the level, and when it gives it back.
 *
 * The numbers are a 30 fps raw stream (frame 33.3 ms), a cap of 1/60 s, and
 * a sensor of ISO 50 to 6400 -- the main lens of the development device.
 */
class ShutterCapTest {

    private val frame = 33_333_333L
    private val cap = 16_666_667L

    private fun cap() = ShutterCap(cap, isoMin = 50, isoMax = 6400, sensorMinExposureNs = 100_000L, maxExposureNs = frame)

    @Test
    fun `a meter inside the cap is left alone`() {
        val c = cap()
        assertThat(c.onAutoResult(iso = 200, exposureNs = 8_000_000L, level = 0.3f)).isNull()
        // Up to the margin: a twentieth past the cap is the meter's rounding.
        assertThat(c.onAutoResult(iso = 200, exposureNs = (cap * 1.04).toLong(), level = 0.3f)).isNull()
        assertThat(c.isHeld).isFalse()
    }

    @Test
    fun `a meter past the cap is held at the cap with the light kept in the gain`() {
        val c = cap()
        // 1/30 s at ISO 400 is 1/60 s at ISO 800: the same light.
        val held = c.onAutoResult(iso = 400, exposureNs = frame, level = 0.25f)!!
        assertThat(held.exposureNs).isEqualTo(cap)
        assertThat(held.iso).isEqualTo(800)
        assertThat(held.targetLevel).isEqualTo(0.25f)
        assertThat(c.isHeld).isTrue()
        assertThat(c.stretched).isFalse()
        // Held, another meter result changes nothing: the meter is off.
        assertThat(c.onAutoResult(iso = 100, exposureNs = frame, level = 0.9f)).isEqualTo(held)
    }

    @Test
    fun `where the gain runs out the cap gives way, up to the frame`() {
        val c = cap()
        // 1/30 s at ISO 4000: at the cap that wants ISO 8000, past the sensor.
        // The sensor's ceiling and the exposure the light needs: 1/48 s.
        val held = c.onAutoResult(iso = 4000, exposureNs = frame, level = 0.1f)!!
        assertThat(held.iso).isEqualTo(6400)
        assertThat(held.exposureNs).isEqualTo((4000.0 * frame / 6400).toLong())
        assertThat(held.exposureNs).isGreaterThan(cap)
        assertThat(held.exposureNs).isLessThan(frame)
        assertThat(c.stretched).isTrue()
    }

    @Test
    fun `a meter already at the sensor's ceiling is not second-guessed`() {
        val c = cap()
        // ISO 6400 at 1/30 s: shortening the shutter would only darken the
        // frame. The meter's choice stands.
        assertThat(c.onAutoResult(iso = 6400, exposureNs = frame, level = 0.05f)).isNull()
        assertThat(c.isHeld).isFalse()
    }

    @Test
    fun `a held frame at the target is kept, and a darker one raises the gain`() {
        val c = cap()
        val held = c.onAutoResult(iso = 400, exposureNs = frame, level = 0.25f)!!
        // The frame at the held values, at the level: nothing to do.
        assertThat(c.onHeldFrame(held.iso, held.exposureNs, 0.25f)).isEqualTo(ShutterCap.Step.Keep)
        // Inside the deadband: a tenth of a stop.
        assertThat(c.onHeldFrame(held.iso, held.exposureNs, 0.24f)).isEqualTo(ShutterCap.Step.Keep)
        // Half the level: a stop under. The gain doubles, the shutter stays.
        val step = c.onHeldFrame(held.iso, held.exposureNs, 0.125f)
        assertThat(step).isInstanceOf(ShutterCap.Step.Adjust::class.java)
        val next = (step as ShutterCap.Step.Adjust).held
        assertThat(next.exposureNs).isEqualTo(cap)
        assertThat(next.iso).isEqualTo(1600)
        assertThat(next.targetLevel).isEqualTo(0.25f)
        assertThat(c.held).isEqualTo(next)
    }

    @Test
    fun `one frame moves the hold by at most a stop and a half`() {
        val c = cap()
        val held = c.onAutoResult(iso = 400, exposureNs = frame, level = 0.25f)!!
        // Four stops under in one frame: the step is clamped; the next frame
        // takes the rest.
        val step = c.onHeldFrame(held.iso, held.exposureNs, 0.25f / 16f) as ShutterCap.Step.Adjust
        assertThat(step.held.iso.toDouble() / held.iso).isWithin(0.02).of(Math.pow(2.0, 1.5))
    }

    @Test
    fun `a frame not at the held values is the sensor catching up, and is skipped`() {
        val c = cap()
        val held = c.onAutoResult(iso = 400, exposureNs = frame, level = 0.25f)!!
        // The old values, a dark reading: not this hold's frame.
        assertThat(c.onHeldFrame(400, frame, 0.05f)).isEqualTo(ShutterCap.Step.Keep)
        assertThat(c.held).isEqualTo(held)
        // The sensor's rounding of the held values is the hold's frame.
        val step = c.onHeldFrame((held.iso * 1.02).toInt(), (held.exposureNs * 0.99).toLong(), 0.125f)
        assertThat(step).isInstanceOf(ShutterCap.Step.Adjust::class.java)
    }

    @Test
    fun `a sensor that never reports the asked-for values is followed anyway after enough frames`() {
        val c = cap()
        c.onAutoResult(iso = 400, exposureNs = frame, level = 0.25f)!!
        // A gain table with coarse steps: it lands on ISO 1000 for 800.
        repeat(ShutterCap.MAX_UNMATCHED - 1) {
            assertThat(c.onHeldFrame(1000, cap, 0.125f)).isEqualTo(ShutterCap.Step.Keep)
        }
        val step = c.onHeldFrame(1000, cap, 0.125f)
        assertThat(step).isInstanceOf(ShutterCap.Step.Adjust::class.java)
        // From the values the sensor said it used: a stop up from 1000.
        assertThat((step as ShutterCap.Step.Adjust).held.iso).isEqualTo(2000)
    }

    @Test
    fun `a scene bright enough for the meter to keep the cap is handed back`() {
        val c = cap()
        var held = c.onAutoResult(iso = 400, exposureNs = frame, level = 0.25f)!!
        // The scene brightens two stops and stays there: the hold comes down
        // a stop and a half a frame, and when the light would want a gain
        // under the sensor's floor the meter can hold the shutter inside the
        // cap by itself, and has the exposure back.
        val isos = mutableListOf(held.iso)
        var frames = 0
        while (frames < 6) {
            frames++
            when (val step = c.onHeldFrame(held.iso, held.exposureNs, 1f)) {
                is ShutterCap.Step.Adjust -> { held = step.held; isos.add(held.iso); assertThat(held.exposureNs).isEqualTo(cap) }
                ShutterCap.Step.Release -> break
                ShutterCap.Step.Keep -> throw AssertionError("a frame two stops bright was kept")
            }
        }
        assertThat(c.isHeld).isFalse()
        assertThat(frames).isAtMost(4)
        assertThat(isos).isInOrder(Comparator<Int> { a, b -> b - a })
        // And if it asks for more than the cap again, it is taken again.
        assertThat(c.onAutoResult(iso = 100, exposureNs = frame, level = 0.3f)).isNotNull()
    }

    @Test
    fun `a hold at the floor with a slightly bright frame stays, with hysteresis`() {
        val c = cap()
        c.onAutoResult(iso = 100, exposureNs = frame, level = 0.25f)!!
        // ISO 200 at the cap; a frame a stop bright wants ISO 100; the floor
        // is 50 -- not under 0.8 of it, so the hold comes down and stays.
        val step = c.onHeldFrame(200, cap, 0.5f)
        assertThat(step).isInstanceOf(ShutterCap.Step.Adjust::class.java)
        assertThat((step as ShutterCap.Step.Adjust).held.iso).isEqualTo(100)
        // Another stop bright at ISO 100 wants 50: exactly the floor, kept.
        val again = c.onHeldFrame(100, cap, 0.5f)
        assertThat(again).isInstanceOf(ShutterCap.Step.Adjust::class.java)
        assertThat((again as ShutterCap.Step.Adjust).held.iso).isEqualTo(50)
    }

    @Test
    fun `the dark end lengthens the shutter past the cap and shortens it back`() {
        val c = cap()
        c.onAutoResult(iso = 2000, exposureNs = frame, level = 0.1f)!!
        // At the cap that is ISO 4000. Two stops darker wants 16000: the
        // ceiling is 6400 and the shutter gives way -- a stop and a half at
        // a step, so first to 29 ms, then to the frame.
        val darker = c.onHeldFrame(4000, cap, 0.1f / 4f) as ShutterCap.Step.Adjust
        assertThat(darker.held.iso).isEqualTo(6400)
        assertThat(darker.held.exposureNs).isGreaterThan(cap)
        assertThat(darker.held.exposureNs).isLessThan(frame)
        assertThat(c.stretched).isTrue()
        val darkest = c.onHeldFrame(darker.held.iso, darker.held.exposureNs, 0.1f / 4f) as ShutterCap.Step.Adjust
        assertThat(darkest.held.iso).isEqualTo(6400)
        assertThat(darkest.held.exposureNs).isEqualTo(frame)
        // Brighter again by a stop: back to the cap, the gain still at the
        // ceiling -- the shutter is the first thing given back.
        val brighter = c.onHeldFrame(6400, frame, 0.1f * 2f) as ShutterCap.Step.Adjust
        assertThat(brighter.held.exposureNs).isEqualTo(cap)
        assertThat(brighter.held.iso).isEqualTo(6400)
        assertThat(c.stretched).isFalse()
    }

    @Test
    fun `the highlight guard's pull moves the target instead of the compensation`() {
        val c = cap()
        c.onAutoResult(iso = 400, exposureNs = frame, level = 0.4f)!!
        // The guard pulls a stop: the hold's frames should land at half.
        c.retarget(1f)
        assertThat(c.held!!.targetLevel).isWithin(1e-6f).of(0.2f)
        val step = c.onHeldFrame(800, cap, 0.4f) as ShutterCap.Step.Adjust
        assertThat(step.held.iso).isEqualTo(400)
        // And back.
        c.retarget(-1f)
        assertThat(c.held!!.targetLevel).isWithin(1e-6f).of(0.4f)
    }

    @Test
    fun `the cap is never under what the sensor can do`() {
        val c = ShutterCap(1_000_000L, isoMin = 50, isoMax = 6400, sensorMinExposureNs = 4_000_000L, maxExposureNs = frame)
        assertThat(c.effectiveCapNs).isEqualTo(4_000_000L)
        val held = c.onAutoResult(iso = 100, exposureNs = frame, level = 0.3f)!!
        assertThat(held.exposureNs).isEqualTo(4_000_000L)
    }

    @Test
    fun `the mean level of a histogram is its weighted bin centre`() {
        // Everything in the top bin of four: the centre of 0.75..1.
        assertThat(ShutterCap.meanLevel(intArrayOf(0, 0, 0, 10))).isWithin(1e-6f).of(0.875f)
        // Half in the bottom, half in the top.
        assertThat(ShutterCap.meanLevel(intArrayOf(10, 0, 0, 10))).isWithin(1e-6f).of(0.5f)
        assertThat(ShutterCap.meanLevel(IntArray(64))).isEqualTo(0f)
    }

    @Test
    fun `release lets the meter have the exposure`() {
        val c = cap()
        c.onAutoResult(iso = 400, exposureNs = frame, level = 0.25f)!!
        c.release()
        assertThat(c.isHeld).isFalse()
        assertThat(c.onHeldFrame(800, cap, 0.1f)).isEqualTo(ShutterCap.Step.Keep)
    }
}
