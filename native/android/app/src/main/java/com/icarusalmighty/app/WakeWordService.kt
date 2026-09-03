package com.icarusalmighty.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import androidx.core.app.NotificationCompat

class WakeWordService : Service() {
    private val engine: WakeWordEngine by lazy { SherpaWakeWordEngine(this) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Listening for “Hey Icarus”"))
        engine.start(::onWakeDetected).onFailure {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(it.message ?: "Wake listener could not start"))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun onWakeDetected() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 82).apply {
                startTone(ToneGenerator.TONE_PROP_ACK, 180)
                android.os.Handler(mainLooper).postDelayed({ release() }, 260L)
            }
        }
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_WAKE_WORD, true)
        runCatching { startActivity(launch) }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("ICARUS activated"))
        stopSelf()
    }

    override fun onDestroy() {
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
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
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
        const val ACTION_STOP = "com.icarusalmighty.app.STOP_WAKE_WORD"
    }
}