package com.icarusalmighty.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Opt-in foreground wake-word listener.
 *
 * The service stays sticky across ordinary process pressure once the user has
 * enabled hands-free listening, but an explicit Stop action still shuts it
 * down. Detection hands microphone ownership to MainActivity for command
 * capture, then ICARUS web/native safety re-arms the listener after the turn.
 */
class WakeWordService : Service() {
    private val engine: SherpaWakeWordEngine by lazy { SherpaWakeWordEngine(this) { message ->
        mainHandler.post {
            lastError = message
            listenerState = "ERROR"
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
            stopping = true
            stopSelf()
        }
    } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopping = false
    private var session: HandsFreeSession? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        listenerState = "STARTING"
        lastError = null
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting wake-word engine…"))
        if (isEnabled(this)) armEngine() else { stopping = true; stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun armEngine() {
        if (stopping || session != null) return
        if (!isEnabled(this)) { stopping = true; stopSelf(); return }
        engine.start(::onWakeDetected)
            .onSuccess {
                listenerState = "LISTENING"
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Listening for “Hey ICARUS”"))
            }
            .onFailure { error ->
                lastError = error.message ?: "Wake listener could not start"
                listenerState = "ERROR"
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(lastError!!))
                stopSelf()
            }
    }

    private fun onWakeDetected() {
        mainHandler.post {
            if (stopping || session != null) return@post
            engine.stop()
            runCatching {
                android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 75).apply {
                    startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
                    mainHandler.postDelayed({ release() }, 250)
                }
            }
            session = HandsFreeSession(this, { value ->
                listenerState = value
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(value.lowercase().replace('_', ' ')))
            }, {
                session = null
                mainHandler.postDelayed(::armEngine, 350)
            })
            mainHandler.postDelayed({ if (!stopping) session?.begin() }, 300)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        session?.close()
        session = null
        engine.stop()
        activeService = null
        if (listenerState != "ERROR") listenerState = "STOPPED"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun diagnostics(): org.json.JSONObject = engine.diagnostics()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ICARUS wake word", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("I.C.A.R.U.S.")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "icarus_wake_word"
        private const val NOTIFICATION_ID = 4401
        private const val REARM_AFTER_LAUNCH_FAILURE_MS = 1200L
        const val ACTION_STOP = "com.icarusalmighty.app.STOP_WAKE_WORD"
        fun isEnabled(context: android.content.Context): Boolean = context.getSharedPreferences("icarus_voice", MODE_PRIVATE).getBoolean("enabled", false)
        fun setEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences("icarus_voice", MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        }
        fun cancelTurn() {
            activeService?.let { service -> service.mainHandler.post {
                service.session?.close()
                service.session = null
                service.mainHandler.removeCallbacksAndMessages(null)
                service.armEngine()
            } }
        }
        @Volatile var listenerState: String = "STOPPED"
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile private var activeService: WakeWordService? = null

        fun diagnostics(): org.json.JSONObject = activeService?.diagnostics() ?: org.json.JSONObject()
    }
}
