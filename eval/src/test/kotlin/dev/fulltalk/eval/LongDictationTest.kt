package dev.fulltalk.eval

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.fulltalk.core.Fetcher
import dev.fulltalk.core.InstallException
import dev.fulltalk.core.MODEL_SAMPLE_RATE
import dev.fulltalk.core.ModelStore
import dev.fulltalk.core.PARAKEET_ULTRA_INT8
import dev.fulltalk.core.SpeechBuckets
import dev.fulltalk.core.narrate
import dev.fulltalk.core.rejectionFor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A long dictation in Brazilian Portuguese, through the same path as the app:
 * [PARAKEET_ULTRA_INT8], [SpeechBuckets] fed 50 ms at a time as the microphone
 * does, [narrate], and sherpa-onnx with the app's settings.
 *
 * The speech is FLEURS: distinct read sentences, one after the other, with a
 * pause after each, the way someone dictates and stops to think. On a phone,
 * a recording like this came back with sentences missing and words in other
 * languages.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LongDictationTest {
    private val data = File(checkNotNull(System.getProperty("fulltalk.evalData")) { "run with ./gradlew :eval:test" })
    private lateinit var recognizer: OfflineRecognizer
    private lateinit var sentences: List<Sentence>

    @BeforeAll
    fun setUp() {
        val store = ModelStore(File(data, "models"), PARAKEET_ULTRA_INT8)
        if (!store.isInstalled()) {
            println("downloading ${PARAKEET_ULTRA_INT8.name} (${PARAKEET_ULTRA_INT8.totalSize / 1_000_000} MB)")
            store.install(HttpFetcher)
        }
        recognizer = OfflineRecognizer(
            OfflineRecognizerConfig.builder()
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(MODEL_SAMPLE_RATE).setFeatureDim(80).build())
                .setOfflineModelConfig(
                    OfflineModelConfig.builder()
                        .setTransducer(
                            OfflineTransducerModelConfig.builder()
                                .setEncoder(store.file("encoder.int8.onnx").path)
                                .setDecoder(store.file("decoder.int8.onnx").path)
                                .setJoiner(store.file("joiner.int8.onnx").path)
                                .build(),
                        )
                        .setTokens(store.file("tokens.txt").path)
                        .setModelType("nemo_transducer")
                        .setNumThreads(4) // what the app uses on a phone with four or more cores
                        .setProvider("cpu")
                        .build(),
                )
                .build(),
        )
        sentences = readFleurs(File(data, "fleurs-pt_br-dev"))
    }

    @AfterAll
    fun tearDown() {
        recognizer.release()
    }

    @ParameterizedTest(name = "{0} s of speech with {1} s pauses")
    @CsvSource("240, 0.6", "300, 3.0")
    fun `a long dictation comes back whole`(seconds: Int, pause: Float) {
        val silence = FloatArray((pause * MODEL_SAMPLE_RATE).toInt())
        val parts = mutableListOf<FloatArray>()
        val reference = mutableListOf<String>()
        for (sentence in sentences) {
            if (parts.sumOf { it.size } >= seconds * MODEL_SAMPLE_RATE) break
            parts += sentence.audio
            parts += silence
            reference += sentence.words
        }
        val recording = concat(parts)

        val buckets = SpeechBuckets()
        val closed = mutableListOf<FloatArray>()
        val chunk = MODEL_SAMPLE_RATE / 20
        for (start in recording.indices step chunk) {
            closed += buckets.feed(recording.copyOfRange(start, minOf(start + chunk, recording.size)))
        }
        closed += listOfNotNull(buckets.finish())

        val pieces = mutableListOf<Float>()
        val started = System.nanoTime()
        var text = ""
        for (bucket in closed) {
            if (rejectionFor(bucket) != null) continue
            pieces += bucket.size / MODEL_SAMPLE_RATE.toFloat()
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(bucket, MODEL_SAMPLE_RATE)
                recognizer.decode(stream)
                text = narrate(text, recognizer.getResult(stream).text)
            } finally {
                stream.release()
            }
        }
        val elapsed = (System.nanoTime() - started) / 1e9
        val heard = words(text)
        val errorRate = wordErrorRate(reference, heard)

        println(
            "%s, %.0f s of audio: WER %.1f%%, %d of %d words, %d bucket(s) of %.0f to %.0f s, %.0f s to transcribe"
                .format(PARAKEET_ULTRA_INT8.name, recording.size / MODEL_SAMPLE_RATE.toFloat(), errorRate * 100,
                    heard.size, reference.size, pieces.size, pieces.min(), pieces.max(), elapsed),
        )
        println("  $text")
        // Read speech, numbers written as digits by the model but as words in FLEURS:
        // a good transcription of these sentences lands at 3 to 7 %.
        assertTrue(errorRate <= MAX_WORD_ERROR_RATE) {
            "WER %.1f%% is above %.0f%%".format(errorRate * 100, MAX_WORD_ERROR_RATE * 100)
        }
    }

    private companion object {
        const val MAX_WORD_ERROR_RATE = 0.08
    }
}

