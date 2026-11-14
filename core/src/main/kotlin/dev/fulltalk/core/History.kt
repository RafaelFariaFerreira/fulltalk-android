package dev.fulltalk.core

/** One past transcription. [createdAt] is in epoch seconds. */
data class HistoryEntry(val text: String, val createdAt: Long)

/** The most recent transcriptions, newest first. Immutable: every change returns a new value. */
class History private constructor(val entries: List<HistoryEntry>, private val capacity: Int) {
    constructor(capacity: Int = DEFAULT_CAPACITY) : this(emptyList(), capacity)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /**
     * Adds a transcription on top. Blank text is ignored; text already present
     * moves to the top instead of appearing twice.
     */
    fun push(text: String, createdAt: Long): History {
        val clean = text.trim()
        if (clean.isEmpty()) return this
        val rest = entries.filter { it.text != clean }
        return History((listOf(HistoryEntry(clean, createdAt)) + rest).take(capacity), capacity)
    }

    /** Rebuilds a history from stored entries, enforcing its rules on whatever was saved. */
    fun restored(saved: List<HistoryEntry>): History =
        saved.asReversed().fold(History(capacity)) { history, entry -> history.push(entry.text, entry.createdAt) }

    override fun equals(other: Any?): Boolean = other is History && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "History($entries)"

    companion object {
        const val DEFAULT_CAPACITY = 5
    }
}
