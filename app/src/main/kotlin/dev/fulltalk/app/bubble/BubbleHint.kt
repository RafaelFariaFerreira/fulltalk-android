package dev.fulltalk.app.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A short message shown beside the bubble ("Texto colado", "Nada foi captado").
 *
 * Android drops toasts from apps that are in the background without the
 * notification permission, and the bubble always is. So the message gets a
 * small window of its own, which never takes touches or focus.
 */
class BubbleHint(private val context: Context, private val windows: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private val dp = context.resources.displayMetrics.density
    private var shown: View? = null
    private val dismiss = Runnable { hide() }

    /** Shows [message] next to the bubble whose window is described by [bubble]. */
    fun show(message: String, bubble: WindowManager.LayoutParams) {
        hide()
        val screen = context.resources.displayMetrics.widthPixels
        val gap = (GAP_DP * dp).roundToInt()
        val bubbleOnTheRight = bubble.x + bubble.width / 2 > screen / 2
        val room = if (bubbleOnTheRight) bubble.x - 2 * gap else screen - bubble.x - bubble.width - 2 * gap

        val text = TextView(context).apply {
            this.text = message
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxWidth = room.coerceAtLeast((MIN_WIDTH_DP * dp).roundToInt())
            val horizontal = (14 * dp).roundToInt()
            val vertical = (9 * dp).roundToInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = 14 * dp
            }
        }
        text.measure(
            View.MeasureSpec.makeMeasureSpec(text.maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        val layout = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = if (bubbleOnTheRight) bubble.x - gap - text.measuredWidth else bubble.x + bubble.width + gap
            y = bubble.y + (bubble.height - text.measuredHeight) / 2
        }
        windows.addView(text, layout)
        shown = text
        main.postDelayed(dismiss, VISIBLE_MILLIS)
    }

    fun hide() {
        main.removeCallbacks(dismiss)
        shown?.let { windows.removeView(it) }
        shown = null
    }

    private companion object {
        const val VISIBLE_MILLIS = 2_600L
        const val GAP_DP = 6
        const val MIN_WIDTH_DP = 120
        const val TEXT = 0xFFFFFFFF.toInt()
        const val BACKGROUND = 0xF01B1A2E.toInt()
    }
}
