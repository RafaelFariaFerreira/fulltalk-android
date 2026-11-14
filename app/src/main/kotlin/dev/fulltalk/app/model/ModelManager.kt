package dev.fulltalk.app.model

import dev.fulltalk.core.Fetcher
import dev.fulltalk.core.InstallException
import dev.fulltalk.core.ModelStore
import dev.fulltalk.core.PARAKEET_TDT_V3_INT8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val done: Long, val total: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}

/** Owns the speech model on disk and its one-time download. */
class ModelManager(filesDir: File, private val scope: CoroutineScope) {
    val store = ModelStore(File(filesDir, "models"), PARAKEET_TDT_V3_INT8)

    private val _state = MutableStateFlow(if (store.isInstalled()) ModelState.Ready else ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts (or resumes) the download. Does nothing if it is running or already done. */
    fun download() {
        if (job?.isActive == true || _state.value == ModelState.Ready) return
        _state.value = ModelState.Downloading(0, store.spec.totalSize)
        job = scope.launch(Dispatchers.IO) {
            var lastStep = -1L
            _state.value = try {
                store.install(HttpFetcher) { done, total ->
                    // A 650 MB download would otherwise publish tens of thousands of updates.
                    val step = done * PROGRESS_STEPS / total
                    if (step != lastStep) {
                        lastStep = step
                        _state.value = ModelState.Downloading(done, total)
                    }
                }
                ModelState.Ready
            } catch (error: IOException) {
                ModelState.Failed(error.message ?: "falha no download")
            }
        }
    }

    private companion object {
        const val PROGRESS_STEPS = 500
    }
}

/** Plain HTTPS with range requests, so an interrupted download picks up where it stopped. */
private object HttpFetcher : Fetcher {
    override fun fetch(url: String, offset: Long, sink: (ByteArray, Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw InstallException("o servidor respondeu $code")
            }
            connection.inputStream.use { input ->
                // A server that ignores the range sends the whole file again.
                if (offset > 0 && code == HttpURLConnection.HTTP_OK) input.skipFully(offset)
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

    private fun InputStream.skipFully(count: Long) {
        var left = count
        val scratch = ByteArray(1 shl 16)
        while (left > 0) {
            val read = read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (read < 0) throw InstallException("o download terminou antes da hora")
            left -= read
        }
    }
}
