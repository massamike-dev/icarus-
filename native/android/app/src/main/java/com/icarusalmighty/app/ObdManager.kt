package com.icarusalmighty.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.Locale
import java.util.UUID

class ObdManager(private val context: Context) {
    private var socket: android.bluetooth.BluetoothSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(address: String) {
        disconnect()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: error("Bluetooth unavailable")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        adapter.cancelDiscovery()
        s.connect()
        socket = s
        input = BufferedInputStream(s.inputStream)
        output = BufferedOutputStream(s.outputStream)

        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { command(it, 2500) }
    }

    @Synchronized
    fun disconnect() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
    }

    @Synchronized
    fun snapshot(): JSONObject {
        if (socket?.isConnected != true) error("OBD adapter not connected")

        val rpm = parsePid(command("010C"), "410C")?.let { bytes ->
            if (bytes.size >= 2) ((bytes[0] * 256) + bytes[1]) / 4.0 else null
        }
        val speed = parsePid(command("010D"), "410D")?.firstOrNull()?.toDouble()
        val coolant = parsePid(command("0105"), "4105")?.firstOrNull()?.let { it - 40.0 }
        val load = parsePid(command("0104"), "4104")?.firstOrNull()?.let { it * 100.0 / 255.0 }
        val throttle = parsePid(command("0111"), "4111")?.firstOrNull()?.let { it * 100.0 / 255.0 }
        val fuel = parsePid(command("012F"), "412F")?.firstOrNull()?.let { it * 100.0 / 255.0 }
        val voltage = Regex("(\\d{1,2}(?:\\.\\d+)?)V", RegexOption.IGNORE_CASE)
            .find(command("ATRV"))?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        return JSONObject()
            .put("connected", true)
            .put("rpm", rpm ?: JSONObject.NULL)
            .put("speedMph", speed?.times(0.621371) ?: JSONObject.NULL)
            .put("coolantF", coolant?.let { (it * 9.0 / 5.0) + 32.0 } ?: JSONObject.NULL)
            .put("engineLoadPercent", load ?: JSONObject.NULL)
            .put("throttlePercent", throttle ?: JSONObject.NULL)
            .put("fuelPercent", fuel ?: JSONObject.NULL)
            .put("voltage", voltage ?: JSONObject.NULL)
            .put("timestamp", System.currentTimeMillis())
    }

    private fun command(cmd: String, timeoutMs: Long = 1800): String {
        val out = output ?: error("OBD output unavailable")
        val inp = input ?: error("OBD input unavailable")
        while (inp.available() > 0) inp.read()
        out.write((cmd.trim() + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()

        val buffer = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            while (inp.available() > 0) {
                val b = inp.read()
                if (b < 0) break
                val c = b.toChar()
                if (c == '>') return buffer.toString()
                buffer.append(c)
            }
            Thread.sleep(20)
        }
        return buffer.toString()
    }

    private fun parsePid(raw: String, prefix: String): List<Int>? {
        val clean = raw.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        val index = clean.indexOf(prefix)
        if (index < 0) return null
        val payload = clean.substring(index + prefix.length)
        if (payload.length < 2) return emptyList()
        return payload.chunked(2).mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
