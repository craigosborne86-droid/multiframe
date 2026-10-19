package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicIntegerArray

class DevelopObserverTest {
    @Test fun observationIsPixelIdenticalAndSeesEveryStage() {
        val sensor = SensorProfile.DEFAULT
        val w = 96; val h = 80
        val source = ShortArray(w * h) { (64 + it * 17 % 959).toShort() }
        val linear = AtomicIntegerArray(DevelopObserver.LinearStage.entries.size)
        val display = mutableListOf<DevelopObserver.DisplayStage>()
        val observer = object : DevelopObserver {
            override fun linear(stage: DevelopObserver.LinearStage, x: Int, y: Int, rgb: FloatArray) {
                linear.incrementAndGet(stage.ordinal)
            }
            override fun display(stage: DevelopObserver.DisplayStage, pixels: IntArray, width: Int, height: Int) {
                display.add(stage)
            }
        }
        val params = DevelopParams(exposureGain = 1.2f, localTone = DevelopParams.LOCAL_TONE_ON,
            shadowLift = DevelopParams.SHADOW_LIFT_ON)
        val color = ColorProfile(floatArrayOf(2f, 1f, 1f, 1.7f), floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f))
        val baseline = RawDeveloper.develop(BayerFrame(w, h, source.copyOf()), sensor, color, params)
        val observed = RawDeveloper.develop(BayerFrame(w, h, source.copyOf()), sensor, color, params, observer = observer)
        assertThat(observed.asList()).containsExactlyElementsIn(baseline.asList()).inOrder()
        for (stage in DevelopObserver.LinearStage.entries) assertThat(linear[stage.ordinal]).isEqualTo(w * h)
        assertThat(display).containsExactlyElementsIn(DevelopObserver.DisplayStage.entries).inOrder()
    }
}
