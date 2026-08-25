package dev.multiframe.camera.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import org.junit.Assume.assumeTrue

/**
 * Whether Compose can actually render on this device right now.
 *
 * Not a given. The test rule launches an activity, and an activity behind the
 * keyguard never reaches the resumed state composition requires, so setContent
 * produces nothing at all. The failure mode that matters is silent: a UI test
 * that renders and never queries the hierarchy passes perfectly while proving
 * nothing, which is exactly what happened here before this existed.
 *
 * Called after setContent, it turns "cannot render" into a skipped test with a
 * reason rather than either a false pass or a wall of failures.
 */
fun ComposeContentTestRule.assumeRendered() {
    waitForIdle()
    val rendered = runCatching { onRoot().fetchSemanticsNode() }.isSuccess
    assumeTrue(
        "Compose cannot render here: the activity never resumed, which on a " +
            "locked device is expected. Unlock the screen to run the UI tests.",
        rendered,
    )
}
