package dev.fulltalk.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** One file of a speech model, pinned by size and SHA-256. */
data class ModelFile(val name: String, val size: Long, val sha256: String)

/** A speech model: where it lives upstream and exactly which bytes are expected. */
data class ModelSpec(val name: String, val baseUrl: String, val files: List<ModelFile>) {
    val totalSize: Long get() = files.sumOf { it.size }

    fun url(file: ModelFile): String = "$baseUrl/${file.name}"
}

/**
 * NVIDIA Parakeet TDT 0.6B v3, int8 ONNX export packaged for sherpa-onnx.
 * The URL pins an upstream revision, so the checksums can never drift.
 */
val PARAKEET_TDT_V3_INT8 = ModelSpec(
    name = "parakeet-tdt-0.6b-v3-int8",
    baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/" +
        "ed5d745623472f97cfc2e6c2916ee248798fc5a8",
    files = listOf(
        ModelFile("encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
        ModelFile("decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
        ModelFile("joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
        ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    ),
)

/** Downloads bytes. The network sits behind this so the installer is testable offline. */
fun interface Fetcher {
    /** Streams [url] from byte [offset] on, handing each chunk to [sink] as `(buffer, length)`. */
    fun fetch(url: String, offset: Long, sink: (ByteArray, Int) -> Unit)
}

class InstallException(message: String) : IOException(message)

/** A model on disk: knows whether it is complete and how to complete it. */
class ModelStore(root: File, val spec: ModelSpec) {
    val dir: File = File(root, spec.name)

    fun file(name: String): File = File(dir, name)

    /** Whether every file is present with its expected size (contents were verified at install time). */
    fun isInstalled(): Boolean = spec.files.all(::isPresent)

    /**
     * Downloads whatever is missing. A partial file from an interrupted run is
     * resumed; each file is verified against its size and SHA-256 before it is
     * given its final name, so a half-written or tampered file is never used.
     */
    fun install(fetcher: Fetcher, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        if (!dir.isDirectory && !dir.mkdirs()) throw InstallException("não foi possível criar ${dir.path}")
        val total = spec.totalSize
        var done = spec.files.filter(::isPresent).sumOf { it.size }
        onProgress(done, total)

        for (file in spec.files.filterNot(::isPresent)) {
            val part = File(dir, "${file.name}.part")
            if (part.length() >= file.size) part.delete() // not a prefix worth keeping
            val offset = part.length()
            done += offset
            var written = offset
            FileOutputStream(part, true).use { out ->
                fetcher.fetch(spec.url(file), offset) { buffer, length ->
                    written += length
                    if (written > file.size) throw InstallException("${file.name} veio maior do que o esperado")
                    out.write(buffer, 0, length)
                    done += length
                    onProgress(done, total)
                }
            }
            if (part.length() != file.size || sha256(part) != file.sha256) {
                part.delete()
                throw InstallException("${file.name} chegou corrompido; tente de novo")
            }
            if (!part.renameTo(file(file.name))) throw InstallException("não foi possível gravar ${file.name}")
        }
    }

    private fun isPresent(file: ModelFile): Boolean = file(file.name).let { it.isFile && it.length() == file.size }
}

/** SHA-256 of a file as lowercase hex. */
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
