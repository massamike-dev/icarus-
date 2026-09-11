package com.icarusalmighty.app.spatial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.icarusalmighty.app.MainActivity
import com.icarusalmighty.app.ObdManager
import com.icarusalmighty.app.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single live OBD-II connection while the XREAL companion is active.
 * Telemetry is exposed only on loopback and requires a per-launch bearer token.
 * No simulated vehicle values and no ECU write/control commands are provided.
 */
class SpatialTelemetryService : Service() {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(2)
    private val idleHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var obd: ObdManager? = null

    @Volatile private var sessionToken: String = ""
    @Volatile private var obdAddress: String = ""
    @Volatile private var latestPayload: String = offlinePayload("STARTING")
    @Volatile private var lastClientAt: Long = 0L
    @Volatile private var startedAt: Long = 0L
    @Volatile private var clientSeen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSpatialSession()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_OBD_ADDRESS).orEmpty().trim()
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty().trim()
                if (address.isBlank() || token.length < 32) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (running.compareAndSet(false, true)) {
                    obdAddress = address
                    sessionToken = token
                    startedAt = System.currentTimeMillis()
                    startForeground(NOTIFICATION_ID, buildNotification("Connecting live OBD telemetry"))
                    workers.execute(::runTelemetryLoop)
                    workers.execute(::runLoopbackServer)
                    scheduleIdleCheck()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        idleHandler.removeCallbacksAndMessages(null)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { obd?.disconnect() }
        obd = null
        workers.shutdownNow()
        super.onDestroy()
    }

    private fun runTelemetryLoop() {
        val manager = ObdManager(applicationContext)
        obd = manager
        while (running.get()) {
            try {
                latestPayload = offlinePayload("CONNECTING")
                manager.connect(obdAddress)
                updateNotification("XREAL Spatial HUD • OBD live")
                while (running.get()) {
                    val snapshot = manager.snapshot()
                    latestPayload = JSONObject()
                        .put("connected", true)
                        .put("sampleTimeMs", System.currentTimeMillis())
                        .put("telemetry", snapshot)
                        .toString()
                    Thread.sleep(650L)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                latestPayload = offlinePayload(e.message ?: "OBD unavailable")
                updateNotification("XREAL Spatial HUD • OBD unavailable")
                runCatching { manager.disconnect() }
                if (running.get()) runCatching { Thread.sleep(2500L) }
            }
        }
        runCatching { manager.disconnect() }
    }

    private fun runLoopbackServer() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 8)
            }
            serverSocket = server
            while (running.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                handleClient(socket)
            }
        } catch (e: Exception) {
            latestPayload = offlinePayload("Spatial bridge unavailable: ${e.message ?: "server error"}")
            stopSelf()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = reader.readLine().orEmpty()
            var authorization = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authorization = line.substringAfter(':').trim()
                }
            }

            val authorized = authorization == "Bearer $sessionToken"
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            val (status, body) = when {
                !authorized -> "401 Unauthorized" to JSONObject().put("error", "unauthorized").toString()
                requestLine.startsWith("GET ") && path == "/telemetry" -> {
                    clientSeen = true
                    lastClientAt = System.currentTimeMillis()
                    "200 OK" to latestPayload
                }
                requestLine.startsWith("GET ") && path == "/health" -> {
                    clientSeen = true
                    lastClientAt = System.currentTimeMillis()
                    "200 OK" to JSONObject()
                        .put("ok", true)
                        .put("liveTelemetryOnly", true)
                        .put("port", PORT)
                        .toString()
                }
                else -> "404 Not Found" to JSONObject().put("error", "not_found").toString()
            }
            writeResponse(client, status, body)
        }
    }

    private fun writeResponse(socket: Socket, status: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))
        writer.write("HTTP/1.1 $status\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun offlinePayload(message: String): String = JSONObject()
        .put("connected", false)
        .put("sampleTimeMs", System.currentTimeMillis())
        .put("error", message)
        .put("telemetry", JSONObject.NULL)
        .toString()

    private fun scheduleIdleCheck() {
        idleHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!running.get()) return
                val now = System.currentTimeMillis()
                val noClientTimeout = !clientSeen && now - startedAt > 90_000L
                val idleClientTimeout = clientSeen && now - lastClientAt > 45_000L
                if (noClientTimeout || idleClientTimeout) {
                    stopSpatialSession()
                } else {
                    idleHandler.postDelayed(this, 10_000L)
                }
            }
        }, 10_000L)
    }

    private fun stopSpatialSession() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { obd?.disconnect() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ICARUS Spatial HUD", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live read-only vehicle telemetry for the XREAL Spatial HUD"
                }
            )
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ICARUS Spatial HUD")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.icarusalmighty.app.spatial.START"
        const val ACTION_STOP = "com.icarusalmighty.app.spatial.STOP"
        const val EXTRA_OBD_ADDRESS = "obd_address"
        const val EXTRA_TOKEN = "session_token"
        const val PORT = 43215
        const val COMPANION_PACKAGE = "com.icarusalmighty.spatial"
        private const val CHANNEL_ID = "icarus_spatial_hud"
        private const val NOTIFICATION_ID = 1601
    }
}
