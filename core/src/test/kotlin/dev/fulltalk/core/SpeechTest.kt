package dev.fulltalk.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.sin

class SpeechBucketsTest {
    private val rate = MODEL_SAMPLE_RATE

    /** Sentences of [spoken] seconds, each followed by [pause] seconds of near silence, for [seconds] in all. */
    private fun dictation(seconds: Float, spoken: Float = 7f, pause: Float = 1f): FloatArray {
        val period = ((spoken + pause) * rate).toInt()
        return FloatArray((seconds * rate).toInt()) { i ->
            val amplitude = if (i % period < spoken * rate) 0.3f else 0.001f
            amplitude * sin(2 * PI * 220 * i / rate).toFloat()
        }
    }

    /** Holds the bubble for the whole [recording], fed 50 ms at a time as the microphone does, then lets go. */
    private fun hold(recording: FloatArray, chunk: Int = rate / 20): List<FloatArray> {
        val buckets = SpeechBuckets()
        val out = mutableListOf<FloatArray>()
        for (start in recording.indices step chunk) {
            out += buckets.feed(recording.copyOfRange(start, minOf(start + chunk, recording.size)))
        }
        return out + listOfNotNull(buckets.finish())
    }

    private fun cuts(buckets: List<FloatArray>) = buckets.dropLast(1).runningFold(0) { at, bucket -> at + bucket.size }.drop(1)

    @ParameterizedTest
    @ValueSource(floats = [0.5f, 12f, 30f])
    fun `letting go within thirty seconds sends one bucket, with all that was said`(seconds: Float) {
        val recording = dictation(seconds)
        assertArrayEquals(recording, hold(recording).single())
    }

    @Test
    fun `nothing leaves before the bucket is full or the speaker lets go`() {
        val buckets = SpeechBuckets()
        assertTrue(buckets.feed(dictation(29.9f)).isEmpty())
        assertEquals((29.9f * rate).toInt(), buckets.finish()?.size)
        assertNull(buckets.finish())
    }

    @ParameterizedTest
    @ValueSource(floats = [30.1f, 45f, 240f, 1800f])
    fun `a longer hold sends a bucket every thirty seconds at most, nothing lost or repeated`(seconds: Float) {
        val recording = dictation(seconds)
        val buckets = hold(recording)

        assertTrue(buckets.dropLast(1).all { it.size in 20 * rate..MAX_BUCKET_SECONDS * rate }) { "buckets of ${buckets.map { it.size / rate }} s" }
        assertTrue(buckets.last().size <= MAX_BUCKET_SECONDS * rate)
        var start = 0
        for (bucket in buckets) {
            assertTrue(Arrays.equals(recording, start, start + bucket.size, bucket, 0, bucket.size)) { "the bucket at $start" }
            start += bucket.size
        }
        assertEquals(recording.size, start)
    }

    @Test
    fun `a full bucket is cut between sentences, not inside one`() {
        val recording = dictation(240f)
        val cuts = cuts(hold(recording))

        assertTrue(cuts.size >= 7)
        assertTrue(cuts.all { rms(recording.copyOfRange(it - 80, it + 80)) < 0.01f }) { "cuts at ${cuts.map { it / rate.toFloat() }} s" }
    }

    @Test
    fun `with no pause to find, the quietest moment is the cut`() {
        val recording = dictation(70f, spoken = 70f, pause = 0f)
        val breaths = listOf(24, 51).map { it * rate until it * rate + rate / 2 }
        for (range in breaths) for (i in range) recording[i] *= 0.2f // half a second, far from silent

        val cuts = cuts(hold(recording))
        assertEquals(2, cuts.size)
        assertTrue(cuts.zip(breaths).all { (cut, range) -> cut in range }) { "cuts at ${cuts.map { it / rate.toFloat() }} s" }
    }

    @Test
    fun `the buckets do not depend on how the audio arrives`() {
        val recording = dictation(100f)
        val expected = hold(recording).map { it.size }

        assertEquals(expected, hold(recording, chunk = 1).map { it.size })
        assertEquals(expected, hold(recording, chunk = 7_919).map { it.size })
        assertEquals(expected, hold(recording, chunk = recording.size).map { it.size })
    }

    @Test
    fun `hours of holding never keep more than a bucket`() {
        val buckets = SpeechBuckets()
        val second = dictation(1f)
        var longest = 0
        repeat(2 * 3600) { for (bucket in buckets.feed(second)) longest = maxOf(longest, bucket.size) }

        assertTrue(longest in 20 * rate..MAX_BUCKET_SECONDS * rate)
        assertTrue((buckets.finish()?.size ?: 0) <= MAX_BUCKET_SECONDS * rate)
    }

    @Test
    fun `one word in a long silence is still speech`() {
        val recording = FloatArray(30 * rate)
        dictation(0.4f).copyInto(recording, 10 * rate)

        assertNull(rejectionFor(recording))
        assertEquals(Rejected.SILENT, rejectionFor(FloatArray(30 * rate) { 0.001f }))
    }
}

class NarrationTest {
    @Test
    fun `pieces are joined with single spaces, in order`() {
        val text = listOf(" Trecho um. \n", "Trecho  dois.", "Trecho três.").fold("", ::narrate)
        assertEquals("Trecho um. Trecho dois. Trecho três.", text)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", ".", " ... "])
    fun `a bucket in which the model heard no words adds nothing`(heard: String) {
        assertEquals("Antes da pausa.", narrate("Antes da pausa.", heard))
        assertEquals("", narrate("", heard))
    }

    @Test
    fun `a piece that starts in lowercase continues the sentence before it`() {
        assertEquals(
            "Mais três bombas explodiram perto dos prédios do governo em duas horas.",
            narrate("Mais três bombas explodiram perto dos prédios.", "do governo em duas horas."),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock = """
            'Não foi justo.'    | 'Certamente não.' | 'Não foi justo. Certamente não.'
            'Não foi justo?'    | 'certamente não.' | 'Não foi justo? certamente não.'
            'E depois...'       | 'nada.'           | 'E depois... nada.'
            'Chegaram às'       | '15h.'            | 'Chegaram às 15h.'
            'Foram ao parque.'  | '15 pessoas.'     | 'Foram ao parque. 15 pessoas.'""",
    )
    fun `any other seam is left as the model wrote it`(soFar: String, heard: String, expected: String) {
        assertEquals(expected, narrate(soFar, heard))
    }
}
