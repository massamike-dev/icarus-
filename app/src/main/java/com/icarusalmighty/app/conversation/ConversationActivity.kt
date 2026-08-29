package com.icarusalmighty.app.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.icarusalmighty.app.audio.BluetoothAudioRouter
import com.icarusalmighty.app.bridge.SessionTokenProvider
import com.icarusalmighty.app.wake.SpeechCommandCapture
import kotlinx.coroutines.launch
import java.util.Locale

class ConversationActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var state: TextView
    private lateinit var transcript: TextView
    private lateinit var speech: TextToSpeech
    private lateinit var capture: SpeechCommandCapture
    private lateinit var bluetooth: BluetoothAudioRouter
    private var active = false
    private var conversationId: String? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginConversation()
        else state.text = "Microphone permission required"
    }

    // Replaced by the Base44 sign-in module during app assembly.
    private val gateway by lazy { ConversationGateway(SessionTokenProvider { getSharedPreferences("icarus_auth", MODE_PRIVATE).getString("session_token", null) }) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = TextToSpeech(this, this); capture = SpeechCommandCapture(this); bluetooth = BluetoothAudioRouter(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48); setBackgroundColor(0xFF07111F.toInt()) }
        root.addView(TextView(this).apply { text = "I.C.A.R.U.S."; textSize = 34f; setTextColor(0xFFC8A24A.toInt()) })
        state = TextView(this).apply { text = "Idle"; textSize = 22f; setTextColor(0xFFF5F1E8.toInt()); setPadding(0, 40, 0, 24) }
        transcript = TextView(this).apply { text = "Conversation mode keeps listening after each response."; textSize = 17f; setTextColor(0xFFD7D2C7.toInt()) }
        root.addView(state); root.addView(transcript)
        root.addView(Button(this).apply { text = "Start conversation"; setOnClickListener { startConversation() } })
        root.addView(Button(this).apply { text = "End conversation"; setOnClickListener { stopConversation() } })
        setContentView(root)
    }

    private fun startConversation() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@ConversationActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this@ConversationActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needed.isNotEmpty()) { permissions.launch(needed.toTypedArray()); return }
        beginConversation()
    }

    private fun beginConversation() {
        active = true; bluetooth.routeForConversation(); listen()
    }

    private fun listen() {
        if (!active) return
        state.text = "Listening"
        capture.listen { heard ->
            if (!active) return@listen
            val text = heard?.trim().orEmpty()
            if (text.equals("end conversation", true) || text.equals("goodbye icarus", true)) { stopConversation(); return@listen }
            if (text.isBlank()) { state.text = "I didn't catch that"; listen(); return@listen }
            transcript.text = "You: $text"; state.text = "Thinking"
            lifecycleScope.launch {
                gateway.send(conversationId, text).onSuccess { reply ->
                    conversationId = reply.conversationId; transcript.text = "You: $text\n\nICARUS: ${reply.content}"; speak(reply.content)
                }.onFailure { state.text = "Connection problem"; transcript.append("\n\n${it.message}") }
            }
        }
    }

    private fun speak(text: String) {
        state.text = "Speaking"
        speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "icarus_reply")
    }

    override fun onInit(status: Int) {
        speech.language = Locale.US
        speech.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) = runOnUiThread { if (active) listen() }
            override fun onDone(utteranceId: String?) = runOnUiThread { if (active) listen() }
        })
    }

    private fun stopConversation() { active = false; capture.cancel(); speech.stop(); bluetooth.release(); state.text = "Conversation ended" }
    override fun onDestroy() { active = false; capture.destroy(); speech.shutdown(); bluetooth.release(); super.onDestroy() }
}
