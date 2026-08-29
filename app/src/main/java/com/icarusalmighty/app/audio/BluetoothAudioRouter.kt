package com.icarusalmighty.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

class BluetoothAudioRouter(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun routeForConversation(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        return if (Build.VERSION.SDK_INT >= 31) {
            val device = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
            device != null && audio.setCommunicationDevice(device)
        } else {
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            true
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        else {
            @Suppress("DEPRECATION") audio.stopBluetoothSco()
            @Suppress("DEPRECATION") audio.isBluetoothScoOn = false
        }
        audio.mode = AudioManager.MODE_NORMAL
    }
}
