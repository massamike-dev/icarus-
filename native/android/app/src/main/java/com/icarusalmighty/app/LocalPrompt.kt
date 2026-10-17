package com.icarusalmighty.app

import org.json.JSONArray
import org.json.JSONObject

/** Caller-owned context only. No engine conversation or hidden cross-chat history is retained. */
object LocalPrompt {
    fun build(prompt: String, messages: JSONArray? = null, memoryContext: String = ""): String {
        if (messages == null && memoryContext.isBlank()) return prompt.takeLast(24000)
        val history = JSONArray()
        if (messages != null) for (i in (messages.length() - 8).coerceAtLeast(0) until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role")
            if (role !in setOf("user", "assistant")) continue
            history.put(JSONObject().put("role", role).put("content", message.optString("content").take(1000)))
        }
        return JSONObject().put("referenceMemory", memoryContext.take(2000))
            .put("conversation", history).put("currentUserMessage", prompt.takeLast(12000)).toString()
    }
}
