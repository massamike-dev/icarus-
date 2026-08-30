package com.icarusalmighty.app.update

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.icarusalmighty.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object PlayUpdateManager {
    private const val UPDATE_REQUEST_CODE = 1200
    private var manager: AppUpdateManager? = null
    private var listener: InstallStateUpdatedListener? = null

    fun checkOnLaunch(activity: Activity) = check(activity, silent = true)

    fun check(activity: Activity, silent: Boolean = false) {
        val updateManager = AppUpdateManagerFactory.create(activity)
        manager = updateManager
        registerDownloadListener(activity, updateManager)

        updateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                        startUpdate(activity, updateManager, info)
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                        loadNotesAndOffer(activity, updateManager, info)
                    !silent -> Toast.makeText(activity, "ICARUS is up to date.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                if (!silent) Toast.makeText(activity, "Unable to check Google Play for updates.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadNotesAndOffer(
        activity: Activity,
        updateManager: AppUpdateManager,
        info: com.google.android.play.core.appupdate.AppUpdateInfo
    ) {
        thread {
            val notes = fetchNotes(info.availableVersionCode())
            activity.runOnUiThread {
                val message = buildString {
                    append("Version ").append(notes.versionName ?: info.availableVersionCode()).append("\n")
                    if (!notes.releaseDate.isNullOrBlank()) append("Released ").append(notes.releaseDate).append("\n")
                    append("\n")
                    if (notes.items.isEmpty()) append("A new ICARUS update is ready.")
                    else append(notes.items.joinToString("\n") { "• $it" })
                    append("\n\nYour conversations, settings, and saved data will remain in place.")
                }
                AlertDialog.Builder(activity)
                    .setTitle("ICARUS update available")
                    .setMessage(message)
                    .setPositiveButton("Update") { _, _ -> startUpdate(activity, updateManager, info) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun startUpdate(
        activity: Activity,
        updateManager: AppUpdateManager,
        info: com.google.android.play.core.appupdate.AppUpdateInfo
    ) {
        try {
            updateManager.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                UPDATE_REQUEST_CODE
            )
        } catch (_: Exception) {
            Toast.makeText(activity, "Google Play could not start the update.", Toast.LENGTH_LONG).show()
        }
    }

    private fun registerDownloadListener(activity: Activity, updateManager: AppUpdateManager) {
        listener?.let(updateManager::unregisterListener)
        val newListener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Update downloaded")
                        .setMessage("Restart ICARUS now to finish the update.")
                        .setPositiveButton("Restart now") { _, _ -> updateManager.completeUpdate() }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
        listener = newListener
        updateManager.registerListener(newListener)
    }

    private fun fetchNotes(versionCode: Int): Notes {
        return try {
            val connection = (URL(BuildConfig.UPDATE_NOTES_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (connection.responseCode !in 200..299) return Notes()
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (json.optInt("versionCode") != versionCode) return Notes()
                val list = json.optJSONArray("patchNotes")
                Notes(
                    versionName = json.optString("versionName").ifBlank { null },
                    releaseDate = json.optString("releaseDate").ifBlank { null },
                    items = buildList {
                        if (list != null) for (i in 0 until list.length()) add(list.optString(i))
                    }
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            Notes()
        }
    }

    private data class Notes(
        val versionName: String? = null,
        val releaseDate: String? = null,
        val items: List<String> = emptyList()
    )
}
