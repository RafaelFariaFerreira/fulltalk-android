package dev.fulltalk.app.data

import android.content.Context
import androidx.core.content.edit
import dev.fulltalk.core.History
import dev.fulltalk.core.HistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Settings and the recent transcriptions, kept in the app's private storage. */
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("fulltalk", Context.MODE_PRIVATE)

    private val _autoPaste = MutableStateFlow(prefs.getBoolean(AUTO_PASTE, true))
    val autoPaste: StateFlow<Boolean> = _autoPaste.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<History> = _history.asStateFlow()

    fun setAutoPaste(enabled: Boolean) {
        _autoPaste.value = enabled
        prefs.edit { putBoolean(AUTO_PASTE, enabled) }
    }

    /** Puts a transcription on top of the history. */
    fun remember(text: String) {
        val updated = _history.value.push(text, System.currentTimeMillis() / 1000)
        _history.value = updated
        val json = JSONArray()
        for (entry in updated.entries) {
            json.put(JSONObject().put("text", entry.text).put("created_at", entry.createdAt))
        }
        prefs.edit { putString(HISTORY, json.toString()) }
    }

    /** Where the user left the bubble, as the top-left corner in pixels. */
    var bubblePosition: Pair<Int, Int>?
        get() = if (prefs.contains(BUBBLE_X)) prefs.getInt(BUBBLE_X, 0) to prefs.getInt(BUBBLE_Y, 0) else null
        set(value) = prefs.edit {
            if (value == null) {
                remove(BUBBLE_X).remove(BUBBLE_Y)
            } else {
                putInt(BUBBLE_X, value.first).putInt(BUBBLE_Y, value.second)
            }
        }

    private fun loadHistory(): History {
        val saved = prefs.getString(HISTORY, null) ?: return History()
        return try {
            val json = JSONArray(saved)
            History().restored(
                List(json.length()) { i ->
                    val item = json.getJSONObject(i)
                    HistoryEntry(item.getString("text"), item.optLong("created_at"))
                },
            )
        } catch (_: JSONException) {
            History()
        }
    }

    private companion object {
        const val AUTO_PASTE = "auto_paste"
        const val HISTORY = "history"
        const val BUBBLE_X = "bubble_x"
        const val BUBBLE_Y = "bubble_y"
    }
}
