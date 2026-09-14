package dev.multiframe.camera.ui

import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.SuperResMachine.Phase
import dev.multiframe.camera.ui.BackNavigation.Action
import dev.multiframe.camera.ui.BackNavigation.Screen
import org.junit.Test

/**
 * The back gesture's stack. Back from the gallery closes the gallery; back
 * from a running Super Res stops it; back from an armed one leaves it for
 * AUTO; and only a bare viewfinder in an ordinary mode lets it exit.
 */
class BackNavigationTest {

    @Test
    fun `back from the gallery closes the gallery and nothing else`() {
        assertThat(BackNavigation.resolve(Screen(galleryOpen = true))).isEqualTo(Action.CLOSE_GALLERY)
        // Even with Super Res armed underneath: the gallery is on top.
        assertThat(BackNavigation.resolve(Screen(galleryOpen = true, superRes = Phase.ARMED)))
            .isEqualTo(Action.CLOSE_GALLERY)
    }

    @Test
    fun `back from a running super res stops it, keeping the mode`() {
        assertThat(BackNavigation.resolve(Screen(superRes = Phase.RUNNING))).isEqualTo(Action.STOP_SUPER_RES)
    }

    @Test
    fun `back from an armed super res returns to auto without exiting`() {
        assertThat(BackNavigation.resolve(Screen(superRes = Phase.ARMED))).isEqualTo(Action.LEAVE_SUPER_RES)
    }

    @Test
    fun `back while a stop is landing waits rather than exiting`() {
        assertThat(BackNavigation.resolve(Screen(superRes = Phase.STOPPING))).isEqualTo(Action.WAIT)
        assertThat(BackNavigation.intercepts(Screen(superRes = Phase.STOPPING))).isTrue()
    }

    @Test
    fun `back exits only from a clean idle viewfinder`() {
        assertThat(BackNavigation.resolve(Screen())).isEqualTo(Action.EXIT)
        assertThat(BackNavigation.intercepts(Screen())).isFalse()
        // Anything open, in any combination, intercepts.
        for (about in listOf(false, true)) for (gallery in listOf(false, true)) for (settings in listOf(false, true))
            for (pro in listOf(false, true)) for (phase in Phase.entries) {
                val screen = Screen(about, gallery, settings, pro, phase)
                val bare = !about && !gallery && !settings && !pro && phase == Phase.IDLE
                assertThat(BackNavigation.intercepts(screen)).isEqualTo(!bare)
            }
    }

    @Test
    fun `back closes the manual drawer before it touches super res`() {
        assertThat(BackNavigation.resolve(Screen(proOpen = true))).isEqualTo(Action.CLOSE_PRO)
        assertThat(BackNavigation.resolve(Screen(proOpen = true, superRes = Phase.RUNNING))).isEqualTo(Action.CLOSE_PRO)
        // The settings screen covers the drawer, so it closes first.
        assertThat(BackNavigation.resolve(Screen(settingsOpen = true, proOpen = true))).isEqualTo(Action.CLOSE_SETTINGS)
    }

    @Test
    fun `the stack closes from the top down`() {
        val everything = Screen(aboutOpen = true, galleryOpen = true, settingsOpen = true, superRes = Phase.RUNNING)
        assertThat(BackNavigation.resolve(everything)).isEqualTo(Action.CLOSE_ABOUT)
        assertThat(BackNavigation.resolve(everything.copy(aboutOpen = false))).isEqualTo(Action.CLOSE_GALLERY)
        assertThat(BackNavigation.resolve(everything.copy(aboutOpen = false, galleryOpen = false))).isEqualTo(Action.CLOSE_SETTINGS)
        assertThat(BackNavigation.resolve(everything.copy(aboutOpen = false, galleryOpen = false, settingsOpen = false)))
            .isEqualTo(Action.STOP_SUPER_RES)
    }
}
