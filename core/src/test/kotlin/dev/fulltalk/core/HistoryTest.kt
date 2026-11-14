package dev.fulltalk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HistoryTest {
    private fun History.texts() = entries.map { it.text }

    @Test
    fun `starts empty with room for five`() {
        assertEquals(emptyList<String>(), History().texts())
        assertEquals(5, History.DEFAULT_CAPACITY)
    }

    @Test
    fun `newest comes first`() {
        val history = History().push("um", 1).push("dois", 2)
        assertEquals(listOf("dois", "um"), history.texts())
        assertEquals(2, history.entries.first().createdAt)
    }

    @Test
    fun `keeps only the most recent entries`() {
        val history = (1..7).fold(History()) { h, i -> h.push("frase $i", i.toLong()) }
        assertEquals(listOf("frase 7", "frase 6", "frase 5", "frase 4", "frase 3"), history.texts())
    }

    @Test
    fun `a repeated text moves to the top instead of appearing twice`() {
        val history = History().push("a", 1).push("b", 2).push("a", 3)
        assertEquals(listOf("a", "b"), history.texts())
        assertEquals(3, history.entries.first().createdAt)
    }

    @Test
    fun `blank text is ignored and surrounding space trimmed`() {
        val history = History().push("  olá  ", 1)
        assertEquals(listOf("olá"), history.texts())
        assertSame(history, history.push("   ", 2))
    }

    @Test
    fun `pushing never changes the original`() {
        val before = History().push("a", 1)
        before.push("b", 2)
        assertEquals(listOf("a"), before.texts())
    }

    @Test
    fun `restoring enforces the rules on whatever was saved`() {
        val saved = listOf("z", "a", "a", " ", "b", "c", "d", "e", "f").mapIndexed { i, t -> HistoryEntry(t, i.toLong()) }
        assertEquals(listOf("z", "a", "b", "c", "d"), History().restored(saved).texts())
        assertEquals(History().push("x", 1), History().restored(listOf(HistoryEntry("x", 1))))
    }

    @Test
    fun `capacity must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { History(0) }
    }
}
