package dev.fulltalk.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class ModelStoreTest {
    @TempDir
    lateinit var root: File

    private val weights = ByteArray(5_000) { (it * 31 % 251).toByte() }
    private val tokens = "<blk> 0\na 1\nb 2\n".toByteArray()
    private val spec = ModelSpec(
        name = "tiny-model",
        baseUrl = "https://models.test/tiny",
        files = listOf(
            ModelFile("weights.onnx", weights.size.toLong(), hex(weights)),
            ModelFile("tokens.txt", tokens.size.toLong(), hex(tokens)),
        ),
    )

    /** Serves files from memory, in small chunks, honouring the resume offset. */
    private class FakeFetcher(private val served: Map<String, ByteArray>) : Fetcher {
        val requests = mutableListOf<Pair<String, Long>>()

        override fun fetch(url: String, offset: Long, sink: (ByteArray, Int) -> Unit) {
            requests += url to offset
            val bytes = served.getValue(url)
            var position = offset.toInt()
            while (position < bytes.size) {
                val length = minOf(1_024, bytes.size - position)
                sink(bytes.copyOfRange(position, position + length), length)
                position += length
            }
        }
    }

    private fun upstream(weights: ByteArray = this.weights, tokens: ByteArray = this.tokens) = FakeFetcher(
        mapOf("https://models.test/tiny/weights.onnx" to weights, "https://models.test/tiny/tokens.txt" to tokens),
    )

    private fun hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `installs every file and reports progress up to the total`() {
        val store = ModelStore(root, spec)
        assertFalse(store.isInstalled())
        val progress = mutableListOf<Pair<Long, Long>>()

        store.install(upstream()) { done, total -> progress += done to total }

        assertTrue(store.isInstalled())
        assertArrayEquals(weights, store.file("weights.onnx").readBytes())
        assertArrayEquals(tokens, store.file("tokens.txt").readBytes())
        assertEquals(0L to spec.totalSize, progress.first())
        assertEquals(spec.totalSize to spec.totalSize, progress.last())
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first })
        assertEquals(emptyList<String>(), store.dir.list()!!.filter { it.endsWith(".part") })
    }

    @Test
    fun `files already in place are not downloaded again`() {
        val store = ModelStore(root, spec)
        store.install(upstream())
        store.file("tokens.txt").delete()
        val fetcher = upstream()
        val progress = mutableListOf<Long>()

        store.install(fetcher) { done, _ -> progress += done }

        assertEquals(listOf("https://models.test/tiny/tokens.txt" to 0L), fetcher.requests)
        assertEquals(weights.size.toLong(), progress.first())
        assertTrue(store.isInstalled())
    }

    @Test
    fun `an interrupted download is resumed where it stopped`() {
        val store = ModelStore(root, spec)
        store.dir.mkdirs()
        File(store.dir, "weights.onnx.part").writeBytes(weights.copyOfRange(0, 2_000))
        val fetcher = upstream()

        store.install(fetcher)

        assertEquals("https://models.test/tiny/weights.onnx" to 2_000L, fetcher.requests.first())
        assertArrayEquals(weights, store.file("weights.onnx").readBytes())
    }

    @Test
    fun `a corrupted file is rejected and nothing is left behind`() {
        val store = ModelStore(root, spec)
        val tampered = weights.copyOf().also { it[42] = (it[42] + 1).toByte() }

        val error = assertThrows(InstallException::class.java) { store.install(upstream(weights = tampered)) }

        assertTrue(error.message!!.contains("weights.onnx"))
        assertFalse(store.isInstalled())
        assertEquals(emptyList<String>(), store.dir.list()!!.toList())
    }

    @Test
    fun `a resumed download built on a bad prefix is rejected too`() {
        val store = ModelStore(root, spec)
        store.dir.mkdirs()
        File(store.dir, "weights.onnx.part").writeBytes(ByteArray(2_000) { 7 })

        assertThrows(InstallException::class.java) { store.install(upstream()) }
        assertFalse(File(store.dir, "weights.onnx.part").exists())
        // The next attempt starts clean and succeeds.
        store.install(upstream())
        assertTrue(store.isInstalled())
    }

    @Test
    fun `a download larger than expected is cut off`() {
        val store = ModelStore(root, spec)
        assertThrows(InstallException::class.java) { store.install(upstream(weights = weights + ByteArray(10))) }
        assertFalse(store.isInstalled())
    }

    @Test
    fun `a truncated download is rejected`() {
        val store = ModelStore(root, spec)
        assertThrows(InstallException::class.java) {
            store.install(upstream(weights = weights.copyOfRange(0, weights.size - 1)))
        }
        assertFalse(store.isInstalled())
    }

    @Test
    fun `a file with the wrong size does not count as installed`() {
        val store = ModelStore(root, spec)
        store.install(upstream())
        store.file("tokens.txt").appendBytes(byteArrayOf(1))
        assertFalse(store.isInstalled())
    }

    @Test
    fun `the shipped model pins a revision and four files`() {
        val model = PARAKEET_TDT_V3_INT8
        assertEquals(4, model.files.size)
        assertTrue(model.baseUrl.matches(Regex("https://huggingface\\.co/.+/resolve/[0-9a-f]{40}")))
        assertTrue(model.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size > 0 })
        assertEquals(670_576_784L, model.totalSize)
        assertEquals("${model.baseUrl}/tokens.txt", model.url(model.files.last()))
    }
}
