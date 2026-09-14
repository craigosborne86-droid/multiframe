package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.SuperResMachine.Event
import dev.multiframe.camera.pipeline.SuperResMachine.Phase
import org.junit.Test

/**
 * The Super Res lifecycle, walked as the field test walked it.
 *
 * The deadlock this machine replaces was a stop followed by a re-arm: the
 * stop cancelled the code that reset the state, and the controls stayed held
 * forever. The sequence is here as a test, with every state along the way
 * asserted for what the screen may and may not do in it.
 */
class SuperResMachineTest {

    private fun walk(vararg events: Event, from: Phase = Phase.IDLE): Phase =
        events.fold(from) { p, e -> SuperResMachine.apply(p, e) }

    @Test
    fun `selecting the mode arms it and the shutter runs it`() {
        assertThat(SuperResMachine.next(Phase.IDLE, Event.Select)).isEqualTo(Phase.ARMED)
        assertThat(SuperResMachine.next(Phase.ARMED, Event.Shutter)).isEqualTo(Phase.RUNNING)
    }

    @Test
    fun `stop then re-arm then run again is a legal walk with the controls free in between`() {
        var p = walk(Event.Select, Event.Shutter)
        assertThat(p).isEqualTo(Phase.RUNNING)
        assertThat(p.shouldContinue).isTrue()
        assertThat(p.controlsEnabled).isFalse()

        p = SuperResMachine.apply(p, Event.Stop)
        assertThat(p).isEqualTo(Phase.STOPPING)
        // The run's loop reads this and returns; nothing else stops it.
        assertThat(p.shouldContinue).isFalse()
        // Until it has, the controls stay held: the stream is still locked.
        assertThat(p.controlsEnabled).isFalse()

        p = SuperResMachine.apply(p, Event.Finished)
        assertThat(p).isEqualTo(Phase.ARMED)
        // The deadlock, had it survived: armed with the controls held.
        assertThat(p.controlsEnabled).isTrue()
        assertThat(p.selected).isTrue()
        assertThat(p.inFlight).isFalse()

        // And it runs again from there, and finishes on its own.
        p = SuperResMachine.apply(p, Event.Shutter)
        assertThat(p).isEqualTo(Phase.RUNNING)
        p = SuperResMachine.apply(p, Event.Finished)
        assertThat(p).isEqualTo(Phase.ARMED)
    }

    @Test
    fun `a run that completes on its own returns to armed, not idle`() {
        assertThat(walk(Event.Select, Event.Shutter, Event.Finished)).isEqualTo(Phase.ARMED)
    }

    @Test
    fun `leaving the mode is what returns to idle, and only from armed`() {
        assertThat(walk(Event.Select, Event.Deselect)).isEqualTo(Phase.IDLE)
        // Not while running: the run has the stream locked.
        assertThat(SuperResMachine.next(Phase.RUNNING, Event.Deselect)).isNull()
        assertThat(SuperResMachine.next(Phase.STOPPING, Event.Deselect)).isNull()
    }

    @Test
    fun `events that mean nothing in a phase are ignored rather than queued`() {
        // A second STOP while stopping; a shutter while running; a Finished
        // from nowhere; a Select while already selected.
        assertThat(SuperResMachine.next(Phase.STOPPING, Event.Stop)).isNull()
        assertThat(SuperResMachine.next(Phase.RUNNING, Event.Shutter)).isNull()
        assertThat(SuperResMachine.next(Phase.IDLE, Event.Finished)).isNull()
        assertThat(SuperResMachine.next(Phase.ARMED, Event.Finished)).isNull()
        assertThat(SuperResMachine.next(Phase.ARMED, Event.Select)).isNull()
        assertThat(SuperResMachine.next(Phase.IDLE, Event.Shutter)).isNull()
        assertThat(SuperResMachine.next(Phase.IDLE, Event.Stop)).isNull()
        // Applied, an ignored event leaves the phase where it was.
        assertThat(SuperResMachine.apply(Phase.STOPPING, Event.Stop)).isEqualTo(Phase.STOPPING)
    }

    @Test
    fun `every phase says what the screen may do in it`() {
        assertThat(Phase.IDLE.controlsEnabled).isTrue()
        assertThat(Phase.IDLE.selected).isFalse()
        assertThat(Phase.ARMED.controlsEnabled).isTrue()
        assertThat(Phase.ARMED.shouldContinue).isFalse()
        assertThat(Phase.RUNNING.inFlight).isTrue()
        assertThat(Phase.STOPPING.inFlight).isTrue()
        // Only one phase keeps the run going.
        assertThat(Phase.entries.filter { it.shouldContinue }).containsExactly(Phase.RUNNING)
    }

    @Test
    fun `from every phase, Finished after anything in flight lands on armed`() {
        for (p in Phase.entries.filter { it.inFlight }) {
            assertThat(SuperResMachine.next(p, Event.Finished)).isEqualTo(Phase.ARMED)
        }
    }
}
