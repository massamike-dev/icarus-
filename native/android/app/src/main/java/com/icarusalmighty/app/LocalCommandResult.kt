package com.icarusalmighty.app

import org.json.JSONObject
import org.json.JSONTokener

/** AI text is an untrusted proposal. Never use model-provided authorization or execution flags. */
object LocalCommandResult {
    fun parse(raw: String): JSONObject {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(text.startsWith("{") && text.endsWith("}")) { "local_command_invalid_response" }
        val parsed = try {
            val tokens = JSONTokener(text)
            val value = tokens.nextValue() as? JSONObject ?: error("local_command_invalid_response")
            require(tokens.nextClean() == '\u0000') { "local_command_invalid_response" }
            value
        } catch (_: Exception) { throw IllegalArgumentException("local_command_invalid_response") }
        require(parsed.opt("action") is String && parsed.opt("arguments") is JSONObject) { "local_command_invalid_response" }
        val action = NativeCommandPolicy.normalizeAction(parsed.getString("action"))
        val input = parsed.getJSONObject("arguments")
        val args = JSONObject()
        fun string(target: String, vararg keys: String): String {
            val values = keys.mapNotNull { key -> (input.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() } }.distinct()
            require(values.size <= 1) { "local_command_invalid_response" }
            val result = values.firstOrNull().orEmpty()
            if (result.isNotBlank()) args.put(target, result.take(1000))
            return result
        }
        fun number(target: String, vararg keys: String): Int? {
            val values = keys.mapNotNull { input.opt(it)?.takeUnless { value -> value == JSONObject.NULL } }
            require(values.map { it.toString() }.distinct().size <= 1) { "local_command_invalid_response" }
            val rawValue = values.firstOrNull()
            val value = when (rawValue) {
                is Number -> rawValue.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
                is String -> rawValue.toIntOrNull()
                else -> null
            }
            if (value != null) args.put(target, value)
            return value
        }
        val clarification = when (action) {
            "open_app" -> if (string("appName", "appName", "app", "name").isBlank()) "Which installed app should I open?" else ""
            "set_alarm" -> {
                val hour = number("hour", "hour")
                val minute = number("minute", "minute")
                if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) "What exact hour and minute should I set the alarm for?" else ""
            }
            "set_timer" -> {
                val seconds = number("durationSeconds", "durationSeconds", "seconds")
                if (seconds == null || seconds !in 1..86400) "How many seconds should the timer run, up to one day?" else ""
            }
            "set_volume", "set_brightness" -> {
                val level = number("level", "level")
                val minimum = if (action == "set_brightness") 1 else 0
                if (level == null || level !in minimum..100) "What percentage should I set, from $minimum to 100?" else ""
            }
            "toggle_flashlight", "wake_word" -> {
                val enabled = input.opt("enabled") as? Boolean
                if (enabled == null) "Would you like it on or off?" else { args.put("enabled", enabled); "" }
            }
            "navigate_to" -> if (string("destination", "destination", "query", "address").isBlank()) "Where would you like to navigate?" else ""
            "make_call", "send_sms" -> {
                val recipient = string("contact", "recipient", "contact", "contactName", "name", "number", "phone")
                if (Regex("\\+?[0-9 ()-]{3,}").matches(recipient)) args.put("number", recipient)
                val message = if (action == "send_sms") string("message", "message", "body", "text") else ""
                NativeCommandPolicy.clarificationFor(action, recipient, message)
            }
            "find_videos", "compose_video_montage" -> { string("query", "query", "subject", "description"); "" }
            "obd_connect" -> {
                val address = string("address", "address", "deviceAddress")
                if (!Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").matches(address)) "Select an exact paired OBD device in Settings." else ""
            }
            "unknown" -> "Try one clear device command. Nothing has been executed."
            else -> ""
        }
        val safeAction = if (clarification.isNotBlank()) "unknown" else action
        return JSONObject().put("action", safeAction)
            .put("arguments", if (safeAction == "unknown") JSONObject() else args)
            .put("spokenSummary", clarification.ifBlank { parsed.optString("spokenSummary", "Review this command.").take(300) })
            .apply { if (clarification.isNotBlank()) put("clarification", clarification) }
            .put("requiresConfirmation", NativeCommandPolicy.requiresConfirmation(safeAction))
            .put("executionStatus", "not_executed")
    }

    /** Flexible speech proposals feed the same existing accept/confirm/execute path as direct commands. */
    fun handsFree(data: JSONObject): HandsFreeCommands.Command? {
        val action = data.optString("action")
        if (action !in HandsFreeCommands.supported) return null
        val args = data.optJSONObject("arguments") ?: return null
        val value = when (action) {
            "toggle_flashlight" -> when (args.opt("enabled")) { true -> "on"; false -> "off"; else -> return null }
            "set_volume" -> args.optInt("level", -1).takeIf { it in 0..100 }?.toString() ?: return null
            "set_timer" -> args.optInt("durationSeconds", -1).takeIf { it in 1..86400 }?.toString() ?: return null
            "make_call" -> args.optString("contact").takeIf { it.isNotBlank() } ?: return null
            "navigate_to" -> args.optString("destination").takeIf { it.isNotBlank() } ?: return null
            "open_app" -> args.optString("appName").takeIf { it.isNotBlank() } ?: return null
            else -> ""
        }
        return HandsFreeCommands.Command(action, value)
    }
}
