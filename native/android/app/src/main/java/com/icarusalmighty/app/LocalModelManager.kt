package com.icarusalmighty.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class LocalModelManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val modelDir = File(context.getExternalFilesDir(null), "models")
    private val modelFile = File(modelDir, MODEL_FILE)
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var backend = "not_loaded"

    fun status(): JSONObject {
        val ready = prefs.getString(KEY_SHA, null) == SHA256 &&
            modelFile.isFile && modelFile.length() == MODEL_SIZE
        if (ready) return JSONObject().put("state", "ready").put("model", MODEL_NAME)
            .put("bytes", MODEL_SIZE).put("backend", backend).put("free", true).put("offline", true)
        val id = prefs.getLong(KEY_ID, -1L)
        if (id > 0) downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val s = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                return JSONObject()
                    .put("state", when (s) {
                        DownloadManager.STATUS_SUCCESSFUL -> "downloaded_unverified"
                        DownloadManager.STATUS_FAILED -> "failed"
                        DownloadManager.STATUS_PAUSED -> "paused"
                        else -> "downloading"
                    })
                    .put("downloadedBytes", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0))
                    .put("totalBytes", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: MODEL_SIZE)
                    .put("model", MODEL_NAME)
            }
        }
        return JSONObject().put("state", "not_downloaded").put("model", MODEL_NAME)
            .put("bytes", MODEL_SIZE).put("free", true).put("offline", true)
    }

    fun startDownload(wifiOnly: Boolean): JSONObject {
        modelDir.mkdirs()
        status().let { if (it.optString("state") in listOf("ready", "downloading")) return it }
        prefs.getLong(KEY_ID, -1L).takeIf { it > 0 }?.let(downloads::remove)
        closeRuntime(); modelFile.delete(); prefs.edit().remove(KEY_SHA).apply()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("ICARUS local intelligence")
            .setDescription("Downloading Gemma 4 E2B (2.58 GB)")
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(!wifiOnly).setAllowedOverRoaming(false)
            .setDestinationUri(Uri.fromFile(modelFile))
        val id = downloads.enqueue(request)
        prefs.edit().putLong(KEY_ID, id).apply()
        return JSONObject().put("state", "downloading").put("downloadId", id).put("model", MODEL_NAME)
    }

    fun deleteModel(): JSONObject {
        prefs.getLong(KEY_ID, -1L).takeIf { it > 0 }?.let(downloads::remove)
        closeRuntime(); modelFile.delete(); prefs.edit().clear().apply()
        return JSONObject().put("state", "not_downloaded").put("deleted", true)
    }

    fun generate(requestId: String?, prompt: String, onResult: (String) -> Unit) {
        scope.launch {
            val result = try {
                require(prompt.isNotBlank()) { "missing_prompt" }
                verifyModel()
                val text = StringBuilder()
                getConversation().sendMessageAsync(prompt.take(24000)).collect { text.append(it) }
                JSONObject().put("ok", true).put("requestId", requestId ?: JSONObject.NULL)
                    .put("data", JSONObject().put("content", text.toString().trim())
                        .put("provider", "on_device").put("model", MODEL_NAME)
                        .put("backend", backend).put("offline", true))
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("requestId", requestId ?: JSONObject.NULL)
                    .put("error", when (e.message) {
                        "model_not_downloaded" -> "local_model_not_downloaded"
                        "model_size_mismatch", "model_checksum_mismatch" -> "local_model_verification_failed"
                        else -> "local_model_failed"
                    }).put("message", e.message ?: e.javaClass.simpleName)
            }
            onResult(result.toString())
        }
    }

    @Synchronized private fun getConversation(): Conversation {
        conversation?.let { return it }
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        fun create(config: EngineConfig, label: String): Engine =
            Engine(config).also { it.initialize(); backend = label }
        val path = modelFile.absolutePath
        val runtime = try {
            create(EngineConfig(modelPath = path, backend = Backend.GPU(),
                visionBackend = Backend.GPU(), audioBackend = Backend.CPU()), "gpu")
        } catch (_: Exception) {
            try {
                create(EngineConfig(modelPath = path, backend = Backend.CPU(),
                    visionBackend = Backend.CPU(), audioBackend = Backend.CPU()), "cpu_multimodal")
            } catch (_: Exception) {
                create(EngineConfig(modelPath = path, backend = Backend.CPU()), "cpu_text")
            }
        }
        engine = runtime
        return runtime.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            .also { conversation = it }
    }

    private fun verifyModel() {
        if (!modelFile.isFile) error("model_not_downloaded")
        if (modelFile.length() != MODEL_SIZE) error("model_size_mismatch")
        if (prefs.getString(KEY_SHA, null) == SHA256) return
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(modelFile).use { input ->
            val buffer = ByteArray(8 * 1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(SHA256, true)) { modelFile.delete(); error("model_checksum_mismatch") }
        prefs.edit().putString(KEY_SHA, actual).apply()
    }

    @Synchronized private fun closeRuntime() {
        try { conversation?.close() } catch (_: Exception) {}
        try { engine?.close() } catch (_: Exception) {}
        conversation = null; engine = null; backend = "not_loaded"
    }

    companion object {
        const val MODEL_NAME = "Gemma 4 E2B"
        const val MODEL_SIZE = 2_588_147_712L
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        private const val PREFS = "icarus_local_model"
        private const val KEY_ID = "download_id"
        private const val KEY_SHA = "verified_sha"
        private const val SYSTEM = """You are ICARUS: Intelligent Companion for Assistance, Reasoning, Understanding, and Support. You are Michael's calm, wise, patient, quietly humorous and reassuring personal assistant. Lead with the useful answer, speak plainly, admit uncertainty, never invent completed actions, and require confirmation for sensitive or irreversible actions. Be especially useful for construction, masonry, Wennig Industries, vehicles, tools, Android development, creative work, research, planning, writing and calculations. Keep spoken answers concise. Wisdom before action, clarity before complexity, and loyalty without dishonesty."""
    }
}
