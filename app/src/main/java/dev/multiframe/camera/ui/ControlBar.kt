package dev.multiframe.camera.ui

import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.FrameCountPolicy
import dev.multiframe.camera.pipeline.SuperResMachine

/**
 * What the controls over the viewfinder are, as distinct from what they look
 * like.
 *
 * ### Why this exists
 *
 * The first look at this interface found nine identical pills doing five
 * different jobs -- persistent modes, cycling values, panel toggles, one-shot
 * actions and navigation -- and left it alone, because fixing it meant
 * restructuring rather than restyling. This is the restructuring.
 *
 * The problem was not that the row was ugly. It was that **tapping `DNG` took a
 * photograph and tapping `MERGE ON` set a flag, and the two were the same
 * object.** A control that writes a file to the user's gallery cannot look
 * identical to one that flips a boolean, and no amount of styling fixes that
 * while both are built by the same call.
 *
 * So a control now declares what kind of thing it is. [ControlBar.settings]
 * builds the settings screen's chips and cannot return an
 * [ControlKind.Action]; the console that replaced the chip rails draws its
 * own controls (`Revision05Console`).
 *
 * ### Why it is here rather than in the screen
 *
 * `CameraScreen` is fifteen hundred lines of camera plumbing, and the control
 * row was defined inside it as a sequence of statements -- which meant the only
 * way to check that, say, a phone with no raw support offers no raw controls
 * was to hold that phone. This is plain data built by a pure function, so the
 * gating is a unit test that needs no device at all.
 */
sealed interface ControlKind {

    /**
     * Persistent, reversible, and free: a flag the next capture will read.
     * Shown filled when on, which is the one visual convention this interface
     * already had.
     */
    data object Mode : ControlKind

    /**
     * Steps through a fixed set of values. Reversible and free, like a mode,
     * but it carries a reading rather than a state, so the value is set apart
     * from the label and in the accent -- the same rule the sliders already
     * follow, where a number is the only thing allowed to be coloured.
     */
    data object Cycle : ControlKind

    /**
     * Happens the moment it is touched, and writes a photograph. Never appears
     * among the modes; it lives beside the shutter, because that is what it is.
     */
    data object Action : ControlKind

    /** Opens a panel or a sheet. Changes nothing by itself. */
    data object Opener : ControlKind
}

/**
 * One control, as data.
 *
 * Carries no lambda deliberately. The screen dispatches on [id], which keeps
 * this list comparable in a test: two runs of [ControlBar.settings] over the same
 * state produce equal lists, and a lambda would make that impossible.
 */
