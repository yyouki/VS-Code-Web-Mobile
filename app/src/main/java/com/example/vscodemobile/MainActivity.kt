package com.example.vscodemobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.webkit.WebMessageCompat
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

private const val POINTER_STYLE_ARROW = 0
private const val POINTER_STYLE_HAND = 1
private const val POINTER_STYLE_TEXT = 2
private const val POINTER_STYLE_CROSSHAIR = 3
private const val POINTER_STYLE_MOVE = 4
private const val POINTER_STYLE_RESIZE_EW = 5
private const val POINTER_STYLE_RESIZE_NS = 6
private const val POINTER_STYLE_RESIZE_NWSE = 7
private const val POINTER_STYLE_RESIZE_NESW = 8
private const val POINTER_STYLE_FORBIDDEN = 9
private const val POINTER_STYLE_RING = 10
private const val POINTER_STYLE_DOT = 11

private const val ORIENTATION_FOLLOW_DEVICE = 0
private const val ORIENTATION_PORTRAIT = 1
private const val ORIENTATION_LANDSCAPE = 2

/**
 * Applies latched toolbar modifiers to Android IME text commits.
 * Most soft keyboards commit text rather than sending hardware-style key events.
 */
private class ModifierAwareWebView(
    context: Context,
    private val modifierProvider: () -> Int,
    private val onImeShortcut: (Int, Int) -> Unit,
    private val onModifierConsumed: () -> Unit
) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val modifiers = modifierProvider()
                if (modifiers == 0 || event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    return super.sendKeyEvent(event)
                }

                // Some IMEs send key events rather than committing a text character.
                val merged = KeyEvent(
                    event.downTime,
                    event.eventTime,
                    event.action,
                    event.keyCode,
                    event.repeatCount,
                    event.metaState or modifiers,
                    event.deviceId,
                    event.scanCode,
                    event.flags,
                    event.source
                )
                val handled = super.sendKeyEvent(merged)
                if (event.action == KeyEvent.ACTION_UP && event.isPrintingKey) {
                    this@ModifierAwareWebView.post { onModifierConsumed() }
                }
                return handled
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val modifiers = modifierProvider()
                val committed = text?.toString().orEmpty()
                val character = committed.singleOrNull()
                val mappedKey = if (modifiers != 0 && character != null) {
                    keyForCharacter(character)
                } else null

                if (mappedKey != null) {
                    // Consume the IME text so Ctrl+A does not insert a literal "A".
                    super.commitText("", 1)
                    this@ModifierAwareWebView.post {
                        onImeShortcut(mappedKey.first, modifiers or mappedKey.second)
                    }
                    return true
                }
                return super.commitText(text, newCursorPosition)
            }
        }
    }

    private fun keyForCharacter(character: Char): Pair<Int, Int>? {
        val shift = KeyEvent.META_SHIFT_ON
        if (character in 'a'..'z' || character in 'A'..'Z') {
            val code = KeyEvent.KEYCODE_A + (character.uppercaseChar() - 'A')
            // Gboard may auto-capitalize; do not turn Ctrl+A into Ctrl+Shift+A.
            return code to 0
        }
        if (character in '0'..'9') {
            return (KeyEvent.KEYCODE_0 + (character - '0')) to 0
        }
        return null
    }
}

/**
 * Touchpad-style pointer. One finger moves the cursor; a tap clicks;
 * two fingers scroll; a pinch changes page zoom.
 */
