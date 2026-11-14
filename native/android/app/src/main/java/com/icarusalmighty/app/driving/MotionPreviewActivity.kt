package com.icarusalmighty.app.driving

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Seated phone test only. Phone motion is not glasses head tracking. */
class MotionPreviewActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private lateinit var status: TextView
    private lateinit var graphic: TextView
    private lateinit var calibrate: Button
    private val handler = Handler(Looper.getMainLooper())
    private val policy = MotionFadePolicy()
    private var current: FloatArray? = null
    private var previous: FloatArray? = null
    private var forward: FloatArray? = null
    private var lastSample = 0L
    private var lastFrame = 0L
    private var sampleTime = 0L
    private var speed = 0.0
    private var active = false
    private var reliable = false
    private var angle = 0.0
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtimeNanos()
            val fresh = reliable && current != null && now - lastSample in 0..150_000_000L
            calibrate.isEnabled = fresh
            val dt = if (lastFrame == 0L) 0.0 else (now-lastFrame)/1e9
            lastFrame = now
            if (fresh && forward != null) angle = separation(forward!!, current!!)
            graphic.alpha = policy.step(angle, speed, dt, fresh && forward != null)
            status.text = when {
                !fresh -> "Waiting for reliable phone motion…"
                forward == null -> "Hold the phone forward, then tap Set forward."
                graphic.alpha < 0.99f -> "Hidden / fading • return forward and hold steady"
                else -> "Visible • turn the phone to test the fade"
            }
            handler.postDelayed(this, 16)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SensorManager::class.java)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(5, 18, 30))
        }
        fun label(text: String, size: Float) = TextView(this).apply {
            this.text = text; textSize = size; gravity = Gravity.CENTER
            setTextColor(Color.rgb(80, 220, 255)); setPadding(16, 12, 16, 12)
        }
        root.addView(label("ICARUS TEST • MOTION FADE", 22f))
        root.addView(label("Seated test: rotate your PHONE, not your head.\nThis is a sample graphic, not navigation or XREAL tracking.", 16f))
        status = label("Waiting for phone motion…", 17f); root.addView(status)
        graphic = label("↑\nFORWARD\nTEST OVERLAY", 34f).apply { alpha = 0f }
        root.addView(graphic, LinearLayout.LayoutParams(-1, 0, 1f))
        calibrate = Button(this).apply {
            text = "Set forward"; isEnabled = false
            setOnClickListener {
                if (reliable && SystemClock.elapsedRealtimeNanos()-lastSample in 0..150_000_000L) {
                    forward = current?.copyOf(); policy.reset(); graphic.alpha = 0f
                }
            }
        }
        root.addView(calibrate)
        root.addView(Button(this).apply { text = "Back to ICARUS Test"; setOnClickListener { finish() } })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left+16, bars.top+12, bars.right+16, bars.bottom+12); insets
        }
        setContentView(root)
    }
    override fun onResume() {
        super.onResume(); active = true
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null || !sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)) {
            status.text = "This phone does not provide a supported rotation sensor."
            return
        }
        handler.post(tick)
    }
    override fun onPause() {
        active = false; handler.removeCallbacks(tick); sensors.unregisterListener(this)
        current = null; previous = null; forward = null; reliable = false
        lastFrame = 0; sampleTime = 0; policy.reset(); graphic.alpha = 0f
        super.onPause()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) { reliable = false; policy.reset(); graphic.alpha = 0f }
    }
    override fun onSensorChanged(event: SensorEvent) {
        val q = FloatArray(4); SensorManager.getQuaternionFromVector(q, event.values)
        val norm = sqrt(q.sumOf { (it * it).toDouble() })
        if (!norm.isFinite() || norm < 0.5 || event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            reliable = false; policy.reset(); graphic.alpha = 0f; return
        }
        for (i in q.indices) q[i] = (q[i]/norm).toFloat()
        val dt = (event.timestamp - sampleTime)/1e9
        speed = if (previous != null && dt > 0 && dt <= 0.15) separation(previous!!, q)/dt else 0.0
        if (dt <= 0 || dt > 0.15) policy.reset()
        previous = q; current = q; sampleTime = event.timestamp
        lastSample = event.timestamp; reliable = true
    }
    private fun separation(a: FloatArray, b: FloatArray): Double =
        Math.toDegrees(2 * acos(abs(a.indices.sumOf { a[it].toDouble()*b[it] }).coerceIn(0.0, 1.0)))
}
