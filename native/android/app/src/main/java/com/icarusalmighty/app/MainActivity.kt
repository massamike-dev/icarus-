package com.icarusalmighty.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebSettings
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
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
    private var pendingWakePermissionRequestId: String? = null
    private var loadErrorShown = false
    private var pageGeneration = 0L
    private val requestPages = ConcurrentHashMap<String, Long>()

    private val wakePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val requestId = pendingWakePermissionRequestId
        pendingWakePermissionRequestId = null
        if (hasMicrophonePermission()) {
            startWakeWordService()
            dispatchWakeStatusAfterStart(requestId)
        } else {
            val permanentlyDenied =
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            if (permanentlyDenied) openIcarusPermissionSettings()
            dispatchNativeResult(
                JSONObject()
                    .put("ok", false)
                    .put("requestId", requestId ?: JSONObject.NULL)
                    .put("error", if (permanentlyDenied) "microphone_permission_blocked" else "microphone_permission_denied")
                    .put("message", if (permanentlyDenied) {
                        "Microphone access is blocked. ICARUS opened App permissions; allow Microphone, then return and enable Hey ICARUS again."
                    } else {
                        "Microphone access is required for Hey ICARUS."
                    })
                    .put("settingsOpened", permanentlyDenied)
                    .toString()
            )
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
        ensureWakeListenerRunning()
        PlayUpdateManager.checkOnLaunch(this)
    }

    override fun onResume() {
        super.onResume()
        ensureWakeListenerRunning()
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
        pageGeneration++
        requestPages.clear()
        mainHandler.removeCallbacksAndMessages(null)
        commandRecognizer?.destroy()
        commandRecognizer = null
        if (::nativeBridge.isInitialized) nativeBridge.close()
        if (::metaWearables.isInitialized) metaWearables.close()
        super.onDestroy()
    }

    private fun isTrustedPage(): Boolean =
        TrustedWebPolicy.isTrustedUrl(webView.url, RuntimeMode.activeUrl(this))

    private fun dispatchNativeResult(payload: String) {
        webView.post {
            val requestId = runCatching { JSONObject(payload).optString("requestId") }.getOrDefault("")
            if (requestId.isBlank() || requestId == "null" || requestPages.remove(requestId) != pageGeneration) return@post
            if (!isTrustedPage()) return@post
            webView.evaluateJavascript(
                "window.ICARUS_NATIVE_RESULT && window.ICARUS_NATIVE_RESULT(" + JSONObject.quote(payload) + ");",
                null
            )
        }
    }

    private fun dispatchNativeStatus(payload: String) {
        val generation = pageGeneration
        webView.post {
            if (generation != pageGeneration || !isTrustedPage() || isDestroyed) return@post
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
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
            userAgentString = "$userAgentString ICARUSNative/${BuildConfig.VERSION_NAME}"
        }
        val packagedAssets = WebViewAssetLoader.AssetsPathHandler(this)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", packagedAssets)
            .addPathHandler("/brand/", WebViewAssetLoader.PathHandler { path -> packagedAssets.handle("web/brand/$path") })
            .addPathHandler("/models/", WebViewAssetLoader.PathHandler { path -> packagedAssets.handle("web/models/$path") })
            .build()
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                invalidatePage()
                super.onPageStarted(view, url, favicon)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                if (TrustedWebPolicy.isTrustedUrl(request.url.toString(), RuntimeMode.LOCAL_ORIGIN)) {
                    // Never let a missing bundled asset fall through to the network.
                    return assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse(404, "Bundled asset not found")
                }
                // The bundled shell cannot quietly call a hosted API or load remote scripts.
                if (RuntimeMode.current(this@MainActivity) == RuntimeMode.LOCAL) return blockedResponse(403, "Cloud mode is off")
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                if (TrustedWebPolicy.isTrustedUrl(request.url.toString(), RuntimeMode.activeUrl(this@MainActivity))) return false
                if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("https", "http", "mailto")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
                if (request.isForMainFrame) showLoadError()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, response: WebResourceResponse?) {
                if (request.isForMainFrame && (response?.statusCode ?: 200) >= 400) showLoadError()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (splashView.parent != null) {
                    splashView.animate()
                        .alpha(0f)
                        .setDuration(320L)
                        .withEndAction { rootView.removeView(splashView) }
                        .start()
                }
                if (!TrustedWebPolicy.isTrustedUrl(url, RuntimeMode.activeUrl(this@MainActivity))) return
                removeLegacyReadyLabel()
                notifyNativeStatus()
                if (pendingWake) {
                    pendingWake = false
                    dispatchWakeWord()
                }
            }
        }

        val allowedOrigins = setOfNotNull(RuntimeMode.LOCAL_ORIGIN, RuntimeMode.cloudOrigin())
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                NATIVE_CHANNEL,
                allowedOrigins
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                // Same-origin iframes do not get phone-control privileges.
                if (!isMainFrame || !isTrustedPage() ||
                    !TrustedWebPolicy.isTrustedUrl(sourceOrigin.toString(), RuntimeMode.activeUrl(this))) return@addWebMessageListener
                handleNativeMessage(message.data)
            }
        }
    }

    private fun removeLegacyReadyLabel() {
        val script = """
            (() => {
              const hideLegacyReady = () => {
                document.querySelectorAll('body *').forEach((element) => {
                  if (
                    element.children.length === 0 &&
                    element.textContent?.trim() === 'Ready' &&
                    !element.closest('button,[role="button"],[aria-live]')
                  ) {
                    element.style.setProperty('display', 'none', 'important');
                    element.setAttribute('aria-hidden', 'true');
                  }
                });
              };
              hideLegacyReady();
              if (!window.__icarusLegacyReadyCleaner) {
                const observer = new MutationObserver(hideLegacyReady);
                observer.observe(document.documentElement, {
                  subtree: true,
                  childList: true,
                  characterData: true
                });
                window.__icarusLegacyReadyCleaner = observer;
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun handleNativeMessage(raw: String?) {
        val text = raw.orEmpty()
        val payload = try { JSONObject(text) } catch (_: Exception) {
            dispatchNativeResult(JSONObject().put("ok", false).put("error", "invalid_payload").toString())
            return
        }
        val actionRequestId = payload.optString("requestId").takeIf { it.isNotBlank() && it != "null" }
        if (actionRequestId != null && payload.optString("bridgeRequest") != "status") requestPages[actionRequestId] = pageGeneration
        if (payload.optString("bridgeRequest") == "status") {
            val requestId = payload.optString("requestId").ifBlank { null }
            val status = JSONObject(nativeBridge.getStatus())
                .put("requestId", requestId ?: JSONObject.NULL)
                .toString()
            dispatchNativeStatus(status)
            return
        }

        if (payload.optString("action") == "set_runtime_mode") {
            val requestId = payload.optString("requestId").ifBlank { null }
            val response = runCatching {
                val mode = payload.optJSONObject("arguments")?.optString("mode").orEmpty()
                RuntimeMode.set(this, mode)
                JSONObject().put("ok", true).put("requestId", requestId ?: JSONObject.NULL).put("data", RuntimeMode.status(this))
            }.getOrElse { JSONObject().put("ok", false).put("requestId", requestId ?: JSONObject.NULL)
                .put("error", it.message ?: "runtime_mode_failed") }
            // The old origin is no longer privileged. Deliver this final mode acknowledgement only there.
            webView.evaluateJavascript("window.ICARUS_NATIVE_RESULT && window.ICARUS_NATIVE_RESULT(" + JSONObject.quote(response.toString()) + ");", null)
            actionRequestId?.let { requestPages.remove(it) }
            if (response.optBoolean("ok")) mainHandler.postDelayed({ webView.clearHistory(); loadIcarus() }, 150)
            return
        }
        val result = nativeBridge.executeAction(text)
        if (result.isNotBlank()) dispatchNativeResult(result)
    }

    private fun blockedResponse(code: Int, message: String) = WebResourceResponse(
        "text/plain", "utf-8", code, message, mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)),
    )

    private fun invalidatePage() {
        pageGeneration++
        requestPages.clear()
        val previous = commandRecognizer
        commandRecognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
    }

    private fun loadIcarus() {
        invalidatePage()
        loadErrorShown = false
        val url = RuntimeMode.activeUrl(this)
        if (url.isBlank()) { showLoadError(); return }
        webView.loadUrl(url)
    }

    private fun showLoadError() {
        if (loadErrorShown || isFinishing || isDestroyed) return
        loadErrorShown = true
        if (splashView.parent != null) rootView.removeView(splashView)
        val cloud = RuntimeMode.current(this) == RuntimeMode.CLOUD
        AlertDialog.Builder(this)
            .setTitle(if (cloud) "Cloud UI unavailable" else "Bundled UI unavailable")
            .setMessage(if (cloud) "Check your connection or explicitly switch back to the on-device assistant. No request was retried in another mode."
                else "The offline interface could not load. Reinstall a build containing the bundled web assets. Native hands-free commands remain available.")
            .setPositiveButton("Retry") { _, _ -> loadIcarus() }
            .apply { if (cloud) setNeutralButton("Use on-device") { _, _ -> RuntimeMode.set(this@MainActivity, RuntimeMode.LOCAL); loadIcarus() } }
            .setNegativeButton("Close", null)
            .show()
    }

    fun requestWakePermissionFromDisclosure(requestId: String?): String {
        WakeWordService.setEnabled(this, true)
        if (hasMicrophonePermission()) {
            startWakeWordService()
            dispatchWakeStatusAfterStart(requestId)
            return ""
        }

        val preferences = getSharedPreferences("icarus_permissions", MODE_PRIVATE)
        val requestedBefore = preferences.getBoolean("microphone_requested", false)
        if (requestedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openIcarusPermissionSettings()
            return JSONObject()
                .put("ok", false)
                .put("requestId", requestId ?: JSONObject.NULL)
                .put("error", "microphone_permission_blocked")
                .put("message", "Microphone access is blocked. Allow it on the App permissions screen, then return to ICARUS.")
                .put("settingsOpened", true)
                .toString()
        }

        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        pendingWakePermissionRequestId = requestId
        preferences.edit().putBoolean("microphone_requested", true).apply()
        wakePermissions.launch(missing.toTypedArray())
        return ""
    }

    private fun startWakeWordService() {
        if (!WakeWordService.isEnabled(this)) return
        val intent = Intent(this, WakeWordService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    /** Wake listening is native and must not depend on the hosted UI loading first. */
    private fun ensureWakeListenerRunning() {
        if (hasMicrophonePermission() && WakeWordService.isEnabled(this)) runCatching { startWakeWordService() }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openIcarusPermissionSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun dispatchWakeStatusAfterStart(requestId: String?, attempt: Int = 0) {
        mainHandler.postDelayed({
            val state = WakeWordService.listenerState
            if (state == "STARTING" && attempt < 12) {
                dispatchWakeStatusAfterStart(requestId, attempt + 1)
                return@postDelayed
            }
            val success = state == "LISTENING"
            val wake = JSONObject(nativeBridge.getStatus()).getJSONObject("wakeWord")
            dispatchNativeResult(
                JSONObject()
                    .put("ok", success)
                    .put("requestId", requestId ?: JSONObject.NULL)
                    .put("error", if (success) JSONObject.NULL else "wake_listener_start_failed")
                    .put("message", WakeWordService.lastError ?: JSONObject.NULL)
                    .put("data", wake.put("enabled", success))
                    .toString()
            )
            notifyNativeStatus()
        }, 500L)
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
        val generation = pageGeneration
        webView.post {
            if (generation != pageGeneration || isDestroyed) return@post
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
            mainHandler.postDelayed({ if (generation == pageGeneration && isTrustedPage()) startNativeCommandCapture() }, 550L)
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
        val generation = pageGeneration
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
                override fun onReadyForSpeech(params: Bundle?) { if (generation == pageGeneration) dispatchVoiceState("listening") }
                override fun onBeginningOfSpeech() { if (generation == pageGeneration) dispatchVoiceState("hearing") }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (generation == pageGeneration) dispatchVoiceState("processing") }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (generation != pageGeneration) return
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
                    if (generation != pageGeneration) return
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
        val generation = pageGeneration
        webView.post {
            if (generation != pageGeneration || !isTrustedPage()) return@post
            webView.evaluateJavascript(
                "sessionStorage.setItem('icarus-native-command',$quoted);" +
                    "window.dispatchEvent(new CustomEvent('icarus-native-command',{detail:{transcript:$quoted}}));",
                null
            )
        }
    }

    private fun dispatchVoiceState(state: String) {
        val generation = pageGeneration
        webView.post {
            if (generation != pageGeneration || !isTrustedPage()) return@post
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('icarus-native-voice-state',{detail:{state:${JSONObject.quote(state)}}}));",
                null
            )
        }
    }

    private fun dispatchVoiceError(message: String) {
        val generation = pageGeneration
        webView.post {
            if (generation != pageGeneration || !isTrustedPage()) return@post
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