private class CursorOverlayView(
    context: Context,
    private val target: WebView,
    private val pinchZoomEnabled: () -> Boolean,
    private val longPressDragEnabled: () -> Boolean,
    private val onPinchZoom: (Float) -> Unit,
    private val onPinchZoomEnd: () -> Unit
) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointerPath = Path()
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTwoFingerX = 0f
    private var lastTwoFingerY = 0f
    private var twoFingerGesture = false
    private var cursorMoved = false
    private var pointerDown = false
    private var dragSelecting = false
    private var initialized = false
    private var pointerDownTime = 0L
    private var speed = 1.35f
    private var pointerSizeDp = 28
    private var pointerStyle = POINTER_STYLE_ARROW

    private val startDragSelection = Runnable {
        if (pointerDown && !cursorMoved && !twoFingerGesture && longPressDragEnabled()) {
            dragSelecting = true
            dispatchMouseEvent(MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis())
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                removeCallbacks(startDragSelection)
                if (dragSelecting) {
                    dispatchMouseEvent(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
                    dragSelecting = false
                }
                twoFingerGesture = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (pinchZoomEnabled()) {
                    val factor = detector.scaleFactor
                    if (factor.isFinite() && factor > 0f && kotlin.math.abs(factor - 1f) > 0.001f) {
                        onPinchZoom(factor)
                    }
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pinchZoomEnabled()) onPinchZoomEnd()
            }
        }
    )

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun updateSettings(pointerSize: Int, pointerSpeed: Float, color: Int, style: Int) {
        pointerSizeDp = pointerSize.coerceIn(6, 48)
        speed = pointerSpeed.coerceIn(0.5f, 3.0f)
        fillPaint.color = color
        accentPaint.color = color
        pointerStyle = style.coerceIn(POINTER_STYLE_ARROW, POINTER_STYLE_DOT)
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (!initialized && width > 0 && height > 0) {
            cursorX = width / 2f
            cursorY = height / 2f
            initialized = true
            sendHover()
        } else {
            cursorX = cursorX.coerceIn(0f, width.toFloat())
            cursorY = cursorY.coerceIn(0f, height.toFloat())
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = dp(pointerSizeDp)
        val left = cursorX
        val top = cursorY
        val centerX = left + size * 0.5f
        val centerY = top + size * 0.5f
        val thinStroke = resources.displayMetrics.density * 1.6f
        val thickStroke = resources.displayMetrics.density * 3f

        when (pointerStyle) {
            POINTER_STYLE_ARROW -> {
                pointerPath.reset()
                pointerPath.moveTo(left, top)
                pointerPath.lineTo(left, top + size * 1.02f)
                pointerPath.lineTo(left + size * 0.25f, top + size * 0.78f)
                pointerPath.lineTo(left + size * 0.46f, top + size * 1.18f)
                pointerPath.lineTo(left + size * 0.63f, top + size * 1.08f)
                pointerPath.lineTo(left + size * 0.43f, top + size * 0.68f)
                pointerPath.lineTo(left + size * 0.82f, top + size * 0.68f)
                pointerPath.close()
                drawOutlinedPath(canvas, pointerPath)
            }
            POINTER_STYLE_HAND -> {
                pointerPath.reset()
                pointerPath.moveTo(left + size * 0.36f, top + size * 1.02f)
                pointerPath.lineTo(left + size * 0.28f, top + size * 0.70f)
                pointerPath.quadTo(left + size * 0.25f, top + size * 0.60f, left + size * 0.35f, top + size * 0.58f)
                pointerPath.quadTo(left + size * 0.42f, top + size * 0.58f, left + size * 0.46f, top + size * 0.68f)
                pointerPath.lineTo(left + size * 0.46f, top + size * 0.22f)
                pointerPath.quadTo(left + size * 0.48f, top + size * 0.08f, left + size * 0.59f, top + size * 0.16f)
                pointerPath.lineTo(left + size * 0.60f, top + size * 0.59f)
                pointerPath.lineTo(left + size * 0.66f, top + size * 0.40f)
                pointerPath.quadTo(left + size * 0.76f, top + size * 0.34f, left + size * 0.79f, top + size * 0.48f)
                pointerPath.lineTo(left + size * 0.75f, top + size * 0.66f)
                pointerPath.lineTo(left + size * 0.82f, top + size * 0.60f)
                pointerPath.quadTo(left + size * 0.94f, top + size * 0.60f, left + size * 0.91f, top + size * 0.74f)
                pointerPath.lineTo(left + size * 0.76f, top + size * 1.02f)
                pointerPath.close()
                drawOutlinedPath(canvas, pointerPath)
            }
            POINTER_STYLE_TEXT -> {
                outlinePaint.strokeWidth = thickStroke
                canvas.drawLine(centerX, top + size * 0.08f, centerX, top + size * 0.92f, outlinePaint)
                canvas.drawLine(left + size * 0.22f, top + size * 0.08f, left + size * 0.78f, top + size * 0.08f, outlinePaint)
                canvas.drawLine(left + size * 0.22f, top + size * 0.92f, left + size * 0.78f, top + size * 0.92f, outlinePaint)
                accentPaint.strokeWidth = thinStroke
                canvas.drawLine(centerX, top + size * 0.08f, centerX, top + size * 0.92f, accentPaint)
                canvas.drawLine(left + size * 0.22f, top + size * 0.08f, left + size * 0.78f, top + size * 0.08f, accentPaint)
                canvas.drawLine(left + size * 0.22f, top + size * 0.92f, left + size * 0.78f, top + size * 0.92f, accentPaint)
                outlinePaint.strokeWidth = thinStroke
            }
            POINTER_STYLE_CROSSHAIR -> {
                outlinePaint.strokeWidth = thickStroke
                canvas.drawLine(centerX - size * 0.48f, centerY, centerX + size * 0.48f, centerY, outlinePaint)
                canvas.drawLine(centerX, centerY - size * 0.48f, centerX, centerY + size * 0.48f, outlinePaint)
                canvas.drawCircle(centerX, centerY, size * 0.13f, outlinePaint)
                accentPaint.strokeWidth = thinStroke
                canvas.drawLine(centerX - size * 0.48f, centerY, centerX + size * 0.48f, centerY, accentPaint)
                canvas.drawLine(centerX, centerY - size * 0.48f, centerX, centerY + size * 0.48f, accentPaint)
                canvas.drawCircle(centerX, centerY, size * 0.13f, accentPaint)
                outlinePaint.strokeWidth = thinStroke
            }
            POINTER_STYLE_MOVE -> {
                outlinePaint.strokeWidth = thickStroke
                canvas.drawLine(left + size * 0.5f, top + size * 0.08f, left + size * 0.5f, top + size * 0.92f, outlinePaint)
                canvas.drawLine(left + size * 0.08f, top + size * 0.5f, left + size * 0.92f, top + size * 0.5f, outlinePaint)
                accentPaint.strokeWidth = thinStroke
                canvas.drawLine(left + size * 0.5f, top + size * 0.08f, left + size * 0.5f, top + size * 0.92f, accentPaint)
                canvas.drawLine(left + size * 0.08f, top + size * 0.5f, left + size * 0.92f, top + size * 0.5f, accentPaint)
                drawArrowHead(canvas, centerX, top + size * 0.08f, -Math.PI.toFloat() / 2f, size * 0.18f)
                drawArrowHead(canvas, centerX, top + size * 0.92f, Math.PI.toFloat() / 2f, size * 0.18f)
                drawArrowHead(canvas, left + size * 0.08f, centerY, Math.PI.toFloat(), size * 0.18f)
                drawArrowHead(canvas, left + size * 0.92f, centerY, 0f, size * 0.18f)
                canvas.drawCircle(centerX, centerY, size * 0.10f, fillPaint)
            }
            POINTER_STYLE_RESIZE_EW -> drawDoubleArrow(canvas, left + size * 0.08f, centerY, left + size * 0.92f, centerY, size)
            POINTER_STYLE_RESIZE_NS -> drawDoubleArrow(canvas, centerX, top + size * 0.08f, centerX, top + size * 0.92f, size)
            POINTER_STYLE_RESIZE_NWSE -> drawDoubleArrow(canvas, left + size * 0.12f, top + size * 0.12f, left + size * 0.88f, top + size * 0.88f, size)
            POINTER_STYLE_RESIZE_NESW -> drawDoubleArrow(canvas, left + size * 0.88f, top + size * 0.12f, left + size * 0.12f, top + size * 0.88f, size)
            POINTER_STYLE_FORBIDDEN -> {
                outlinePaint.strokeWidth = thickStroke
                canvas.drawCircle(centerX, centerY, size * 0.40f, outlinePaint)
                canvas.drawLine(centerX - size * 0.30f, centerY + size * 0.30f, centerX + size * 0.30f, centerY - size * 0.30f, outlinePaint)
                accentPaint.strokeWidth = thinStroke
                canvas.drawCircle(centerX, centerY, size * 0.40f, accentPaint)
                canvas.drawLine(centerX - size * 0.30f, centerY + size * 0.30f, centerX + size * 0.30f, centerY - size * 0.30f, accentPaint)
                outlinePaint.strokeWidth = thinStroke
            }
            POINTER_STYLE_RING -> {
                outlinePaint.strokeWidth = thickStroke
                canvas.drawCircle(centerX, centerY, size * 0.42f, outlinePaint)
                accentPaint.strokeWidth = thinStroke
                canvas.drawCircle(centerX, centerY, size * 0.42f, accentPaint)
                canvas.drawCircle(centerX, centerY, size * 0.07f, fillPaint)
                outlinePaint.strokeWidth = thinStroke
            }
            else -> {
                canvas.drawCircle(centerX, centerY, size * 0.24f, outlinePaint)
                canvas.drawCircle(centerX, centerY, size * 0.16f, fillPaint)
            }
        }
        outlinePaint.strokeWidth = thinStroke
    }

    private fun drawOutlinedPath(canvas: Canvas, path: Path) {
        canvas.drawPath(path, outlinePaint)
        canvas.drawPath(path, fillPaint)
    }

    private fun drawDoubleArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, size: Float) {
        outlinePaint.strokeWidth = dp(3)
        canvas.drawLine(x1, y1, x2, y2, outlinePaint)
        accentPaint.strokeWidth = dp(1)
        canvas.drawLine(x1, y1, x2, y2, accentPaint)
        val angle = kotlin.math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()).toFloat()
        drawArrowHead(canvas, x1, y1, angle + Math.PI.toFloat(), size * 0.20f)
        drawArrowHead(canvas, x2, y2, angle, size * 0.20f)
        outlinePaint.strokeWidth = resources.displayMetrics.density * 1.6f
    }

    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, angle: Float, length: Float) {
        val wing = length * 0.72f
        pointerPath.reset()
        pointerPath.moveTo(x, y)
        pointerPath.lineTo(
            x - length * kotlin.math.cos(angle.toDouble()).toFloat() + wing * kotlin.math.sin(angle.toDouble()).toFloat(),
            y - length * kotlin.math.sin(angle.toDouble()).toFloat() - wing * kotlin.math.cos(angle.toDouble()).toFloat()
        )
        pointerPath.lineTo(
            x - length * kotlin.math.cos(angle.toDouble()).toFloat() - wing * kotlin.math.sin(angle.toDouble()).toFloat(),
            y - length * kotlin.math.sin(angle.toDouble()).toFloat() + wing * kotlin.math.cos(angle.toDouble()).toFloat()
        )
        pointerPath.close()
        canvas.drawPath(pointerPath, outlinePaint)
        canvas.drawPath(pointerPath, fillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pinchZoomEnabled()) scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDown = true
                pointerDownTime = event.eventTime
                lastTouchX = event.x
                lastTouchY = event.y
                cursorMoved = false
                twoFingerGesture = false
                dragSelecting = false
                removeCallbacks(startDragSelection)
                if (longPressDragEnabled()) {
                    postDelayed(startDragSelection, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(startDragSelection)
                if (dragSelecting) {
                    dispatchMouseEvent(MotionEvent.ACTION_UP, event.eventTime)
                    dragSelecting = false
                }
                if (event.pointerCount >= 2) {
                    twoFingerGesture = true
                    lastTwoFingerX = averagePointerX(event)
                    lastTwoFingerY = averagePointerY(event)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    removeCallbacks(startDragSelection)
                    val nextX = averagePointerX(event)
                    val nextY = averagePointerY(event)
                    if (!scaleDetector.isInProgress) {
                        val deltaX = nextX - lastTwoFingerX
                        val deltaY = nextY - lastTwoFingerY
                        if (kotlin.math.abs(deltaX) >= dp(2) || kotlin.math.abs(deltaY) >= dp(2)) {
                            sendWheel(deltaX, deltaY)
                        }
                    }
                    lastTwoFingerX = nextX
                    lastTwoFingerY = nextY
                } else if (!twoFingerGesture) {
                    val rawDx = event.x - lastTouchX
                    val rawDy = event.y - lastTouchY
                    if (kotlin.math.abs(rawDx) > ViewConfiguration.get(context).scaledTouchSlop ||
                        kotlin.math.abs(rawDy) > ViewConfiguration.get(context).scaledTouchSlop
                    ) {
                        cursorMoved = true
                        if (!dragSelecting) removeCallbacks(startDragSelection)
                    }

                    val dx = rawDx * speed
                    val dy = rawDy * speed
                    cursorX = (cursorX + dx).coerceIn(0f, width.toFloat())
                    cursorY = (cursorY + dy).coerceIn(0f, height.toFloat())
                    lastTouchX = event.x
                    lastTouchY = event.y
                    if (dragSelecting) {
                        dispatchMouseEvent(MotionEvent.ACTION_MOVE, event.eventTime)
                    } else {
                        sendHover(event.eventTime)
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(startDragSelection)
                if (dragSelecting) {
                    dispatchMouseEvent(MotionEvent.ACTION_UP, event.eventTime)
                    dragSelecting = false
                } else if (!twoFingerGesture && !cursorMoved) {
                    sendClick(event.eventTime)
                    performClick()
                }
                pointerDown = false
                twoFingerGesture = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startDragSelection)
                if (dragSelecting) {
                    dispatchMouseEvent(MotionEvent.ACTION_UP, event.eventTime)
                    dragSelecting = false
                }
                pointerDown = false
                twoFingerGesture = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun averagePointerX(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getX(index)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun averagePointerY(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getY(index)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun sendHover(eventTime: Long = SystemClock.uptimeMillis()) {
        val hover = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0)
        hover.source = InputDevice.SOURCE_MOUSE
        target.dispatchGenericMotionEvent(hover)
        hover.recycle()
    }

    private fun dispatchMouseEvent(action: Int, eventTime: Long) {
        val downTime = if (action == MotionEvent.ACTION_DOWN) eventTime else pointerDownTime
        val mouseEvent = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            cursorX,
            cursorY,
            0
        )
        mouseEvent.source = InputDevice.SOURCE_MOUSE
        mouseEvent.setButtonState(
            if (action == MotionEvent.ACTION_UP) 0 else MotionEvent.BUTTON_PRIMARY
        )
        target.dispatchTouchEvent(mouseEvent)
        mouseEvent.recycle()
    }

    private fun sendWheel(deltaX: Float, deltaY: Float) {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val pageScale = target.scale.takeIf { it > 0f } ?: 1f
        val cssX = cursorX / density / pageScale
        val cssY = cursorY / density / pageScale
        val cssDeltaX = -deltaX / density / pageScale
        val cssDeltaY = -deltaY / density / pageScale
        val script = "(() => { const point = document.elementFromPoint(" +
            cssX + ", " + cssY + ") || document; point.dispatchEvent(new WheelEvent('wheel', {" +
            "bubbles: true, cancelable: true, deltaX: " + cssDeltaX +
            ", deltaY: " + cssDeltaY +
            ", deltaMode: WheelEvent.DOM_DELTA_PIXEL})); })();"
        target.evaluateJavascript(script, null)
    }

    private fun sendClick(eventTime: Long) {
        pointerDownTime = eventTime
        sendHover(eventTime)
        dispatchMouseEvent(MotionEvent.ACTION_DOWN, eventTime)
        dispatchMouseEvent(MotionEvent.ACTION_UP, eventTime + 1)
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
class MainActivity : Activity() {
    private lateinit var editor: ModifierAwareWebView
    private lateinit var editorContainer: FrameLayout
    private lateinit var cursorOverlay: CursorOverlayView
    private lateinit var pageScaleDetector: ScaleGestureDetector
    private lateinit var keyboardBar: HorizontalScrollView
    private lateinit var loadingProgressBar: ProgressBar
    private val modifierButtons = mutableMapOf<Int, Button>()
    private var activeModifiers = 0
    private var editorTouchStartX = 0f
    private var editorTouchStartY = 0f
    private var editorGestureMoved = false
    private var editorTouchSlop = 8f
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private data class PendingPicker(
        val requestId: String,
        val replyProxy: JavaScriptReplyProxy
    )

    private var pendingBridgePicker: PendingPicker? = null
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var selectedTreeUri: Uri? = null
    private var authPopupDialog: AlertDialog? = null
    private var authPopupWebView: WebView? = null
    private var originalUserAgent: String? = null
    private var desktopMode = false
    private var pageZoom = 1.0f
    private var appliedPageZoom = 1.0f
    private var keyBarVisible = true
    private var cursorModeEnabled = false
    private var cursorSpeed = 1.35f
    private var cursorSizeDp = 28
    private var cursorColor = Color.WHITE
    private var cursorStyle = POINTER_STYLE_ARROW
    private var pinchZoomEnabled = true
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var orientationMode = ORIENTATION_FOLLOW_DEVICE
    private var openWebPagesInApp = true
    private var cursorLongPressDragEnabled = true
    private var pendingTerminalOnLoad = false
    private var authPopupContainer: View? = null
    private var zoomFrameScheduled = false
    private var zoomLabelButton: Button? = null
    private var desktopModeButton: Button? = null
    private var cursorModeButton: Button? = null
    private var headerCursorButton: Button? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow content into display cutouts while keeping interactive controls in safe insets.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
        }
        enableImmersiveStatusBar()
        registerSystemBackCallback()

        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        selectedTreeUri = preferences.getString(KEY_TREE_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        desktopMode = preferences.getBoolean(KEY_DESKTOP_MODE, false)
        pageZoom = preferences.getFloat(KEY_PAGE_ZOOM, 1.0f)
            .takeIf { it.isFinite() && it > 0f } ?: 1.0f
        cursorSpeed = preferences.getFloat(KEY_CURSOR_SPEED, 1.35f).coerceIn(0.5f, 3.0f)
        cursorSizeDp = preferences.getInt(KEY_CURSOR_SIZE, 28).coerceIn(6, 48)
        cursorColor = preferences.getInt(KEY_CURSOR_COLOR, Color.WHITE)
        cursorStyle = preferences.getInt(KEY_CURSOR_STYLE, POINTER_STYLE_ARROW)
            .coerceIn(POINTER_STYLE_ARROW, POINTER_STYLE_DOT)
        cursorModeEnabled = preferences.getBoolean(KEY_CURSOR_MODE_ENABLED, false)
        pinchZoomEnabled = preferences.getBoolean(KEY_PINCH_ZOOM_ENABLED, true)
        viewportWidth = preferences.getInt(KEY_VIEWPORT_WIDTH, 0)
        viewportHeight = preferences.getInt(KEY_VIEWPORT_HEIGHT, 0)
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            viewportWidth = 0
            viewportHeight = 0
        }
        orientationMode = preferences.getInt(KEY_ORIENTATION_MODE, ORIENTATION_FOLLOW_DEVICE)
            .coerceIn(ORIENTATION_FOLLOW_DEVICE, ORIENTATION_LANDSCAPE)
        openWebPagesInApp = preferences.getBoolean(KEY_OPEN_WEB_PAGES_IN_APP, true)
        cursorLongPressDragEnabled = preferences.getBoolean(KEY_CURSOR_LONG_PRESS_DRAG, true)
        applyRequestedOrientation()
        keyBarVisible = preferences.getBoolean(KEY_KEYBAR_VISIBLE, true)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editorTouchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        window.statusBarColor = Color.rgb(30, 30, 30)
        window.navigationBarColor = Color.rgb(30, 30, 30)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(30, 30, 30))

            // Respect the notch and navigation gesture area in edge-to-edge mode.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.navigationBars()
                )
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(
                    safeInsets.left,
                    safeInsets.top,
                    safeInsets.right,
                    maxOf(safeInsets.bottom, imeInsets.bottom)
                )
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(8), dp(3))
            setBackgroundColor(Color.rgb(37, 37, 38))
        }

        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }
        )

        header.addView(
            TextView(this).apply {
                text = "Code Web"
                textSize = 12f
                isSingleLine = true
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )

        header.addView(makeHeaderButton("Folder") { openFolderFromToolbar() })
        header.addView(makeHeaderButton("Remote") { showRemoteDialog() })
        headerCursorButton = makeHeaderButton(if (cursorModeEnabled) "Ptr ✓" else "Ptr") { toggleCursorMode() }.apply {
            setOnLongClickListener {
                showAppSettingsDialog()
                true
            }
        }
        header.addView(headerCursorButton)
        header.addView(makeHeaderButton("Keys") { toggleKeyboardBar() })
        header.addView(makeHeaderButton("Settings") { showAppSettingsDialog() })
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        )

        loadingProgressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            visibility = View.GONE
        }
        root.addView(
            loadingProgressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        )

        editor = ModifierAwareWebView(
            context = this,
            modifierProvider = { activeModifiers },
            onImeShortcut = { keyCode, modifiers ->
                activeModifiers = modifiers
                sendKey(keyCode)
            },
            onModifierConsumed = {
                activeModifiers = 0
                syncModifierButtons()
            }
        ).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(Color.rgb(30, 30, 30))
            isFocusable = true
            isFocusableInTouchMode = true

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true // SAF returns content:// URIs for selected Android files.
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            // Support viewport-aware zoomBy while hiding Android's built-in pinch/zoom controls.
            settings.setSupportZoom(true)
            // Toolbar and ScaleGestureDetector own zoom; hide WebView's own pinch/buttons.
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            // Keep desktop page width intact so it can be panned instead of auto-shrunk/clipped.
            settings.loadWithOverviewMode = false
            isHorizontalScrollBarEnabled = true
            isVerticalScrollBarEnabled = true
            pointerIcon = PointerIcon.getSystemIcon(this@MainActivity, PointerIcon.TYPE_ARROW)
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.textZoom = 100
            originalUserAgent = settings.userAgentString
            if (desktopMode) {
                settings.userAgentString = asDesktopUserAgent(originalUserAgent.orEmpty())
            }
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pageScaleDetector.onTouchEvent(event)
                        editorTouchStartX = event.x
                        editorTouchStartY = event.y
                        editorGestureMoved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        pageScaleDetector.onTouchEvent(event)
                        val deltaX = kotlin.math.abs(event.x - editorTouchStartX)
                        val deltaY = kotlin.math.abs(event.y - editorTouchStartY)
                        if (deltaX > editorTouchSlop || deltaY > editorTouchSlop) {
                            editorGestureMoved = true
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                        pageScaleDetector.onTouchEvent(event)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pageScaleDetector.onTouchEvent(event)
                        if (editorGestureMoved) {
                            // A swipe/scroll should dismiss the IME without blurring Monaco's editor.
                            view.postDelayed({ hideKeyboardAfterEditorScroll() }, 90)
                        }
                        editorGestureMoved = false
                    }
                }
                false // Preserve WebView taps, text selection, and scrolling behavior.
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    appliedPageZoom = 1.0f // The WebView page scale resets on full navigation.
                    loadingProgressBar.progress = 5
                    loadingProgressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loadingProgressBar.progress = 100
                    loadingProgressBar.visibility = View.GONE
                    super.onPageFinished(view, url)
                    val loadedUri = Uri.parse(url)
                    CookieManager.getInstance().flush()
                    applyPagePresentation(view)
                    if (loadedUri.scheme == "https" && loadedUri.host == "vscode.dev") {
                        restoreRememberedDirectory(view)
                    }
                    val loadedHost = loadedUri.host.orEmpty().lowercase()
                    if (pendingTerminalOnLoad &&
                        (loadedHost == "github.dev" || loadedHost.endsWith(".github.dev"))
                    ) {
                        pendingTerminalOnLoad = false
                        editor.postDelayed({ createIntegratedTerminal() }, 1800)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = handleExternalUrl(request.url)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadingProgressBar.progress = newProgress
                    loadingProgressBar.visibility =
                        if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message
                ): Boolean {
                    return showAuthenticationPopup(resultMsg)
                }

                override fun onCloseWindow(window: WebView) {
                    if (window === authPopupWebView) closeAuthenticationPopup()
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    pendingFileChooser?.onReceiveValue(null)
                    pendingFileChooser = filePathCallback

                    val intent = buildOpenFileIntent(
                        fileChooserParams.acceptTypes.toList(),
                        fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )

                    return try {
                        startActivityForResult(intent, REQUEST_UPLOAD_FILE)
                        true
                    } catch (_: Exception) {
                        pendingFileChooser = null
                        filePathCallback.onReceiveValue(null)
                        Toast.makeText(
                            this@MainActivity,
                            "Android file picker is unavailable.",
                            Toast.LENGTH_LONG
                        ).show()
                        false
                    }
                }
            }
        }

        pageScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (pinchZoomEnabled) queuePinchZoom(detector.scaleFactor)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (pinchZoomEnabled) persistPageZoom()
                }
            }
        )

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(editor, true)
        }

        editorContainer = FrameLayout(this)
        editorContainer.addView(
            editor,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        cursorOverlay = CursorOverlayView(
            this,
            editor,
            { pinchZoomEnabled },
            { cursorLongPressDragEnabled },
            ::queuePinchZoom,
            ::persistPageZoom
        ).apply {
            visibility = if (cursorModeEnabled) View.VISIBLE else View.GONE
            updateSettings(cursorSizeDp, cursorSpeed, cursorColor, cursorStyle)
        }
        editorContainer.addView(
            cursorOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            editorContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // All mobile keys live in one horizontally scrollable strip.
        keyboardBar = createKeyRow().apply {
            setBackgroundColor(Color.rgb(37, 37, 38))
            setPadding(dp(3), dp(2), dp(3), dp(2))
            visibility = if (keyBarVisible) View.VISIBLE else View.GONE
        }

        addControlButton(keyboardBar, "⌨") { showInternalKeyboard() }
        addControlButton(keyboardBar, "−") { changePageZoom(zoomIn = false) }
        zoomLabelButton = makeKeyButton("${(pageZoom * 100).toInt()}%").apply {
            setOnClickListener { resetPageZoom() }
        }
        rowContent(keyboardBar).addView(zoomLabelButton)
        addControlButton(keyboardBar, "+") { changePageZoom(zoomIn = true) }
        addControlButton(keyboardBar, "ALL") { selectAllEditorText() }
        addControlButton(keyboardBar, "TERM") { toggleIntegratedTerminal() }
        desktopModeButton = makeKeyButton(if (desktopMode) "Desktop ✓" else "Desktop").apply {
            setOnClickListener { toggleDesktopMode() }
        }
        rowContent(keyboardBar).addView(desktopModeButton)
        cursorModeButton = makeKeyButton(if (cursorModeEnabled) "Cursor ON" else "Cursor OFF").apply {
            setOnClickListener { toggleCursorMode() }
            setOnLongClickListener {
                showAppSettingsDialog()
                true
            }
        }
        rowContent(keyboardBar).addView(cursorModeButton)

        addModifierButton(keyboardBar, "CTRL", KeyEvent.META_CTRL_ON)
        addModifierButton(keyboardBar, "ALT", KeyEvent.META_ALT_ON)
        addModifierButton(keyboardBar, "SHIFT", KeyEvent.META_SHIFT_ON)
        addModifierButton(keyboardBar, "META", KeyEvent.META_META_ON)
        addKeyButton(keyboardBar, "ESC", KeyEvent.KEYCODE_ESCAPE)
        addKeyButton(keyboardBar, "TAB", KeyEvent.KEYCODE_TAB)
        addKeyButton(keyboardBar, "F1", KeyEvent.KEYCODE_F1)
        addKeyButton(keyboardBar, "F5", KeyEvent.KEYCODE_F5)
        addKeyButton(keyboardBar, "P", KeyEvent.KEYCODE_P)
        addKeyButton(keyboardBar, "A", KeyEvent.KEYCODE_A)
        addKeyButton(keyboardBar, "C", KeyEvent.KEYCODE_C)
        addKeyButton(keyboardBar, "V", KeyEvent.KEYCODE_V)
        addKeyButton(keyboardBar, "X", KeyEvent.KEYCODE_X)
        listOf("{", "}", "[", "]", "(", ")", ";", ":", "/", "\\", "|", "~", "_")
            .forEach { addSymbolButton(keyboardBar, it) }
        addKeyButton(keyboardBar, "←", KeyEvent.KEYCODE_DPAD_LEFT)
        addKeyButton(keyboardBar, "↓", KeyEvent.KEYCODE_DPAD_DOWN)
        addKeyButton(keyboardBar, "↑", KeyEvent.KEYCODE_DPAD_UP)
        addKeyButton(keyboardBar, "→", KeyEvent.KEYCODE_DPAD_RIGHT)
        addKeyButton(keyboardBar, "HOME", KeyEvent.KEYCODE_MOVE_HOME)
        addKeyButton(keyboardBar, "END", KeyEvent.KEYCODE_MOVE_END)
        addKeyButton(keyboardBar, "DEL", KeyEvent.KEYCODE_DEL)

        root.addView(
            keyboardBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        enableImmersiveStatusBar()
        installAndroidFileSystemBridge()
        val restoredWebView = savedInstanceState?.let { editor.restoreState(it) } != null
        if (!restoredWebView) {
            editor.loadUrl(VSCODE_WEB_URL)
        }
    }

    private fun showAuthenticationPopup(resultMsg: Message): Boolean {
        if (authPopupWebView != null) return false

        val popup = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString = editor.settings.userAgentString
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        val host = uri.host.orEmpty().lowercase()
                        if (host == "github.dev" || host.endsWith(".github.dev")) {
                            editor.loadUrl(uri.toString())
                            closeAuthenticationPopup()
                            return true
                        }
                        // Keep the provider and vscode.dev callback in this same popup
                        // so window.opener/postMessage can complete the VS Code auth flow.
                        return false
                    }
                    return handleExternalUrl(uri)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    closeAuthenticationPopup()
                }
            }
        }

        val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
            popup.destroy()
            return false
        }
        val popupRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setBackgroundColor(Color.rgb(37, 37, 38))
        }
        val title = TextView(this).apply {
            text = "VS Code sign-in"
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        toolbar.addView(title)
        val browserButton = makeHeaderButton("Browser") {
            val current = popup.url?.let(Uri::parse)
            if (current != null) openInDefaultBrowser(current)
        }
        toolbar.addView(browserButton)
        toolbar.addView(makeHeaderButton("Close") { closeAuthenticationPopup() })
        popupRoot.addView(
            toolbar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        )
        popupRoot.addView(
            popup,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        editorContainer.addView(
            popupRoot,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        popupRoot.bringToFront()
        transport.webView = popup
        authPopupWebView = popup
        authPopupContainer = popupRoot
        resultMsg.sendToTarget()
        return true
    }
    private fun closeAuthenticationPopup() {
        authPopupDialog?.dismiss()
        val popup = authPopupWebView
        authPopupWebView = null // Clear first so onCloseWindow cannot recursively destroy it.
        val popupRoot = authPopupContainer
        authPopupContainer = null
        if (popupRoot != null) {
            (popupRoot.parent as? ViewGroup)?.removeView(popupRoot)
        }
        if (popup != null) {
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.stopLoading()
            popup.destroy()
        }
    }

    private fun handleExternalUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val host = uri.host.orEmpty().lowercase()
            val isWorkspaceHost = host == "vscode.dev" ||
                host == "insiders.vscode.dev" ||
                host == "github.dev" ||
                host.endsWith(".github.dev")
            if (isWorkspaceHost || openWebPagesInApp) return false
            return openInDefaultBrowser(uri)
        }

        return when (scheme) {
            "mailto", "tel", "sms", "geo", "intent", "vscode" -> {
                try {
                    val intent = if (scheme == "intent") {
                        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, uri)
                    }.addCategory(Intent.CATEGORY_BROWSABLE)
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "No Android app is available to open this link.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            }
            else -> true
        }
    }

    private fun openInDefaultBrowser(uri: Uri): Boolean {
        return try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            )
            true
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No default browser is available to open this link.",
                Toast.LENGTH_LONG
            ).show()
            true
        }
    }

    private fun toggleKeyboardBar() {
        keyBarVisible = !keyBarVisible
        keyboardBar.visibility = if (keyBarVisible) View.VISIBLE else View.GONE
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEYBAR_VISIBLE, keyBarVisible)
            .apply()
    }

    private fun syncModifierButtons() {
        modifierButtons.forEach { (flag, button) ->
            val label = when (flag) {
                KeyEvent.META_CTRL_ON -> "CTRL"
                KeyEvent.META_ALT_ON -> "ALT"
                KeyEvent.META_SHIFT_ON -> "SHIFT"
                KeyEvent.META_META_ON -> "META"
                else -> button.text.toString().substringBefore(" ✓")
            }
            button.text = if ((activeModifiers and flag) != 0) "$label ✓" else label
        }
    }

    private fun applyModifierState(args: JSONObject) {
        activeModifiers = 0
        if (args.optBoolean("ctrl")) activeModifiers = activeModifiers or KeyEvent.META_CTRL_ON
        if (args.optBoolean("alt")) activeModifiers = activeModifiers or KeyEvent.META_ALT_ON
        if (args.optBoolean("shift")) activeModifiers = activeModifiers or KeyEvent.META_SHIFT_ON
        if (args.optBoolean("meta")) activeModifiers = activeModifiers or KeyEvent.META_META_ON
        runOnUiThread { syncModifierButtons() }
    }

    private fun changePageZoom(zoomIn: Boolean) {
        val factor = if (zoomIn) 1.10f else (1f / 1.10f)
        val nextZoom = pageZoom * factor
        if (!nextZoom.isFinite() || nextZoom <= 0f || nextZoom == pageZoom) return

        pageZoom = nextZoom
        persistPageZoom()
        applyNativePageZoom()
    }

    private fun resetPageZoom() {
        pageZoom = 1.0f
        persistPageZoom()
        applyNativePageZoom()
    }

    private fun zoomLabel(value: Float = pageZoom): String {
        if (!value.isFinite() || value <= 0f) return "Zoom"
        return if (value >= 100f || value < 0.01f) {
            String.format(java.util.Locale.US, "%.2g×", value.toDouble())
        } else {
            String.format(java.util.Locale.US, "%.0f%%", value * 100f)
        }
    }

    private fun persistPageZoom() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PAGE_ZOOM, pageZoom)
            .apply()
        zoomLabelButton?.text = zoomLabel()
    }

    /**
     * WebView.scale includes device density; appliedPageZoom tracks only the
     * page-scale factor requested by this wrapper.
     */
    private fun applyNativePageZoom(view: WebView = editor) {
        if (view.width <= 0 || view.height <= 0) return
        scheduleZoomFrame(view)
    }

    private fun queuePinchZoom(scaleFactor: Float) {
        if (!pinchZoomEnabled || !::editor.isInitialized || !scaleFactor.isFinite() || scaleFactor <= 0f) {
            return
        }
        val nextZoom = pageZoom * scaleFactor
        if (!nextZoom.isFinite() || nextZoom <= 0f || nextZoom == pageZoom) return
        pageZoom = nextZoom
        zoomLabelButton?.text = zoomLabel()
        scheduleZoomFrame(editor)
    }

    private fun scheduleZoomFrame(view: WebView) {
        if (zoomFrameScheduled) return
        zoomFrameScheduled = true
        view.postOnAnimation {
            zoomFrameScheduled = false
            applyNextZoomStep(view)
        }
    }

    private fun applyNextZoomStep(view: WebView) {
        if (view.width <= 0 || view.height <= 0) return
        val ratio = pageZoom / appliedPageZoom
        if (!ratio.isFinite() || ratio <= 0f) return
        if (kotlin.math.abs(kotlin.math.ln(ratio.toDouble())) < 0.001) {
            zoomLabelButton?.text = zoomLabel()
            return
        }

        // WebView.zoomBy accepts a Float. Apply very large/small cumulative
        // changes in safe per-frame steps without imposing an app zoom ceiling.
        val factor = ratio.coerceIn(0.25f, 4f)
        val changed = runCatching { view.zoomBy(factor) }.getOrDefault(false)
        if (changed) {
            appliedPageZoom *= factor
            if (!appliedPageZoom.isFinite() || appliedPageZoom <= 0f) {
                appliedPageZoom = pageZoom
            }
            zoomLabelButton?.text = zoomLabel()
            if (kotlin.math.abs(kotlin.math.ln((pageZoom / appliedPageZoom).toDouble())) >= 0.001) {
                scheduleZoomFrame(view)
            }
        } else {
            // The WebView renderer has reached its own supported scale limit.
            pageZoom = appliedPageZoom
            persistPageZoom()
        }
    }
    private fun applyPagePresentation(view: WebView) {
        val isDesktop = desktopMode
        val effectiveWidth = when {
            viewportWidth > 0 -> viewportWidth
            desktopMode -> 1280
            else -> 0
        }
        val effectiveHeight = when {
            viewportHeight > 0 -> viewportHeight
            desktopMode -> 720
            else -> 0
        }
        val viewport = if (effectiveWidth > 0 && effectiveHeight > 0) {
            "width=$effectiveWidth, height=$effectiveHeight, initial-scale=1.0, " +
                "user-scalable=yes"
        } else {
            "width=device-width, height=device-height, initial-scale=1.0, " +
                "user-scalable=yes"
        }
        val viewportJson = JSONObject.quote(viewport)
        val script = """
            (() => {
              let meta = document.querySelector('meta[name="viewport"]');
              if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                document.head.appendChild(meta);
              }
              meta.setAttribute('content', $viewportJson);
              // Remove CSS scaling so native WebView page zoom remains the single zoom source.
              document.documentElement.style.removeProperty('zoom');
              document.documentElement.style.removeProperty('transform-origin');
              if (document.body) {
                document.body.style.removeProperty('zoom');
                document.body.style.removeProperty('transform-origin');
              }
              window.dispatchEvent(new Event('resize'));
              window.__androidDesktopViewport = $isDesktop;
            })();
        """.trimIndent()
        view.evaluateJavascript(script) {
            applyNativePageZoom(view)
        }
        zoomLabelButton?.text = (pageZoom * 100).toInt().toString() + "%"
        desktopModeButton?.text = if (desktopMode) "Desktop ✓" else "Desktop"
    }

    private fun asDesktopUserAgent(userAgent: String): String {
        val chromeVersion = Regex("Chrome/([0-9.]+)")
            .find(userAgent)
            ?.groupValues
            ?.getOrNull(1)
            ?: "131.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DESKTOP_MODE, desktopMode)
            .apply()

        val userAgent = originalUserAgent ?: editor.settings.userAgentString
        editor.settings.userAgentString =
            if (desktopMode) asDesktopUserAgent(userAgent) else userAgent
        editor.settings.useWideViewPort = true
        // Desktop mode keeps a fixed-width canvas that the user can pan around.
        editor.settings.loadWithOverviewMode = false
        editor.settings.setSupportZoom(true)
        desktopModeButton?.text = if (desktopMode) "Desktop ✓" else "Desktop"

        val currentUrl = editor.url
            ?.takeIf { Uri.parse(it).scheme == "https" }
            ?: VSCODE_WEB_URL
        editor.loadUrl(currentUrl)
    }

    private fun showRemoteDialog() {
        AlertDialog.Builder(this)
            .setTitle("VS Code remote tools")
            .setItems(
                arrayOf(
                    "Accounts: Sign In (GitHub / Microsoft)",
                    "Settings Sync: Turn On",
                    "Open VS Code in system browser (sign-in fallback)",
                    "Open VS Code Command Palette",
                    "Toggle integrated terminal",
                    "Create new integrated terminal",
                    "Open Codespaces dashboard",
                    "Create / resume Codespace for a repository",
                    "Open existing Codespace by name",
                    "GitHub Copilot extension"
                )
            ) { _, which ->
                when (which) {
                    0 -> openVsCodeSignIn()
                    1 -> openSettingsSync()
                    2 -> openVsCodeInBrowserForSignIn()
                    3 -> openVsCodeRemoteCommandPalette()
                    4 -> toggleIntegratedTerminal()
                    5 -> createIntegratedTerminal()
                    6 -> openCodespaces()
                    7 -> promptCreateCodespace()
                    8 -> promptOpenCodespaceByName()
                    9 -> openCopilotExtension()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
    /**
     * Opens VS Code's built-in Accounts: Sign In action. The provider selection and
     * OAuth implementation remain owned by VS Code; provider URLs are handed to Android's browser.
     */
    private fun openVsCodeSignIn() {
        // F1 is also the documented Command Palette fallback in browser builds.
        activeModifiers = 0
        syncModifierButtons()
        sendKey(KeyEvent.KEYCODE_F1)
        editor.postDelayed({
            val query = """
                (() => {
                  const input = document.querySelector(
                    '.quick-input-widget input, .quick-input-widget .monaco-inputbox input'
                  );
                  if (!input) return false;
                  const descriptor = Object.getOwnPropertyDescriptor(
                    HTMLInputElement.prototype,
                    'value'
                  );
                  descriptor?.set?.call(input, 'Accounts: Sign In');
                  input.dispatchEvent(new InputEvent('input', {
                    bubbles: true, inputType: 'insertText', data: 'Accounts: Sign In'
                  }));
                  input.focus();
                  return true;
                })();
            """.trimIndent()
            editor.evaluateJavascript(query) { result ->
                if (result == "true") {
                    editor.postDelayed({ sendKey(KeyEvent.KEYCODE_ENTER) }, 350)
                    Toast.makeText(
                        this,
                        "Accounts: Sign In opened. Select GitHub or Microsoft in the VS Code picker.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Press F1 and search for Accounts: Sign In.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, 350)
    }

    private fun openSettingsSync() {
        runQuickPickCommand(
            "Settings Sync: Turn On",
            "Open Manage → Backup and Sync Settings, then choose GitHub or Microsoft."
        )
    }

    private fun openVsCodeInBrowserForSignIn() {
        val currentUrl = editor.url?.takeIf { Uri.parse(it).scheme == "https" } ?: VSCODE_WEB_URL
        openInDefaultBrowser(Uri.parse(currentUrl))
        Toast.makeText(
            this,
            "VS Code opened in the system browser. Sign-in completed there will stay in that browser session, not this WebView.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun runQuickPickCommand(query: String, fallbackMessage: String) {
        activeModifiers = 0
        syncModifierButtons()
        sendKey(KeyEvent.KEYCODE_F1)
        editor.postDelayed({
            val quotedQuery = JSONObject.quote(query)
            val script = """
                (() => {
                  const input = document.querySelector(
                    '.quick-input-widget input, .quick-input-widget .monaco-inputbox input'
                  );
                  if (!input) return false;
                  const descriptor = Object.getOwnPropertyDescriptor(
                    HTMLInputElement.prototype,
                    'value'
                  );
                  descriptor?.set?.call(input, $quotedQuery);
                  input.dispatchEvent(new InputEvent('input', {
                    bubbles: true, inputType: 'insertText', data: $quotedQuery
                  }));
                  input.focus();
                  return true;
                })();
            """.trimIndent()
            editor.evaluateJavascript(script) { result ->
                if (result == "true") {
                    editor.postDelayed({ sendKey(KeyEvent.KEYCODE_ENTER) }, 350)
                } else {
                    Toast.makeText(this, fallbackMessage, Toast.LENGTH_LONG).show()
                }
            }
        }, 300)
    }
    /**
     * Repository and tunnel navigation stays inside VS Code's own command system.
     * No repository URL or tunnel URL is assembled by the Android wrapper.
     */
    private fun openVsCodeRemoteCommandPalette() {
        sendKey(
            KeyEvent.KEYCODE_P,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        )
        Toast.makeText(
            this,
            "In VS Code, run GitHub Repositories: Open Repository... or Remote Tunnels: Connect to Tunnel.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openCodespaces() {
        editor.loadUrl(CODESPACES_URL)
    }

    private fun promptCreateCodespace() {
        val input = EditText(this).apply {
            hint = "owner/repository"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Create or resume Codespace")
            .setMessage("Enter a GitHub repository. GitHub will resume a matching Codespace or offer to create one.")
            .setView(container)
            .setPositiveButton("Continue") { _, _ ->
                val repository = input.text.toString().trim()
                if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repository)) {
                    Toast.makeText(this, "Use the format owner/repository.", Toast.LENGTH_LONG).show()
                } else {
                    pendingTerminalOnLoad = true
                    editor.loadUrl("https://codespaces.new/$repository?quickstart=1")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptOpenCodespaceByName() {
        val input = EditText(this).apply {
            hint = "permanent-codespace-name"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Open existing Codespace")
            .setMessage("Enter the permanent Codespace name from GitHub. This opens its VS Code web client and remote terminal.")
            .setView(container)
            .setPositiveButton("Open") { _, _ ->
                val name = input.text.toString().trim().lowercase()
                if (!Regex("^[a-z0-9][a-z0-9-]*$").matches(name)) {
                    Toast.makeText(this, "Enter the permanent Codespace name.", Toast.LENGTH_LONG).show()
                } else {
                    pendingTerminalOnLoad = true
                    editor.loadUrl("https://$name.github.dev")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCopilotExtension() {
        // Open VS Code's own Extensions view instead of leaving the editor for the Marketplace website.
        sendKey(
            KeyEvent.KEYCODE_X,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        )
        Toast.makeText(
            this,
            "Extensions opened. Search for GitHub Copilot and install it here.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun addControlButton(
        row: HorizontalScrollView,
        label: String,
        onClick: () -> Unit
    ) {
        val button = makeKeyButton(label)
        button.setOnClickListener { onClick() }
        rowContent(row).addView(button)
    }

    private fun showAppSettingsDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }

        fun addSection(title: String) {
            panel.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(12), 0, dp(4))
            })
        }

        val resolutions = listOf(
            Triple(0, 0, if (desktopMode) "Desktop default (1280 × 720)" else "Default device"),
            Triple(1280, 720, "1280 × 720"),
            Triple(1366, 768, "1366 × 768"),
            Triple(1600, 900, "1600 × 900"),
            Triple(1920, 1080, "1920 × 1080"),
            Triple(2560, 1440, "2560 × 1440")
        )

        addSection("Display viewport")
        val resolutionGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        resolutions.forEachIndexed { index, item ->
            resolutionGroup.addView(RadioButton(this).apply {
                id = index + 1
                text = item.third
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
        }
        val selectedResolution = resolutions.indexOfFirst {
            it.first == viewportWidth && it.second == viewportHeight
        }.takeIf { it >= 0 } ?: 0
        resolutionGroup.check(selectedResolution + 1)
        panel.addView(resolutionGroup)
        panel.addView(TextView(this).apply {
            text = "These profiles change the browser CSS viewport, not the phone panel's physical resolution."
            textSize = 12f
            setTextColor(Color.DKGRAY)
        })

        addSection("Orientation")
        val orientationGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            "Follow device rotation" to ORIENTATION_FOLLOW_DEVICE,
            "Portrait" to ORIENTATION_PORTRAIT,
            "Landscape" to ORIENTATION_LANDSCAPE
        ).forEach { choice ->
            orientationGroup.addView(RadioButton(this).apply {
                id = choice.second + 1
                text = choice.first
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
        }
        orientationGroup.check(orientationMode + 1)
        panel.addView(orientationGroup)

        addSection("Web pages")
        val webPagesSwitch = Switch(this).apply {
            text = "Open normal webpages inside this app"
            isChecked = openWebPagesInApp
            setTextColor(Color.DKGRAY)
        }
        panel.addView(webPagesSwitch)

        addSection("Zoom")
        val pinchSwitch = Switch(this).apply {
            text = "Enable pinch-to-zoom"
            isChecked = pinchZoomEnabled
            setTextColor(Color.DKGRAY)
        }
        panel.addView(pinchSwitch)
        panel.addView(TextView(this).apply {
            text = "No zoom limit is imposed by this app. The Android WebView renderer may still enforce its own limits."
            textSize = 12f
            setTextColor(Color.DKGRAY)
        })

        addSection("Virtual cursor")
        val cursorSwitch = Switch(this).apply {
            text = "Enable virtual cursor"
            isChecked = cursorModeEnabled
            setTextColor(Color.DKGRAY)
        }
        panel.addView(cursorSwitch)

        val dragSelectSwitch = Switch(this).apply {
            text = "Hold, then drag to select text"
            isChecked = cursorLongPressDragEnabled
            setTextColor(Color.DKGRAY)
        }
        panel.addView(dragSelectSwitch)

        val speedLabel = TextView(this).apply {
            text = "Cursor speed: " + String.format("%.1fx", cursorSpeed)
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(speedLabel)
        val speedSlider = SeekBar(this).apply {
            max = 25
            progress = ((cursorSpeed - 0.5f) * 10f).toInt().coerceIn(0, max)
        }
        speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speedLabel.text = "Cursor speed: " + String.format("%.1fx", 0.5f + progress * 0.1f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        panel.addView(speedSlider)

        val sizeLabel = TextView(this).apply {
            text = "Cursor size: " + cursorSizeDp + " dp"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(sizeLabel)
        val sizeSlider = SeekBar(this).apply {
            max = 42 // 6dp through 48dp.
            progress = (cursorSizeDp - 6).coerceIn(0, max)
        }
        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sizeLabel.text = "Cursor size: " + (6 + progress) + " dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        panel.addView(sizeSlider)

        addSection("Pointer shape")
        val styleGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val styles = listOf(
            "Arrow (normal select)" to POINTER_STYLE_ARROW,
            "Hand (link select)" to POINTER_STYLE_HAND,
            "I-beam (text select)" to POINTER_STYLE_TEXT,
            "Crosshair (precision)" to POINTER_STYLE_CROSSHAIR,
            "Move (four directions)" to POINTER_STYLE_MOVE,
            "Resize left / right" to POINTER_STYLE_RESIZE_EW,
            "Resize up / down" to POINTER_STYLE_RESIZE_NS,
            "Resize diagonal ↘" to POINTER_STYLE_RESIZE_NWSE,
            "Resize diagonal ↗" to POINTER_STYLE_RESIZE_NESW,
            "Not allowed" to POINTER_STYLE_FORBIDDEN,
            "Ring" to POINTER_STYLE_RING,
            "Dot" to POINTER_STYLE_DOT
        )
        styles.forEach { choice ->
            styleGroup.addView(RadioButton(this).apply {
                id = choice.second + 1
                text = choice.first
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
        }
        styleGroup.check(cursorStyle + 1)
        panel.addView(styleGroup)

        addSection("Cursor color")
        val colorGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val colorChoices = listOf(
            Triple(1, "White", Color.WHITE),
            Triple(2, "Yellow", Color.YELLOW),
            Triple(3, "Cyan", Color.CYAN),
            Triple(4, "Magenta", Color.MAGENTA),
            Triple(5, "Red", Color.RED),
            Triple(6, "Green", Color.GREEN)
        )
        colorChoices.forEach { choice ->
            colorGroup.addView(RadioButton(this).apply {
                id = choice.first
                text = choice.second
                textSize = 13f
                setTextColor(Color.DKGRAY)
                buttonTintList = android.content.res.ColorStateList.valueOf(choice.third)
            })
        }
        colorGroup.check(colorChoices.indexOfFirst { it.third == cursorColor }.let { if (it >= 0) it + 1 else 1 })

        panel.addView(colorGroup)
        addSection("Keyboard")
        val keyboardSwitch = Switch(this).apply {
            text = "Show keyboard shortcut bar"
            isChecked = keyBarVisible
            setTextColor(Color.DKGRAY)
        }
        panel.addView(keyboardSwitch)

        panel.addView(Button(this).apply {
            text = "About Code Web Companion"
            isAllCaps = false
            setOnClickListener { showAboutDialog() }
        })

        val scrollView = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }

        AlertDialog.Builder(this)
            .setTitle("App settings")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val selected = resolutions.getOrElse(resolutionGroup.checkedRadioButtonId - 1) {
                    resolutions.first()
                }
                viewportWidth = selected.first
                viewportHeight = selected.second
                orientationMode = (orientationGroup.checkedRadioButtonId - 1)
                    .coerceIn(ORIENTATION_FOLLOW_DEVICE, ORIENTATION_LANDSCAPE)
                openWebPagesInApp = webPagesSwitch.isChecked
                pinchZoomEnabled = pinchSwitch.isChecked
                cursorModeEnabled = cursorSwitch.isChecked
                cursorLongPressDragEnabled = dragSelectSwitch.isChecked
                cursorSpeed = (0.5f + speedSlider.progress * 0.1f).coerceIn(0.5f, 3.0f)
                cursorSizeDp = (6 + sizeSlider.progress).coerceIn(6, 48)
                cursorStyle = (styleGroup.checkedRadioButtonId - 1)
                    .coerceIn(POINTER_STYLE_ARROW, POINTER_STYLE_DOT)
                cursorColor = colorChoices.firstOrNull {
                    it.first == colorGroup.checkedRadioButtonId
                }?.third ?: Color.WHITE
                keyBarVisible = keyboardSwitch.isChecked

                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_VIEWPORT_WIDTH, viewportWidth)
                    .putInt(KEY_VIEWPORT_HEIGHT, viewportHeight)
                    .putInt(KEY_ORIENTATION_MODE, orientationMode)
                    .putBoolean(KEY_OPEN_WEB_PAGES_IN_APP, openWebPagesInApp)
                    .putBoolean(KEY_PINCH_ZOOM_ENABLED, pinchZoomEnabled)
                    .putFloat(KEY_CURSOR_SPEED, cursorSpeed)
                    .putInt(KEY_CURSOR_SIZE, cursorSizeDp)
                    .putInt(KEY_CURSOR_COLOR, cursorColor)
                    .putInt(KEY_CURSOR_STYLE, cursorStyle)
                    .putBoolean(KEY_CURSOR_MODE_ENABLED, cursorModeEnabled)
                    .putBoolean(KEY_CURSOR_LONG_PRESS_DRAG, cursorLongPressDragEnabled)
                    .putBoolean(KEY_KEYBAR_VISIBLE, keyBarVisible)
                    .apply()

                applyRequestedOrientation()
                cursorOverlay.updateSettings(cursorSizeDp, cursorSpeed, cursorColor, cursorStyle)
                cursorOverlay.visibility = if (cursorModeEnabled) View.VISIBLE else View.GONE
                cursorModeButton?.text = if (cursorModeEnabled) "Cursor ON" else "Cursor OFF"
                headerCursorButton?.text = if (cursorModeEnabled) "Ptr ✓" else "Ptr"
                keyboardBar.visibility = if (keyBarVisible) View.VISIBLE else View.GONE
                applyPagePresentation(editor)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun toggleCursorMode() {
        cursorModeEnabled = !cursorModeEnabled
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CURSOR_MODE_ENABLED, cursorModeEnabled)
            .apply()
        cursorOverlay.visibility = if (cursorModeEnabled) View.VISIBLE else View.GONE
        cursorModeButton?.text = if (cursorModeEnabled) "Cursor ON" else "Cursor OFF"
        headerCursorButton?.text = if (cursorModeEnabled) "Ptr ✓" else "Ptr"
        Toast.makeText(
            this,
            if (cursorModeEnabled) {
                "Pointer mode: drag one finger to move, release to click, use two fingers to scroll."
            } else {
                "Pointer mode disabled. Normal touch and physical mouse input are active."
            },
            Toast.LENGTH_LONG
        ).show()
    }

    private fun isCodespacesWorkspace(): Boolean {
        val host = runCatching { Uri.parse(editor.url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "github.dev" || host.endsWith(".github.dev")
    }

    private fun toggleIntegratedTerminal() {
        if (!isCodespacesWorkspace()) {
            AlertDialog.Builder(this)
                .setTitle("VS Code terminal needs a remote runtime")
                .setMessage(
                    "The plain vscode.dev browser editor has no integrated terminal. Open a GitHub Codespace or connect to a PC using Remote Tunnels first."
                )
                .setPositiveButton("Open Codespaces") { _, _ -> openCodespaces() }
                .setNeutralButton("Remote Tunnels") { _, _ ->
                    openVsCodeRemoteCommandPalette()
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        runQuickPickCommand(
            "Terminal: Toggle Terminal",
            "Use F1 and run Terminal: Toggle Terminal."
        )
    }

    private fun createIntegratedTerminal() {
        if (!isCodespacesWorkspace()) {
            toggleIntegratedTerminal()
            return
        }
        runQuickPickCommand(
            "Terminal: Create New Terminal",
            "This workspace did not expose the terminal command. Confirm that the Codespace has finished starting."
        )
    }

    private fun selectAllEditorText() {
        val focusScript = """
            (() => {
              const focused = document.querySelector('.monaco-editor.focused textarea.inputarea');
              const input = focused || document.querySelector('.monaco-editor textarea.inputarea');
              if (input) {
                input.focus();
                return true;
              }
              const active = document.activeElement;
              if (active && typeof active.focus === 'function') {
                active.focus();
                return true;
              }
              return false;
            })();
        """.trimIndent()
        editor.evaluateJavascript(focusScript) {
            editor.postDelayed({
                sendKey(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            }, 60)
        }
    }

    private fun showInternalKeyboard() {
        editor.requestFocusFromTouch()
        val focusScript = """
            (function () {
              const input = document.querySelector(
                ".monaco-editor textarea.inputarea, .monaco-editor textarea"
              );
              if (input) input.focus();
            })();
        """.trimIndent()

        editor.evaluateJavascript(focusScript) {
            editor.postDelayed({
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
                inputMethodManager.showSoftInput(
                    editor,
                    InputMethodManager.SHOW_IMPLICIT
                )
            }, 120)
        }
    }

    private fun hideKeyboardAfterEditorScroll() {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
        val inputMethodManager =
            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    private fun makeHeaderButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            minHeight = dp(38)
            minimumHeight = dp(38)
            minWidth = dp(52)
            minimumWidth = dp(52)
            setPadding(dp(4), 0, dp(4), 0)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(65, 65, 68))
            setOnClickListener { onClick() }
        }
    }

    private fun installAndroidFileSystemBridge() {
        val supported =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        if (!supported) {
            Toast.makeText(
                this,
                "Local folder support needs an updated Android System WebView.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Expose the native storage bridge only to the exact VS Code Web origin.
        WebViewCompat.addWebMessageListener(
            editor,
            "AndroidFs",
            setOf(VSCODE_ORIGIN)
        ) { _, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy ->
            if (!isMainFrame || sourceOrigin.toString() != VSCODE_ORIGIN) {
                return@addWebMessageListener
            }
            handleBridgeMessage(message.data ?: return@addWebMessageListener, replyProxy)
        }

        // Install before navigation so the editor sees the compatibility APIs at startup.
        val shim = assets.open("android-fs-access.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(editor, shim, setOf(VSCODE_ORIGIN))
    }

    private fun handleBridgeMessage(rawMessage: String, replyProxy: JavaScriptReplyProxy) {
        val request = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
        val requestId = request.optString("id")
        val method = request.optString("method")
        val args = request.optJSONObject("args") ?: JSONObject()

        when (method) {
            "pickDirectory" -> {
                startBridgePicker(
                    requestId,
                    replyProxy,
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    REQUEST_BRIDGE_FOLDER
                )
            }
            "modifierState" -> {
                applyModifierState(args)
            }
            "pickFile" -> {
                val acceptTypes = args.optJSONArray("types")?.let { values ->
                    buildList {
                        for (index in 0 until values.length()) {
                            val item = values.optJSONObject(index) ?: continue
                            val accept = item.optJSONObject("accept") ?: continue
                            val keys = accept.keys()
                            while (keys.hasNext()) add(keys.next())
                        }
                    }
                }.orEmpty()
                val intent = buildOpenFileIntent(
                    acceptTypes,
                    args.optBoolean("multiple", false)
                )
                startBridgePicker(requestId, replyProxy, intent, REQUEST_BRIDGE_FILE)
            }
            else -> ioExecutor.execute {
                try {
                    val result = performStorageOperation(method, args)
                    runOnUiThread { postBridgeSuccess(replyProxy, requestId, result) }
                } catch (error: Exception) {
                    runOnUiThread {
                        postBridgeError(
                            replyProxy,
                            requestId,
                            error.message ?: "Storage operation failed.",
                            "NotAllowedError"
                        )
                    }
                }
            }
        }
    }

    private fun startBridgePicker(
        requestId: String,
        replyProxy: JavaScriptReplyProxy,
        intent: Intent,
        requestCode: Int
    ) {
        runOnUiThread {
            if (pendingBridgePicker != null) {
                postBridgeError(
                    replyProxy,
                    requestId,
                    "Another Android storage picker is already open.",
                    "InvalidStateError"
                )
                return@runOnUiThread
            }

            pendingBridgePicker = PendingPicker(requestId, replyProxy)
            try {
                startActivityForResult(intent, requestCode)
            } catch (_: Exception) {
                pendingBridgePicker = null
                postBridgeError(
                    replyProxy,
                    requestId,
                    "Android could not open the storage picker.",
                    "NotAllowedError"
                )
            }
        }
    }

    private fun openFolderFromToolbar() {
        if (pendingBridgePicker != null) {
            Toast.makeText(this, "A storage picker is already open.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                REQUEST_TOOLBAR_FOLDER
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Android folder picker is unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreRememberedDirectory(view: WebView) {
        val treeUri = selectedTreeUri ?: return
        val permissionGranted = contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }

        if (!permissionGranted) {
            selectedTreeUri = null
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .remove(KEY_TREE_URI)
                .apply()
            return
        }

        val name = runCatching { displayName(treeUri) }.getOrDefault("Selected folder")
        val script = "window.__androidProvideDirectory(" +
            JSONObject.quote(treeUri.toString()) + "," +
            JSONObject.quote(name) + ");"
        view.evaluateJavascript(script, null)
    }

    private fun buildOpenFileIntent(acceptTypes: List<String>, allowMultiple: Boolean): Intent {
        val mimeTypes = acceptTypes
            .mapNotNull { value ->
                val candidate = value.trim().lowercase()
                when {
                    candidate.contains("/") -> candidate
                    candidate.startsWith(".") -> MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(candidate.removePrefix("."))
                    else -> null
                }
            }
            .distinct()

        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when (mimeTypes.size) {
                0 -> "*/*"
                1 -> mimeTypes.first()
                else -> "*/*"
            }
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    private fun performStorageOperation(method: String, args: JSONObject): Any {
        return when (method) {
            "list" -> listDirectory(args.getString("uri"))
            "read" -> readFile(args.getString("uri"))
            "write" -> {
                writeFile(args.getString("uri"), args.getString("base64"))
                JSONObject().put("written", true)
            }
            "createDirectory" -> createEntry(
                args.getString("parentUri"),
                args.getString("name"),
                DocumentsContract.Document.MIME_TYPE_DIR
            )
            "createFile" -> createEntry(
                args.getString("parentUri"),
                args.getString("name"),
                args.optString("mimeType", "application/octet-stream")
            )
            "delete" -> {
                deleteEntry(args.getString("uri"), args.optBoolean("recursive", false))
                JSONObject().put("deleted", true)
            }
            "rename" -> renameEntry(args.getString("uri"), args.getString("name"))
            "move" -> moveEntry(
                args.getString("uri"),
                args.getString("sourceParentUri"),
                args.getString("destinationUri"),
                args.getString("name")
            )
            else -> throw IllegalArgumentException("Unsupported storage operation: $method")
        }
    }

    private fun getSelectedTreeUri(): Uri {
        val uri = selectedTreeUri
            ?: getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(KEY_TREE_URI, null)
                ?.let(Uri::parse)
        return uri ?: throw IllegalStateException("Select a local folder first.")
    }

    private fun documentIdFor(uri: Uri, treeUri: Uri): String {
        return if (uri.toString() == treeUri.toString()) {
            DocumentsContract.getTreeDocumentId(treeUri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
    }

    private fun listDirectory(uriString: String): JSONArray {
        val treeUri = getSelectedTreeUri()
        val parentUri = Uri.parse(uriString)
        val parentId = documentIdFor(parentUri, treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val result = JSONArray()
        contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                )
                val name = cursor.getString(
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                ) ?: "untitled"
                val mimeType = cursor.getString(
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                ) ?: "application/octet-stream"

                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    cursor.getLong(modifiedIndex)
                } else {
                    0L
                }

                result.put(
                    JSONObject()
                        .put("name", name)
                        .put(
                            "uri",
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()
                        )
                        .put("isDirectory", mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
                        .put("mimeType", mimeType)
                        .put("size", size)
                        .put("lastModified", modified)
                )
            }
        } ?: throw IllegalStateException("Android could not list this folder.")

        return result
    }

    private fun readFile(uriString: String): JSONObject {
        val uri = Uri.parse(uriString)
        val output = ByteArrayOutputStream()
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) {
                    throw IllegalArgumentException("Files larger than 32 MB are not supported yet.")
                }
                output.write(buffer, 0, count)
            }
        } ?: throw IllegalStateException("Android could not read this file.")

        return JSONObject()
            .put("base64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
            .put("mimeType", mimeType)
            .put("lastModified", System.currentTimeMillis())
    }

    private fun writeFile(uriString: String, base64: String) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.size > MAX_FILE_BYTES) {
            throw IllegalArgumentException("Files larger than 32 MB are not supported yet.")
        }

        contentResolver.openOutputStream(Uri.parse(uriString), "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IllegalStateException("Android could not write this file.")
    }

    private fun createEntry(parentUriString: String, name: String, mimeType: String): JSONObject {
        requireSafeName(name)
        val treeUri = getSelectedTreeUri()
        val parentId = documentIdFor(Uri.parse(parentUriString), treeUri)
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val createdUri = DocumentsContract.createDocument(
            contentResolver,
            parentDocumentUri,
            mimeType,
            name
        ) ?: throw IllegalStateException("Android could not create this item.")

        return JSONObject()
            .put("name", name)
            .put("uri", createdUri.toString())
            .put("isDirectory", mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
            .put("mimeType", mimeType)
    }

    private fun deleteEntry(uriString: String, recursive: Boolean) {
        val uri = Uri.parse(uriString)
        val mimeType = contentResolver.getType(uri)

        if (recursive && mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            val nested = listDirectory(uriString)
            for (index in 0 until nested.length()) {
                val child = nested.getJSONObject(index)
                deleteEntry(child.getString("uri"), true)
            }
        }

        if (!DocumentsContract.deleteDocument(contentResolver, uri)) {
            throw IllegalStateException("Android could not delete this item.")
        }
    }

    private fun renameEntry(uriString: String, newName: String): JSONObject {
        requireSafeName(newName)
        val renamedUri = DocumentsContract.renameDocument(
            contentResolver,
            Uri.parse(uriString),
            newName
        ) ?: throw IllegalStateException("Android could not rename this item.")

        return JSONObject()
            .put("uri", renamedUri.toString())
            .put("name", newName)
    }

    private fun moveEntry(
        uriString: String,
        sourceParentUriString: String,
        destinationUriString: String,
        newName: String
    ): JSONObject {
        requireSafeName(newName)
        val treeUri = getSelectedTreeUri()
        val itemUri = Uri.parse(uriString)
        val sourceParentId = documentIdFor(Uri.parse(sourceParentUriString), treeUri)
        val destinationParentId = documentIdFor(Uri.parse(destinationUriString), treeUri)
        val sourceParent = DocumentsContract.buildDocumentUriUsingTree(treeUri, sourceParentId)
        val destinationParent = DocumentsContract.buildDocumentUriUsingTree(treeUri, destinationParentId)
        val currentName = displayName(itemUri)

        val movedUri = if (sourceParentId == destinationParentId) {
            if (currentName == newName) {
                itemUri
            } else {
                DocumentsContract.renameDocument(contentResolver, itemUri, newName)
                    ?: throw IllegalStateException("Android could not rename this item.")
            }
        } else {
            var result = DocumentsContract.moveDocument(
                contentResolver,
                itemUri,
                sourceParent,
                destinationParent
            ) ?: throw IllegalStateException("This storage provider does not support moving items.")

            if (currentName != newName) {
                result = DocumentsContract.renameDocument(contentResolver, result, newName)
                    ?: throw IllegalStateException("The item moved, but Android could not rename it.")
            }
            result
        }

        return JSONObject()
            .put("uri", movedUri.toString())
            .put("name", displayName(movedUri))
    }

    private fun requireSafeName(name: String) {
        require(name.isNotBlank() && name != "." && name != "..") { "Invalid item name." }
        require(!name.contains("/") && !name.contains("\\")) { "Names cannot contain path separators." }
    }

    private fun postBridgeSuccess(
        replyProxy: JavaScriptReplyProxy,
        requestId: String,
        data: Any
    ) {
        val response = JSONObject()
            .put("id", requestId)
            .put("ok", true)
            .put("data", data)
        runCatching { replyProxy.postMessage(response.toString()) }
    }

    private fun postBridgeError(
        replyProxy: JavaScriptReplyProxy,
        requestId: String,
        error: String,
        errorName: String
    ) {
        val response = JSONObject()
            .put("id", requestId)
            .put("ok", false)
            .put("error", error)
            .put("errorName", errorName)
        runCatching { replyProxy.postMessage(response.toString()) }
    }

    private fun persistTreePermission(uri: Uri, flags: Int) {
        val grants = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        if (grants != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, grants) }
                .onFailure {
                    Toast.makeText(
                        this,
                        "Folder selected for this session, but Android could not persist its permission.",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
        selectedTreeUri = uri
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    private fun displayName(uri: Uri): String {
        // ACTION_OPEN_DOCUMENT can return MediaStore, Downloads, cloud-provider, or document URIs.
        val openableName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        }.getOrNull()
        if (!openableName.isNullOrBlank()) return openableName

        val documentName = runCatching {
            val queryUri = if (DocumentsContract.isTreeUri(uri)) {
                val rootId = DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
            } else uri

            contentResolver.query(
                queryUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

        return documentName
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Selected item"
    }

    @Deprecated("Legacy activity result API retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        try {
            when (requestCode) {
                REQUEST_BRIDGE_FOLDER, REQUEST_TOOLBAR_FOLDER -> {
                val selectedUri = if (resultCode == RESULT_OK) data?.data else null

                if (selectedUri == null) {
                    if (requestCode == REQUEST_BRIDGE_FOLDER) {
                        val pending = pendingBridgePicker
                        pendingBridgePicker = null
                        pending?.let {
                            postBridgeError(
                                it.replyProxy,
                                it.requestId,
                                "Folder selection was cancelled.",
                                "AbortError"
                            )
                        }
                    }
                    return
                }

                persistTreePermission(selectedUri, data?.flags ?: 0)
                val name = displayName(selectedUri)

                if (requestCode == REQUEST_BRIDGE_FOLDER) {
                    val pending = pendingBridgePicker
                    pendingBridgePicker = null
                    pending?.let {
                        postBridgeSuccess(
                            it.replyProxy,
                            it.requestId,
                            JSONObject().put("uri", selectedUri.toString()).put("name", name)
                        )
                    }
                } else {
                    // The VS Code Web File > Open Folder action consumes this selected handle.
                    val script = "window.__androidProvideDirectory(" +
                        JSONObject.quote(selectedUri.toString()) + "," +
                        JSONObject.quote(name) + ");"
                    editor.evaluateJavascript(script, null)
                    Toast.makeText(
                        this,
                        "Folder selected. In the editor, choose File > Open Folder.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            REQUEST_BRIDGE_FILE -> {
                val pending = pendingBridgePicker
                pendingBridgePicker = null
                if (pending == null) return

                if (resultCode != RESULT_OK || data == null) {
                    postBridgeError(
                        pending.replyProxy,
                        pending.requestId,
                        "File selection was cancelled.",
                        "AbortError"
                    )
                    return
                }

                val uris = selectedUris(data)
                val result = JSONArray()
                uris.forEach { uri ->
                    persistFilePermission(uri, data.flags)
                    result.put(
                        JSONObject()
                            .put("uri", uri.toString())
                            .put("name", displayName(uri))
                            .put("mimeType", contentResolver.getType(uri) ?: "application/octet-stream")
                    )
                }
                postBridgeSuccess(pending.replyProxy, pending.requestId, result)
            }

            REQUEST_UPLOAD_FILE -> {
                val callback = pendingFileChooser
                pendingFileChooser = null
                if (callback == null) return

                if (resultCode != RESULT_OK || data == null) {
                    callback.onReceiveValue(null)
                    return
                }

                val uris = selectedUris(data)
                uris.forEach { persistFilePermission(it, data.flags) }
                callback.onReceiveValue(uris.toTypedArray())
                }
            }
        } catch (error: Exception) {
            val pending = pendingBridgePicker
            pendingBridgePicker = null
            pending?.let {
                postBridgeError(
                    it.replyProxy,
                    it.requestId,
                    error.message ?: "Android could not open the selected item.",
                    "NotAllowedError"
                )
            }

            pendingFileChooser?.onReceiveValue(null)
            pendingFileChooser = null
            Toast.makeText(
                this,
                error.message ?: "Android could not access the selected item.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun selectedUris(data: Intent): List<Uri> {
        val result = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                result.add(clip.getItemAt(index).uri)
            }
        } ?: data.data?.let(result::add)
        return result
    }

    private fun persistFilePermission(uri: Uri, flags: Int) {
        val persistable = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val grants = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        if (persistable != 0 && grants != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, grants) }
        }
    }

    private fun applyRequestedOrientation() {
        requestedOrientation = when (orientationMode) {
            ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::editor.isInitialized) {
            editor.post { applyPagePresentation(editor) }
        }
    }

    private fun showAboutDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        content.addView(
            TextView(this).apply {
                text = "An independent Android wrapper for the official Visual Studio Code web editor. This application is not affiliated with or endorsed by Microsoft."
                textSize = 14f
                setTextColor(Color.DKGRAY)
            }
        )

        val officialLink = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://code.visualstudio.com/"))
                )
            }
        }

        officialLink.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_vscode_link)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            }
        )
        officialLink.addView(
            TextView(this).apply {
                text = "  Visit Visual Studio Code"
                textSize = 14f
                setTextColor(Color.rgb(0, 122, 204))
            }
        )
        content.addView(officialLink)

        AlertDialog.Builder(this)
            .setTitle("About Code Web Companion")
            .setView(content)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun createKeyRow(): HorizontalScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
            )
            tag = content
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        }
    }

    private fun rowContent(row: HorizontalScrollView): LinearLayout = row.tag as LinearLayout

    private fun addModifierButton(row: HorizontalScrollView, label: String, flag: Int) {
        val button = makeKeyButton(label)
        modifierButtons[flag] = button
        button.setOnClickListener {
            activeModifiers = activeModifiers xor flag
            syncModifierButtons()
            editor.requestFocus()
        }
        rowContent(row).addView(button)
    }

    private fun addKeyButton(row: HorizontalScrollView, label: String, keyCode: Int) {
        val button = makeKeyButton(label)
        button.setOnClickListener { sendKey(keyCode) }
        rowContent(row).addView(button)
    }

    private fun addSymbolButton(row: HorizontalScrollView, symbol: String) {
        val mapping = symbolKey(symbol) ?: return
        val button = makeKeyButton(symbol)
        button.setOnClickListener { sendKey(mapping.first, mapping.second) }
        rowContent(row).addView(button)
    }

    private fun makeKeyButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        isFocusable = false
        isFocusableInTouchMode = false
        minWidth = dp(42)
        minimumWidth = dp(42)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(7), 0, dp(7), 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(65, 65, 68))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(42)
        ).apply {
            setMargins(dp(2), dp(1), dp(2), dp(1))
        }
    }

    private fun sendKey(keyCode: Int, extraModifiers: Int = 0) {
        editor.requestFocus()
        val requestedMeta = activeModifiers or extraModifiers
        val meta = KeyEvent.normalizeMetaState(requestedMeta)
        val downTime = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_KEYBOARD

        // Send a paired key event carrying the modifier state to Chromium's input pipeline.
        val down = KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            source
        )
        val up = KeyEvent(
            downTime,
            downTime + 1,
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            source
        )

        editor.dispatchKeyEvent(down)
        editor.dispatchKeyEvent(up)

        activeModifiers = 0
        syncModifierButtons()
    }

    private fun symbolKey(symbol: String): Pair<Int, Int>? {
        val shift = KeyEvent.META_SHIFT_ON
        return when (symbol) {
            "[" -> KeyEvent.KEYCODE_LEFT_BRACKET to 0
            "]" -> KeyEvent.KEYCODE_RIGHT_BRACKET to 0
            "{" -> KeyEvent.KEYCODE_LEFT_BRACKET to shift
            "}" -> KeyEvent.KEYCODE_RIGHT_BRACKET to shift
            "(" -> KeyEvent.KEYCODE_9 to shift
            ")" -> KeyEvent.KEYCODE_0 to shift
            ";" -> KeyEvent.KEYCODE_SEMICOLON to 0
            ":" -> KeyEvent.KEYCODE_SEMICOLON to shift
            "/" -> KeyEvent.KEYCODE_SLASH to 0
            "\\" -> KeyEvent.KEYCODE_BACKSLASH to 0
            "|" -> KeyEvent.KEYCODE_BACKSLASH to shift
            "~" -> KeyEvent.KEYCODE_GRAVE to shift
            "_" -> KeyEvent.KEYCODE_MINUS to shift
            else -> null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun enableImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // A swipe from the top edge temporarily reveals the status bar.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onResume() {
        super.onResume()
        if (::editor.isInitialized) editor.onResume()
        enableImmersiveStatusBar()
    }

    override fun onPause() {
        if (::editor.isInitialized) editor.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized) editor.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveStatusBar()
    }

    private fun registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { handleBackNavigation() }
            backInvokedCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
    }

    private fun handleBackNavigation() {
        if (authPopupDialog?.isShowing == true || authPopupWebView != null) {
            closeAuthenticationPopup()
            return
        }

        if (!::editor.isInitialized) return

        // Back navigates inside the editor; it never closes the wrapper activity.
        if (editor.canGoBack()) {
            editor.goBack()
            return
        }

        val currentHost = runCatching { Uri.parse(editor.url).host }.getOrNull()
        if (currentHost != "vscode.dev" &&
            currentHost?.endsWith(".github.dev") != true &&
            currentHost != "github.dev"
        ) {
            editor.loadUrl(VSCODE_WEB_URL)
        }
    }

    @Deprecated("Legacy callback retained for Android versions below 13.")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    override fun onDestroy() {
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        pendingBridgePicker = null
        closeAuthenticationPopup()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        backInvokedCallback = null
        authPopupDialog = null
        authPopupWebView = null
        ioExecutor.shutdownNow()

        if (::editor.isInitialized) {
            editor.stopLoading()
            editor.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val VSCODE_WEB_URL = "https://vscode.dev/"
        private const val VSCODE_ORIGIN = "https://vscode.dev"
        private const val CODESPACES_URL = "https://github.com/codespaces"
        private const val PREFERENCES = "local_workspace"
        private const val KEY_TREE_URI = "selected_tree_uri"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_PAGE_ZOOM = "page_zoom"
        private const val KEY_KEYBAR_VISIBLE = "keybar_visible"
        private const val KEY_CURSOR_SPEED = "cursor_speed"
        private const val KEY_CURSOR_SIZE = "cursor_size"
        private const val KEY_CURSOR_COLOR = "cursor_color"
        private const val KEY_CURSOR_STYLE = "cursor_style"
        private const val KEY_CURSOR_MODE_ENABLED = "cursor_mode_enabled"
        private const val KEY_PINCH_ZOOM_ENABLED = "pinch_zoom_enabled"
        private const val KEY_VIEWPORT_WIDTH = "viewport_width"
        private const val KEY_VIEWPORT_HEIGHT = "viewport_height"
        private const val KEY_ORIENTATION_MODE = "orientation_mode"
        private const val KEY_OPEN_WEB_PAGES_IN_APP = "open_web_pages_in_app"
        private const val KEY_CURSOR_LONG_PRESS_DRAG = "cursor_long_press_drag"
        private const val REQUEST_BRIDGE_FOLDER = 4201
        private const val REQUEST_BRIDGE_FILE = 4202
        private const val REQUEST_TOOLBAR_FOLDER = 4203
        private const val REQUEST_UPLOAD_FILE = 4204
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }
}
