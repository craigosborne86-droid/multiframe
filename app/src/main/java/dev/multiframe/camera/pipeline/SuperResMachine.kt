package dev.multiframe.camera.pipeline

/**
 * Where a Super Res capture is in its life.
 *
 * ### Why a machine, and why a pure one
 *
 * The first version kept two booleans on the screen, `armed` and `running`,
 * and the run itself inside a `LaunchedEffect` keyed on `running`. Tapping
 * STOP flipped `running` to false, which *cancelled the effect* -- and with
 * it the code after the run that put the booleans back, restored the lens and
 * re-enabled the controls. The screen was left armed, busy and unresponsive,
 * with no control that could reach the state it was stuck in; the only way
 * out was to kill the app.
 *
 * So the states are named, the transitions between them are a table, and the
 * table is a pure function that a test can walk without a camera. The screen
 * applies events to it and reads the phase back; the run is a job the screen
 * launches, whose `finally` block delivers [Event.Finished] whatever happened
 * inside it. Nothing about the phase is inferred from a coroutine's fate.
 *
 * ### The phases
 *
 *  * [Phase.IDLE]: another mode is selected. Nothing of Super Res is on the
 *    screen.
 *  * [Phase.ARMED]: SUPER RES is the selected mode. The capture lens is up, the
 *    outline of the picture and its nodes are on the viewfinder, and the grid
 *    can be chosen. The shutter starts the capture.
 *  * [Phase.RUNNING]: the mosaic is anchored and the guidance is live. Every
 *    control that would change the capture is held; STOP and the system back
 *    gesture end it, keeping what was taken.
 *  * [Phase.STOPPING]: the stop was asked for and the run is winding down --
 *    saving the partial canvas, unlocking the stream. Brief, and shown as such;
 *    a second STOP is ignored rather than queued.
 *
 * Finishing, by any route, returns to [Phase.ARMED], not to IDLE: the mode is
 * still SUPER RES, the lens is still the telephoto, and the shutter runs
 * another. Leaving the mode is what returns to IDLE.
 */
object SuperResMachine {

    enum class Phase {
        IDLE, ARMED, RUNNING, STOPPING;

        /** Whether the run should keep going: only while genuinely running. */
        val shouldContinue: Boolean get() = this == RUNNING

        /** Whether a capture is in flight, in either of its two stages. */
        val inFlight: Boolean get() = this == RUNNING || this == STOPPING

        /** Whether Super Res is the selected mode, in any of its stages. */
        val selected: Boolean get() = this != IDLE

        /** Whether the controls that reconfigure the capture -- lenses, grid, modes -- may be used. */
        val controlsEnabled: Boolean get() = !inFlight
    }

    sealed interface Event {
        /** SUPER RES chosen in the mode carousel. */
        data object Select : Event

        /** Another mode chosen. */
        data object Deselect : Event

        /** The shutter, while armed. */
        data object Shutter : Event

        /** STOP, or the system back gesture, while running. */
        data object Stop : Event

        /** The run's `finally`: it ended, however it ended. */
        data object Finished : Event
    }

    /**
     * The phase after [event] in [phase], or null where the event means
     * nothing there and is ignored -- a second STOP, a shutter press while
     * running, a Finished with nothing in flight.
     */
    fun next(phase: Phase, event: Event): Phase? = when (phase) {
        Phase.IDLE -> when (event) {
            Event.Select -> Phase.ARMED
            else -> null
        }
        Phase.ARMED -> when (event) {
            Event.Deselect -> Phase.IDLE
            Event.Shutter -> Phase.RUNNING
            else -> null
        }
        Phase.RUNNING -> when (event) {
            Event.Stop -> Phase.STOPPING
            Event.Finished -> Phase.ARMED
            else -> null
        }
        Phase.STOPPING -> when (event) {
            Event.Finished -> Phase.ARMED
            else -> null
        }
    }

    /** [next], or the phase unchanged where the event is ignored. */
    fun apply(phase: Phase, event: Event): Phase = next(phase, event) ?: phase
}
