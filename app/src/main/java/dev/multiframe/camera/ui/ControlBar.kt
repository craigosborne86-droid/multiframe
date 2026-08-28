package dev.multiframe.camera.ui

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
 * So a control now declares what kind of thing it is, and the kinds are kept
 * apart by construction: [modes] cannot return an [ControlKind.Action] and
 * [actions] cannot return anything else. The row over the picture renders
 * `modes`; the strip beside the shutter renders `actions`. An action cannot
 * appear among the toggles by mistake, because the function that builds the
 * toggles cannot express one.
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
 * this list comparable in a test: two runs of [ControlBar.modes] over the same
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
 * Everything the control bar needs to know, and nothing else.
 *
 * Deliberately primitives and strings. The screen holds camera characteristics,
 * capture plans and stream handles; none of that belongs in a decision about
 * which pill to draw, and keeping it out is what lets the whole bar be tested
 * on the JVM.
 */
data class ControlBarState(
    val busy: Boolean = false,
    val mergeEnabled: Boolean = true,
    val abMode: Boolean = false,
    val burstFrames: Int = 4,
    val timerSeconds: Int = 0,
    val guidesLabel: String = "OFF",
    val guidesOn: Boolean = false,
    val proOpen: Boolean = false,
    val aboutOpen: Boolean = false,
    /** Null where there is no zero-shutter-lag stream to have a mode for. */
    val captureModeLabel: String? = null,
    val captureModeActive: Boolean = false,
    val highlightGuardOffered: Boolean = false,
    val highlightGuardOn: Boolean = false,
    val guardPull: Float = 0f,
    val zslOffered: Boolean = false,
    val zslOn: Boolean = false,
    val sweepOffered: Boolean = false,
    val sweepRunning: Boolean = false,
    val rawBurstOffered: Boolean = false,
    val dngOffered: Boolean = false,
)

object ControlBar {

    const val MERGE = "merge"
    const val AB = "ab"
    const val FRAMES = "frames"
    const val CAPTURE_MODE = "captureMode"
    const val ZSL = "zsl"
    const val GUARD = "guard"
    const val TIMER = "timer"
    const val GUIDES = "guides"
    const val PRO = "pro"
    const val ABOUT = "about"

    const val SWEEP = "sweep"
    const val RAW_BURST = "rawBurst"
    const val DNG = "dng"

    /** Burst sizes offered, filtered against what the ring can actually hold. */
    val BURST_STEPS = listOf(1, 2, 4, 8, 12, 16, 24, 28, 32)

    fun burstStepsUpTo(ceiling: Int): List<Int> =
        BURST_STEPS.filter { it <= ceiling }.ifEmpty { listOf(1) }

    /** The next burst size, wrapping. */
    fun nextBurst(current: Int, ceiling: Int): Int {
        val steps = burstStepsUpTo(ceiling)
        val here = steps.indexOf(current)
        return if (here < 0) steps.first() else steps[(here + 1) % steps.size]
    }

    /** The next self-timer delay, wrapping. */
    fun nextTimer(current: Int): Int = when (current) {
        0 -> 3
        3 -> 10
        else -> 0
    }

    /**
     * The row over the picture: what the next capture will be, then what is
     * drawn over it, then the two openers.
     *
     * Grouped rather than in the order the features happened to be built. The
     * first group is the capture itself, which is what a photographer changes
     * between shots; the guides are about the display and nothing else; the
     * openers come last because they are the only ones that lead somewhere.
     */
    fun modes(state: ControlBarState): List<ControlSpec> = buildList {
        // Nothing here may change while a capture is in flight: these are read
        // by the capture that is already running.
        val settled = !state.busy

        add(
            ControlSpec(
                id = MERGE,
                label = if (state.mergeEnabled) "MERGE ON" else "MERGE OFF",
                kind = ControlKind.Mode,
                active = state.mergeEnabled,
                enabled = settled,
            )
        )
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
        state.captureModeLabel?.let { label ->
            add(
                ControlSpec(
                    id = CAPTURE_MODE,
                    label = "MODE",
                    kind = ControlKind.Cycle,
                    value = label,
                    active = state.captureModeActive,
                    enabled = settled,
                )
            )
        }
        if (state.zslOffered) {
            add(
                ControlSpec(
                    id = ZSL,
                    label = if (state.zslOn) "ZSL ON" else "ZSL OFF",
                    kind = ControlKind.Mode,
                    active = state.zslOn,
                    enabled = settled,
                )
            )
        }
        if (state.highlightGuardOffered) {
            add(
                ControlSpec(
                    id = GUARD,
                    label = "GUARD",
                    kind = ControlKind.Mode,
                    // The pull is a live reading of what the guard is doing, so
                    // it belongs in the accent beside the label rather than
                    // spliced into it.
                    value = if (state.guardPull < -0.05f) {
                        "%.1f".format(state.guardPull)
                    } else {
                        null
                    },
                    active = state.highlightGuardOn,
                    enabled = settled,
                )
            )
        }
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
                id = AB,
                label = "A/B",
                kind = ControlKind.Mode,
                active = state.abMode,
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
                id = PRO,
                label = "PRO",
                kind = ControlKind.Opener,
                active = state.proOpen,
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

    /**
     * The strip beside the shutter: the things that take a photograph.
     *
     * All of these write a file the moment they are touched, which is why they
     * are not in the row above. Every one is gated on the hardware having said
     * yes -- a phone that cannot deliver raw offers no raw control at all,
     * rather than a control that sends a request the camera will reject.
     */
    fun actions(state: ControlBarState): List<ControlSpec> = buildList {
        if (state.sweepOffered || state.sweepRunning) {
            add(
                ControlSpec(
                    id = SWEEP,
                    label = if (state.sweepRunning) "STOP SWEEP" else "SUPER RES",
                    kind = ControlKind.Action,
                    active = state.sweepRunning,
                    // Stopping a sweep must stay available while one runs, or
                    // the only way out is to leave the app.
                    enabled = state.sweepRunning || !state.busy,
                )
            )
        }
        if (state.rawBurstOffered) {
            add(
                ControlSpec(
                    id = RAW_BURST,
                    label = "RAW",
                    kind = ControlKind.Action,
                    value = "×${state.burstFrames}",
                    enabled = !state.busy,
                )
            )
        }
        if (state.dngOffered) {
            add(
                ControlSpec(
                    id = DNG,
                    label = "DNG",
                    kind = ControlKind.Action,
                    enabled = !state.busy,
                )
            )
        }
    }
}
