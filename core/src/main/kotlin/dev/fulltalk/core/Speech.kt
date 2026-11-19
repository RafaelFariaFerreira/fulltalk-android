package dev.fulltalk.core

/**
 * The most audio the model hears at once. It was trained on short utterances:
 * given minutes in one pass it drops sentences, drifts into other languages,
 * and its memory grows with the square of the length (more than 4 GB for four
 * minutes).
 */
const val MAX_BUCKET_SECONDS = 30

/** A full bucket is cut at the quietest moment of its last seconds, so that words stay whole. */
const val CUT_SEARCH_SECONDS = 10

/** Shorter dips than this are the silent part of a "p" or a "t", not a place to cut. */
private const val QUIET_SECONDS = 0.3f

/**
 * Speech on its way to the model while it is still being spoken. Audio goes in
 * as the microphone delivers it and comes out in buckets: consecutive pieces
 * of at most [MAX_BUCKET_SECONDS]. A bucket goes out when it is full; the open
 * one goes out when the speaker lets go of the bubble ([finish]). Nothing is
 * lost or repeated between them, and nothing older than the open bucket is
 * kept, however long the bubble is held.
 */
class SpeechBuckets(private val sampleRate: Int = MODEL_SAMPLE_RATE) {
    private val limit = MAX_BUCKET_SECONDS * sampleRate
    private val open = FloatArray(limit)
    private var size = 0

    /** Takes the next [length] samples and returns the buckets they filled, usually none. */
    fun feed(samples: FloatArray, length: Int = samples.size): List<FloatArray> {
        var full: MutableList<FloatArray>? = null
        var taken = 0
        while (taken < length) {
            if (size == limit) {
                // Full, and the speaker goes on: what comes after the cut opens the next bucket.
                val cut = quietest(open, limit - CUT_SEARCH_SECONDS * sampleRate, limit, sampleRate)
                full = (full ?: mutableListOf()).also { it += close(cut) }
            }
            val count = minOf(length - taken, limit - size)
            samples.copyInto(open, size, taken, taken + count)
            size += count
            taken += count
        }
        return full.orEmpty()
    }

    /** The speaker let go: returns what the open bucket holds, if anything. */
    fun finish(): FloatArray? = if (size == 0) null else close(size)

    private fun close(cut: Int): FloatArray {
        val bucket = open.copyOfRange(0, cut)
        open.copyInto(open, 0, cut, size)
        size -= cut
        return bucket
    }
}

/**
 * Adds what the model heard in the next bucket to the text so far. A bucket in
 * which it heard no words adds nothing. When a piece starts in lowercase the
 * model took it for the middle of a sentence, so the full stop it put at the
 * end of the piece before, as it does at the end of every piece, is dropped.
 */
fun narrate(soFar: String, heard: String): String {
    val piece = normalize(heard)
    return when {
        piece.isEmpty() -> soFar
        soFar.isEmpty() -> piece
        piece.first().isLowerCase() && soFar.endsWith('.') && !soFar.endsWith("..") -> "${soFar.dropLast(1)} $piece"
        else -> "$soFar $piece"
    }
}

/** The middle of the quietest [QUIET_SECONDS] between [from] and [to], looked for every 10 ms. */
private fun quietest(samples: FloatArray, from: Int, to: Int, sampleRate: Int): Int {
    val window = (QUIET_SECONDS * sampleRate).toInt()
    val step = sampleRate / 100
    var best = from
    var lowest = Double.MAX_VALUE
    var at = from
    while (at + window <= to) {
        var energy = 0.0
        for (i in at until at + window) energy += samples[i].toDouble() * samples[i].toDouble()
        if (energy < lowest) {
            lowest = energy
            best = at
        }
        at += step
    }
    return best + window / 2
}
