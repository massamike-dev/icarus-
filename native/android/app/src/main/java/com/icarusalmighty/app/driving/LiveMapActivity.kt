package com.icarusalmighty.app.driving

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Dedicated, foreground-only online map. Does not expose the assistant bridge. */
class LiveMapActivity : AppCompatActivity(), LocationListener {
    private lateinit var web: WebView
    private var locations: LocationManager? = null
    @Volatile private var networkEnabled = false
    private var enabled = false
    private var resumed = false
    private var ready = false
    private var pendingStart = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) { if (resumed) beginSession() else pendingStart = true }
        else emit(JSONObject().put("state", "denied"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locations = getSystemService(LocationManager::class.java)
        web = WebView(this)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString ICARUS-Maps/1.0 (+https://icarusassistant.com)"
        }
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && request.hasGesture() && request.url.toString() in setOf(
                        "https://www.openstreetmap.org/copyright", "https://www.openstreetmap.org/fixthemap"))
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
            override fun onPageFinished(view: WebView?, url: String?) { ready = url == URL }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
                    uri.path.orEmpty().startsWith("/assets/web/live-map/"))
                    return assets.shouldInterceptRequest(uri) ?: blocked()
                val tiles = uri.host == "tile.openstreetmap.org" &&
                    Regex("/\\d{1,2}/\\d+/\\d+\\.png").matches(uri.path.orEmpty()) && uri.query == null
                val routing = uri.host == "routing.openstreetmap.de" &&
                    uri.path.orEmpty().startsWith("/routed-car/route/v1/driving/")
                return if (networkEnabled && request.method == "GET" && uri.scheme == "https" &&
                    uri.port == -1 && uri.userInfo == null && (tiles || routing)) null else blocked()
            }
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun begin() = runOnUiThread {
                if (!ready || !resumed) return@runOnUiThread
                if (hasLocation()) beginSession() else permission.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            @JavascriptInterface fun stop() = runOnUiThread { stopSession() }
            @JavascriptInterface fun close() = runOnUiThread { finish() }
        }, "IcarusMapHost")
        setContentView(web)
        web.loadUrl(URL)
    }

    private fun beginSession() {
        if (!resumed || !ready || isDestroyed) return
        enabled = true; networkEnabled = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        emit(JSONObject().put("state", "started"))
        startLocation()
    }

    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        locations?.removeUpdates(this)
        if (!hasLocation()) { emit(JSONObject().put("state", "denied")); return }
        var started = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (locations?.isProviderEnabled(provider) == true) runCatching {
                locations?.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper()); started = true
            }
        }
        emit(JSONObject().put("state", if (started) "waiting" else "disabled"))
    }

    override fun onLocationChanged(location: Location) {
        if (!enabled || !resumed || !hasLocation()) return
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (age !in 0..15000 || !location.hasAccuracy() || !location.latitude.isFinite() || !location.longitude.isFinite() || !location.accuracy.isFinite()) return
        emit(JSONObject().put("state", "fix").put("latitude", location.latitude)
            .put("longitude", location.longitude).put("accuracy", location.accuracy)
            .put("timestamp", System.currentTimeMillis() - age)
            .put("speed", if (location.hasSpeed() && location.speed.isFinite()) location.speed else JSONObject.NULL)
            .put("heading", if (location.hasBearing() && location.bearing.isFinite()) location.bearing else JSONObject.NULL))
    }
    override fun onProviderDisabled(provider: String) { emit(JSONObject().put("state", "waiting")) }
    override fun onProviderEnabled(provider: String) { if (enabled && resumed) startLocation() }
    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun emit(value: JSONObject) {
        if (ready && !isDestroyed) web.evaluateJavascript("window.icarusMapLocation && window.icarusMapLocation(${value});", null)
    }
    private fun stopSession() {
        enabled = false; networkEnabled = false
        locations?.removeUpdates(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        emit(JSONObject().put("state", "stopped"))
    }
    override fun onResume() { super.onResume(); resumed = true; if (pendingStart) { pendingStart = false; beginSession() } }
    override fun onPause() { resumed = false; stopSession(); super.onPause() }
    override fun onDestroy() { stopSession(); ready = false; web.removeJavascriptInterface("IcarusMapHost"); web.destroy(); super.onDestroy() }
    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    companion object { private const val URL = "https://appassets.androidplatform.net/assets/web/live-map/index.html" }
}
