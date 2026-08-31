package com.icarusalmighty.app.update

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.google.android.play.core.appupdate.AppUpdateInfo
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

/**
 * Google Play flexible updater with ICARUS patch notes.
 *
 * Only Google Play test/production installs can receive this flow. Play verifies
 * package identity, version code, signing lineage, entitlement and installation.
 */
object PlayUpdateManager {
    private var manager: AppUpdateManager? = null
    private var listener: InstallStateUpdatedListener? = null
    private var offerShowing = false
    private var completionShowing = false

    fun checkOnLaunch(activity: Activity) = check(activity, silent = true)

    fun check(activity: Activity, silent: Boolean = false) {
        val updateManager = obtainManager(activity)
        updateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED ->
                        offerCompletion(activity, updateManager)

                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                        startImmediateRecovery(activity, updateManager, info)

                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                        loadNotesAndOffer(activity, updateManager, info)

                    !silent -> toast(activity, "ICARUS is up to date.")
                }
            }
            .addOnFailureListener {
                if (!silent) toast(activity, "Unable to check Google Play for updates.")
            }
    }

    fun resumeIfNeeded(activity: Activity) {
        val updateManager = obtainManager(activity)
        updateManager.appUpdateInfo.addOnSuccessListener { info ->
            when {
                info.installStatus() == InstallStatus.DOWNLOADED ->
                    offerCompletion(activity, updateManager)

                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                    startImmediateRecovery(activity, updateManager, info)
            }
        }
    }

    private fun obtainManager(activity: Activity): AppUpdateManager {
        manager?.let { return it }
        return AppUpdateManagerFactory.create(activity.applicationContext).also {
            manager = it
            registerDownloadListener(activity, it)
        }
    }

    private fun loadNotesAndOffer(
        activity: Activity,
        updateManager: AppUpdateManager,
        info: AppUpdateInfo
    ) {
        if (offerShowing) return
        offerShowing = true
        thread {
            val notes = fetchNotes(info.availableVersionCode())
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) {
                    offerShowing = false
                    return@runOnUiThread
                }
                val message = buildString {
                    append("Version ").append(notes.versionName ?: info.availableVersionCode()).append("\n")
                    if (!notes.releaseDate.isNullOrBlank()) {
                        append("Released ").append(notes.releaseDate).append("\n")
                    }
                    append("\n")
                    if (notes.items.isEmpty()) append("A new ICARUS update is ready.")
                    else append(notes.items.joinToString("\n") { "• $it" })
                    append("\n\nYour conversations, settings and approved memories remain in place.")
                }
                AlertDialog.Builder(activity)
                    .setTitle("ICARUS update available")
                    .setMessage(message)
                    .setPositiveButton("Update") { _, _ ->
                        offerShowing = false
                        startFlexible(activity, updateManager, info)
                    }
                    .setNegativeButton("Later") { _, _ -> offerShowing = false }
                    .setOnCancelListener { offerShowing = false }
                    .show()
            }
        }
    }

    private fun startFlexible(
        activity: Activity,
        updateManager: AppUpdateManager,
        info: AppUpdateInfo
    ) {
        val options = AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE)
            .setAllowAssetPackDeletion(false)
            .build()
        updateManager.startUpdateFlow(info, activity, options)
            .addOnFailureListener {
                toast(activity, "Google Play could not start the ICARUS update.")
            }
    }

    private fun startImmediateRecovery(
        activity: Activity,
        updateManager: AppUpdateManager,
        info: AppUpdateInfo
    ) {
        val options = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE)
            .setAllowAssetPackDeletion(false)
            .build()
        updateManager.startUpdateFlow(info, activity, options)
            .addOnFailureListener {
                toast(activity, "Google Play could not resume the ICARUS update.")
            }
    }

    private fun registerDownloadListener(activity: Activity, updateManager: AppUpdateManager) {
        val newListener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                offerCompletion(activity, updateManager)
            }
        }
        listener = newListener
        updateManager.registerListener(newListener)
    }

    private fun offerCompletion(activity: Activity, updateManager: AppUpdateManager) {
        if (completionShowing) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || completionShowing) return@runOnUiThread
            completionShowing = true
            AlertDialog.Builder(activity)
                .setTitle("ICARUS update downloaded")
                .setMessage("Restart ICARUS now to finish installing the update. No uninstall is required.")
                .setPositiveButton("Restart now") { _, _ ->
                    completionShowing = false
                    updateManager.completeUpdate()
                }
                .setNegativeButton("Later") { _, _ -> completionShowing = false }
                .setOnCancelListener { completionShowing = false }
                .show()
        }
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
                        if (list != null) {
                            for (index in 0 until list.length()) {
                                list.optString(index).takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            Notes()
        }
    }

    private fun toast(activity: Activity, message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class Notes(
        val versionName: String? = null,
        val releaseDate: String? = null,
        val items: List<String> = emptyList()
    )
}
