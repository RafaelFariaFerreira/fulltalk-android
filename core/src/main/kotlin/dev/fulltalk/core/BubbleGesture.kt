package dev.fulltalk.core

import kotlin.math.hypot

/**
 * Turns raw touches on the bubble into what the user meant.
 *
 * One finger has to do four things: tap (toggle recording), double tap (open
 * the app), hold (talk while held) and drag (move the bubble). The first tap
 * of a double tap is reported as a plain [BubbleEvent.Tap] right away, so a
 * single tap never waits for a timeout; the second arrives as
 * [BubbleEvent.DoubleTap] and the caller undoes what the first one started.
 *
 * Times are in milliseconds from any monotonic clock, distances in pixels.
 */
class BubbleGesture(private val touchSlop: Float) {
    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastTapAt: Long? = null

    /** Whether a finger is down and neither a hold nor a drag was decided yet. */
    val awaitingHold: Boolean get() = phase == Phase.PRESSED

    /** When [holdElapsed] should be called for the current press. */
    val holdDeadline: Long get() = downAt + HOLD_MILLIS

    fun down(x: Float, y: Float, now: Long): BubbleEvent? {
        phase = Phase.PRESSED
        downX = x
        downY = y
        downAt = now
        return null
    }

    fun move(x: Float, y: Float): BubbleEvent? = when (phase) {
        Phase.PRESSED ->
            if (hypot(x - downX, y - downY) > touchSlop) {
                phase = Phase.DRAGGING
                BubbleEvent.Drag(x - downX, y - downY)
            } else {
                null
            }
        Phase.DRAGGING -> BubbleEvent.Drag(x - downX, y - downY)
        // While talking, a wandering finger must not turn into a drag.
        Phase.HOLDING, Phase.IDLE -> null
    }

    /** The finger stayed down and still for [HOLD_MILLIS]: it is a hold. */
    fun holdElapsed(now: Long): BubbleEvent? {
        if (phase != Phase.PRESSED || now < holdDeadline) return null
        phase = Phase.HOLDING
        lastTapAt = null
        return BubbleEvent.HoldStarted
    }

    fun up(now: Long): BubbleEvent? {
        val ended = phase
        phase = Phase.IDLE
        return when (ended) {
            Phase.PRESSED -> {
                val previous = lastTapAt
                if (previous != null && now - previous <= DOUBLE_TAP_MILLIS) {
                    lastTapAt = null
                    BubbleEvent.DoubleTap
                } else {
                    lastTapAt = now
                    BubbleEvent.Tap
                }
            }
            Phase.HOLDING -> BubbleEvent.HoldReleased
            Phase.DRAGGING -> BubbleEvent.DragEnded
            Phase.IDLE -> null
        }
    }

    /** The system took the touch away (rotation, another window): nothing was meant. */
    fun cancel(): BubbleEvent? {
        val ended = phase
        phase = Phase.IDLE
        return when (ended) {
            Phase.HOLDING -> BubbleEvent.HoldReleased
            Phase.DRAGGING -> BubbleEvent.DragEnded
            Phase.PRESSED, Phase.IDLE -> null
        }
    }

    private enum class Phase { IDLE, PRESSED, HOLDING, DRAGGING }

    companion object {
        /** How long a still finger must stay down to count as a hold. */
        const val HOLD_MILLIS = 350L

        /** Two taps this close together are a double tap. */
        const val DOUBLE_TAP_MILLIS = 300L
    }
}

sealed interface BubbleEvent {
    data object Tap : BubbleEvent
    data object DoubleTap : BubbleEvent
    data object HoldStarted : BubbleEvent
    data object HoldReleased : BubbleEvent

    /** The bubble should sit ([dx], [dy]) away from where it was when the finger went down. */
    data class Drag(val dx: Float, val dy: Float) : BubbleEvent
    data object DragEnded : BubbleEvent
}
