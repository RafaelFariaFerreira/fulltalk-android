package dev.fulltalk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.math.PI
import kotlin.math.sin

class TextTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock = """
            '  olá   mundo  ' | 'olá mundo'
            'linha um'        | 'linha um'
            '.'               | ''
            ' ... ? '         | ''
            ''                | ''
            'Ok.'             | 'Ok.'
            '15h'             | '15h'""",
    )
    fun `normalizes model output`(raw: String, expected: String) {
        assertEquals(expected, normalize(raw))
    }

    @Test
    fun `newlines and tabs collapse to single spaces`() {
        assertEquals("linha um dois", normalize("linha\num\tdois"))
    }
}

class AudioTest {
    private fun tone(seconds: Float, amplitude: Float) =
        FloatArray((seconds * MODEL_SAMPLE_RATE).toInt()) { i ->
            amplitude * sin(2 * PI * 220 * i / MODEL_SAMPLE_RATE).toFloat()
        }

    @Test
    fun `rms of silence and of a full-scale square`() {
        assertEquals(0f, rms(FloatArray(0)))
        assertEquals(0f, rms(FloatArray(100)))
        assertEquals(1f, rms(FloatArray(100) { if (it % 2 == 0) 1f else -1f }), 1e-6f)
    }

    @Test
    fun `rms can look at just the filled part of a buffer`() {
        val buffer = FloatArray(8).also { it[0] = 0.5f; it[1] = -0.5f; it[7] = 1f }
        assertEquals(0.5f, rms(buffer, length = 2), 1e-6f)
    }

    @Test
    fun `the meter spans -60 to 0 dBFS`() {
        assertEquals(0f, meterLevel(0f))
        assertEquals(0f, meterLevel(0.0005f)) // below -60 dBFS
        assertEquals(0.5f, meterLevel(0.0316f), 0.01f) // -30 dBFS
        assertEquals(1f, meterLevel(1f))
        assertEquals(1f, meterLevel(4f))
    }

    @Test
    fun `speech goes to the model`() {
        assertNull(rejectionFor(tone(seconds = 1f, amplitude = 0.2f)))
    }

    @Test
    fun `a slip of the finger is too short`() {
        assertEquals(Rejected.TOO_SHORT, rejectionFor(tone(seconds = 0.2f, amplitude = 0.2f)))
        assertEquals(Rejected.TOO_SHORT, rejectionFor(FloatArray(0)))
    }

    @Test
    fun `a quiet room is silent`() {
        assertEquals(Rejected.SILENT, rejectionFor(tone(seconds = 1f, amplitude = 0.001f)))
        assertTrue(SILENCE_RMS > 0f)
    }
}

class DeliveryTest {
    @Test
    fun `pastes into a focused text field`() {
        assertEquals(DeliveryPlan.PASTE, deliveryPlan(FocusTarget.TEXT_INPUT, autoPaste = true))
    }

    @Test
    fun `only copies when nothing editable has focus`() {
        assertEquals(DeliveryPlan.CLIPBOARD_ONLY, deliveryPlan(FocusTarget.OTHER, autoPaste = true))
    }

    @Test
    fun `only copies when auto paste is off`() {
        assertEquals(DeliveryPlan.CLIPBOARD_ONLY, deliveryPlan(FocusTarget.TEXT_INPUT, autoPaste = false))
    }
}
