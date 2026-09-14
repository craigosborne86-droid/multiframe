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
    val mosaicOffered: Boolean = false,
    /** Armed: the capture lens is up and the outline is on the viewfinder, waiting for the shutter. */
    val mosaicArmed: Boolean = false,
    val mosaicRunning: Boolean = false,
    val rawBurstOffered: Boolean = false,
    val dngOffered: Boolean = false,
)

/**
 * Where the dials have stops.
 *
 * A slider on a touchscreen has no mechanical detents, so the value under the
 * finger is something you read rather than something you feel. These are the
 * positions worth feeling: the ISO stops a photographer already thinks in, and
 * zero on the exposure compensation scale, which is the one value on that
 * control anybody navigates back to.
 *
 * Here rather than in the panel that draws them for the reason the rest of this
 * file exists: it is arithmetic, and arithmetic can be tested without a phone.
 */
object Detents {

    /** The full-stop ISO series, which is what an ISO dial has always clicked at. */
    val ISO_STOPS = listOf(
        50f, 100f, 200f, 400f, 800f, 1600f, 3200f, 6400f, 12800f, 25600f,
    )

    /** How near counts as arrived, as a fraction of the distance to the next stop. */
    const val TOLERANCE = 0.06f

    /** The ISO stops the hardware can actually reach. */
    fun isoStops(min: Int, max: Int): List<Float> =
        ISO_STOPS.filter { it >= min && it <= max }

    /**
     * Exposure compensation, which has exactly one stop: zero.
     *
     * The index scale is the camera's own, so zero index is zero compensation
     * whatever the step size happens to be.
     */
    fun evStops(): List<Float> = listOf(0f)

    /**
     * The stop [value] is sitting at, or null if it is between two.
     *
     * Proportional rather than absolute tolerance, because the ISO series is
     * geometric: 6 units either side of 100 is a different part of the scale
     * from 6 units either side of 6400, and an absolute window would make the
     * bottom of the dial one long detent and the top of it frictionless.
     */
    fun nearest(value: Float, stops: List<Float>): Float? =
        stops.firstOrNull { stop ->
            val window = (kotlin.math.abs(stop) * TOLERANCE).coerceAtLeast(0.5f)
            kotlin.math.abs(value - stop) <= window
        }
}

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

    const val MOSAIC = "mosaic"
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
     *
     * **Six, because a phone shows six.** It was nine, and on a Pixel 9 Pro XL
     * about two and a half were visible before the pinned openers — a row that
     * reads as unfinished however carefully the rest of it is drawn. `A/B` and
     * `GUARD` moved into the PRO panel, which gives that panel a meaning it did
     * not have: *what you set once*, against *what you change between shots*.
     * Neither is something a photographer touches while composing.
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
        if (state.mosaicOffered || state.mosaicArmed || state.mosaicRunning) {
            // "Mosaic", not "sweep": a sweep is a pan, and a pan is the blurred
            // frame this mode exists to avoid. The word says what the picture
            // is -- tiles, each held still.
            add(
                ControlSpec(
                    id = MOSAIC,
                    label = when {
                        state.mosaicRunning -> "STOP MOSAIC"
                        state.mosaicArmed -> "MOSAIC ARMED"
                        else -> "MOSAIC"
                    },
                    kind = ControlKind.Action,
                    active = state.mosaicRunning || state.mosaicArmed,
                    // Stopping a mosaic must stay available while one runs, or
                    // the only way out is to leave the app.
                    enabled = state.mosaicRunning || state.mosaicArmed || !state.busy,
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
