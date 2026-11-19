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
    fun `a hold records while it lasts`() {
        val session = Session()
        assertEquals(listOf(Effect.StartCapture), session.handle(Input.HoldStarted))
        assertEquals(State.Recording, session.state)
        assertEquals(listOf(Effect.FinishCapture), session.handle(Input.HoldReleased))
        assertEquals(State.Transcribing, session.state)
    }

    @Test
    fun `a tap starts nothing and says how to talk`() {
        val session = Session()
        assertEquals(listOf(Effect.Notify(Notice.HoldToTalk)), session.handle(Input.Tapped))
        assertEquals(State.Idle, session.state)
    }

    @Test
    fun `only letting go ends a recording`() {
        val session = Session(State.Recording)
        for (input in listOf(Input.Tapped, Input.HoldStarted)) {
            assertTrue(session.handle(input).isEmpty())
            assertEquals(State.Recording, session.state)
        }
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
    fun `a hold while transcribing records nothing, and says so`() {
        val session = Session(State.Transcribing)
        assertEquals(listOf(Effect.Notify(Notice.StillTranscribing)), session.handle(Input.HoldStarted))
        assertTrue(session.handle(Input.HoldReleased).isEmpty())
        assertEquals(State.Transcribing, session.state)
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
        for (state in listOf(State.Recording, State.Transcribing)) {
            val session = Session(state)
            assertEquals(listOf(Effect.Notify(Notice.NothingHeard)), session.handle(Input.NothingCaptured))
            assertEquals(State.Idle, session.state)
        }
    }

    @Test
    fun `failures surface an error and reset`() {
        val recording = Session(State.Recording)
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
