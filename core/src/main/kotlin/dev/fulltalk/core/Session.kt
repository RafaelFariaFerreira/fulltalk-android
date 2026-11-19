package dev.fulltalk.core

/**
 * The dictation session state machine.
 *
 * The bubble translates touches into [Input]s; the session answers with the
 * [Effect]s to run. Keeping it pure makes every rule of the push-to-talk flow
 * testable without a microphone, a model or a screen.
 */
class Session(state: State = State.Idle) {
    var state: State = state
        private set

    /** Applies an input and returns the effects the caller must run, in order. */
    fun handle(input: Input): List<Effect> {
        val current = state
        val (next, effects) = when {
            // Push to talk: the microphone is open exactly while the bubble is held.
            current is State.Idle && input is Input.HoldStarted ->
                State.Recording to listOf(Effect.StartCapture)

            current is State.Recording && input is Input.HoldReleased ->
                State.Transcribing to listOf(Effect.FinishCapture)

            // A tap starts nothing: a recording nobody is holding could go on forever.
            current is State.Idle && input is Input.Tapped ->
                State.Idle to listOf(Effect.Notify(Notice.HoldToTalk))

            current is State.Recording && input is Input.CaptureFailed ->
                State.Idle to listOf(Effect.Notify(Notice.Error(input.reason)))

            // The bubble shows an X while it transcribes: a tap gives up on the text.
            current is State.Transcribing && input is Input.Tapped ->
                State.Idle to listOf(Effect.DiscardTranscription)

            // The speaker must not talk to a bubble that is not listening yet.
            current is State.Transcribing && input is Input.HoldStarted ->
                State.Transcribing to listOf(Effect.Notify(Notice.StillTranscribing))

            current is State.Transcribing && input is Input.TranscriptionFailed ->
                State.Idle to listOf(Effect.Notify(Notice.Error(input.reason)))

            (current is State.Recording || current is State.Transcribing) && input is Input.NothingCaptured ->
                State.Idle to listOf(Effect.Notify(Notice.NothingHeard))

            current is State.Transcribing && input is Input.TranscriptionSucceeded -> {
                val clean = normalize(input.text)
                State.Idle to listOf(if (clean.isEmpty()) Effect.Notify(Notice.NothingHeard) else Effect.Deliver(clean))
            }

            // Everything else is a no-op (stray releases, late results).
            else -> current to emptyList()
        }
        state = next
        return effects
    }
}

sealed interface State {
    data object Idle : State

    /** The bubble is held and the microphone is open. */
    data object Recording : State

    /** The bubble was let go; the last of what was said is still with the model. */
    data object Transcribing : State
}

sealed interface Input {
    data object Tapped : Input
    data object HoldStarted : Input
    data object HoldReleased : Input

    /** The microphone could not be opened or failed mid-recording. */
    data class CaptureFailed(val reason: String) : Input

    /** The recording was too short or too quiet to be worth transcribing. */
    data object NothingCaptured : Input
    data class TranscriptionSucceeded(val text: String) : Input
    data class TranscriptionFailed(val reason: String) : Input
}

sealed interface Notice {
    data object NothingHeard : Notice

    /** A tap does not record: the bubble has to be held. */
    data object HoldToTalk : Notice

    /** A new hold came before the text of the last one: it was not recorded. */
    data object StillTranscribing : Notice
    data class Error(val reason: String) : Notice
}

sealed interface Effect {
    data object StartCapture : Effect

    /** Stop the microphone and wait for the text of what was said. */
    data object FinishCapture : Effect

    /** Give up on the transcription in progress: its result must never be delivered. */
    data object DiscardTranscription : Effect

    /** Hand the final text to the delivery layer (clipboard, paste, history). */
    data class Deliver(val text: String) : Effect
    data class Notify(val notice: Notice) : Effect
}
