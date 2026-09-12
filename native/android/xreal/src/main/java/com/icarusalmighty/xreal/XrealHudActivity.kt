package com.icarusalmighty.xreal

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlin.math.min

/**
 * High-contrast, black-background HUD designed for XREAL optical displays.
 * Black pixels remain visually transparent while cyan/gold information stays legible.
 */
class XrealHudActivity : Activity() {
    private lateinit var hudView: XrealHudView

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == XrealModeController.ACTION_UPDATE_XREAL) {
                hudView.update(intent.toHudState())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        hudView = XrealHudView(this)
        hudView.mode = intent.toMode()
        hudView.update(intent.toHudState())
        setContentView(hudView)
        enterImmersiveMode()

        val filter = IntentFilter(XrealModeController.ACTION_UPDATE_XREAL)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hudView.mode = intent.toMode()
        hudView.update(intent.toHudState())
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
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

    private fun Intent.toHudState() = XrealHudState(
        assistantStatus = getStringExtra(XrealModeController.EXTRA_ASSISTANT_STATUS) ?: "ICARUS ONLINE",
        primaryText = getStringExtra(XrealModeController.EXTRA_PRIMARY_TEXT),
        navigationInstruction = getStringExtra(XrealModeController.EXTRA_NAVIGATION_INSTRUCTION),
        navigationDistance = getStringExtra(XrealModeController.EXTRA_NAVIGATION_DISTANCE),
        speedMph = intExtraOrNull(XrealModeController.EXTRA_SPEED_MPH),
        engineTempF = intExtraOrNull(XrealModeController.EXTRA_ENGINE_TEMP_F),
    )

    private fun Intent.intExtraOrNull(key: String): Int? =
        if (hasExtra(key)) getIntExtra(key, 0) else null
}

private class XrealHudView(context: Context) : View(context) {
    var mode: XrealMode = XrealMode.ASSISTANT
        set(value) {
            field = value
            invalidate()
        }

    private var state = XrealHudState()
    private val cyan = Color.rgb(45, 226, 230)
    private val gold = Color.rgb(244, 190, 68)
    private val dimCyan = Color.argb(145, 45, 226, 230)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
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
        if (mode == XrealMode.VEHICLE) drawVehicle(canvas, logicalWidth, logicalHeight)
        else drawAssistant(canvas, logicalWidth, logicalHeight)
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
        label(canvas, state.assistantStatus.uppercase(), w - margin - 20f, margin + 52f, 24f, cyan, Paint.Align.RIGHT)
    }

    private fun drawAssistant(canvas: Canvas, w: Float, h: Float) {
        label(canvas, "ASSISTANT", w / 2f, 238f, 28f, gold, Paint.Align.CENTER)
        val message = state.primaryText?.takeIf { it.isNotBlank() } ?: "Say “Hey ICARUS”"
        drawWrappedText(canvas, message, w / 2f, h / 2f - 50f, w * 0.68f, 48f, cyan)
        label(canvas, "VOICE  •  VISION  •  CONTROL", w / 2f, h - 145f, 22f, dimCyan, Paint.Align.CENTER)
    }

    private fun drawVehicle(canvas: Canvas, w: Float, h: Float) {
        val instruction = state.navigationInstruction?.takeIf { it.isNotBlank() } ?: "ROUTE READY"
        label(canvas, instruction.uppercase(), w / 2f, 205f, 40f, cyan, Paint.Align.CENTER)
        state.navigationDistance?.takeIf { it.isNotBlank() }?.let {
            label(canvas, it.uppercase(), w / 2f, 260f, 26f, gold, Paint.Align.CENTER)
        }

        val speed = state.speedMph?.coerceIn(0, 199)
        label(canvas, speed?.toString() ?: "—", w / 2f, h / 2f + 95f, 180f, Color.WHITE, Paint.Align.CENTER)
        label(canvas, "MPH", w / 2f, h / 2f + 145f, 30f, cyan, Paint.Align.CENTER)

        val temp = state.engineTempF?.let { "$it°F" } ?: "—"
        metric(canvas, "ENGINE", temp, 170f, h - 178f)
        metric(canvas, "MODE", "VEHICLE", w - 170f, h - 178f, Paint.Align.RIGHT)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = dimCyan
        canvas.drawArc(RectF(w / 2f - 220f, h / 2f - 205f, w / 2f + 220f, h / 2f + 235f), 205f, 130f, false, paint)
    }

    private fun metric(canvas: Canvas, name: String, value: String, x: Float, y: Float, align: Paint.Align = Paint.Align.LEFT) {
        label(canvas, name, x, y, 21f, dimCyan, align)
        label(canvas, value, x, y + 45f, 35f, gold, align)
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
        lines.take(5).forEachIndexed { index, value ->
            canvas.drawText(value, centerX, startY + index * (size * 1.35f), paint)
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
