package com.icarusalmighty.app.tools

import android.app.AlarmManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import com.icarusalmighty.app.media.MediaCatalog
import com.icarusalmighty.app.conversation.ConversationActivity

object CommandRouter {
    fun parse(raw: String): PhoneCommand {
        val text = raw.trim()
        val lower = text.lowercase()
        Regex("(?:set )?alarm (?:for |at )?(\\d{1,2})(?::(\\d{2}))?").find(lower)?.let {
            return PhoneCommand.Alarm(it.groupValues[1].toInt().coerceIn(0, 23), it.groupValues[2].ifBlank { "0" }.toInt().coerceIn(0, 59))
        }
        Regex("(?:set )?timer (?:for )?(\\d+) (second|minute|hour)s?").find(lower)?.let {
            val n = it.groupValues[1].toInt(); val multiplier = when (it.groupValues[2]) { "hour" -> 3600; "minute" -> 60; else -> 1 }
            return PhoneCommand.Timer(n * multiplier)
        }
        if ("flashlight" in lower) return PhoneCommand.Flashlight
        Regex("volume (?:to )?(\\d+)").find(lower)?.let { return PhoneCommand.Volume(it.groupValues[1].toInt().coerceIn(0, 100)) }
        Regex("brightness (?:to )?(\\d+)").find(lower)?.let { return PhoneCommand.Brightness(it.groupValues[1].toInt().coerceIn(0, 100)) }
        Regex("navigate to (.+)").find(text, RegexOption.IGNORE_CASE)?.let { return PhoneCommand.Navigate(it.groupValues[1]) }
        Regex("(?:call|dial) (.+)").find(text, RegexOption.IGNORE_CASE)?.let { return PhoneCommand.Dial(it.groupValues[1]) }
        Regex("(?:text|message) ([^:]+?)(?: saying|:)(.+)").find(text, RegexOption.IGNORE_CASE)?.let { return PhoneCommand.Sms(it.groupValues[1].trim(), it.groupValues[2].trim()) }
        if ("battery" in lower) return PhoneCommand.Battery
        if (lower.contains("conversation mode") || lower.contains("let's talk") || lower.contains("lets talk") || lower.contains("talk face to face")) return PhoneCommand.ConversationMode
        if (lower.startsWith("open camera") || lower == "take a picture") return PhoneCommand.Camera
        if ("montage" in lower && "video" in lower) return PhoneCommand.Montage(text)
        if (("find" in lower || "show" in lower) && "video" in lower) return PhoneCommand.FindVideos(text)
        Regex("open (.+)").find(text, RegexOption.IGNORE_CASE)?.let { return PhoneCommand.OpenApp(it.groupValues[1]) }
        return PhoneCommand.Unsupported(text)
    }

    fun execute(context: Context, command: PhoneCommand) {
        when (command) {
            is PhoneCommand.Alarm -> launch(context, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, command.hour).putExtra(AlarmClock.EXTRA_MINUTES, command.minute))
            is PhoneCommand.Timer -> launch(context, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, command.seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, false))
            PhoneCommand.Flashlight -> toggleFlashlight(context)
            is PhoneCommand.Volume -> setVolume(context, command.percent)
            is PhoneCommand.Brightness -> launch(context, Intent(Settings.ACTION_DISPLAY_SETTINGS))
            is PhoneCommand.Navigate -> launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(command.destination)}")))
            is PhoneCommand.Dial -> launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(command.numberOrName)}")))
            is PhoneCommand.Sms -> launch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(command.recipient)}")).putExtra("sms_body", command.message))
            is PhoneCommand.Calendar -> launch(context, Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, command.title))
            PhoneCommand.Camera -> launch(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            PhoneCommand.Battery -> Toast.makeText(context, "Battery ${context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%", Toast.LENGTH_LONG).show()
            PhoneCommand.ConversationMode -> launch(context, Intent(context, ConversationActivity::class.java))
            is PhoneCommand.OpenApp -> openApp(context, command.appName)
            is PhoneCommand.FindVideos -> Toast.makeText(context, "Found ${MediaCatalog(context).listVideos().size} accessible videos. Visual matching is the next module.", Toast.LENGTH_LONG).show()
            is PhoneCommand.Montage -> Toast.makeText(context, "Montage request queued for review. No source video will be changed.", Toast.LENGTH_LONG).show()
            is PhoneCommand.Unsupported -> launch(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, command.raw))
        }
    }

    private fun launch(context: Context, intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "No compatible app is available.", Toast.LENGTH_LONG).show() }

    private fun setVolume(context: Context, percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java); val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
    }

    private fun toggleFlashlight(context: Context) = runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        manager.setTorchMode(id, true)
    }.onFailure { Toast.makeText(context, "Flashlight unavailable.", Toast.LENGTH_LONG).show() }

    private fun openApp(context: Context, name: String) {
        val pm = context.packageManager
        val target = pm.getInstalledApplications(0).firstOrNull { pm.getApplicationLabel(it).toString().equals(name, true) }
        val intent = target?.packageName?.let(pm::getLaunchIntentForPackage)
        if (intent != null) launch(context, intent) else Toast.makeText(context, "$name was not found.", Toast.LENGTH_LONG).show()
    }
}
