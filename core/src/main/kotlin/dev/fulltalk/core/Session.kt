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
            current is State.Idle && input is Input.Tapped ->
                State.Recording(Trigger.TAP) to listOf(Effect.StartCapture)

            current is State.Idle && input is Input.HoldStarted ->
                State.Recording(Trigger.HOLD) to listOf(Effect.StartCapture)

            // A tap always stops; letting go only stops what a hold started.
            current is State.Recording && input is Input.Tapped ->
                State.Transcribing to listOf(Effect.FinishCapture)

            current is State.Recording && current.trigger == Trigger.HOLD && input is Input.HoldReleased ->
                State.Transcribing to listOf(Effect.FinishCapture)

            // Nothing to transcribe and nothing to say: it never happened.
            current is State.Recording && input is Input.Cancelled ->
                State.Idle to listOf(Effect.DiscardCapture)

            current is State.Recording && input is Input.CaptureFailed ->
                State.Idle to listOf(Effect.Notify(Notice.Error(input.reason)))

            current is State.Transcribing && input is Input.TranscriptionFailed ->
                State.Idle to listOf(Effect.Notify(Notice.Error(input.reason)))

            (current is State.Recording || current is State.Transcribing) && input is Input.NothingCaptured ->
                State.Idle to listOf(Effect.Notify(Notice.NothingHeard))

            current is State.Transcribing && input is Input.TranscriptionSucceeded -> {
                val clean = normalize(input.text)
                State.Idle to listOf(if (clean.isEmpty()) Effect.Notify(Notice.NothingHeard) else Effect.Deliver(clean))
            }

            // Everything else is a no-op (taps while busy, stray releases, late results).
            else -> current to emptyList()
        }
        state = next
        return effects
    }
}

/** What started the current recording, which decides what may stop it. */
enum class Trigger {
    /** Tap to start, tap again to stop. */
    TAP,

    /** Recording lasts while the bubble is held. */
    HOLD,
}

sealed interface State {
    data object Idle : State
    data class Recording(val trigger: Trigger) : State
    data object Transcribing : State
}

sealed interface Input {
    data object Tapped : Input
    data object HoldStarted : Input
    data object HoldReleased : Input

    /** Throw away the recording in progress (its tap was the first half of a double tap). */
    data object Cancelled : Input

    /** The microphone could not be opened or failed mid-recording. */
    data class CaptureFailed(val reason: String) : Input

    /** The recording was too short or too quiet to be worth transcribing. */
    data object NothingCaptured : Input
    data class TranscriptionSucceeded(val text: String) : Input
    data class TranscriptionFailed(val reason: String) : Input
}

sealed interface Notice {
    data object NothingHeard : Notice
    data class Error(val reason: String) : Notice
}

sealed interface Effect {
    data object StartCapture : Effect

    /** Stop the microphone and transcribe what was captured. */
    data object FinishCapture : Effect

    /** Stop the microphone and drop what was captured. */
    data object DiscardCapture : Effect

    /** Hand the final text to the delivery layer (clipboard, paste, history). */
    data class Deliver(val text: String) : Effect
    data class Notify(val notice: Notice) : Effect
}
