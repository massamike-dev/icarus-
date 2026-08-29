package com.icarusalmighty.app.conversation

import com.icarusalmighty.app.BuildConfig
import com.icarusalmighty.app.bridge.SessionTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ConversationReply(val conversationId: String, val content: String)

class ConversationGateway(private val tokenProvider: SessionTokenProvider) {
    suspend fun send(conversationId: String?, message: String): Result<ConversationReply> = withContext(Dispatchers.IO) {
        runCatching {
            require(BuildConfig.BASE44_URL.startsWith("https://")) { "Secure Base44 URL is not configured" }
            val token = tokenProvider.token() ?: error("Sign in to ICARUS first")
            val connection = (URL(BuildConfig.BASE44_URL.trimEnd('/') + "/api/functions/nativeConversationTurn").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
                setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Content-Type", "application/json")
            }
            val request = JSONObject().put("userMessage", message)
            if (conversationId != null) request.put("conversationId", conversationId)
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) error("ICARUS conversation request failed")
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            ConversationReply(json.getString("conversationId"), json.getString("content"))
        }
    }
}
