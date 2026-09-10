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
 * capture, then Base44/native safety re-arms the listener after the turn.
 */
class WakeWordService : Service() {
    private val engine: WakeWordEngine by lazy { SherpaWakeWordEngine(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Listening for “Hey ICARUS”"))
        armEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun armEngine() {
        if (stopping) return
        engine.start(::onWakeDetected).onFailure { error ->
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(error.message ?: "Wake listener could not start"))
            stopSelf()
        }
    }

    private fun onWakeDetected() {
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_WAKE_WORD, true)

        val launched = runCatching { startActivity(launch) }.isSuccess
        if (launched) {
            // MainActivity provides the single acknowledgement tone and then
            // owns the microphone for speech recognition. Stop this service so
            // the two recognizers never fight over AudioRecord.
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("ICARUS activated"))
            stopSelf()
            return
        }

        // If Android refused to surface the activity, do not silently lose
        // hands-free mode. The Sherpa worker releases its recorder after this
        // callback returns, then we arm a fresh stream.
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("Wake detected. Re-arming listener…"))
        mainHandler.postDelayed(::armEngine, REARM_AFTER_LAUNCH_FAILURE_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
    }
}
