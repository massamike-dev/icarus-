package com.icarusalmighty.xreal.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.icarusalmighty.xreal.R
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Reference-led windshield HUD with native instruments and a bundled vehicle texture. The center is deliberately quiet:
 * notification guidance has no road geometry or calibration with which to draw
 * a road-aligned ribbon. The lower pickup is an illustrative telemetry model.
 * Nothing here starts a scan, infers diagnostic health, or manufactures data.
 */
open class CinematicDrivingHudView(
    context: Context,
    private val surface: DrivingHudSurface = DrivingHudSurface.XREAL,
    private val onAction: ((DrivingHudAction) -> Unit)? = null
) : View(context) {
    init {
        // This View draws instruments only. The host supplies the display-specific
        // backdrop; a transparent View alone is not a camera or passthrough feed.
        setBackgroundColor(Color.TRANSPARENT)
    }

    private val blue = Color.rgb(87, 165, 236)
    private val cyan = Color.rgb(121, 209, 255)
    private val gold = Color.rgb(227, 190, 116)
    private val white = Color.rgb(232, 245, 255)
    private val dim = Color.rgb(147, 180, 207)
    private val warning = Color.rgb(255, 186, 105)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val normal = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val path = Path()
    private val arc = RectF()
    private var state = CinematicDrivingScene()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // Coordinates are in the same aspect-preserving space as the painted HUD.
    private val navigationHit = RectF(592f, 80f, 1048f, 248f)
    private val diagnosticsHit = RectF(462f, 556f, 1208f, 800f)
    private val enginePanel = RectF(1240f, 558f, 1532f, 678f)
    private val engineHit = RectF()
    private val voiceHit = RectF(1340f, 821f, 1532f, 887f)
    private val exitHit = RectF(1420f, 32f, 1532f, 100f)
    private val fuelPanel = RectF(80f, 603f, 432f, 773f)
    private val scanPanel = RectF(890f, 562f, 1208f, 800f)
    private val statusPanel = RectF(1240f, 700f, 1532f, 800f)
    private val pickupBounds = RectF(474f, 565f, 882f, 769f)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // One bounded decode per view, never in onDraw. About 1.5 MiB ARGB at
    // 887 x 443/444 versus about 6 MiB at the original 1774 x 887 pixels.
    // The original RGBA PNG is packaged unchanged, with no density upscaling.
    private val pickupBitmap: Bitmap? = runCatching {
        BitmapFactory.decodeResource(resources, R.drawable.icarus_hud_pickup,
            BitmapFactory.Options().apply {
                inScaled = false
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
    }.getOrNull()
    private val pickupDrawBounds = pickupBitmap?.let { bitmap ->
        val fit = min(pickupBounds.width() / bitmap.width, pickupBounds.height() / bitmap.height)
        val halfWidth = bitmap.width * fit * .5f
        val halfHeight = bitmap.height * fit * .5f
        RectF(pickupBounds.centerX() - halfWidth, pickupBounds.centerY() - halfHeight,
            pickupBounds.centerX() + halfWidth, pickupBounds.centerY() + halfHeight)
    } ?: RectF(pickupBounds)

    fun render(value: CinematicDrivingScene) {
        state = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        // A fixed design plane keeps circles circular on wide phones and on a
        // mirrored 16:9 display. Touches are transformed back to this plane.
        scale = min(width / WIDTH, height / HEIGHT)
        offsetX = (width - WIDTH * scale) * .5f
        offsetY = (height - HEIGHT * scale) * .5f
        val saved = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        // Defensive clipping also keeps unexpectedly long external labels out of
        // the forward center and aspect-fit margins. This is not road calibration.
        canvas.clipRect(0f, 0f, WIDTH, HEIGHT)
        canvas.clipOutRect(470f, 340f, 1208f, 550f)
        drawPeripheralDividers(canvas)
        drawBrand(canvas)
        drawSpeedCluster(canvas)
        drawNavigation(canvas)
        drawFuel(canvas)
        drawVehicleScan(canvas)
        drawEnginePanel(canvas)
        drawStatusPanel(canvas)
        drawFooter(canvas)
        val alert = state.alertMessage?.takeIf {
            state.obdConnected || !it.contains("CONNECT AN OBD ADAPTER", ignoreCase = true)
        }
        if (alert != null) drawAlert(canvas, alert)
        canvas.restoreToCount(saved)
    }

    private fun drawPeripheralDividers(canvas: Canvas) {
        // No full-screen tint, bloom, scene or fill. The reserved center and
        // aspect-ratio margins contribute zero HUD pixels to the host surface.
        stroke(blue, .8f, 40)
        canvas.drawLine(72f, 808f, 1532f, 808f, paint)
        stroke(gold, 1f, 90)
        canvas.drawLine(480f, 808f, 740f, 808f, paint)
    }

    private fun drawBrand(canvas: Canvas) {
        text(canvas, "I C A R U S", 78f, 82f, 37f, gold, face = light)
        text(canvas, "Spatial HUD", 80f, 118f, 27f, blue, face = light)
        fitted(canvas, state.sourceLabel.uppercase(Locale.US), 82f, 151f, 430f, 14f,
            if (state.obdConnected || state.speedMph != null || state.heading != null) dim else warning)
        if (surface == DrivingHudSurface.PHONE) {
            glassPanel(canvas, exitHit)
            text(canvas, "EXIT", exitHit.centerX(), 74f, 18f, white, Paint.Align.CENTER)
        } else {
            text(canvas, "XREAL HUD", 1532f, 74f, 18f, cyan, Paint.Align.RIGHT)
            text(canvas, "OUTPUT UNVERIFIED", 1532f, 100f, 12f, warning, Paint.Align.RIGHT)
        }
    }

    private fun drawSpeedCluster(canvas: Canvas) {
        val cx = 256f
        val cy = 375f
        val radius = 175f
        // Four fine concentric contours and separate measured-speed segments.
        ring(canvas, cx, cy, radius + 15f, 137f, 276f, blue, 1f, 145)
        ring(canvas, cx, cy, radius + 6f, 140f, 269f, cyan, 1.2f, 92)
        ring(canvas, cx, cy, radius - 7f, 145f, 250f, blue, 10f, 34)
        ring(canvas, cx, cy, radius - 21f, 145f, 250f, cyan, 1f, 120)
        ring(canvas, cx, cy, radius - 30f, 147f, 246f, gold, .8f, 90)
        for (i in 0..40) {
            val angle = Math.toRadians((145 + i * 6.25).toDouble())
            val inner = radius - if (i % 5 == 0) 38f else 31f
            stroke(blue, if (i % 5 == 0) 1.7f else .8f, if (i % 5 == 0) 160 else 78)
            canvas.drawLine(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * (radius - 24f), cy + sin(angle).toFloat() * (radius - 24f), paint)
        }
        state.speedMph?.let { speed ->
            val sweep = speed.coerceIn(0, 160) / 160f * 250f
            ring(canvas, cx, cy, radius - 7f, 145f, sweep, blue, 17f, 35)
            ring(canvas, cx, cy, radius - 7f, 145f, sweep, cyan, 8f, 235)
            ring(canvas, cx, cy, radius - 7f, 145f, min(sweep, 41f), gold, 8f, 245)
            val angle = Math.toRadians((145f + sweep).toDouble())
            val px = cx + cos(angle).toFloat() * (radius - 7f)
            val py = cy + sin(angle).toFloat() * (radius - 7f)
            paint.style = Paint.Style.FILL
            paint.color = gold
            canvas.drawCircle(px, py, 3.3f, paint)
        }
        text(canvas, state.speedMph?.toString() ?: "—", cx, cy + 25f, 106f, white, Paint.Align.CENTER, light)
        text(canvas, "MPH", cx, cy + 64f, 25f, white, Paint.Align.CENTER)
        // Unknown remains a dash, never zero. The gold divider is ornamental.
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = LinearGradient(cx - 102f, 0f, cx + 102f, 0f,
            intArrayOf(Color.TRANSPARENT, gold, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(cx - 102f, cy + 101f, cx + 102f, cy + 103f, paint)
        paint.shader = null
        text(canvas, state.rpm?.toString() ?: "—", cx - 5f, cy + 144f, 31f, cyan, Paint.Align.RIGHT)
        text(canvas, "RPM", cx + 9f, cy + 141f, 18f, dim)
        text(canvas, if (state.speedMph == null) "SPEED UNAVAILABLE" else "LIVE SPEED", cx, cy + 178f, 12f, dim, Paint.Align.CENTER)
    }

    private fun drawNavigation(canvas: Canvas) {
        glassPanel(canvas, navigationHit, state.navigationExpanded)
        val instruction = state.navigationInstruction?.takeIf { it.isNotBlank() } ?: state.nextRoad
        val distance = state.navigationDistance?.takeIf { it.isNotBlank() }
            ?: state.nextTurnDistanceFt?.let { "$it ft" }
        if (!instruction.isNullOrBlank()) {
            // Glyph reflects only explicit text. A turn card is not AR routing.
            drawNavigationGlyph(canvas, instruction, 636f, 159f)
            text(canvas, "NEXT TURN", 686f, 113f, 14f, dim)
            fitted(canvas, distance ?: "DISTANCE UNAVAILABLE", 686f, 155f, 334f,
                if (distance == null) 18f else 34f, white, medium)
            fitted(canvas, instruction, 686f, 193f, 334f, 21f, cyan)
            fitted(canvas, state.navigationSource ?: "LIVE NAVIGATION", 686f, 224f, 334f, 12f, dim)
        } else {
            drawNavigationGlyph(canvas, "", 636f, 159f)
            text(canvas, "NAVIGATION", 686f, 113f, 14f, dim)
            text(canvas, if (state.navigationAccessGranted) "ROUTE STANDBY" else "ACCESS REQUIRED", 686f, 151f, 25f, white)
            fitted(canvas, if (state.navigationAccessGranted) "Start Google Maps or Waze" else if (surface == DrivingHudSurface.PHONE) "Tap while parked to enable access"
                else "Enable access on phone when parked",
                686f, 188f, 334f, 16f, if (state.navigationAccessGranted) dim else warning)
            text(canvas, "NO ROAD-ALIGNED GUIDANCE", 686f, 221f, 11f, dim)
        }
    }

    private fun drawNavigationGlyph(canvas: Canvas, instruction: String, cx: Float, cy: Float) {
        val lower = instruction.lowercase(Locale.US)
        // Road names such as "Left Bank Road" must not invent a left turn.
        val left = LEFT_TURN.containsMatchIn(lower)
        val right = RIGHT_TURN.containsMatchIn(lower)
        stroke(cyan, 6f, 220)
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        path.reset()
        when {
            left || right -> {
                val sign = if (left) -1f else 1f
                path.moveTo(cx - sign * 12f, cy + 25f)
                path.lineTo(cx - sign * 12f, cy - 10f)
                path.lineTo(cx + sign * 21f, cy - 10f)
                path.moveTo(cx + sign * 9f, cy - 23f)
                path.lineTo(cx + sign * 22f, cy - 10f)
                path.lineTo(cx + sign * 9f, cy + 3f)
            }
            STRAIGHT_AHEAD.containsMatchIn(lower) -> {
                path.moveTo(cx, cy + 25f); path.lineTo(cx, cy - 23f)
                path.moveTo(cx - 15f, cy - 7f); path.lineTo(cx, cy - 23f); path.lineTo(cx + 15f, cy - 7f)
            }
            else -> {
                // Neutral waypoint, without inventing an upcoming turn.
                path.moveTo(cx, cy - 26f); path.lineTo(cx + 22f, cy)
                path.lineTo(cx, cy + 26f); path.lineTo(cx - 22f, cy); path.close()
            }
        }
        canvas.drawPath(path, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    private fun drawFuel(canvas: Canvas) {
        glassPanel(canvas, fuelPanel)
        drawFuelIcon(canvas, 111f, 638f)
        text(canvas, "Fuel", 165f, 641f, 20f, dim)
        text(canvas, state.fuelPercent?.let { "$it%" } ?: "—", 165f, 685f, 37f, white)
        meter(canvas, RectF(107f, 711f, 403f, 724f), state.fuelPercent?.let { it / 100f }, false)
        text(canvas, if (state.fuelPercent == null) "FUEL DATA UNAVAILABLE" else "LIVE OBD-II READING", 109f, 751f, 12f, dim)
    }

    private fun drawFuelIcon(canvas: Canvas, x: Float, y: Float) {
        stroke(gold, 2.3f, 235)
        canvas.drawRoundRect(RectF(x, y, x + 24f, y + 44f), 3f, 3f, paint)
        canvas.drawRect(x + 4f, y + 5f, x + 20f, y + 20f, paint)
        canvas.drawLine(x - 4f, y + 45f, x + 29f, y + 45f, paint)
        path.reset(); path.moveTo(x + 24f, y + 15f); path.lineTo(x + 32f, y + 21f)
        path.lineTo(x + 32f, y + 39f); path.cubicTo(x + 32f, y + 47f, x + 40f, y + 47f, x + 40f, y + 39f)
        path.lineTo(x + 40f, y + 9f); path.lineTo(x + 33f, y + 2f)
        canvas.drawPath(path, paint)
    }

    private fun drawVehicleScan(canvas: Canvas) {
        // Small static plinth sits entirely in the lower telemetry band.
        stroke(blue, 1f, 64)
        for (i in 0..3) {
            canvas.drawOval(RectF(486f + i * 20f, 746f + i * 6f, 880f - i * 20f, 786f - i * 3f), paint)
        }
        canvas.drawLine(482f, 772f, 874f, 772f, paint)
        drawVehicleModel(canvas)
        text(canvas, "ILLUSTRATIVE VEHICLE MODEL", 670f, 798f, 11f, dim, Paint.Align.CENTER)
        glassPanel(canvas, scanPanel, state.diagnosticsExpanded)
        text(canvas, "OBD Vehicle Scan", 911f, 596f, 22f, cyan)
        text(canvas, "READ-ONLY TELEMETRY", 912f, 620f, 11f, dim)
        drawPidRing(canvas, 948f, 692f)
        if (state.diagnosticsExpanded) {
            val values = listOf(
                "RPM" to (state.rpm?.toString() ?: "—"),
                "Coolant" to (state.engineTempF?.let { "$it°F" } ?: "—"),
                "Load" to (state.engineLoadPercent?.let { "$it%" } ?: "—"),
                "Voltage" to (state.batteryVolts?.let { String.format(Locale.US, "%.1fV", it) } ?: "—")
            )
            values.forEachIndexed { index, row ->
                val y = 652f + index * 28f
                text(canvas, row.first, 1007f, y, 14f, dim)
                text(canvas, row.second, 1188f, y, 16f, white, Paint.Align.RIGHT)
            }
        } else {
            text(canvas, "DETAILS HIDDEN", 1007f, 680f, 14f, dim)
            text(canvas, if (surface == DrivingHudSurface.PHONE) "Tap while parked" else "READ-ONLY VIEW", 1007f, 707f, 13f, dim)
        }
        text(canvas, if (state.obdConnected) "ADAPTER CONNECTED" else "NO OBD CONNECTION", 912f, 778f, 13f,
            if (state.obdConnected) cyan else warning)
    }

    private fun drawPidRing(canvas: Canvas, cx: Float, cy: Float) {
        val available = listOf(state.rpm, state.engineTempF, state.engineLoadPercent, state.batteryVolts).count { it != null }
        ring(canvas, cx, cy, 47f, 0f, 360f, blue, 1f, 130)
        ring(canvas, cx, cy, 41f, 0f, 360f, blue, 3f, 55)
        for (i in 0..3) {
            ring(canvas, cx, cy, 41f, -90f + i * 90f, 70f, if (i < available) cyan else blue, 3f, if (i < available) 220 else 50)
        }
        ring(canvas, cx, cy, 51f, 224f, 72f, gold, 2f, 180)
        text(canvas, "$available / 4", cx, cy + 1f, 21f, white, Paint.Align.CENTER)
        text(canvas, "PIDs", cx, cy + 19f, 11f, dim, Paint.Align.CENTER)
    }

    private fun drawVehicleModel(canvas: Canvas) {
        val bitmap = pickupBitmap
        if (bitmap == null) {
            // Missing/failed texture decoding never blocks either HUD surface.
            drawTruckWireframe(canvas)
            return
        }
        canvas.drawBitmap(bitmap, null, pickupDrawBounds, bitmapPaint)
        val engineX = pickupDrawBounds.left + pickupDrawBounds.width() * .69f
        val engineY = pickupDrawBounds.top + pickupDrawBounds.height() * .44f
        engineHit.set(engineX - 34f, engineY - 26f, engineX + 34f, engineY + 26f)
        state.engineTempF?.let { temperature ->
            // The bundled base stays blue. This subtle accent only indicates
            // an available coolant measurement, never a diagnostic pass.
            val tint = if (temperature >= 235) warning else gold
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.shader = RadialGradient(engineX, engineY, 35f,
                Color.argb(60, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(engineX, engineY, 35f, paint)
            paint.shader = null
            stroke(tint, 1f, 150)
            canvas.drawLine(engineX - 19f, engineY - 6f, engineX + 12f, engineY + 1f, paint)
            canvas.drawLine(engineX - 17f, engineY + 1f, engineX + 14f, engineY + 8f, paint)
        }
    }

    /** Static fallback pickup, projected from a small local 3D wire model. */
    private fun drawTruckWireframe(canvas: Canvas) {
        val originX = 675f
        val originY = 718f
        val unit = 64f
        fun point(x: Float, y: Float, z: Float): Pair<Float, Float> =
            (originX + (x + y * .48f) * unit) to (originY + (x * .16f - y * .38f - z * .91f) * unit)
        fun modelLine(a: FloatArray, b: FloatArray, color: Int = cyan, opacity: Int = 140, weight: Float = 1f) {
            val p = point(a[0], a[1], a[2]); val q = point(b[0], b[1], b[2])
            stroke(color, weight, opacity)
            canvas.drawLine(p.first, p.second, q.first, q.second, paint)
        }
        fun mesh(points: Array<FloatArray>, close: Boolean = false, opacity: Int = 145, fill: Boolean = false) {
            path.reset()
            points.forEachIndexed { index, p ->
                val v = point(p[0], p[1], p[2])
                if (index == 0) path.moveTo(v.first, v.second) else path.lineTo(v.first, v.second)
            }
            if (close) path.close()
            if (fill) {
                paint.style = Paint.Style.FILL; paint.color = Color.argb(95, 8, 32, 53)
                canvas.drawPath(path, paint)
            }
            stroke(cyan, 1.25f, opacity)
            canvas.drawPath(path, paint)
        }
        fun wheel(x: Float, y: Float, rear: Boolean) {
            val opacity = if (rear) 64 else 185
            for (radius in floatArrayOf(.46f, .40f, .28f, .12f)) {
                val points = Array(33) { i ->
                    val theta = i * Math.PI * 2 / 32
                    floatArrayOf(x + cos(theta).toFloat() * radius, y, .43f + sin(theta).toFloat() * radius)
                }
                mesh(points, true, opacity)
            }
            for (i in 0..11) {
                val theta = i * Math.PI / 6
                val c = cos(theta).toFloat(); val s = sin(theta).toFloat()
                modelLine(floatArrayOf(x + c * .12f, y, .43f + s * .12f),
                    floatArrayOf(x + c * .27f, y, .43f + s * .27f), opacity = opacity)
                modelLine(floatArrayOf(x + c * .40f, y, .43f + s * .40f),
                    floatArrayOf(x + c * .46f, y, .43f + s * .46f), opacity = opacity / 2)
            }
        }
        // Far wheels and chassis first so nearer body lines remain legible.
        wheel(-1.65f, .89f, true); wheel(1.64f, .89f, true)
        for (y in floatArrayOf(-.83f, .83f)) {
            mesh(arrayOf(floatArrayOf(-2.52f, y, .62f), floatArrayOf(-2.52f, y, 1.24f),
                floatArrayOf(-.66f, y, 1.24f), floatArrayOf(-.58f, y, 1.96f),
                floatArrayOf(.67f, y, 1.96f), floatArrayOf(1.20f, y, 1.27f),
                floatArrayOf(2.42f, y, 1.17f), floatArrayOf(2.56f, y, .61f)), opacity = if (y < 0) 230 else 130)
            modelLine(floatArrayOf(-2.5f, y, .72f), floatArrayOf(2.5f, y, .72f), opacity = 68)
            modelLine(floatArrayOf(-2.5f, y, 1.08f), floatArrayOf(2.43f, y, 1.08f), opacity = 80)
        }
        // Hood, roof, tailgate and near-side cabin receive restrained glass fill.
        mesh(arrayOf(floatArrayOf(1.2f, -.83f, 1.27f), floatArrayOf(1.2f, .83f, 1.27f),
            floatArrayOf(2.42f, .83f, 1.17f), floatArrayOf(2.42f, -.83f, 1.17f)), true, 200, true)
        mesh(arrayOf(floatArrayOf(-.58f, -.83f, 1.96f), floatArrayOf(-.58f, .83f, 1.96f),
            floatArrayOf(.67f, .83f, 1.96f), floatArrayOf(.67f, -.83f, 1.96f)), true, 180, true)
        mesh(arrayOf(floatArrayOf(-.66f, -.84f, .70f), floatArrayOf(-.58f, -.84f, 1.96f),
            floatArrayOf(.67f, -.84f, 1.96f), floatArrayOf(1.20f, -.84f, 1.27f),
            floatArrayOf(1.12f, -.84f, .70f)), true, 190, true)
        mesh(arrayOf(floatArrayOf(.67f, -.83f, 1.96f), floatArrayOf(.67f, .83f, 1.96f),
            floatArrayOf(1.20f, .83f, 1.27f), floatArrayOf(1.20f, -.83f, 1.27f)), true, 165)
        for (y in floatArrayOf(-.72f, -.3f, .3f, .72f)) {
            modelLine(floatArrayOf(.75f, y, 1.84f), floatArrayOf(1.12f, y, 1.39f), opacity = 45)
            modelLine(floatArrayOf(1.30f, y, 1.26f), floatArrayOf(2.32f, y, 1.17f), opacity = 62)
            modelLine(floatArrayOf(-2.45f, y, 1.16f), floatArrayOf(-.73f, y, 1.16f), opacity = 85)
        }
        // Bed rails, tailgate and shallow ribs establish the pickup silhouette.
        for (x in floatArrayOf(-2.52f, -.72f)) {
            modelLine(floatArrayOf(x, -.83f, 1.24f), floatArrayOf(x, .83f, 1.24f), opacity = 180)
        }
        for (x in floatArrayOf(-2.38f, -2.08f, -1.78f, -1.48f, -1.18f, -.88f)) {
            modelLine(floatArrayOf(x, -.84f, .83f), floatArrayOf(x, -.84f, 1.15f), opacity = 48)
        }
        for (y in floatArrayOf(-.7f, -.35f, 0f, .35f, .7f)) {
            modelLine(floatArrayOf(-2.52f, y, .68f), floatArrayOf(-2.52f, y, 1.2f), opacity = 65)
        }
        // Split windows, door seams, handle, running board and wing mirror.
        mesh(arrayOf(floatArrayOf(-.48f, -.855f, 1.35f), floatArrayOf(-.43f, -.855f, 1.84f),
            floatArrayOf(.02f, -.855f, 1.84f), floatArrayOf(.02f, -.855f, 1.35f)), true, 190)
        mesh(arrayOf(floatArrayOf(.12f, -.855f, 1.35f), floatArrayOf(.12f, -.855f, 1.84f),
            floatArrayOf(.59f, -.855f, 1.84f), floatArrayOf(.96f, -.855f, 1.35f)), true, 205)
        modelLine(floatArrayOf(.065f, -.86f, .76f), floatArrayOf(.065f, -.86f, 1.91f), opacity = 165)
        for (x in floatArrayOf(-.41f, .24f)) {
            modelLine(floatArrayOf(x, -.87f, 1.20f), floatArrayOf(x + .17f, -.87f, 1.20f), opacity = 230, weight = 2f)
        }
        modelLine(floatArrayOf(-.85f, -.98f, .61f), floatArrayOf(.96f, -.98f, .61f), opacity = 215, weight = 2f)
        mesh(arrayOf(floatArrayOf(.82f, -.85f, 1.41f), floatArrayOf(.87f, -1.08f, 1.47f),
            floatArrayOf(1.09f, -1.08f, 1.45f), floatArrayOf(1.10f, -.92f, 1.35f)), true, 205)
        // Front grille plane, double bumper and individual headlamp segments.
        mesh(arrayOf(floatArrayOf(2.42f, -.83f, 1.17f), floatArrayOf(2.42f, .83f, 1.17f),
            floatArrayOf(2.56f, .83f, .61f), floatArrayOf(2.56f, -.83f, .61f)), true, 210, true)
        for (z in floatArrayOf(.66f, .75f, .85f, .95f, 1.05f)) {
            modelLine(floatArrayOf(2.52f, -.7f, z), floatArrayOf(2.52f, .7f, z), opacity = 150)
        }
        for (y in floatArrayOf(-.57f, -.36f, -.15f, .06f, .27f, .48f)) {
            modelLine(floatArrayOf(2.53f, y, .81f), floatArrayOf(2.49f, y, 1.06f), opacity = 130)
        }
        modelLine(floatArrayOf(2.61f, -.91f, .56f), floatArrayOf(2.61f, .91f, .56f), opacity = 225, weight = 2f)
        for (y in floatArrayOf(-.69f, .49f)) {
            mesh(arrayOf(floatArrayOf(2.46f, y, 1.08f), floatArrayOf(2.46f, y + .19f, 1.08f),
                floatArrayOf(2.50f, y + .19f, .96f), floatArrayOf(2.50f, y, .96f)), true, 235)
        }
        // Wheel arches and near-side rims are foreground contours, not blobs.
        for (x in floatArrayOf(-1.65f, 1.64f)) {
            mesh(Array(17) { i ->
                val a = i * Math.PI / 16
                floatArrayOf(x + cos(a).toFloat() * .56f, -.87f, .44f + sin(a).toFloat() * .56f)
            }, opacity = 215)
            wheel(x, -.97f, false)
        }
        val engineCenter = point(1.73f, -.16f, 1.13f)
        engineHit.set(engineCenter.first - 34f, engineCenter.second - 26f, engineCenter.first + 34f, engineCenter.second + 26f)
        state.engineTempF?.let { temperature ->
            // Highlight is tied to a real coolant reading; it is not a diagnosis.
            val tint = if (temperature >= 235) warning else gold
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.shader = RadialGradient(engineCenter.first, engineCenter.second, 42f,
                Color.argb(55, Color.red(tint), Color.green(tint), Color.blue(tint)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(engineCenter.first, engineCenter.second, 42f, paint)
            paint.shader = null
            for (x in floatArrayOf(1.43f, 1.60f, 1.77f, 1.94f)) {
                modelLine(floatArrayOf(x, -.48f, 1.29f), floatArrayOf(x, .27f, 1.29f), tint, 190, 1.5f)
            }
        }
    }

    private fun drawEnginePanel(canvas: Canvas) {
        glassPanel(canvas, enginePanel, state.diagnosticsExpanded)
        stroke(cyan, 2f, 220)
        canvas.drawRoundRect(RectF(1263f, 582f, 1272f, 615f), 4f, 4f, paint)
        canvas.drawCircle(1267.5f, 619f, 9f, paint)
        canvas.drawLine(1268f, 590f, 1268f, 622f, paint)
        for (i in 0..3) canvas.drawLine(1280f, 586f + i * 7f, 1285f, 586f + i * 7f, paint)
        text(canvas, "Engine Temp", 1304f, 587f, 19f, dim)
        text(canvas, state.engineTempF?.let { "$it°F" } ?: "—", 1304f, 628f, 35f, white)
        meter(canvas, RectF(1263f, 647f, 1510f, 658f), state.engineTempF?.let { (it - 80f) / 180f }, state.engineTempF?.let { it >= 235 } == true)
    }

    private fun drawStatusPanel(canvas: Canvas) {
        glassPanel(canvas, statusPanel)
        // Status comes from the controller. No decorative ROAD CLEAR approval.
        fitted(canvas, state.roadStatus, 1258f, 727f, 255f, 17f, white)
        fitted(canvas, state.roadDetail, 1258f, 753f, 255f, 12f, dim)
        val movement = when (state.vehicleMoving) { true -> "MOVING"; false -> "STOPPED"; null -> "SPEED UNKNOWN" }
        fitted(canvas, movement + state.heading?.let { " · $it" }.orEmpty(), 1258f, 780f, 255f, 13f, gold)
    }

    private fun drawFooter(canvas: Canvas) {
        text(canvas, "LIVE DATA ONLY", 82f, 846f, 12f, dim)
        text(canvas, if (surface == DrivingHudSurface.PHONE) "PHONE: INSTRUMENTS ONLY / NO CAMERA"
            else "STATIONARY EVALUATION ONLY", 82f, 868f, 11f,
            if (surface == DrivingHudSurface.PHONE) dim else warning)
        text(canvas, "I C A R U S", 800f, 848f, 19f, gold, Paint.Align.CENTER, light)
        text(canvas, "D R I V I N G  M O D E", 800f, 870f, 10f, white, Paint.Align.CENTER)
        if (surface != DrivingHudSurface.PHONE) return
        glassPanel(canvas, voiceHit, state.listening)
        stroke(if (state.listening) gold else cyan, 1.8f, 220)
        canvas.drawRoundRect(RectF(1361f, 838f, 1373f, 858f), 6f, 6f, paint)
        canvas.drawArc(RectF(1357f, 844f, 1377f, 864f), 0f, 180f, false, paint)
        canvas.drawLine(1367f, 865f, 1367f, 870f, paint)
        text(canvas, if (state.listening) "LISTENING…" else "VOICE", 1391f, 861f, 18f,
            if (state.listening) gold else white)
    }

    private fun drawAlert(canvas: Canvas, message: String) {
        val box = RectF(570f, 270f, 1070f, 335f)
        glassPanel(canvas, box, true)
        stroke(warning, 2f, 240)
        canvas.drawLine(box.left + 14f, box.top + 16f, box.left + 14f, box.bottom - 16f, paint)
        fitted(canvas, message, box.left + 29f, box.centerY() + 6f, box.width() - 50f, 17f, warning)
    }

    private fun meter(canvas: Canvas, rect: RectF, fraction: Float?, hot: Boolean) {
        stroke(gold, 1f, 155)
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        if (fraction == null || !fraction.isFinite()) return
        val amount = fraction.coerceIn(0f, 1f)
        if (amount == 0f) return
        val fill = RectF(rect.left, rect.top, rect.left + rect.width() * amount, rect.bottom)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, if (hot) gold else blue,
            if (hot) warning else cyan, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(fill, rect.height() / 2, rect.height() / 2, paint)
        paint.shader = null
    }

    private fun glassPanel(canvas: Canvas, rect: RectF, emphasized: Boolean = false) {
        val cut = min(17f, rect.height() * .16f)
        path.reset()
        path.moveTo(rect.left + cut, rect.top); path.lineTo(rect.right - cut * .5f, rect.top)
        path.lineTo(rect.right, rect.top + cut * .5f); path.lineTo(rect.right, rect.bottom - cut)
        path.lineTo(rect.right - cut, rect.bottom); path.lineTo(rect.left + cut * .5f, rect.bottom)
        path.lineTo(rect.left, rect.bottom - cut * .5f); path.lineTo(rect.left, rect.top + cut); path.close()
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(Color.argb(165, 18, 43, 68), Color.argb(100, 5, 16, 29), Color.argb(148, 11, 31, 49)),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        stroke(blue, if (emphasized) 1.5f else 1f, if (emphasized) 185 else 125)
        canvas.drawPath(path, paint)
        stroke(cyan, 1f, 165)
        canvas.drawLine(rect.left + cut, rect.top, rect.left + rect.width() * .38f, rect.top, paint)
        stroke(blue, 4f, 16)
        canvas.drawLine(rect.left + cut, rect.bottom - 3f, rect.right - cut, rect.bottom - 3f, paint)
    }

    private fun ring(canvas: Canvas, cx: Float, cy: Float, radius: Float, start: Float, sweep: Float, color: Int, width: Float, alpha: Int) {
        if (sweep <= 0f) return
        stroke(color, width, alpha)
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, start, sweep, false, paint)
    }

    private fun stroke(color: Int, width: Float, alpha: Int = 255) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.alpha = alpha
        paint.strokeWidth = width
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
                     align: Paint.Align = Paint.Align.LEFT, face: Typeface = normal) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        textPaint.typeface = face
        canvas.drawText(value, x, y, textPaint)
    }

    private fun fitted(canvas: Canvas, value: String, x: Float, y: Float, maxWidth: Float, size: Float,
                       color: Int, face: Typeface = normal) {
        textPaint.textSize = size
        textPaint.typeface = face
        // Ellipsize rather than let a long road name paint over another panel.
        val display = TextUtils.ellipsize(value.replace('\n', ' '), textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        text(canvas, display, x, y, size, color, face = face)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A display-only glasses HUD must not advertise or consume dead controls.
        if (surface != DrivingHudSurface.PHONE || onAction == null) return false
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = (event.x - offsetX) / scale
        val y = (event.y - offsetY) / scale
        when {
            hit(exitHit, x, y) -> onAction(DrivingHudAction.EXIT)
            hit(voiceHit, x, y) -> onAction(DrivingHudAction.VOICE)
            hit(engineHit, x, y) || hit(enginePanel, x, y) -> onAction(DrivingHudAction.SHOW_ENGINE)
            hit(diagnosticsHit, x, y) -> onAction(DrivingHudAction.TOGGLE_DIAGNOSTICS)
            hit(navigationHit, x, y) -> onAction(DrivingHudAction.TOGGLE_NAVIGATION)
        }
        performClick()
        return true
    }

    private fun hit(rect: RectF, x: Float, y: Float): Boolean {
        // Fine optical artwork can still have a usable phone touch target.
        val minimum = 48f * resources.displayMetrics.density / scale
        val dx = ((minimum - rect.width()) * .5f).coerceAtLeast(0f)
        val dy = ((minimum - rect.height()) * .5f).coerceAtLeast(0f)
        return x >= rect.left - dx && x <= rect.right + dx &&
            y >= rect.top - dy && y <= rect.bottom + dy
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        val LEFT_TURN = Regex("\\b(turn|keep|bear|slight|sharp)\\s+(to\\s+the\\s+)?left\\b|\\bleft\\s+turn\\b")
        val RIGHT_TURN = Regex("\\b(turn|keep|bear|slight|sharp)\\s+(to\\s+the\\s+)?right\\b|\\bright\\s+turn\\b")
        val STRAIGHT_AHEAD = Regex("\\b(go|continue|head)\\s+straight\\b|\\bstraight\\s+ahead\\b")
        const val WIDTH = 1600f
        const val HEIGHT = 900f
    }
}
