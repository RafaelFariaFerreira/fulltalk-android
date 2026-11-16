package dev.fulltalk.app.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * The floating microphone: grey while idle, violet while it listens or
 * transcribes, amber when something went wrong. While it transcribes the
 * microphone gives way to an X: tapping it then cancels.
 */
class BubbleView(context: Context) : View(context) {
    enum class Mode { IDLE, RECORDING, BUSY, PREPARING, ALERT }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Live microphone level (0–1), read on every frame while recording. */
    var level: () -> Float = { 0f }

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = WHITE
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = RADIUS_DP * dp
        val seconds = SystemClock.uptimeMillis() / 1000f

        fill.color = SHADOW
        canvas.drawCircle(cx, cy + 2 * dp, radius + dp, fill)

        if (mode == Mode.RECORDING) {
            // A halo that breathes with the voice.
            val breathe = sin(seconds * 3.2f) * 0.5f + 0.5f
            fill.color = HALO
            canvas.drawCircle(cx, cy, radius + (3f + 5f * level() + 2f * breathe) * dp, fill)
        }

        fill.color = when (mode) {
            Mode.ALERT -> AMBER
            Mode.RECORDING, Mode.BUSY -> VIOLET
            Mode.IDLE, Mode.PREPARING -> GREY
        }
        canvas.drawCircle(cx, cy, radius, fill)

        line.strokeWidth = 2.2f * dp
        when (mode) {
            Mode.ALERT -> drawWarning(canvas, cx, cy)
            Mode.BUSY -> drawCancel(canvas, cx, cy)
            else -> drawMicrophone(canvas, cx, cy)
        }

        // Progress rides just inside the rim, in white so it shows on any fill.
        if (mode == Mode.BUSY || mode == Mode.PREPARING) {
            val rim = radius - 4 * dp
            box.set(cx - rim, cy - rim, cx + rim, cy + rim)
            line.strokeWidth = 2.5f * dp
            val turnsPerSecond = if (mode == Mode.BUSY) 1.4f else 0.7f
            canvas.drawArc(box, seconds * turnsPerSecond * 360f % 360f, 90f, false, line)
        }

        if (mode == Mode.RECORDING || mode == Mode.BUSY || mode == Mode.PREPARING) postInvalidateOnAnimation()
    }

    private fun drawMicrophone(canvas: Canvas, cx: Float, cy: Float) {
        box.set(cx - 4.5f * dp, cy - 11f * dp, cx + 4.5f * dp, cy + 3.5f * dp)
        canvas.drawRoundRect(box, 4.5f * dp, 4.5f * dp, line)
        box.set(cx - 8.5f * dp, cy - 9.5f * dp, cx + 8.5f * dp, cy + 7.5f * dp)
        canvas.drawArc(box, 0f, 180f, false, line)
        canvas.drawLine(cx, cy + 7.5f * dp, cx, cy + 11.5f * dp, line)
    }

    private fun drawCancel(canvas: Canvas, cx: Float, cy: Float) {
        val arm = 6.5f * dp
        line.strokeWidth = 2.6f * dp
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, line)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, line)
    }

    private fun drawWarning(canvas: Canvas, cx: Float, cy: Float) {
        line.strokeWidth = 3f * dp
        canvas.drawLine(cx, cy - 9f * dp, cx, cy + 2f * dp, line)
        canvas.drawPoint(cx, cy + 8.5f * dp, line)
    }

    companion object {
        /** Side of the bubble's window: the circle plus room for its halo and shadow. */
        const val WINDOW_DP = 72
        private const val RADIUS_DP = 26f

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFF7D7C8E.toInt()
        private const val VIOLET = 0xFF5B3FE4.toInt()
        private const val AMBER = 0xFFC26B00.toInt()
        private const val HALO = 0x595B3FE4
        private const val SHADOW = 0x38000000
    }
}
