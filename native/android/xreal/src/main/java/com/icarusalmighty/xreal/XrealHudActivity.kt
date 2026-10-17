package com.icarusalmighty.xreal

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import com.icarusalmighty.xreal.hud.CinematicDrivingHudView
import com.icarusalmighty.xreal.hud.DrivingHudSurface
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import kotlin.math.min

/**
 * High-contrast, black-background HUD designed for XREAL optical displays.
 * Black emits no display light on additive optical glasses; a phone still shows black.
 * Physical lens dimming and real-world legibility require device verification.
 */
class XrealHudActivity : Activity() {
    private lateinit var root: FrameLayout
    private var hudMode = XrealMode.ASSISTANT
    private var vehicleView: CinematicDrivingHudView? = null
    private var vehicleSnapshot = XrealHudState()
    private val vehicleExpiry = Runnable {
        if (currentActivity?.get() === this && !isDestroyed && hudMode == XrealMode.VEHICLE) {
            renderVehicleSnapshot()
        }
    }
    private var assistantView: XrealAssistantView? = null
    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = recordActivityDisplay()
        override fun onDisplayChanged(displayId: Int) = recordActivityDisplay()
        override fun onDisplayRemoved(displayId: Int) {
            if (currentActivity?.get() === this@XrealHudActivity && session.actualDisplayId == displayId) {
                // A detached decor may briefly retain its former display object.
                session.updateActualDisplayId(null)
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == XrealModeController.ACTION_UPDATE_XREAL) {
                renderHud(hudMode, intent.toHudState())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        // The host, not the shared instrument layer, supplies optical no-light black.
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val mode = intent.toMode()
        // Android may restore the original launch Intent after process death,
        // without the app-side live telemetry owner. Never replay its old readings.
        val initialState = if (savedInstanceState != null && mode == XrealMode.VEHICLE) {
            XrealHudState(sourceLabel = "WAITING FOR LIVE TELEMETRY")
        } else intent.toHudState()
        renderHud(mode, initialState)
        setContentView(root)
        enterImmersiveMode()

        val filter = IntentFilter(XrealModeController.ACTION_UPDATE_XREAL)
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        currentActivity = WeakReference(this)
        displayManager = getSystemService(DisplayManager::class.java)
        runCatching { displayManager?.registerDisplayListener(displayListener, null) }
        if (session.activityCreated(hudMode)) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderHud(intent.toMode(), intent.toHudState())
        if (session.activityCreated(hudMode)) finish() else recordActivityDisplay()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        if (hudMode == XrealMode.VEHICLE) renderVehicleSnapshot()
        // Post until the decor is attached. A logical display ID is not proof of glasses.
        window.decorView.post { if (!isDestroyed) recordActivityDisplay() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) recordActivityDisplay()
    }

    private fun recordActivityDisplay() {
        if (currentActivity?.get() !== this || isDestroyed) return
        session.updateActualDisplayId(window.decorView.display?.takeIf { it.isValid }?.displayId)
    }

    private fun renderHud(mode: XrealMode, state: XrealHudState) {
        hudMode = mode
        window.decorView.removeCallbacks(vehicleExpiry)
        val view: View = when (mode) {
            XrealMode.VEHICLE -> {
                val vehicle = vehicleView ?: CinematicDrivingHudView(
                    this, surface = DrivingHudSurface.XREAL,
                ).also { vehicleView = it }
                vehicleSnapshot = state
                renderVehicleSnapshot()
                vehicle
            }
            XrealMode.ASSISTANT -> {
                val assistant = assistantView ?: XrealAssistantView(this).also { assistantView = it }
                assistant.update(state)
                assistant
            }
        }
        if (root.childCount == 0 || root.getChildAt(0) !== view) {
            root.removeAllViews()
            root.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
    }

    private fun renderVehicleSnapshot() {
        window.decorView.removeCallbacks(vehicleExpiry)
        val now = SystemClock.elapsedRealtime()
        val fresh = XrealVehicleFreshness.sanitize(vehicleSnapshot, now)
        vehicleView?.render(fresh.toDrivingScene())
        XrealVehicleFreshness.nextExpiryAt(vehicleSnapshot, now)?.let { deadline ->
            // This is a one-shot liveness deadline, never an animation/render loop.
            window.decorView.postDelayed(vehicleExpiry, (deadline - now).coerceAtLeast(1L))
        }
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(vehicleExpiry)
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        displayManager = null
        runCatching { unregisterReceiver(stateReceiver) }
        if (currentActivity?.get() === this) {
            currentActivity = null
            session.activityDestroyed()
        }
        super.onDestroy()
    }

    companion object {
        internal val session = XrealHudSession()
        private var currentActivity: WeakReference<XrealHudActivity>? = null

        /** Called only on the main thread; never retains an Activity past destruction. */
        internal fun requestClose(): Boolean {
            val requested = session.requestClose()
            currentActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
            return requested
        }
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun Intent.toMode(): XrealMode =
        runCatching { XrealMode.valueOf(getStringExtra(XrealModeController.EXTRA_MODE).orEmpty()) }
            .getOrDefault(XrealMode.ASSISTANT)

}

/** The existing assistant surface remains independent of the driving layout. */
private class XrealAssistantView(context: Context) : View(context) {
    private var state = XrealHudState()
    private val cyan = Color.rgb(45, 226, 230)
    private val gold = Color.rgb(244, 190, 68)
    private val dimCyan = Color.argb(145, 45, 226, 230)
    private val quietCyan = Color.argb(72, 45, 226, 230)
    private val warning = Color.rgb(255, 116, 83)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        strokeCap = Paint.Cap.ROUND
    }

    fun update(value: XrealHudState) {
        state = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val scale = min(width / 1920f, height / 1080f).coerceAtLeast(0.45f)
        canvas.save()
        canvas.scale(scale, scale)
        val logicalWidth = width / scale
        val logicalHeight = height / scale
        drawFrame(canvas, logicalWidth, logicalHeight)
        drawAssistant(canvas, logicalWidth, logicalHeight)
        canvas.restore()
    }

    private fun drawFrame(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = dimCyan
        val margin = 86f
        val corner = 42f
        canvas.drawLine(margin, margin + corner, margin, margin, paint)
        canvas.drawLine(margin, margin, margin + 250f, margin, paint)
        canvas.drawLine(w - margin - 250f, margin, w - margin, margin, paint)
        canvas.drawLine(w - margin, margin, w - margin, margin + corner, paint)
        canvas.drawLine(margin, h - margin - corner, margin, h - margin, paint)
        canvas.drawLine(margin, h - margin, margin + 250f, h - margin, paint)
        canvas.drawLine(w - margin - 250f, h - margin, w - margin, h - margin, paint)
        canvas.drawLine(w - margin, h - margin, w - margin, h - margin - corner, paint)
        label(canvas, "I C A R U S", margin + 20f, margin + 52f, 28f, gold)
        drawStatus(canvas, w - margin - 20f, margin + 52f)
        state.batteryPercent?.coerceIn(0, 100)?.let {
            drawBattery(canvas, it, w - margin - 20f, margin + 82f)
        }
    }

    private fun drawAssistant(canvas: Canvas, w: Float, h: Float) {
        label(canvas, "ASSISTANT", w / 2f, 238f, 28f, gold, Paint.Align.CENTER)
        val message = state.primaryText?.takeIf { it.isNotBlank() } ?: "Say “Hey ICARUS”"
        drawCommandCore(canvas, w / 2f, h / 2f - 74f)
        drawWrappedText(canvas, message, w / 2f, h / 2f + 100f, w * 0.68f, 46f, cyan)
        label(canvas, "VOICE  •  VISION  •  CONTROL", w / 2f, h - 145f, 22f, dimCyan, Paint.Align.CENTER)
    }

    private fun drawStatus(canvas: Canvas, right: Float, baseline: Float) {
        val status = state.assistantStatus.trim().ifBlank { "ICARUS ONLINE" }.uppercase().take(28)
        val healthy = !status.contains("OFF") && !status.contains("ERROR") && !status.contains("LOST")
        paint.textSize = 24f
        paint.style = Paint.Style.FILL
        paint.color = if (healthy) cyan else warning
        canvas.drawCircle(right - paint.measureText(status).coerceAtLeast(120f) - 25f, baseline - 8f, 6f, paint)
        label(canvas, status, right, baseline, 24f, if (healthy) cyan else warning, Paint.Align.RIGHT)
    }

    private fun drawBattery(canvas: Canvas, percent: Int, right: Float, baseline: Float) {
        val body = RectF(right - 82f, baseline - 16f, right - 32f, baseline + 2f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (percent <= 20) warning else dimCyan
        canvas.drawRoundRect(body, 4f, 4f, paint)
        canvas.drawLine(right - 29f, baseline - 11f, right - 29f, baseline - 3f, paint)
        paint.style = Paint.Style.FILL
        val fill = (body.width() - 6f) * (percent / 100f)
        canvas.drawRoundRect(RectF(body.left + 3f, body.top + 3f, body.left + 3f + fill, body.bottom - 3f), 2f, 2f, paint)
        label(canvas, "$percent%", right, baseline, 18f, paint.color, Paint.Align.RIGHT)
    }

    private fun drawCommandCore(canvas: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = quietCyan
        canvas.drawCircle(x, y, 76f, paint)
        canvas.drawArc(RectF(x - 96f, y - 96f, x + 96f, y + 96f), -38f, 76f, false, paint)
        paint.color = gold
        paint.strokeWidth = 5f
        val mark = Path().apply {
            moveTo(x - 40f, y - 21f)
            lineTo(x - 20f, y + 28f)
            lineTo(x, y - 6f)
            lineTo(x + 20f, y + 28f)
            lineTo(x + 40f, y - 21f)
        }
        canvas.drawPath(mark, paint)
        paint.style = Paint.Style.FILL
        paint.color = cyan
        canvas.drawCircle(x, y, 5f, paint)
    }

    private fun drawWrappedText(canvas: Canvas, text: String, centerX: Float, startY: Float, maxWidth: Float, size: Float, color: Int) {
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.color = color
        paint.style = Paint.Style.FILL
        val words = text.replace("\n", " ").split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotBlank()) {
                lines += line
                line = word
            } else line = candidate
        }
        if (line.isNotBlank()) lines += line
        val visible = lines.take(4)
        val firstBaseline = startY - ((visible.size - 1) * size * 1.35f) / 2f
        visible.forEachIndexed { index, value ->
            canvas.drawText(value, centerX, firstBaseline + index * (size * 1.35f), paint)
        }
    }

    private fun label(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = align
        paint.textSize = size
        paint.color = color
        canvas.drawText(text, x, y, paint)
    }
}
