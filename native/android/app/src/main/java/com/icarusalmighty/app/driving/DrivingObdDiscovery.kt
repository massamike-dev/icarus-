package com.icarusalmighty.app.driving

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale

object DrivingObdDiscovery {
    private val markers = listOf(
        "obd", "elm327", "elm 327", "obdlink", "vgate", "veepeak", "gearworks", "carista", "blue driver"
    )

    @SuppressLint("MissingPermission")
    fun findSingleKnownAddress(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return null
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return null
        val candidates = adapter.bondedDevices.orEmpty().filter { device ->
            val name = device.name.orEmpty().lowercase(Locale.US)
            markers.any(name::contains)
        }
        return candidates.singleOrNull()?.address
    }
}
