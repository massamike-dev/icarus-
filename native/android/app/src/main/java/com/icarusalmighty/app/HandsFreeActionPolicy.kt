package com.icarusalmighty.app

/** Deterministic shortcuts keep their behavior; untrusted model proposals never silently mutate settings. */
object HandsFreeActionPolicy {
    fun requiresConfirmation(action: String, modelInterpreted: Boolean): Boolean =
        action in setOf("make_call", "navigate_to", "open_app", "set_timer") ||
            (modelInterpreted && action !in setOf("get_battery", "cancel"))
}