/** One FLEURS sentence: its audio and the words of its transcription. */
internal class Sentence(val audio: FloatArray, val words: List<String>)

/** Lowercase words, punctuation dropped, accents kept. */
internal fun words(text: String): List<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/** Word-level Levenshtein distance over the reference length. */
internal fun wordErrorRate(reference: List<String>, hypothesis: List<String>): Double {
    var previous = IntArray(hypothesis.size + 1) { it }
    for (i in 1..reference.size) {
        val current = IntArray(hypothesis.size + 1)
        current[0] = i
        for (j in 1..hypothesis.size) {
            val substitution = previous[j - 1] + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
        }
        previous = current
    }
    return previous[hypothesis.size].toDouble() / reference.size
}

private fun concat(parts: List<FloatArray>): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

/** One recording per sentence, in the order of dev.tsv (it holds several readers of each). */
private fun readFleurs(dir: File): List<Sentence> {
    val seen = mutableSetOf<String>()
    return File(dir, "dev.tsv").readLines().mapNotNull { line ->
        val columns = line.split('\t')
        if (!seen.add(columns[0])) return@mapNotNull null
        Sentence(readWav(File(dir, "dev/${columns[1]}")), words(columns[3]))
    }
}

/** 16 kHz mono WAV, 16-bit PCM or 32-bit float, into -1…1 samples. */
private fun readWav(file: File): FloatArray {
    val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var format = 0
    var bits = 0
    while (offset + 8 <= bytes.capacity()) {
        val id = bytes.getInt(offset)
        val size = bytes.getInt(offset + 4)
        val body = offset + 8
        when (id) {
            0x20746d66 -> { // "fmt "
                format = bytes.getShort(body).toInt()
                require(bytes.getShort(body + 2).toInt() == 1 && bytes.getInt(body + 4) == MODEL_SAMPLE_RATE) { "$file: not 16 kHz mono" }
                bits = bytes.getShort(body + 14).toInt()
            }
            0x61746164 -> return when { // "data"
                format == 1 && bits == 16 -> FloatArray(size / 2) { bytes.getShort(body + it * 2) / 32_768f }
                format == 3 && bits == 32 -> FloatArray(size / 4) { bytes.getFloat(body + it * 4) }
                else -> error("$file: format $format, $bits bit")
            }
        }
        offset = body + size + (size and 1)
    }
    error("$file: no audio data")
}

/** Plain HTTPS with range requests, like the app's own downloader. */
private object HttpFetcher : Fetcher {
    override fun fetch(url: String, offset: Long, sink: (ByteArray, Int) -> Unit) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw InstallException("$url: $code")
            if (offset > 0 && code == HttpURLConnection.HTTP_OK) throw InstallException("$url: range ignored")
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink(buffer, read)
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
