package com.icarusalmighty.app.driving

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight native HUD renderer. Vehicle values are rendered only when a
 * live source supplies them; decorative animation is never used as telemetry.
 */
class DrivingHudView(
    context: Context,
    private val onAction: (HudAction) -> Unit
) : View(context) {
    private val cyan = Color.rgb(54, 220, 255)
    private val blue = Color.rgb(57, 129, 255)
    private val gold = Color.rgb(220, 176, 76)
    private val white = Color.rgb(236, 248, 255)
    private val dim = Color.rgb(122, 174, 194)
    private val dark = Color.rgb(2, 8, 18)
    private val warning = Color.rgb(255, 177, 66)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
    private val path = Path()
    private var state = DrivingHudState()

    private val diagnosticsHit = RectF()
    private val navigationHit = RectF()
    private val engineHit = RectF()
    private val voiceHit = RectF()
    private val exitHit = RectF()

    fun render(value: DrivingHudState) {
        state = value
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawBackground(canvas, w, h)
        drawDepthGrid(canvas, w, h)
        drawBrand(canvas, w, h)
        drawSpeedCluster(canvas, w, h)
        drawNavigation(canvas, w, h)
        drawVehicleScan(canvas, w, h)
        drawEnginePanel(canvas, w, h)
        drawStatusPanel(canvas, w, h)
        drawFooter(canvas, w, h)

        if (state.alertMessage != null) drawAlert(canvas, w, h, state.alertMessage!!)
        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(2, 12, 27), dark, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.color = Color.argb(24, 30, 170, 255)
        canvas.drawCircle(w * .54f, h * .48f, min(w, h) * .47f, paint)
    }

    private fun drawDepthGrid(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(38, 69, 204, 255)
        val horizon = h * .39f
        for (i in 0..12) {
            val y = horizon + (h - horizon) * (i / 12f) * (i / 12f)
            canvas.drawLine(0f, y, w, y, paint)
        }
        for (i in -9..9) {
            val x = w * .5f + i * w * .065f
            canvas.drawLine(w * .5f, horizon, x, h, paint)
        }
    }

    private fun drawBrand(canvas: Canvas, w: Float, h: Float) {
        drawText(canvas, "ICARUS", w * .035f, h * .075f, h * .048f, gold, true)
        drawText(canvas, "SPATIAL HUD", w * .035f, h * .112f, h * .021f, cyan, false)
        drawText(canvas, state.sourceLabel, w * .035f, h * .145f, h * .017f, if (state.obdConnected) cyan else warning, false)

        exitHit.set(w * .91f, h * .03f, w * .98f, h * .12f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(150, 85, 210, 255)
        canvas.drawRoundRect(exitHit, 12f, 12f, paint)
        drawText(canvas, "EXIT", exitHit.centerX(), exitHit.centerY() + h * .008f, h * .019f, white, true, Paint.Align.CENTER)
    }

    private fun drawSpeedCluster(canvas: Canvas, w: Float, h: Float) {
        val cx = w * .15f
        val cy = h * .48f
        val r = min(w, h) * .19f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * .006f
        paint.color = Color.argb(150, 39, 184, 255)
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 135f, 270f, false, paint)
        paint.strokeWidth = h * .002f
        paint.color = Color.argb(100, 220, 176, 76)
        canvas.drawArc(RectF(cx - r * .84f, cy - r * .84f, cx + r * .84f, cy + r * .84f), 138f, 265f, false, paint)

        drawText(canvas, state.speedMph?.toString() ?: "—", cx, cy + h * .012f, h * .105f, white, true, Paint.Align.CENTER)
        drawText(canvas, "MPH", cx, cy + h * .074f, h * .025f, cyan, false, Paint.Align.CENTER)
        drawText(canvas, "${state.rpm?.toString() ?: "—"} RPM", cx, cy + h * .118f, h * .021f, gold, false, Paint.Align.CENTER)

        val fuelText = state.fuelPercent?.let { "$it%" } ?: "—"
        drawText(canvas, "FUEL  $fuelText", w * .055f, h * .79f, h * .021f, white, true)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(110, 45, 202, 255)
        canvas.drawRoundRect(RectF(w * .055f, h * .81f, w * .24f, h * .83f), 10f, 10f, paint)
        val fuel = state.fuelPercent
        if (fuel != null) {
            paint.style = Paint.Style.FILL
            paint.color = cyan
            canvas.drawRoundRect(RectF(w * .055f, h * .81f, w * (.055f + .185f * (fuel / 100f)), h * .83f), 10f, 10f, paint)
        }
    }

    private fun drawNavigation(canvas: Canvas, w: Float, h: Float) {
        navigationHit.set(w * .35f, h * .055f, w * .68f, h * .25f)
        glassPanel(canvas, navigationHit, state.navigationExpanded)

        val distance = state.nextTurnDistanceFt
        val road = state.nextRoad
        if (distance != null && !road.isNullOrBlank()) {
            drawText(canvas, "NEXT TURN", navigationHit.left + w * .02f, navigationHit.top + h * .045f, h * .018f, dim, false)
            drawText(canvas, "↱  $distance ft", navigationHit.left + w * .02f, navigationHit.top + h * .102f, h * .04f, white, true)
            drawText(canvas, road, navigationHit.left + w * .02f, navigationHit.top + h * .145f, h * .021f, cyan, false)
            if (state.navigationExpanded) drawRouteRibbon(canvas, w, h)
        } else {
            drawText(canvas, "NAVIGATION", navigationHit.left + w * .02f, navigationHit.top + h * .052f, h * .019f, dim, false)
            drawText(canvas, "NOT ACTIVE", navigationHit.left + w * .02f, navigationHit.top + h * .11f, h * .035f, white, true)
            drawText(canvas, "Route data appears only from a live navigation source", navigationHit.left + w * .02f, navigationHit.top + h * .153f, h * .016f, dim, false)
        }
    }

    private fun drawRouteRibbon(canvas: Canvas, w: Float, h: Float) {
        val pulse = ((SystemClock.uptimeMillis() % 1800L) / 1800f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = h * .016f
        paint.color = Color.argb(145, 35, 197, 255)
        path.reset()
        path.moveTo(w * .5f, h * .92f)
        path.cubicTo(w * (.49f + pulse * .01f), h * .72f, w * .56f, h * .59f, w * .53f, h * .40f)
        canvas.drawPath(path, paint)
        paint.strokeWidth = h * .004f
        paint.color = gold
        canvas.drawPath(path, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawVehicleScan(canvas: Canvas, w: Float, h: Float) {
        diagnosticsHit.set(w * .34f, h * .48f, w * .68f, h * .89f)
        val cx = diagnosticsHit.centerX()
        val cy = diagnosticsHit.centerY()
        val radius = min(diagnosticsHit.width(), diagnosticsHit.height()) * .42f
        val phase = (SystemClock.uptimeMillis() % 3200L) / 3200f * 360f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(100, 48, 205, 255)
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawArc(RectF(cx - radius * 1.13f, cy - radius * 1.13f, cx + radius * 1.13f, cy + radius * 1.13f), phase, 95f, false, paint)
        paint.color = Color.argb(135, 220, 176, 76)
        canvas.drawArc(RectF(cx - radius * .91f, cy - radius * .91f, cx + radius * .91f, cy + radius * .91f), -phase * .72f, 70f, false, paint)

        drawTruckWireframe(canvas, cx, cy, radius)
        drawText(canvas, "OBD VEHICLE SCAN", cx, diagnosticsHit.bottom - h * .03f, h * .019f, if (state.obdConnected) cyan else dim, true, Paint.Align.CENTER)
        drawText(canvas, if (state.obdConnected) "LIVE" else "NO DATA", cx, diagnosticsHit.bottom, h * .016f, if (state.obdConnected) gold else warning, false, Paint.Align.CENTER)
    }

    private fun drawTruckWireframe(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val body = RectF(cx - r * .72f, cy - r * .18f, cx + r * .72f, cy + r * .26f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.argb(165, 69, 211, 255)
        canvas.drawRoundRect(body, r * .09f, r * .09f, paint)
        path.reset()
        path.moveTo(cx - r * .35f, cy - r * .18f)
        path.lineTo(cx - r * .12f, cy - r * .47f)
        path.lineTo(cx + r * .29f, cy - r * .47f)
        path.lineTo(cx + r * .48f, cy - r * .18f)
        canvas.drawPath(path, paint)
        canvas.drawCircle(cx - r * .43f, cy + r * .28f, r * .16f, paint)
        canvas.drawCircle(cx + r * .43f, cy + r * .28f, r * .16f, paint)

        val temp = state.engineTempF
        engineHit.set(cx - r * .55f, cy - r * .10f, cx - r * .08f, cy + r * .16f)
        paint.style = Paint.Style.FILL
        paint.color = when {
            temp == null -> Color.argb(18, 220, 176, 76)
            temp >= 235 -> Color.argb(155, 255, 90, 40)
            temp >= 215 -> Color.argb(120, 255, 177, 66)
            else -> Color.argb(75, 220, 176, 76)
        }
        canvas.drawRoundRect(engineHit, 12f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(165, 220, 176, 76)
        canvas.drawRoundRect(engineHit, 12f, 12f, paint)
    }

    private fun drawEnginePanel(canvas: Canvas, w: Float, h: Float) {
        val panel = RectF(w * .72f, h * .27f, w * .965f, h * .57f)
        glassPanel(canvas, panel, state.diagnosticsExpanded)
        drawText(canvas, "ENGINE", panel.left + w * .018f, panel.top + h * .045f, h * .018f, dim, false)
        drawText(canvas, state.engineTempF?.let { "$it°F" } ?: "—", panel.left + w * .018f, panel.top + h * .11f, h * .052f, white, true)
        drawText(canvas, "COOLANT", panel.left + w * .018f, panel.top + h * .145f, h * .016f, cyan, false)

        if (state.diagnosticsExpanded) {
            drawText(canvas, "LOAD  ${state.engineLoadPercent?.let { "$it%" } ?: "—"}", panel.left + w * .018f, panel.top + h * .205f, h * .019f, white, false)
            drawText(canvas, "VOLTAGE  ${state.batteryVolts?.let { "%.1fV".format(it) } ?: "—"}", panel.left + w * .018f, panel.top + h * .248f, h * .019f, white, false)
        }
    }

    private fun drawStatusPanel(canvas: Canvas, w: Float, h: Float) {
        val panel = RectF(w * .72f, h * .62f, w * .965f, h * .83f)
        glassPanel(canvas, panel, false)
        drawText(canvas, state.roadStatus, panel.left + w * .018f, panel.top + h * .06f, h * .025f, if (state.obdConnected) cyan else white, true)
        drawText(canvas, state.roadDetail, panel.left + w * .018f, panel.top + h * .11f, h * .016f, dim, false)
        val movement = when (state.vehicleMoving) { true -> "MOVING"; false -> "STOPPED"; null -> "SPEED UNKNOWN" }
        drawText(canvas, movement, panel.left + w * .018f, panel.top + h * .158f, h * .016f, gold, false)
    }

    private fun drawFooter(canvas: Canvas, w: Float, h: Float) {
        voiceHit.set(w * .76f, h * .87f, w * .95f, h * .96f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = if (state.listening) gold else cyan
        canvas.drawRoundRect(voiceHit, h * .03f, h * .03f, paint)
        drawText(canvas, if (state.listening) "LISTENING…" else "VOICE", voiceHit.centerX(), voiceHit.centerY() + h * .007f, h * .021f, if (state.listening) gold else white, true, Paint.Align.CENTER)

        drawText(canvas, "LIVE DATA ONLY • NO SIMULATED TELEMETRY", w * .035f, h * .955f, h * .015f, dim, false)
    }

    private fun drawAlert(canvas: Canvas, w: Float, h: Float, message: String) {
        val box = RectF(w * .26f, h * .285f, w * .70f, h * .37f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(215, 12, 22, 34)
        canvas.drawRoundRect(box, 18f, 18f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = warning
        canvas.drawRoundRect(box, 18f, 18f, paint)
        drawText(canvas, message.take(72), box.centerX(), box.centerY() + h * .008f, h * .019f, warning, true, Paint.Align.CENTER)
    }

    private fun glassPanel(canvas: Canvas, rect: RectF, emphasized: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(if (emphasized) 92 else 65, 7, 28, 46)
        canvas.drawRoundRect(rect, 20f, 20f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (emphasized) 2.5f else 1.5f
        paint.color = Color.argb(if (emphasized) 175 else 105, 61, 205, 255)
        canvas.drawRoundRect(rect, 20f, 20f, paint)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        textPaint.typeface = android.graphics.Typeface.create("sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        canvas.drawText(text, x, y, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        when {
            exitHit.contains(x, y) -> onAction(HudAction.EXIT)
            voiceHit.contains(x, y) -> onAction(HudAction.VOICE)
            engineHit.contains(x, y) -> onAction(HudAction.SHOW_ENGINE)
            diagnosticsHit.contains(x, y) -> onAction(HudAction.TOGGLE_DIAGNOSTICS)
            navigationHit.contains(x, y) -> onAction(HudAction.TOGGLE_NAVIGATION)
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
