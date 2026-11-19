package dev.fulltalk.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.fulltalk.core.MODEL_SAMPLE_RATE
import dev.fulltalk.core.SpeechBuckets
import dev.fulltalk.core.meterLevel
import dev.fulltalk.core.rms
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Microphone capture, straight at the rate the model wants. The microphone is
 * only open while recording, so the system's indicator is off the rest of the time.
 *
 * The recording is not kept: it is handed over in buckets while it goes on
 * (see [SpeechBuckets]), so it can last as long as the speaker does.
 */
class Recorder {
    /** Live input level, 0–1. */
    @Volatile
    var level = 0f
        private set

    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private val lock = Any()
    private var buckets = SpeechBuckets()

    val isRecording: Boolean get() = record != null

    /**
     * Opens the microphone. Each bucket of speech goes to [onBucket] as soon as
     * it closes, on the microphone's own thread. Needs the RECORD_AUDIO permission.
     */
    @SuppressLint("MissingPermission") // checked by the caller, which can explain what is missing
    fun start(onBucket: (FloatArray) -> Unit) {
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
        synchronized(lock) { buckets = SpeechBuckets() }
        record = audio
        reader = thread(name = "fulltalk-mic") { read(audio, onBucket) }
    }

    /** Closes the microphone and returns the bucket that was still open, if it holds anything. */
    fun stop(): FloatArray? {
        val audio = record ?: return null
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
        return synchronized(lock) { buckets.finish() }
    }

    private fun read(audio: AudioRecord, onBucket: (FloatArray) -> Unit) {
        val chunk = FloatArray(MODEL_SAMPLE_RATE / 20) // 50 ms
        while (record === audio) {
            val read = audio.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) break
            level = meterLevel(rms(chunk, read))
            synchronized(lock) { buckets.feed(chunk, read) }.forEach(onBucket)
        }
    }
}
