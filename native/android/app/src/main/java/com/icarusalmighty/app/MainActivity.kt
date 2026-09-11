package com.icarusalmighty.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.icarusalmighty.app.update.PlayUpdateManager
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var rootView: FrameLayout
    private lateinit var webView: WebView
    private lateinit var splashView: ImageView
    private var pendingWake = false
    private var commandRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var metaWearables: MetaWearablesController
    private lateinit var nativeBridge: IcarusNativeBridge

    private val wakePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWakeWordService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWake = intent.getBooleanExtra(EXTRA_WAKE_WORD, false)

        rootView = FrameLayout(this)
        webView = WebView(this)
        metaWearables = MetaWearablesController(this, ::dispatchNativeResult)
        nativeBridge = IcarusNativeBridge(this, metaWearables, ::dispatchNativeResult)
        splashView = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            setBackgroundColor(android.graphics.Color.rgb(2, 8, 23))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = getString(R.string.app_name)
        }
        webView.clearCache(true)
        rootView.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootView.addView(
            splashView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(rootView)
        configureWebView()
        loadIcarus()
        PlayUpdateManager.checkOnLaunch(this)
    }

    override fun onResume() {
        super.onResume()
        PlayUpdateManager.resumeIfNeeded(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_WAKE_WORD, false)) {
            dispatchWakeWord()
        }
    }

    override fun onDestroy() {
        commandRecognizer?.destroy()
        commandRecognizer = null
        if (::nativeBridge.isInitialized) nativeBridge.close()
        if (::metaWearables.isInitialized) metaWearables.close()
        super.onDestroy()
    }

    private fun isTrustedPage(): Boolean =
        TrustedWebPolicy.isTrustedUrl(webView.url, BuildConfig.ICARUS_WEB_URL)

    private fun dispatchNativeResult(payload: String) {
        webView.post {
            if (!isTrustedPage()) return@post
            webView.evaluateJavascript(
                "window.ICARUS_NATIVE_RESULT && window.ICARUS_NATIVE_RESULT(" + JSONObject.quote(payload) + ");",
                null
            )
        }
    }

    private fun dispatchNativeStatus(payload: String) {
        webView.post {
            if (!isTrustedPage()) return@post
            webView.evaluateJavascript(
                "window.ICARUS_NATIVE_STATUS && window.ICARUS_NATIVE_STATUS(" + JSONObject.quote(payload) + ");",
                null
            )
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
            userAgentString = "$userAgentString ICARUSNative/${BuildConfig.VERSION_NAME}"
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (splashView.parent != null) {
                    splashView.animate()
                        .alpha(0f)
                        .setDuration(320L)
                        .withEndAction { rootView.removeView(splashView) }
                        .start()
                }
                if (!TrustedWebPolicy.isTrustedUrl(url, BuildConfig.ICARUS_WEB_URL)) return
                notifyNativeStatus()
                if (pendingWake) {
                    pendingWake = false
                    dispatchWakeWord()
                }
            }
        }

        val allowedOrigin = TrustedWebPolicy.originRule(BuildConfig.ICARUS_WEB_URL)
        if (allowedOrigin != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                NATIVE_CHANNEL,
                setOf(allowedOrigin)
            ) { _, message, _, isMainFrame, _ ->
                // Same-origin iframes do not get phone-control privileges.
                if (!isMainFrame) return@addWebMessageListener
                handleNativeMessage(message.data)
            }
        }
    }

    private fun handleNativeMessage(raw: String?) {
        val text = raw.orEmpty()
        val payload = try { JSONObject(text) } catch (_: Exception) {
            dispatchNativeResult(JSONObject().put("ok", false).put("error", "invalid_payload").toString())
            return
        }
        if (payload.optString("bridgeRequest") == "status") {
            val requestId = payload.optString("requestId").ifBlank { null }
            val status = JSONObject(nativeBridge.getStatus())
                .put("requestId", requestId ?: JSONObject.NULL)
                .toString()
            dispatchNativeStatus(status)
            return
        }

        val result = nativeBridge.executeAction(text)
        if (result.isNotBlank()) dispatchNativeResult(result)
    }

    private fun loadIcarus() {
        val url = BuildConfig.ICARUS_WEB_URL.trim()
        if (TrustedWebPolicy.isTrustedUrl(url, url)) {
            webView.loadUrl(url)
            return
        }
        webView.loadDataWithBaseURL(
            null,
            """
            <!doctype html><html><body style='margin:0;background:#020817;color:#eee;font-family:sans-serif'>
            <div style='max-width:720px;margin:12vh auto;padding:32px'>
            <h1 style='color:#d4af37'>I.C.A.R.U.S. Native Host</h1>
            <p>The Android bridge is installed, but ICARUS_WEB_URL has not been set to a valid HTTPS ICARUS origin.</p>
            <p>Set the published Base44 HTTPS URL in <code>native/android/gradle.properties</code>, rebuild, and install again.</p>
            </div></body></html>
            """.trimIndent(),
            "text/html",
            "utf-8",
            null
        )
    }

    fun requestWakePermissionFromDisclosure() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) startWakeWordService() else wakePermissions.launch(missing.toTypedArray())
    }

    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun notifyNativeStatus() {
        if (!isTrustedPage()) return
        dispatchNativeStatus(nativeBridge.getStatus())
    }

    private fun dispatchWakeWord() {
        if (!::webView.isInitialized) {
            pendingWake = true
            return
        }
        webView.post {
            if (!isTrustedPage()) {
                pendingWake = true
                loadIcarus()
                return@post
            }
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('icarus-wake-word',{detail:{phrase:'hey icarus'}}));",
                null
            )
            playWakeTone()
            mainHandler.postDelayed({ startNativeCommandCapture() }, 550L)
        }
    }

    private fun playWakeTone() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 78).apply {
                startTone(ToneGenerator.TONE_PROP_ACK, 180)
                mainHandler.postDelayed({ release() }, 260L)
            }
        }
    }

    private fun startNativeCommandCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            dispatchVoiceError("Microphone permission is required.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            dispatchVoiceError("Android speech recognition is unavailable.")
            restartWakeWordLater(1500L)
            return
        }
        commandRecognizer?.destroy()
        commandRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = dispatchVoiceState("listening")
                override fun onBeginningOfSpeech() = dispatchVoiceState("hearing")
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = dispatchVoiceState("processing")
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear a command."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition is unavailable offline on this device."
                        else -> "I couldn't capture that command."
                    }
                    dispatchVoiceError(message)
                    restartWakeWordLater(1600L)
                }
                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    if (transcript.isBlank()) {
                        dispatchVoiceError("I didn't catch that.")
                        restartWakeWordLater(1600L)
                    } else {
                        dispatchNativeCommand(transcript)
                        restartWakeWordLater(25_000L)
                    }
                }
            })
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            )
        }
    }

    private fun dispatchNativeCommand(transcript: String) {
        val quoted = JSONObject.quote(transcript)
        webView.post {
            if (!isTrustedPage()) return@post
            webView.evaluateJavascript(
                "sessionStorage.setItem('icarus-native-command',$quoted);" +
                    "window.dispatchEvent(new CustomEvent('icarus-native-command',{detail:{transcript:$quoted}}));",
                null
            )
        }
    }

    private fun dispatchVoiceState(state: String) {
        webView.post {
            if (!isTrustedPage()) return@post
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('icarus-native-voice-state',{detail:{state:${JSONObject.quote(state)}}}));",
                null
            )
        }
    }

    private fun dispatchVoiceError(message: String) {
        webView.post {
            if (!isTrustedPage()) return@post
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('icarus-native-voice-error',{detail:{message:${JSONObject.quote(message)}}}));",
                null
            )
        }
    }

    private fun restartWakeWordLater(delayMs: Long) {
        mainHandler.postDelayed({ startWakeWordService() }, delayMs)
    }

    companion object {
        const val EXTRA_WAKE_WORD = "wake_word"
        private const val NATIVE_CHANNEL = "ICARUS_NATIVE_CHANNEL"
    }
}
