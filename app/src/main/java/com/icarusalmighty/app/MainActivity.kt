package com.icarusalmighty.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.icarusalmighty.app.wake.WakeWordService
import com.icarusalmighty.app.wake.WakeEnrollment
import com.icarusalmighty.app.wake.WakeTemplateStore
import com.icarusalmighty.app.conversation.ConversationActivity
import com.icarusalmighty.app.update.PlayUpdateManager
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var enrollAfterPermission = false
    private val listenerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(WakeWordService.EXTRA_STATE)?.let(::renderStatus)
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (enrollAfterPermission) { enrollAfterPermission = false; enrollWakePhrase() } else startListener()
        }
        else renderStatus("Microphone permission is required for Hey ICARUS.")
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            listenerStateReceiver,
            IntentFilter(WakeWordService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        renderStatus(if (WakeWordService.isRunning) "Listening for Hey ICARUS." else "Off until you enable it after this restart.")
    }

    override fun onStop() {
        unregisterReceiver(listenerStateReceiver)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(0xFF07111F.toInt())
        }
        root.addView(TextView(this).apply {
            text = "I.C.A.R.U.S. Native Bridge"
            textSize = 26f
            setTextColor(0xFFC8A24A.toInt())
        })
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFF5F1E8.toInt())
            setPadding(0, 32, 0, 32)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Enroll Hey ICARUS"
            setOnClickListener { enrollWakePhrase() }
        })
        root.addView(Button(this).apply {
            text = "Enable until restart"
            setOnClickListener { requestAndStart() }
        })
        root.addView(Button(this).apply {
            text = "Open Conversation Mode"
            setOnClickListener { startActivity(Intent(this@MainActivity, ConversationActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Stop background listening"
            setOnClickListener {
                stopService(Intent(this@MainActivity, WakeWordService::class.java))
                renderStatus("Background listening is off.")
            }
        })
        root.addView(Button(this).apply {
            text = "Check for updates"
            setOnClickListener { PlayUpdateManager.check(this@MainActivity, silent = false) }
        })
        root.addView(Button(this).apply {
            text = "Battery settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        setContentView(root)
        renderStatus(if (WakeWordService.isRunning) "Listening for Hey ICARUS." else "Off until you enable it after this restart.")
        PlayUpdateManager.checkOnLaunch(this)
        if (intent?.action == WakeWordService.ACTION_COMMAND_READY) {
            intent.getStringExtra(WakeWordService.EXTRA_COMMAND)?.let { CommandReviewActivity.launch(this, it) }
        }
    }

    private fun requestAndStart() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
            if (android.os.Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needed.isEmpty()) startListener() else permissions.launch(needed.toTypedArray())
    }

    private fun startListener() {
        if (!WakeTemplateStore(this).isEnrolled()) {
            renderStatus("Hey ICARUS is not enrolled. Tap Enroll Hey ICARUS first.")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
        renderStatus("Starting background listener…")
    }

    private fun enrollWakePhrase() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            enrollAfterPermission = true
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return
        }
        renderStatus("Say ‘Hey ICARUS’ clearly five times. Recording 1 of 5…")
        thread {
            val templates = mutableListOf<Array<FloatArray>>()
            repeat(5) { index ->
                runOnUiThread { renderStatus("Say ‘Hey ICARUS’ — recording ${index + 1} of 5…") }
                Thread.sleep(600)
                templates += WakeEnrollment.capture()
                Thread.sleep(500)
            }
            WakeTemplateStore(this).save(templates)
            runOnUiThread { renderStatus("Hey ICARUS enrolled. Tap Enable until restart.") }
        }
    }

    private fun renderStatus(value: String) { status.text = value }
}
