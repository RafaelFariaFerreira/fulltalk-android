package dev.fulltalk.core

import kotlin.math.log10
import kotlin.math.sqrt

/** Sample rate the speech model expects; the microphone is opened at this rate. */
const val MODEL_SAMPLE_RATE = 16_000

/** Recordings shorter than this are slips of the finger, not speech. */
const val MIN_SPEECH_SECONDS = 0.3f

/** Below this RMS the microphone picked up nothing but its own noise floor. */
const val SILENCE_RMS = 0.003f

/** Root-mean-square amplitude; `0` for empty input. */
fun rms(samples: FloatArray, length: Int = samples.size): Float {
    if (length <= 0) return 0f
    var sum = 0.0
    for (i in 0 until length) sum += samples[i].toDouble() * samples[i].toDouble()
    return sqrt(sum / length).toFloat()
}

/** Maps an RMS amplitude to a 0–1 meter value on a -60…0 dBFS scale. */
fun meterLevel(rms: Float): Float {
    if (rms <= 0f) return 0f
    val db = 20f * log10(rms)
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
}

/** Why a piece of a recording is not worth transcribing. */
enum class Rejected { TOO_SHORT, SILENT }

/**
 * Decides whether a piece of a recording goes to the model; `null` means yes.
 * It is judged by its loudest moment: one word in half a minute of silence is
 * still speech.
 */
fun rejectionFor(samples: FloatArray, sampleRate: Int = MODEL_SAMPLE_RATE): Rejected? = when {
    samples.size < MIN_SPEECH_SECONDS * sampleRate -> Rejected.TOO_SHORT
    loudest(samples, (MIN_SPEECH_SECONDS * sampleRate).toInt()) < SILENCE_RMS -> Rejected.SILENT
    else -> null
}

/** The RMS of the loudest stretch of [window] samples, looked for every half window. */
private fun loudest(samples: FloatArray, window: Int): Float {
    var peak = 0.0
    var at = 0
    while (true) {
        val start = minOf(at, samples.size - window)
        var energy = 0.0
        for (i in start until start + window) energy += samples[i].toDouble() * samples[i].toDouble()
        peak = maxOf(peak, energy)
        if (start == samples.size - window) break
        at += window / 2
    }
    return sqrt(peak / window).toFloat()
}
