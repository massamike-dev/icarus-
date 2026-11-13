package com.icarusalmighty.app.driving

import java.net.URI

/** Only map imagery and fixed-format route requests may leave the map WebView. */
object LiveMapNetworkPolicy {
    fun allows(url: String, enabled: Boolean): Boolean = runCatching {
        if (!enabled || url.length > 1024) return false
        val uri = URI(url)
        if (uri.scheme != "https" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) return false
        when (uri.host) {
            "tile.openstreetmap.org" -> {
                if (uri.query != null) return false
                val parts = Regex("/(\\d{1,2})/(\\d+)/(\\d+)\\.png").matchEntire(uri.path)?.groupValues ?: return false
                val zoom = parts[1].toIntOrNull() ?: return false
                if (zoom !in 0..19) return false
                val x = parts[2].toIntOrNull() ?: return false
                val y = parts[3].toIntOrNull() ?: return false
                x in 0 until (1 shl zoom) && y in 0 until (1 shl zoom)
            }
            "routing.openstreetmap.de" -> Regex("/routed-car/route/v1/driving/-?\\d{1,3}\\.\\d{6},-?\\d{1,2}\\.\\d{6};-?\\d{1,3}\\.\\d{6},-?\\d{1,2}\\.\\d{6}").matches(uri.path) &&
                uri.query == "overview=full&geometries=geojson&steps=false&alternatives=false"
            else -> false
        }
    }.getOrDefault(false)
}
