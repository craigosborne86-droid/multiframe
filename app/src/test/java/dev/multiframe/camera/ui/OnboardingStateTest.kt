package dev.multiframe.camera.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The first run's flow, as a table. */
class OnboardingStateTest {

    @Test
    fun `the camera granted is the camera, whatever else`() {
        for (denied in listOf(false, true)) for (again in listOf(false, true)) {
            assertThat(OnboardingState.step(granted = true, deniedOnce = denied, canAskAgain = again))
                .isEqualTo(OnboardingState.Step.DONE)
        }
    }

    @Test
    fun `before any refusal the statement is shown, and the system's rationale flag does not matter`() {
        // Before any request the system reports "no rationale" too; that is
        // not a permanent refusal, and deniedOnce is what tells them apart.
        assertThat(OnboardingState.step(granted = false, deniedOnce = false, canAskAgain = false))
            .isEqualTo(OnboardingState.Step.INTRODUCE)
        assertThat(OnboardingState.step(granted = false, deniedOnce = false, canAskAgain = true))
            .isEqualTo(OnboardingState.Step.INTRODUCE)
    }

    @Test
    fun `a refusal asks again while the system will still ask, and points to the settings when it will not`() {
        assertThat(OnboardingState.step(granted = false, deniedOnce = true, canAskAgain = true))
            .isEqualTo(OnboardingState.Step.DENIED)
        assertThat(OnboardingState.step(granted = false, deniedOnce = true, canAskAgain = false))
            .isEqualTo(OnboardingState.Step.DENIED_PERMANENTLY)
    }
}
