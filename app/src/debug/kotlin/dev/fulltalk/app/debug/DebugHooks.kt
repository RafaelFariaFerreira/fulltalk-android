package dev.fulltalk.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.fulltalk.app.bubble.BubbleService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: lets an automated test dictate a WAV file.
 *
 * Emulators rarely have a usable microphone. After
 *
 * ```sh
 * adb shell am broadcast -a dev.fulltalk.app.debug.DICTATE --es path /absolute/file.wav
 * ```
 *
 * the next recording made with the bubble uses that file (16 kHz, mono,
 * 16-bit PCM) instead of the microphone. Everything else is the real thing:
 * the touches, the state machine, the model and the paste.
 */
object DebugHooks {
    private const val ACTION = "dev.fulltalk.app.debug.DICTATE"
    private const val TAG = "fulltalk"
    private var receiver: BroadcastReceiver? = null

    fun install(service: BubbleService) {
        val added = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val path = intent.getStringExtra("path") ?: return
                runCatching { readWav(File(path)) }
                    .onSuccess {
                        service.injectedRecording = it
                        Log.i(TAG, "debug: the next recording will be $path (${it.size / 16_000f} s)")
                    }
                    .onFailure { Log.e(TAG, "debug: could not read $path", it) }
            }
        }
        ContextCompat.registerReceiver(service, added, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        receiver = added
    }

    fun uninstall(service: BubbleService) {
        receiver?.let(service::unregisterReceiver)
        receiver = null
    }

    /** Reads a canonical 16 kHz mono 16-bit PCM WAV into -1…1 samples. */
    private fun readWav(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.getInt(0) == 0x46464952 && bytes.getInt(8) == 0x45564157) { "not a WAV file" }
        var offset = 12
        var samples: FloatArray? = null
        while (offset + 8 <= bytes.capacity()) {
            val id = bytes.getInt(offset)
            val size = bytes.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                0x20746d66 -> { // "fmt "
                    val format = bytes.getShort(body).toInt()
                    val channels = bytes.getShort(body + 2).toInt()
                    val rate = bytes.getInt(body + 4)
                    val bits = bytes.getShort(body + 14).toInt()
                    require(format == 1 && channels == 1 && rate == 16_000 && bits == 16) {
                        "expected 16 kHz mono 16-bit PCM, got format $format, $channels ch, $rate Hz, $bits bit"
                    }
                }
                0x61746164 -> { // "data"
                    val count = minOf(size, bytes.capacity() - body) / 2
                    samples = FloatArray(count) { bytes.getShort(body + it * 2) / 32_768f }
                }
            }
            offset = body + size + (size and 1)
        }
        return requireNotNull(samples) { "no audio data in the file" }
    }
}
