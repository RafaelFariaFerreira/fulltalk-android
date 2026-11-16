package dev.fulltalk.app.bubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import dev.fulltalk.app.AppContainer
import dev.fulltalk.app.audio.Recorder
import dev.fulltalk.app.container
import dev.fulltalk.app.debug.DebugHooks
import dev.fulltalk.app.model.ModelState
import dev.fulltalk.app.ui.MainActivity
import dev.fulltalk.core.BubbleEvent
import dev.fulltalk.core.BubbleGesture
import dev.fulltalk.core.DeliveryPlan
import dev.fulltalk.core.Effect
import dev.fulltalk.core.FocusTarget
import dev.fulltalk.core.Input
import dev.fulltalk.core.Notice
import dev.fulltalk.core.Session
import dev.fulltalk.core.State
import dev.fulltalk.core.deliveryPlan
import dev.fulltalk.core.rejectionFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The floating microphone, as an accessibility service.
 *
 * Being one is what lets its window sit on top of every app and lets it find
 * the text field that has focus, to paste the transcription there. It does
 * not listen to accessibility events and reads nothing else from the screen.
 *
 * This class is glue: the rules live in [Session] and [BubbleGesture].
 */
class BubbleService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val session = Session()
    private val recorder = Recorder()
    private lateinit var app: AppContainer
    private lateinit var gesture: BubbleGesture
    private lateinit var windows: WindowManager

    private var bubble: BubbleView? = null
    private lateinit var hint: BubbleHint
    private val layout = WindowManager.LayoutParams()
    private var dragOrigin = 0 to 0
    private var loadingModel = false
    private var alertUntil = 0L

    /**
     * Which transcription the session is waiting for. A cancelled one keeps
     * running (the decoder cannot be interrupted), so its result is told apart
     * by this number and dropped.
     */
    @Volatile
    private var transcription = 0

    /**
     * A recording to use in place of the microphone, once. Only debug builds
     * ever set it (see `DebugHooks`), to test on emulators that cannot hear.
     */
    @Volatile
    internal var injectedRecording: FloatArray? = null

    private val holdCheck = Runnable { dispatch(gesture.holdElapsed(SystemClock.uptimeMillis())) }
    private val refresh = Runnable { render() }

    override fun onServiceConnected() {
        app = container
        gesture = BubbleGesture(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        windows = getSystemService(WindowManager::class.java)
        hint = BubbleHint(this, windows)
        showBubble()
        DebugHooks.install(this)
        scope.launch {
            app.model.state.collect { state ->
                if (state == ModelState.Ready) loadModel()
                render()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // focus is looked up on demand

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        moveTo(layout.x, layout.y) // keep it on screen after a rotation
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tearDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun tearDown() {
        if (bubble != null) DebugHooks.uninstall(this)
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        if (recorder.isRecording) recorder.stop()
        if (::hint.isInitialized) hint.hide()
        bubble?.let { windows.removeView(it) }
        bubble = null
        worker.shutdown()
    }

    // ---------------------------------------------------------------- bubble

    @SuppressLint("ClickableViewAccessibility") // a custom gesture surface, not a button
    private fun showBubble() {
        val size = (BubbleView.WINDOW_DP * resources.displayMetrics.density).roundToInt()
        layout.apply {
            width = size
            height = size
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            // Never focusable: the field the user is typing in keeps the focus and the keyboard.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
        }
        val (x, y) = app.preferences.bubblePosition ?: defaultPosition(size)
        layout.x = x
        layout.y = y
        clampToScreen()

        val view = BubbleView(this)
        view.level = { recorder.level }
        view.setOnTouchListener { _, event ->
            onTouch(event)
            true
        }
        windows.addView(view, layout)
        bubble = view
    }

    /** Top-right corner, below the status bar: where the desktop app puts it too. */
    private fun defaultPosition(size: Int): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val x = resources.displayMetrics.widthPixels - size - (MARGIN_DP * density).roundToInt()
        return x to (TOP_DP * density).roundToInt()
    }

    private fun clampToScreen() {
        val metrics = resources.displayMetrics
        layout.x = layout.x.coerceIn(0, maxOf(0, metrics.widthPixels - layout.width))
        layout.y = layout.y.coerceIn(0, maxOf(0, metrics.heightPixels - layout.height))
    }

    private fun moveTo(x: Int, y: Int) {
        hint.hide() // it would be left behind
        layout.x = x
        layout.y = y
        clampToScreen()
        bubble?.let { windows.updateViewLayout(it, layout) }
    }

    private fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragOrigin = layout.x to layout.y
                gesture.down(event.rawX, event.rawY, event.eventTime)
                main.postDelayed(holdCheck, BubbleGesture.HOLD_MILLIS)
            }
            MotionEvent.ACTION_MOVE -> {
                dispatch(gesture.move(event.rawX, event.rawY))
                if (!gesture.awaitingHold) main.removeCallbacks(holdCheck)
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(holdCheck)
                dispatch(gesture.up(event.eventTime))
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(holdCheck)
                dispatch(gesture.cancel())
            }
        }
    }

    private fun dispatch(event: BubbleEvent?) {
        when (event) {
            null -> return
            BubbleEvent.Tap -> if (canStart()) apply(Input.Tapped)
            BubbleEvent.HoldStarted -> if (canStart()) {
                bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                apply(Input.HoldStarted)
            }
            BubbleEvent.HoldReleased -> apply(Input.HoldReleased)
            BubbleEvent.DoubleTap -> {
                // The first tap of the pair has already toggled recording. If it
                // started one, drop it; if it stopped one, let that finish.
                apply(Input.Cancelled)
                openApp()
            }
            is BubbleEvent.Drag -> moveTo(dragOrigin.first + event.dx.roundToInt(), dragOrigin.second + event.dy.roundToInt())
            BubbleEvent.DragEnded -> app.preferences.bubblePosition = layout.x to layout.y
        }
    }

    /** Whether a new recording may start; says what is missing when it may not. */
    private fun canStart(): Boolean {
        if (session.state != State.Idle) return true // stopping is always allowed
        val missing = when {
            app.model.state.value != ModelState.Ready -> "Baixe o modelo de voz no app antes de ditar."
            !app.transcriber.isLoaded -> "Carregando o modelo de voz, só um instante."
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED -> "Permita o uso do microfone no app."
            else -> return true
        }
        say(missing)
        if (app.model.state.value != ModelState.Ready || !app.transcriber.isLoaded) loadModel() else openApp()
        return false
    }

    private fun render() {
        val now = SystemClock.uptimeMillis()
        bubble?.mode = when {
            now < alertUntil -> BubbleView.Mode.ALERT
            session.state is State.Recording -> BubbleView.Mode.RECORDING
            session.state == State.Transcribing -> BubbleView.Mode.BUSY
            loadingModel || app.model.state.value is ModelState.Downloading -> BubbleView.Mode.PREPARING
            else -> BubbleView.Mode.IDLE
        }
    }

    // --------------------------------------------------------------- session

    /** Feeds the state machine and runs the effects it asks for. */
    private fun apply(input: Input) {
        for (effect in session.handle(input)) {
            when (effect) {
                Effect.StartCapture -> startCapture()
                Effect.FinishCapture -> finishCapture()
                Effect.DiscardCapture -> recorder.stop()
                Effect.DiscardTranscription -> {
                    transcription++
                    say("Transcrição cancelada")
                }
                is Effect.Deliver -> deliver(effect.text)
                is Effect.Notify -> notify(effect.notice)
            }
        }
        render()
    }

    private fun startCapture() {
        if (injectedRecording != null) return // no microphone needed for this one
        try {
            recorder.start()
        } catch (error: IOException) {
            Log.w(TAG, "could not open the microphone", error)
            apply(Input.CaptureFailed(error.message ?: "microfone indisponível"))
        } catch (error: SecurityException) {
            Log.w(TAG, "microphone refused", error)
            apply(Input.CaptureFailed("o sistema não liberou o microfone"))
        }
    }

    private fun finishCapture() {
        val samples = injectedRecording?.also { injectedRecording = null } ?: recorder.stop()
        val rejected = rejectionFor(samples)
        if (rejected != null) {
            Log.i(TAG, "recording of ${samples.size / 16_000f} s rejected: $rejected")
            apply(Input.NothingCaptured)
            return
        }
        val id = ++transcription
        worker.execute {
            if (id != transcription) return@execute // cancelled while it waited its turn
            val started = SystemClock.elapsedRealtime()
            val result = runCatching { app.transcriber.transcribe(samples) }
            Log.i(TAG, "transcribed ${samples.size / 16_000f} s in ${SystemClock.elapsedRealtime() - started} ms")
            main.post {
                if (id != transcription) return@post // cancelled: the text is not wanted anymore
                result.fold(
                    onSuccess = { apply(Input.TranscriptionSucceeded(it)) },
                    onFailure = { apply(Input.TranscriptionFailed(it.message ?: "falha na transcrição")) },
                )
            }
        }
    }

    private fun loadModel() {
        if (loadingModel || app.transcriber.isLoaded || app.model.state.value != ModelState.Ready) return
        loadingModel = true
        render()
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            val result = runCatching { app.transcriber.load() }
            Log.i(TAG, "speech model loaded in ${SystemClock.elapsedRealtime() - started} ms")
            main.post {
                loadingModel = false
                result.onFailure {
                    Log.e(TAG, "could not load the speech model", it)
                    alert("Não foi possível carregar o modelo de voz.")
                }
                render()
            }
        }
    }

    // -------------------------------------------------------------- delivery

    /** Clipboard first, so the text is never lost; then paste where the cursor is. */
    private fun deliver(text: String) {
        app.preferences.remember(text)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("fulltalk dev", text))

        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        val target = if (field != null) FocusTarget.TEXT_INPUT else FocusTarget.OTHER
        val pasted = deliveryPlan(target, app.preferences.autoPaste.value) == DeliveryPlan.PASTE &&
            field != null &&
            (field.performAction(AccessibilityNodeInfo.ACTION_PASTE) || insert(field, text))
        Log.i(TAG, "delivered ${text.length} characters, ${if (pasted) "pasted" else "copied"}")
        say(if (pasted) "Texto colado" else "Texto copiado")
    }

    /** For fields that refuse a paste: rewrite their text with [text] at the cursor. */
    private fun insert(field: AccessibilityNodeInfo, text: String): Boolean {
        val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val start = field.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val end = field.textSelectionEnd.takeIf { it in start..current.length } ?: start
        val written = field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    current.replaceRange(start, end, text),
                )
            },
        )
        if (written) {
            val cursor = start + text.length
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
        }
        return written
    }

    private fun notify(notice: Notice) {
        when (notice) {
            Notice.NothingHeard -> say("Nada foi captado")
            is Notice.Error -> alert(notice.reason)
        }
    }

    /** Something went wrong: the bubble turns amber and the hint says why. */
    private fun alert(message: String) {
        alertUntil = SystemClock.uptimeMillis() + ALERT_MILLIS
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, ALERT_MILLIS)
        say(message)
        render()
    }

    private fun say(message: String) = hint.show(message, layout)

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val TAG = "fulltalk"
        const val MARGIN_DP = 12
        const val TOP_DP = 96
        const val ALERT_MILLIS = 4_000L
    }
}
