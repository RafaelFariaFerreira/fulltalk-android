package dev.fulltalk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionTest {
    @Test
    fun `starts idle`() {
        assertEquals(State.Idle, Session().state)
    }

    @Test
    fun `a tap toggles recording`() {
        val session = Session()
        assertEquals(listOf(Effect.StartCapture), session.handle(Input.Tapped))
        assertEquals(State.Recording(Trigger.TAP), session.state)
        assertEquals(listOf(Effect.FinishCapture), session.handle(Input.Tapped))
        assertEquals(State.Transcribing, session.state)
    }

    @Test
    fun `a hold records while it lasts`() {
        val session = Session()
        assertEquals(listOf(Effect.StartCapture), session.handle(Input.HoldStarted))
        assertEquals(State.Recording(Trigger.HOLD), session.state)
        assertEquals(listOf(Effect.FinishCapture), session.handle(Input.HoldReleased))
        assertEquals(State.Transcribing, session.state)
    }

    @Test
    fun `letting go does not stop a recording started by a tap`() {
        val session = Session(State.Recording(Trigger.TAP))
        assertTrue(session.handle(Input.HoldStarted).isEmpty())
        assertTrue(session.handle(Input.HoldReleased).isEmpty())
        assertEquals(State.Recording(Trigger.TAP), session.state)
    }

    @Test
    fun `a tap can stop a recording started by a hold`() {
        val session = Session(State.Recording(Trigger.HOLD))
        assertEquals(listOf(Effect.FinishCapture), session.handle(Input.Tapped))
    }

    @Test
    fun `cancelling discards the recording without transcribing`() {
        for (trigger in Trigger.entries) {
            val session = Session(State.Recording(trigger))
            assertEquals(listOf(Effect.DiscardCapture), session.handle(Input.Cancelled))
            assertEquals(State.Idle, session.state)
        }
    }

    @Test
    fun `a double tap while idle leaves no trace`() {
        // The first tap starts a recording; the second cancels it instead of
        // transcribing a fraction of a second.
        val session = Session()
        assertEquals(listOf(Effect.StartCapture), session.handle(Input.Tapped))
        assertEquals(listOf(Effect.DiscardCapture), session.handle(Input.Cancelled))
        assertEquals(State.Idle, session.state)
    }

    @Test
    fun `cancelling does not touch a transcription in progress`() {
        // A double tap on a running recording: the first tap already stopped
        // it, and its text must still arrive.
        val session = Session(State.Recording(Trigger.TAP))
        assertEquals(listOf(Effect.FinishCapture), session.handle(Input.Tapped))
        assertTrue(session.handle(Input.Cancelled).isEmpty())
        assertEquals(State.Transcribing, session.state)
        assertTrue(Session().handle(Input.Cancelled).isEmpty())
    }

    @Test
    fun `a tap cancels a transcription in progress`() {
        val session = Session(State.Transcribing)
        assertEquals(listOf(Effect.DiscardTranscription), session.handle(Input.Tapped))
        assertEquals(State.Idle, session.state)
        // Its result arrives anyway, and goes nowhere.
        assertTrue(session.handle(Input.TranscriptionSucceeded("tarde demais")).isEmpty())
        assertEquals(State.Idle, session.state)
    }

    @Test
    fun `holds are ignored while transcribing`() {
        val session = Session(State.Transcribing)
        for (input in listOf(Input.HoldStarted, Input.HoldReleased)) {
            assertTrue(session.handle(input).isEmpty())
            assertEquals(State.Transcribing, session.state)
        }
    }

    @Test
    fun `a transcription is normalized and delivered`() {
        val session = Session(State.Transcribing)
        assertEquals(
            listOf(Effect.Deliver("olá mundo")),
            session.handle(Input.TranscriptionSucceeded("  olá \n mundo ")),
        )
        assertEquals(State.Idle, session.state)
    }

    @Test
    fun `an empty transcription says nothing was heard`() {
        val session = Session(State.Transcribing)
        assertEquals(
            listOf(Effect.Notify(Notice.NothingHeard)),
            session.handle(Input.TranscriptionSucceeded(" . ")),
        )
        assertEquals(State.Idle, session.state)
    }

    @Test
    fun `nothing captured returns to idle`() {
        for (state in listOf(State.Recording(Trigger.HOLD), State.Transcribing)) {
            val session = Session(state)
            assertEquals(listOf(Effect.Notify(Notice.NothingHeard)), session.handle(Input.NothingCaptured))
            assertEquals(State.Idle, session.state)
        }
    }

    @Test
    fun `failures surface an error and reset`() {
        val recording = Session(State.Recording(Trigger.TAP))
        assertEquals(
            listOf(Effect.Notify(Notice.Error("sem microfone"))),
            recording.handle(Input.CaptureFailed("sem microfone")),
        )
        assertEquals(State.Idle, recording.state)

        val transcribing = Session(State.Transcribing)
        assertEquals(
            listOf(Effect.Notify(Notice.Error("modelo"))),
            transcribing.handle(Input.TranscriptionFailed("modelo")),
        )
        assertEquals(State.Idle, transcribing.state)
    }

    @Test
    fun `stray results while idle are ignored`() {
        val session = Session()
        assertTrue(session.handle(Input.TranscriptionSucceeded("tarde demais")).isEmpty())
        assertTrue(session.handle(Input.NothingCaptured).isEmpty())
        assertTrue(session.handle(Input.HoldReleased).isEmpty())
        assertEquals(State.Idle, session.state)
    }
}