data class ControlSpec(
    val id: String,
    val label: String,
    val kind: ControlKind,
    /** The reading, for a [ControlKind.Cycle]. Set apart from the label. */
    val value: String? = null,
    /**
     * Whether [value] is a reading rather than the word for not having one.
     *
     * The palette spends its single accent on numbers and live readings. `8`
     * is one; `OFF` is the absence of one, and colouring it made a timer that
     * was switched off look like a timer that was running.
     */
    val valueIsReading: Boolean = true,
    val active: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * Everything the controls need to know, and nothing else.
 *
 * Deliberately primitives and strings. The screen holds camera characteristics,
 * capture plans and stream handles; none of that belongs in a decision about
 * which pill to draw, and keeping it out is what lets the whole bar be tested
 * on the JVM.
 */
data class ControlBarState(
    val busy: Boolean = false,
    val burstFrames: Int = 4,
    val timerSeconds: Int = 0,
    val guidesLabel: String = "OFF",
    val guidesOn: Boolean = false,
    val histogramOn: Boolean = false,
    val dngOn: Boolean = true,
    val settingsOpen: Boolean = false,
    val proOpen: Boolean = false,
    val aboutOpen: Boolean = false,
    /** The selected capture mode, by label. */
    val mode: String = "PHOTO",
    /** What AUTO settled on for this scene, when it settled on something other than itself. */
    val resolvedMode: String? = null,
    /**
     * Whether this camera can run the zero-shutter-lag raw ring at all. The
     * ring is the engine, not a setting -- it is on wherever the hardware
     * allows it -- and the burst modes and Super Res are plans for it, so
     * without it there is only AUTO.
     */
    val streamOffered: Boolean = false,
    /** Whether the manual controls can be offered: a sensor that takes them, or a lens that focuses. */
    val manualOffered: Boolean = false,
    /** Whether Super Res is offered: a longer raw lens, a ring, and an attitude sensor. */
    val superResOffered: Boolean = false,
    val superRes: SuperResMachine.Phase = SuperResMachine.Phase.IDLE,
)

object ControlBar {

    const val FRAMES = "frames"
    const val TIMER = "timer"
    const val GUIDES = "guides"
    const val HISTOGRAM = "histogram"
    const val DNG = "dng"
    const val SETTINGS = "settings"
    const val PRO = "pro"
    const val ABOUT = "about"

    const val STOP = "stop"

    /** The id of a mode carousel entry, and the mode it stands for. */
    private const val MODE_PREFIX = "mode."
    fun modeId(mode: CaptureMode): String = MODE_PREFIX + mode.name
    fun modeOf(id: String): CaptureMode? =
        if (!id.startsWith(MODE_PREFIX)) null
        else CaptureMode.entries.firstOrNull { it.name == id.removePrefix(MODE_PREFIX) }

    /** The next burst size, wrapping. */
    fun nextBurst(current: Int, ceiling: Int): Int {
        return FrameCountPolicy.next(current, ceiling)
    }

    /** The next self-timer delay, wrapping. */
    fun nextTimer(current: Int): Int = when (current) {
        0 -> 3
        3 -> 10
        else -> 0
    }

    /**
     * The settings screen's chips: what the next capture writes, then what
     * is drawn over it, then the one opener that leads further.
     *
     * Nothing here is a developer's switch. The ring, the merge, the A/B
     * pair, the bracket and the ISP were all chips once; a photographer never
     * touched one and a developer touched each once. The ring and the merge
     * are the engine and are always on; the rest is reachable from a test and
     * from nowhere else. Every chip is gated on the hardware where the
     * hardware has a say.
     */
    fun settings(state: ControlBarState): List<ControlSpec> = buildList {
        // Nothing here may change while a capture is in flight: these are read
        // by the capture that is already running.
        val settled = !state.busy && !state.superRes.inFlight

        add(
            ControlSpec(
                id = FRAMES,
                label = "FRAMES",
                kind = ControlKind.Cycle,
                value = state.burstFrames.toString(),
                active = false,
                enabled = settled,
            )
        )
        add(
            ControlSpec(
                id = TIMER,
                label = "TIMER",
                kind = ControlKind.Cycle,
                value = if (state.timerSeconds == 0) "OFF" else "${state.timerSeconds}s",
                valueIsReading = state.timerSeconds > 0,
                active = state.timerSeconds > 0,
                enabled = settled,
            )
        )
        add(
            ControlSpec(
                id = DNG,
                label = if (state.dngOn) "DNG ON" else "DNG OFF",
                kind = ControlKind.Mode,
                active = state.dngOn,
                enabled = settled,
            )
        )
        add(
            ControlSpec(
                id = GUIDES,
                label = "GUIDES",
                kind = ControlKind.Cycle,
                value = state.guidesLabel,
                valueIsReading = state.guidesOn,
                active = state.guidesOn,
                // Drawing a grid does not touch the capture, so this one stays
                // live while a capture runs.
                enabled = true,
            )
        )
        add(
            ControlSpec(
                id = HISTOGRAM,
                label = if (state.histogramOn) "HISTOGRAM ON" else "HISTOGRAM OFF",
                kind = ControlKind.Mode,
                active = state.histogramOn,
                enabled = true,
            )
        )
        add(
            ControlSpec(
                id = ABOUT,
                label = "INFO",
                kind = ControlKind.Opener,
                active = state.aboutOpen,
                enabled = true,
            )
        )
    }
}
