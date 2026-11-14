package dev.fulltalk.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.fulltalk.core.MODEL_SAMPLE_RATE
import dev.fulltalk.core.meterLevel
import dev.fulltalk.core.rms
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Microphone capture, straight at the rate the model wants. The microphone is
 * only open while recording, so the system's indicator is off the rest of the time.
 */
class Recorder {
    /** Live input level, 0–1. */
    @Volatile
    var level = 0f
        private set

    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private val lock = Any()
    private var samples = FloatArray(MODEL_SAMPLE_RATE * 30)
    private var size = 0

    val isRecording: Boolean get() = record != null

    /** Opens the microphone and starts buffering. Needs the RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission") // checked by the caller, which can explain what is missing
    fun start() {
        check(record == null) { "already recording" }
        val minBytes = AudioRecord.getMinBufferSize(
            MODEL_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) throw IOException("microfone indisponível")
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MODEL_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBytes, MODEL_SAMPLE_RATE * Float.SIZE_BYTES / 2),
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw IOException("não foi possível abrir o microfone")
        }
        audio.startRecording()
        if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audio.release()
            throw IOException("o microfone está em uso por outro app")
        }
        synchronized(lock) { size = 0 }
        record = audio
        reader = thread(name = "fulltalk-mic") { read(audio) }
    }

    /** Closes the microphone and returns what was captured. */
    fun stop(): FloatArray {
        val audio = record ?: return FloatArray(0)
        record = null
        try {
            audio.stop()
        } catch (_: IllegalStateException) {
            // Already stopped by the system; the samples read so far still count.
        }
        reader?.join(500)
        reader = null
        audio.release()
        level = 0f
        return synchronized(lock) { samples.copyOf(size) }
    }

    private fun read(audio: AudioRecord) {
        val chunk = FloatArray(MODEL_SAMPLE_RATE / 20) // 50 ms
        while (record === audio) {
            val read = audio.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) break
            level = meterLevel(rms(chunk, read))
            synchronized(lock) {
                if (size + read > MAX_SAMPLES) return
                if (size + read > samples.size) samples = samples.copyOf(minOf(samples.size * 2, MAX_SAMPLES))
                chunk.copyInto(samples, size, 0, read)
                size += read
            }
        }
    }

    private companion object {
        /** Five minutes: far beyond any dictation, and a bound on memory. */
        const val MAX_SAMPLES = MODEL_SAMPLE_RATE * 60 * 5
    }
}
