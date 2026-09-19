package com.icarusalmighty.app

/** The bundled overlay never implicitly falls through to the separate Unity app. */
enum class SpatialHudTarget {
    BUNDLED,
    COMPANION;

    companion object {
        fun parse(value: String): SpatialHudTarget? = when (value.trim().lowercase()) {
            "", "bundled" -> BUNDLED
            "companion" -> COMPANION
            else -> null
        }
    }
}
