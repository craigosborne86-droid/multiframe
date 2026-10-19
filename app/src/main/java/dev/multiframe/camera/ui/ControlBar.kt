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
 * So a control now declares what kind of thing it is, and the kinds are kept
 * apart by construction: [ControlBar.settings], [ControlBar.chrome] and
 * [ControlBar.carousel] cannot return an [ControlKind.Action] and
 * [ControlBar.actions] cannot return anything else. The settings screen
 * renders `settings`; the top of the viewfinder renders `chrome`; the row
 * under the shutter renders `carousel`; the strip beside the shutter renders
 * `actions`. An action cannot appear among the toggles by mistake, because
 * the function that builds the toggles cannot express one.
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

    /** Burst sizes offered, filtered against what the ring can actually hold. */
    val BURST_STEPS = FrameCountPolicy.STEPS

    fun burstStepsUpTo(ceiling: Int): List<Int> =
        FrameCountPolicy.stepsUpTo(ceiling)

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
     * What sits over the picture: one opener, and nothing else.
     *
     * The row was six settings and two openers. Every one of them was a word
     * on top of the photograph the user was trying to look at, and a
     * photographer changes none of them while composing -- a frame count, a
     * self-timer, a grid overlay are set once. They are behind the opener
     * now, in [settings], and the top of the viewfinder is the exposure
     * gauge and this.
     */
    fun chrome(state: ControlBarState): List<ControlSpec> = listOf(
        ControlSpec(
            id = SETTINGS,
            label = "SETTINGS",
            kind = ControlKind.Opener,
            active = state.settingsOpen,
            // Always reachable: the settings screen's own controls are the
            // ones that hold still during a capture.
            enabled = true,
        ),
    )

    /**
     * The manual opener beside the shutter: PRO slides the manual drawer up
     * over the bottom bar, with the viewfinder left whole above it. Offered
     * only where the sensor takes a manual exposure or the lens a manual
     * focus; a camera with neither has nothing to draw in the drawer.
     */
    fun pro(state: ControlBarState): List<ControlSpec> = buildList {
        if (!state.manualOffered) return@buildList
        add(
            ControlSpec(
                id = PRO,
                label = "PRO",
                kind = ControlKind.Opener,
                active = state.proOpen,
                enabled = true,
            )
        )
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

    /** PHOTO and NIGHT remain separate from automatic zoom reconstruction and Labs. */
    fun carousel(state: ControlBarState): List<ControlSpec> = buildList {
        // Switching mode reconfigures the capture; not while one is in flight.
        val settled = !state.busy && state.superRes.controlsEnabled
        fun mode(m: CaptureMode, value: String? = null) = ControlSpec(
            id = modeId(m),
            label = m.label,
            kind = ControlKind.Mode,
            value = value,
            active = state.mode == m.label,
            enabled = settled,
        )
        add(mode(CaptureMode.PHOTO))
        add(mode(CaptureMode.NIGHT))
    }

    /**
     * The strip beside the shutter: the one thing that stops a capture.
     *
     * The shutter is the only capture. `RAW xN` and `DNG` were actions here
     * once -- the sequential path's burst and a single DNG -- and they were
     * two squared chips floating over the shutter for a photographer who
     * takes photographs with the shutter; the DNG is a setting now, written
     * beside every JPEG, and the sequential path is the fallback for a phone
     * without a ring. What is left is STOP, while a Super Res runs, and
     * nothing otherwise.
     */
    fun actions(state: ControlBarState): List<ControlSpec> = buildList {
        val phase = state.superRes
        if (phase.inFlight) {
            // Stopping must stay available while a capture runs, or the only
            // way out is to leave the app. Once asked for, it is landing, and
            // the chip says so rather than taking a second tap.
            add(
                ControlSpec(
                    id = STOP,
                    label = if (phase == SuperResMachine.Phase.STOPPING) "STOPPING" else "STOP",
                    kind = ControlKind.Action,
                    active = true,
                    enabled = phase == SuperResMachine.Phase.RUNNING,
                )
            )
        }
    }
}
