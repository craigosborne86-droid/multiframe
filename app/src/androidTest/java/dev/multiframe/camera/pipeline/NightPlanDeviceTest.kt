package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NIGHT's shake-o-meter on the phone: the gyroscope delivers what
 * [MotionMeter] needs, and a phone lying on the desk -- where the device lab
 * keeps it -- reads as TRIPOD, which is the measurement the thresholds and
 * [MotionMeter.GYRO_NOISE_RAD_S] were not calibrated on. Needs the phone
 * still on the desk for the run.
 */
@RunWith(AndroidJUnit4::class)
class NightPlanDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theGyroscopeFillsTheWindow() = runBlocking {
        MotionSensor(context).use { motion ->
            assumeTrue("no gyroscope", motion.isAvailable)
            motion.start()
            delay(1_000)
            val now = motion.nowNs()
            val trace = motion.gyroTrace(now - MotionMeter.WINDOW_NS)!!
            // At the fastest rate a 500 ms window holds a sample in every 20 ms bin.
            assertThat(trace.size).isAtLeast(MotionMeter.MIN_BINS * 2)
            assertThat(MotionMeter.rmsRate(trace, now)).isFinite()
        }
    }

    @Test fun aPhoneOnTheDeskReadsAsATripod() = runBlocking {
        val manager = context.getSystemService(CameraManager::class.java)
        val lens = LensCatalog.rear(LensCatalog.enumerate(manager)).firstOrNull()
        assumeTrue("no rear lens", lens != null)
        val profile = LensProfiles.probeOne(manager, lens!!)
        assumeTrue("no lens profile", profile != null)
        val ppr = profile!!.pixelsPerRadian(profile.activeArrayWidth)
        assertThat(ppr).isGreaterThan(0f)
        MotionSensor(context).use { motion ->
            assumeTrue("no gyroscope", motion.isAvailable)
            motion.start()
            val classifier = MotionClassifier(ppr)
            val rates = mutableListOf<Float>()
            repeat(20) {
                delay(NightPlanner.POLL_MS)
                val now = motion.nowNs()
                val rate = MotionMeter.rmsRate(motion.gyroTrace(now - MotionMeter.WINDOW_NS)!!, now)
                rates += rate
                classifier.update(rate, now)
            }
            android.util.Log.i("NightPlanDeviceTest", "desk rates rad/s: $rates, tripod below ${classifier.tripodRate}")
            assertThat(classifier.current).isEqualTo(MotionClass.TRIPOD)
        }
    }
}
