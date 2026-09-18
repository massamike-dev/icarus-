package com.icarusalmighty.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/** Opt-in native listener. One service owns the microphone and each command turn. */
class WakeWordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine: SherpaWakeWordEngine by lazy { SherpaWakeWordEngine(this) { message ->
        mainHandler.post {
            if (!stopping && session == null && !engine.diagnostics().optBoolean("engineRunning")) {
                lastError = message
                listenerState = "ERROR"
                updateNotification(message)
                stopping = true
                stopSelf()
            }
        }
    } }
    @Volatile private var stopping = false
    private var session: HandsFreeSession? = null
    private var generation = 0L
    private var armedAt = 0L
    private var lastTurnAt = 0L
    private var lastTrigger = "none"
    private var lastNotification = ""
    private var turnLock: PowerManager.WakeLock? = null
    private val monitor = object : Runnable {
        override fun run() {
            if (stopping) return
            if (session == null && listenerState != "ERROR") {
                val audio = engine.diagnostics()
                val age = audio.optLong("audioAgeMs", -1)
                val stalled = System.currentTimeMillis() - armedAt > 5000 &&
                    (!audio.optBoolean("engineRunning") || age < 0 || age > 5000)
                when {
                    audio.optBoolean("microphoneSilenced") -> {
                        listenerState = "MICROPHONE_BLOCKED"
                        updateNotification("Android is silencing the microphone. Stop other listeners or recordings, then retry.")
                    }
                    stalled -> {
                        listenerState = "AUDIO_STALLED"
                        updateNotification("No microphone samples are arriving. Stop listening, then enable it again.")
                    }
                    else -> {
                        listenerState = "LISTENING"
                        updateNotification(lastError ?: "Listening for “Hey ICARUS”")
                    }
                }
            }
            mainHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        listenerState = "STARTING"
        lastError = null
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting wake-word engine…"))
        if (isEnabled(this)) {
            armEngine()
            mainHandler.postDelayed(monitor, 2000)
        } else { stopping = true; stopSelf() }
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
        if (engine.diagnostics().optBoolean("engineRunning")) return
        val expectedGeneration = ++generation
        armedAt = System.currentTimeMillis()
        listenerState = "STARTING"
        engine.start {
            // AudioRecord keeps the CPU awake during capture. The bounded lock
            // bridges command/network/TTS work after the wake recorder closes.
            acquireTurnLock()
            mainHandler.post {
                if (!stopping && expectedGeneration == generation && session == null) beginTurn("wake_phrase")
                if (session == null) releaseTurnLock()
            }
        }.onSuccess {
            listenerState = "LISTENING"
            updateNotification(lastError ?: "Listening for “Hey ICARUS”")
        }.onFailure { error ->
            lastError = error.message ?: "Wake listener could not start"
            listenerState = "ERROR"
            updateNotification(lastError!!)
            stopping = true
            stopSelf()
        }
    }

    private fun beginTurn(trigger: String) {
        if (stopping || session != null || !isEnabled(this)) return
        generation++
        acquireTurnLock()
        engine.stop()
        lastError = null
        lastTrigger = trigger
        lastTurnAt = System.currentTimeMillis()
        listenerState = "PREPARING_VOICE"
        updateNotification("Preparing voice…")
        session = HandsFreeSession(this, { value ->
            listenerState = value
            updateNotification(value.lowercase().replace('_', ' '))
        }, {
            session = null
            releaseTurnLock()
            mainHandler.postDelayed(::armEngine, 350)
        }, { message ->
            lastError = message
            updateNotification(message)
        })
        mainHandler.postDelayed({ if (!stopping) session?.begin() }, 150)
    }

    private fun acquireTurnLock() {
        synchronized(this) {
            if (stopping) return
            val lock = turnLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ICARUS:voiceTurn")
                .also { it.setReferenceCounted(false); turnLock = it }
            if (!lock.isHeld) lock.acquire(180_000L)
        }
    }

    private fun releaseTurnLock() {
        synchronized(this) { turnLock?.let { if (it.isHeld) it.release() } }
    }

    override fun onDestroy() {
        stopping = true
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        session?.close()
        session = null
        engine.stop()
        releaseTurnLock()
        activeService = null
        if (listenerState != "ERROR") listenerState = "STOPPED"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun diagnostics(): JSONObject = engine.diagnostics()
        .put("lastTurnAt", lastTurnAt)
        .put("lastTrigger", lastTrigger)
        .put("talkNowSupported", true)
        .put("mediaVolumePercent", getSystemService(AudioManager::class.java).let {
            100 * it.getStreamVolume(AudioManager.STREAM_MUSIC) / it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        })

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ICARUS wake word", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification(text: String) {
        if (text == lastNotification) return
        lastNotification = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (BuildConfig.PRIVATE_TEST) "ICARUS Test" else "I.C.A.R.U.S.")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
        fun isEnabled(context: android.content.Context): Boolean = context.getSharedPreferences("icarus_voice", MODE_PRIVATE).getBoolean("enabled", false)
        fun setEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences("icarus_voice", MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        }
        /** Called only from the trusted foreground UI; does not enable persistent listening. */
        fun requestTurn(): Boolean {
            val service = activeService ?: return false
            if (service.stopping || !isEnabled(service) || service.session != null) return false
            service.mainHandler.post { service.beginTurn("talk_now") }
            return true
        }
        fun cancelTurn() {
            activeService?.let { service -> service.mainHandler.post {
                service.session?.close()
                service.session = null
                service.generation++
                service.releaseTurnLock()
                service.mainHandler.removeCallbacksAndMessages(null)
                service.engine.stop()
                service.armEngine()
                service.mainHandler.postDelayed(service.monitor, 2000)
            } }
        }
        @Volatile var listenerState: String = "STOPPED"
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile private var activeService: WakeWordService? = null
        fun diagnostics(): JSONObject = activeService?.diagnostics() ?: JSONObject().put("talkNowSupported", true)
    }
}
