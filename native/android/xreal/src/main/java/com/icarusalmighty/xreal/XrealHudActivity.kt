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
        eta = getStringExtra(XrealModeController.EXTRA_ETA),
        heading = getStringExtra(XrealModeController.EXTRA_HEADING),
        speedMph = intExtraOrNull(XrealModeController.EXTRA_SPEED_MPH),
        rpm = intExtraOrNull(XrealModeController.EXTRA_RPM),
        engineTempF = intExtraOrNull(XrealModeController.EXTRA_ENGINE_TEMP_F),
        batteryPercent = intExtraOrNull(XrealModeController.EXTRA_BATTERY_PERCENT),
        alertText = getStringExtra(XrealModeController.EXTRA_ALERT_TEXT),
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

    private fun drawVehicle(canvas: Canvas, w: Float, h: Float) {
        val instruction = state.navigationInstruction?.takeIf { it.isNotBlank() } ?: "ROUTE READY"
        drawTurnCue(canvas, instruction, w / 2f - 355f, 216f)
        drawFittedText(canvas, instruction.uppercase(), w / 2f, 205f, w * 0.55f, 40f, 28f, cyan)
        state.navigationDistance?.takeIf { it.isNotBlank() }?.let {
            label(canvas, it.uppercase(), w / 2f, 260f, 26f, gold, Paint.Align.CENTER)
        }
        state.eta?.takeIf { it.isNotBlank() }?.let {
            label(canvas, "ETA ${it.uppercase()}", w / 2f, 298f, 20f, dimCyan, Paint.Align.CENTER)
        }

        drawHeadingTape(canvas, state.heading, w / 2f, 350f)

        val speed = state.speedMph?.coerceIn(0, 199)
        label(canvas, speed?.toString() ?: "—", w / 2f, h / 2f + 105f, 178f, Color.WHITE, Paint.Align.CENTER)
        label(canvas, "MPH", w / 2f, h / 2f + 154f, 29f, cyan, Paint.Align.CENTER)

        val temp = state.engineTempF?.let { "$it°F" } ?: "—"
        metric(canvas, "ENGINE", temp, 170f, h - 178f)
        val rpm = state.rpm?.coerceIn(0, 9999)?.toString() ?: "—"
        metric(canvas, "RPM", rpm, 385f, h - 178f)
        val heading = state.heading?.takeIf { it.isNotBlank() }?.uppercase() ?: "—"
        metric(canvas, "HEADING", heading, w - 385f, h - 178f, Paint.Align.RIGHT)
        metric(canvas, "MODE", "VEHICLE", w - 170f, h - 178f, Paint.Align.RIGHT)

        state.alertText?.takeIf { it.isNotBlank() }?.let {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(220, 105, 22, 19)
            canvas.drawRoundRect(RectF(w / 2f - 360f, h - 265f, w / 2f + 360f, h - 205f), 18f, 18f, paint)
            drawFittedText(canvas, it.uppercase(), w / 2f, h - 224f, 650f, 24f, 18f, Color.WHITE)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = dimCyan
        canvas.drawArc(RectF(w / 2f - 220f, h / 2f - 205f, w / 2f + 220f, h / 2f + 235f), 205f, 130f, false, paint)
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

    private fun drawTurnCue(canvas: Canvas, instruction: String, x: Float, y: Float) {
        val lower = instruction.lowercase()
        val direction = when {
            "u-turn" in lower || "uturn" in lower -> 2
            "left" in lower -> -1
            "right" in lower -> 1
            else -> 0
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.color = gold
        val path = Path()
        when (direction) {
            -1 -> { path.moveTo(x + 32f, y + 35f); path.lineTo(x + 32f, y - 22f); path.lineTo(x - 30f, y - 22f); path.moveTo(x - 30f, y - 22f); path.lineTo(x - 4f, y - 48f); path.moveTo(x - 30f, y - 22f); path.lineTo(x - 4f, y + 4f) }
            1 -> { path.moveTo(x - 32f, y + 35f); path.lineTo(x - 32f, y - 22f); path.lineTo(x + 30f, y - 22f); path.moveTo(x + 30f, y - 22f); path.lineTo(x + 4f, y - 48f); path.moveTo(x + 30f, y - 22f); path.lineTo(x + 4f, y + 4f) }
            2 -> { path.moveTo(x + 34f, y + 28f); path.cubicTo(x + 34f, y - 40f, x - 34f, y - 40f, x - 34f, y + 5f); path.moveTo(x - 34f, y + 5f); path.lineTo(x - 12f, y - 17f); path.moveTo(x - 34f, y + 5f); path.lineTo(x - 52f, y - 18f) }
            else -> { path.moveTo(x, y + 35f); path.lineTo(x, y - 42f); path.moveTo(x, y - 42f); path.lineTo(x - 24f, y - 14f); path.moveTo(x, y - 42f); path.lineTo(x + 24f, y - 14f) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawHeadingTape(canvas: Canvas, heading: String?, centerX: Float, baseline: Float) {
        val labels = listOf("W", "NW", "N", "NE", "E", "SE", "S", "SW")
        val selected = heading?.trim()?.uppercase()?.let { value -> labels.indexOfFirst { it == value } }?.takeIf { it >= 0 } ?: 2
        val spacing = 78f
        for (offset in -2..2) {
            val index = (selected + offset + labels.size) % labels.size
            val x = centerX + offset * spacing
            val active = offset == 0
            label(canvas, labels[index], x, baseline, if (active) 28f else 19f, if (active) gold else dimCyan, Paint.Align.CENTER)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (active) 3f else 2f
            paint.color = if (active) gold else quietCyan
            canvas.drawLine(x, baseline + 13f, x, baseline + if (active) 33f else 25f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = quietCyan
        canvas.drawLine(centerX - 205f, baseline + 42f, centerX + 205f, baseline + 42f, paint)
    }

    private fun drawFittedText(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, preferred: Float, minimum: Float, color: Int) {
        var size = preferred
        paint.textSize = size
        while (size > minimum && paint.measureText(text) > maxWidth) {
            size -= 1f
            paint.textSize = size
        }
        label(canvas, text, x, y, size, color, Paint.Align.CENTER)
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
