package dev.multiframe.camera.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class CaptureFeedbackTimingTest {
    @Test fun vibratorRunsOnlyAfterExposureCompletes() = runBlocking {
        val events = mutableListOf<String>()
        val exposure = CompletableDeferred<String>()
        val capture = async {
            CaptureFeedback.afterSuspending(
                capture = {
                    events += "exposure started"
                    val result = exposure.await()
                    events += "exposure complete"
                    result
                },
                successful = { it == "captured" },
                vibrate = { events += "vibrate" },
            )
        }
        yield()
        assertThat(events).containsExactly("exposure started")
        exposure.complete("captured")
        capture.await()
        assertThat(events).containsExactly("exposure started", "exposure complete", "vibrate").inOrder()

        events.clear()
        CaptureFeedback.afterSync(
            capture = { events += "ZSL frames locked"; 16 },
            successful = { it == 16 },
            vibrate = { events += "vibrate" },
        )
        assertThat(events).containsExactly("ZSL frames locked", "vibrate").inOrder()

        events.clear()
        CaptureFeedback.afterSync(capture = { 0 }, successful = { it > 0 },
            vibrate = { events += "vibrate" })
        assertThat(events).isEmpty()
    }
}
