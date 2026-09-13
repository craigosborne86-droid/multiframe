package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * The tripod detector, fed synthetic motion.
 *
 * Three regimes are fed in -- a sensor's noise floor, a steady hand, and a
 * tap on a table -- and the detector has to name each one. The thresholds are
 * an order of magnitude from either regime, so these are not near-misses: a
 * detector that gets any of them wrong has the wrong sign somewhere.
 */
class TripodDetectorTest {

    /** 200 Hz, which is what a phone IMU delivers. */
    private val stepNs = 5_000_000L

    private fun feed(
        detector: TripodDetector,
        seconds: Float,
        gyroSigma: Float,
        accelSigma: Float,
        seed: Int = 1,
        gravity: Triple<Float, Float, Float> = Triple(0f, 0f, 9.81f),
        startNs: Long = 0L,
    ): Long {
        val rnd = Random(seed)
        fun gauss(sigma: Float): Float {
            // Box-Muller.
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
            val u2 = rnd.nextDouble()
            return (sigma * kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
                kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
        }
        val n = (seconds * 1e9 / stepNs).toInt()
        var t = startNs
        repeat(n) {
            detector.gyroscope(t, gauss(gyroSigma), gauss(gyroSigma), gauss(gyroSigma))
            detector.accelerometer(
                t,
                gravity.first + gauss(accelSigma),
                gravity.second + gauss(accelSigma),
                gravity.third + gauss(accelSigma),
            )
            t += stepNs
        }
        return t
    }

    @Test
    fun nothingIsKnownBeforeAWindowHasFilled() {
        val d = TripodDetector()
        assertThat(d.state).isEqualTo(TripodDetector.State.UNKNOWN)
        // A tenth of a window.
        feed(d, 0.05f, gyroSigma = 0.0005f, accelSigma = 0.002f)
        assertThat(d.state).isEqualTo(TripodDetector.State.UNKNOWN)
    }

    /**
     * The sensor's own noise floor. Gyro at 0.0006 rad/s per axis is about
     * 0.001 RMS across three -- a fifth of the threshold. This is a phone on
     * a tripod, and it has to read as one.
     */
    @Test
    fun aRigidMountReadsAsMounted() {
        val d = TripodDetector()
        feed(d, 1.5f, gyroSigma = 0.0006f, accelSigma = 0.005f)
        assertThat(d.gyroRms).isLessThan(TripodDetector.GYRO_RMS_THRESHOLD)
        assertThat(d.accelRms).isLessThan(TripodDetector.ACCEL_RMS_THRESHOLD)
        assertThat(d.state).isEqualTo(TripodDetector.State.MOUNTED)
    }

    /**
     * A steady hand. 0.02 rad/s per axis is the gentle end of what a hand
     * does and is still seven times the threshold. Nothing about a careful
     * grip should ever read as a tripod.
     */
    @Test
    fun aSteadyHandReadsAsHandheld() {
        val d = TripodDetector()
        feed(d, 1.5f, gyroSigma = 0.02f, accelSigma = 0.1f)
        assertThat(d.state).isEqualTo(TripodDetector.State.HANDHELD)
    }

    /**
     * Both thresholds are required. A phone flat on a table is still against
     * gravity -- the gyro reads nothing -- but a tap on the surface moves it
     * linearly, and the accelerometer alone has to catch that.
     */
    @Test
    fun linearMotionAloneIsEnoughToDenyAMount() {
        val d = TripodDetector()
        feed(d, 1.5f, gyroSigma = 0.0006f, accelSigma = 0.15f)
        assertThat(d.gyroRms).isLessThan(TripodDetector.GYRO_RMS_THRESHOLD)
        assertThat(d.accelRms).isGreaterThan(TripodDetector.ACCEL_RMS_THRESHOLD)
        assertThat(d.state).isEqualTo(TripodDetector.State.HANDHELD)
    }

    @Test
    fun rotationAloneIsEnoughToDenyAMount() {
        val d = TripodDetector()
        feed(d, 1.5f, gyroSigma = 0.03f, accelSigma = 0.005f)
        assertThat(d.gyroRms).isGreaterThan(TripodDetector.GYRO_RMS_THRESHOLD)
        assertThat(d.accelRms).isLessThan(TripodDetector.ACCEL_RMS_THRESHOLD)
        assertThat(d.state).isEqualTo(TripodDetector.State.HANDHELD)
    }

    /**
     * Gravity is not motion. A phone held at any angle has 9.81 m/s^2 split
     * across its axes, and if that were counted as acceleration no phone would
     * ever read as still. The tracker has to remove it.
     */
    @Test
    fun gravityIsRemovedWhateverTheOrientation() {
        for (g in listOf(
            Triple(0f, 0f, 9.81f),
            Triple(9.81f, 0f, 0f),
            Triple(5.66f, 5.66f, 5.66f),
        )) {
            val d = TripodDetector()
            feed(d, 1.5f, gyroSigma = 0.0006f, accelSigma = 0.005f, gravity = g)
            assertThat(d.state).isEqualTo(TripodDetector.State.MOUNTED)
        }
    }

    /**
     * The failure mode the PRD names: the phone is picked up mid-burst. The
     * detector has to notice within a window, not carry the old verdict.
     */
    @Test
    fun pickingThePhoneUpIsNoticedWithinAWindow() {
        val d = TripodDetector()
        val t = feed(d, 1.5f, gyroSigma = 0.0006f, accelSigma = 0.005f)
        assertThat(d.state).isEqualTo(TripodDetector.State.MOUNTED)

        // Half a second of hand. The window is half a second, so by its end
        // the mounted samples have all aged out.
        feed(d, 0.5f, gyroSigma = 0.03f, accelSigma = 0.1f, seed = 2, startNs = t)
        assertThat(d.state).isEqualTo(TripodDetector.State.HANDHELD)
    }

    /** And the reverse: settling onto a mount is noticed too. */
    @Test
    fun settlingOntoAMountIsNoticedWithinAWindow() {
        val d = TripodDetector()
        val t = feed(d, 1.5f, gyroSigma = 0.03f, accelSigma = 0.1f)
        assertThat(d.state).isEqualTo(TripodDetector.State.HANDHELD)

        // Long enough for both the window to turn over and the gravity tracker
        // to have settled on its new estimate.
        feed(d, 1.5f, gyroSigma = 0.0006f, accelSigma = 0.005f, seed = 2, startNs = t)
        assertThat(d.state).isEqualTo(TripodDetector.State.MOUNTED)
    }

    @Test
    fun resetForgetsEverything() {
        val d = TripodDetector()
        feed(d, 1.5f, gyroSigma = 0.0006f, accelSigma = 0.005f)
        assertThat(d.state).isEqualTo(TripodDetector.State.MOUNTED)
        d.reset()
        assertThat(d.state).isEqualTo(TripodDetector.State.UNKNOWN)
    }

    /** The thresholds are the PRD's numbers, and a change to them is a decision. */
    @Test
    fun theThresholdsAreThePrdsNumbers() {
        assertThat(TripodDetector.GYRO_RMS_THRESHOLD).isEqualTo(0.005f)
        assertThat(TripodDetector.ACCEL_RMS_THRESHOLD).isEqualTo(0.02f)
        assertThat(TripodDetector.DEFAULT_WINDOW_NS).isEqualTo(500_000_000L)
    }
}
