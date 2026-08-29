package com.icarusalmighty.app.bridge

import com.icarusalmighty.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class InterpretedCommand(
    val action: String,
    val argumentsJson: String,
    val spokenSummary: String,
    val requiresConfirmation: Boolean
)

/** The app's authenticated Base44 session supplies a short-lived bearer token. */
fun interface SessionTokenProvider { suspend fun token(): String? }

class NativeCommandGateway(private val tokenProvider: SessionTokenProvider) {
    suspend fun interpret(command: String): Result<InterpretedCommand> = withContext(Dispatchers.IO) {
        runCatching {
            require(BuildConfig.BASE44_URL.startsWith("https://")) { "Secure Base44 URL is not configured" }
            val token = tokenProvider.token() ?: error("Sign in to ICARUS first")
            val connection = (URL(BuildConfig.BASE44_URL.trimEnd('/') + "/api/functions/interpretNativeCommand").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(JSONObject().put("command", command).toString().toByteArray()) }
            if (connection.responseCode !in 200..299) error("ICARUS could not interpret the command")
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            check(json.optString("executionStatus") == "not_executed") { "Unsafe command response" }
            InterpretedCommand(
                action = json.optString("action", "unknown"),
                argumentsJson = json.optJSONObject("arguments")?.toString() ?: "{}",
                spokenSummary = json.optString("spokenSummary", "Review this command."),
                requiresConfirmation = json.optBoolean("requiresConfirmation", true)
            )
        }
    }
}
