package dev.fulltalk.app.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.fulltalk.core.MODEL_SAMPLE_RATE
import dev.fulltalk.core.ModelStore

/**
 * Speech to text with Parakeet Ultra, running on the CPU through sherpa-onnx.
 * Loading takes seconds and hundreds of megabytes, so the model is loaded
 * once and kept; calls are serialized.
 */
class Transcriber(private val store: ModelStore) {
    private var recognizer: OfflineRecognizer? = null

    val isLoaded: Boolean
        @Synchronized get() = recognizer != null

    /** Loads the model into memory. Slow: call it off the main thread. */
    @Synchronized
    fun load() {
        if (recognizer != null) return
        check(store.isInstalled()) { "o modelo de voz não está instalado" }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = MODEL_SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = store.file("encoder.int8.onnx").path,
                    decoder = store.file("decoder.int8.onnx").path,
                    joiner = store.file("joiner.int8.onnx").path,
                ),
                tokens = store.file("tokens.txt").path,
                modelType = "nemo_transducer",
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                provider = "cpu",
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(null, config)
    }

    /**
     * Transcribes 16 kHz mono samples in the -1…1 range: one bucket of a
     * dictation, never more than `MAX_BUCKET_SECONDS` (see `SpeechBuckets`).
     */
    @Synchronized
    fun transcribe(samples: FloatArray): String {
        val recognizer = checkNotNull(recognizer) { "o modelo de voz não foi carregado" }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, MODEL_SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
    }
}
