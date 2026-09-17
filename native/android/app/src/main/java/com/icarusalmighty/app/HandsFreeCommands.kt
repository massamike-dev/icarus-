package com.icarusalmighty.app

/** Offline commands stay available even when the hosted interface cannot load. */
object HandsFreeCommands {
    data class Command(val action: String, val value: String = "")
    val supported = setOf("get_battery", "toggle_flashlight", "set_volume", "make_call", "navigate_to", "open_app", "set_timer", "stop_listening", "cancel")
    fun parse(text: String): Command? {
        val t = text.trim().lowercase().removeSuffix(".")
        if (t in setOf("stop", "cancel", "never mind", "nevermind")) return Command("cancel")
        if (t in setOf("stop listening", "disable listening", "go to sleep")) return Command("stop_listening")
        if (t in setOf("battery", "battery level", "what's my battery", "what is my battery level")) return Command("get_battery")
        Regex("(?:turn (on|off) (?:the )?(?:flashlight|torch)|(?:flashlight|torch) (on|off))").matchEntire(t)?.let {
            return Command("toggle_flashlight", it.groupValues[1].ifBlank { it.groupValues[2] })
        }
        Regex("(?:set )?volume (?:to )?(\\d{1,3})(?: percent|%)?").matchEntire(t)?.let {
            return it.groupValues[1].toInt().takeIf { n -> n in 0..100 }?.let { n -> Command("set_volume", "$n") }
        }
        Regex("(?:set (?:a )?)?timer (?:for )?(\\d+) (seconds?|minutes?)").matchEntire(t)?.let {
            val amount = it.groupValues[1].toLongOrNull()?.takeIf { n -> n in 1..86400 } ?: return null
            val seconds = amount * (if (it.groupValues[2].startsWith("minute")) 60 else 1)
            return seconds.takeIf { n -> n in 1..86400 }?.let { n -> Command("set_timer", "$n") }
        }
        for ((prefix, action) in listOf("call " to "make_call", "navigate to " to "navigate_to", "open " to "open_app")) {
            if (t.startsWith(prefix) && t.length > prefix.length) return Command(action, text.trim().drop(prefix.length).trim())
        }
        return null
    }
    fun confirms(text: String) = text.trim().lowercase().removeSuffix(".") in setOf("yes", "confirm", "yes confirm", "do it")
}
