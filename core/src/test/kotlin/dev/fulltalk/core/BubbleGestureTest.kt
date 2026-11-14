package dev.fulltalk.core

import dev.fulltalk.core.BubbleGesture.Companion.DOUBLE_TAP_MILLIS
import dev.fulltalk.core.BubbleGesture.Companion.HOLD_MILLIS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BubbleGestureTest {
    private val gesture = BubbleGesture(touchSlop = 10f)

    private fun tap(at: Long): BubbleEvent? {
        gesture.down(0f, 0f, at)
        return gesture.up(at + 60)
    }

    @Test
    fun `a quick press is a tap`() {
        assertNull(gesture.down(5f, 5f, 1_000))
        assertTrue(gesture.awaitingHold)
        assertEquals(BubbleEvent.Tap, gesture.up(1_080))
        assertFalse(gesture.awaitingHold)
    }

    @Test
    fun `a finger that trembles inside the slop still taps`() {
        gesture.down(0f, 0f, 0)
        assertNull(gesture.move(6f, 6f)) // 8.5 px away
        assertEquals(BubbleEvent.Tap, gesture.up(90))
    }

    @Test
    fun `the first tap acts at once and the second is a double tap`() {
        assertEquals(BubbleEvent.Tap, tap(at = 0))
        assertEquals(BubbleEvent.DoubleTap, tap(at = 200))
    }

    @Test
    fun `slow taps are two separate taps`() {
        assertEquals(BubbleEvent.Tap, tap(at = 0))
        assertEquals(BubbleEvent.Tap, tap(at = 60 + DOUBLE_TAP_MILLIS + 1))
    }

    @Test
    fun `a third quick tap starts over`() {
        tap(at = 0)
        tap(at = 150)
        assertEquals(BubbleEvent.Tap, tap(at = 300))
    }

    @Test
    fun `a still finger becomes a hold and letting go releases it`() {
        gesture.down(0f, 0f, 1_000)
        assertEquals(1_000 + HOLD_MILLIS, gesture.holdDeadline)
        assertNull(gesture.holdElapsed(1_000 + HOLD_MILLIS - 1))
        assertEquals(BubbleEvent.HoldStarted, gesture.holdElapsed(1_000 + HOLD_MILLIS))
        assertFalse(gesture.awaitingHold)
        assertEquals(BubbleEvent.HoldReleased, gesture.up(3_000))
    }

    @Test
    fun `a wandering finger does not drag while talking`() {
        gesture.down(0f, 0f, 0)
        gesture.holdElapsed(HOLD_MILLIS)
        assertNull(gesture.move(200f, 0f))
        assertEquals(BubbleEvent.HoldReleased, gesture.up(2_000))
    }

    @Test
    fun `a tap right after a hold is not a double tap`() {
        gesture.down(0f, 0f, 0)
        gesture.holdElapsed(HOLD_MILLIS)
        gesture.up(HOLD_MILLIS + 10)
        assertEquals(BubbleEvent.Tap, tap(at = HOLD_MILLIS + 50))
    }

    @Test
    fun `moving past the slop drags, relative to where the finger went down`() {
        gesture.down(100f, 100f, 0)
        assertEquals(BubbleEvent.Drag(30f, 0f), gesture.move(130f, 100f))
        assertEquals(BubbleEvent.Drag(-20f, 45f), gesture.move(80f, 145f))
        // Once dragging, coming back near the start is still a drag.
        assertEquals(BubbleEvent.Drag(2f, 0f), gesture.move(102f, 100f))
        assertEquals(BubbleEvent.DragEnded, gesture.up(900))
    }

    @Test
    fun `a drag never turns into a hold or a tap`() {
        gesture.down(0f, 0f, 0)
        gesture.move(50f, 0f)
        assertFalse(gesture.awaitingHold)
        assertNull(gesture.holdElapsed(HOLD_MILLIS * 2))
        assertEquals(BubbleEvent.DragEnded, gesture.up(HOLD_MILLIS * 2 + 5))
        // And it does not arm a double tap either.
        assertEquals(BubbleEvent.Tap, tap(at = HOLD_MILLIS * 2 + 50))
    }

    @Test
    fun `a cancelled touch means nothing, but ends what had started`() {
        gesture.down(0f, 0f, 0)
        assertNull(gesture.cancel())

        gesture.down(0f, 0f, 1_000)
        gesture.holdElapsed(1_000 + HOLD_MILLIS)
        assertEquals(BubbleEvent.HoldReleased, gesture.cancel())

        gesture.down(0f, 0f, 5_000)
        gesture.move(40f, 0f)
        assertEquals(BubbleEvent.DragEnded, gesture.cancel())
    }

    @Test
    fun `events without a finger down are ignored`() {
        assertNull(gesture.move(50f, 50f))
        assertNull(gesture.up(10))
        assertNull(gesture.holdElapsed(10_000))
        assertNull(gesture.cancel())
    }
}
