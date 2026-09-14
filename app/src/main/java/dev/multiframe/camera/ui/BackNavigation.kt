package dev.multiframe.camera.ui

import dev.multiframe.camera.pipeline.SuperResMachine

/**
 * What the system back gesture does, given what is on the screen.
 *
 * ### Why this exists
 *
 * The app had no back handling at all. The gallery, the about sheet and the
 * manual panel are overlays on one composable, not destinations, so the
 * system's default -- finish the activity -- was what every back gesture did:
 * back from the gallery quit the app, and back during a running Super Res
 * quit the app with the stream locked and the canvas unsaved.
 *
 * The rule is a stack, innermost first: a sheet over the gallery closes
 * before the gallery, the gallery before the settings, the settings before
 * the manual drawer, and Super Res is stopped or left before the app is. Only on the bare viewfinder, in an
 * ordinary mode, does back reach the system and leave. Pure, so that the
 * order is a test rather than a thing to remember.
 */
object BackNavigation {

    /** What is open, from the outside in. */
    data class Screen(
        val aboutOpen: Boolean = false,
        val galleryOpen: Boolean = false,
        val settingsOpen: Boolean = false,
        /** The manual drawer over the bottom bar. */
        val proOpen: Boolean = false,
        val superRes: SuperResMachine.Phase = SuperResMachine.Phase.IDLE,
    )

    enum class Action {
        /** The about sheet closes. */
        CLOSE_ABOUT,

        /** The gallery closes. (The gallery's own review overlay closes itself first.) */
        CLOSE_GALLERY,

        /** The settings screen closes. */
        CLOSE_SETTINGS,

        /** The manual drawer slides away. */
        CLOSE_PRO,

        /** A running Super Res is stopped, keeping what was taken; the mode stays selected. */
        STOP_SUPER_RES,

        /** An armed Super Res is left for AUTO; the lens returns. */
        LEAVE_SUPER_RES,

        /** A stop is already in progress: nothing more to do until it lands. */
        WAIT,

        /** Nothing is open: the gesture reaches the system and the app exits. */
        EXIT,
    }

    fun resolve(screen: Screen): Action = when {
        screen.aboutOpen -> Action.CLOSE_ABOUT
        screen.galleryOpen -> Action.CLOSE_GALLERY
        screen.settingsOpen -> Action.CLOSE_SETTINGS
        screen.proOpen -> Action.CLOSE_PRO
        screen.superRes == SuperResMachine.Phase.RUNNING -> Action.STOP_SUPER_RES
        screen.superRes == SuperResMachine.Phase.STOPPING -> Action.WAIT
        screen.superRes == SuperResMachine.Phase.ARMED -> Action.LEAVE_SUPER_RES
        else -> Action.EXIT
    }

    /** Whether the app should intercept back at all: everything but [Action.EXIT]. */
    fun intercepts(screen: Screen): Boolean = resolve(screen) != Action.EXIT
}
