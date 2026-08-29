package com.icarusalmighty.app.wake

import android.content.Context
import org.json.JSONArray

class WakeTemplateStore(context: Context) {
    private val preferences = context.getSharedPreferences("wake_templates", Context.MODE_PRIVATE)

    fun save(templates: List<Array<FloatArray>>) {
        val root = JSONArray()
        templates.forEach { template ->
            val frames = JSONArray()
            template.forEach { vector -> frames.put(JSONArray(vector.toList())) }
            root.put(frames)
        }
        preferences.edit().putString(KEY, root.toString()).apply()
    }

    fun load(): List<Array<FloatArray>> = runCatching {
        val root = JSONArray(preferences.getString(KEY, "[]"))
        List(root.length()) { i ->
            val frames = root.getJSONArray(i)
            Array(frames.length()) { j ->
                val values = frames.getJSONArray(j)
                FloatArray(values.length()) { k -> values.getDouble(k).toFloat() }
            }
        }
    }.getOrDefault(emptyList())

    fun isEnrolled() = load().size >= 3
    companion object { private const val KEY = "hey_icarus" }
}
