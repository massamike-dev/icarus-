package com.icarusalmighty.app.wake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.icarusalmighty.app.MainActivity
import com.icarusalmighty.app.R

class WakeWordService : Service() {
    private val engine: WakeWordEngine by lazy { EnrolledWakeWordEngine(this) }
    private val commandCapture: SpeechCommandCapture by lazy { SpeechCommandCapture(this) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ICARUS is listening")
            .setContentText("Say Hey ICARUS. Stops when the phone restarts.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        engine.start(::onWakeDetected).onFailure {
            updateNotification("Wake model not installed — open ICARUS to finish setup")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY // deliberate: do not return after reboot or OS termination
    }

    private fun onWakeDetected() {
        engine.stop()
        updateNotification("ICARUS is listening for your command…")
        commandCapture.listen { command ->
            if (!command.isNullOrBlank()) postCommandReview(command)
            else updateNotification("I didn't catch that. Say Hey ICARUS and try again.")
            engine.start(::onWakeDetected)
        }
    }

    private fun postCommandReview(command: String) {
        val reviewIntent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_COMMAND_READY)
            .putExtra(EXTRA_COMMAND, command)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val review = PendingIntent.getActivity(this, 3, reviewIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ICARUS heard a command")
            .setContentText(command)
            .setStyle(NotificationCompat.BigTextStyle().bigText(command))
            .setContentIntent(review)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun updateNotification(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ICARUS Native Bridge")
            .setContentText(text).setOngoing(true).build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ICARUS background listening", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() { engine.stop(); commandCapture.destroy(); isRunning = false; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.icarusalmighty.app.STOP_LISTENING"
        const val ACTION_COMMAND_READY = "com.icarusalmighty.app.COMMAND_READY"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL = "icarus_wake"
        private const val NOTIFICATION_ID = 1107
        @Volatile var isRunning = false
    }
}
