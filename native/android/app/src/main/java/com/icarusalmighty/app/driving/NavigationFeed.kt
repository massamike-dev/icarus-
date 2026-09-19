package com.icarusalmighty.app.driving

import java.util.concurrent.CopyOnWriteArraySet

data class NavigationSnapshot(
    val active: Boolean = false,
    val instruction: String? = null,
    val distance: String? = null,
    val source: String? = null,
)

/** Process-local feed. It publishes only text received from an active navigation notification. */
object NavigationFeed {
    private val listeners = CopyOnWriteArraySet<(NavigationSnapshot) -> Unit>()
    @Volatile private var current = NavigationSnapshot()

    fun publish(value: NavigationSnapshot) {
        current = value
        listeners.forEach { it(value) }
    }

    fun subscribe(listener: (NavigationSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        listener(current)
        return AutoCloseable { listeners -= listener }
    }
}

object NavigationTextParser {
    private val distance = Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:ft|feet|mi|mile|miles|m|km)\\b", RegexOption.IGNORE_CASE)
    private val maneuver = Regex("\\b(turn|continue|merge|exit|keep|head|arrive|destination|roundabout|u-turn|uturn|take|slight|bear|stay)\\b", RegexOption.IGNORE_CASE)

    fun parse(values: List<CharSequence?>, source: String): NavigationSnapshot {
        val text = values.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }.distinct()
        val instruction = text.firstOrNull { maneuver.containsMatchIn(it) }
        val distanceText = text.asSequence().mapNotNull { distance.find(it)?.value }.firstOrNull()
        return if (instruction == null) NavigationSnapshot() else NavigationSnapshot(
            active = true,
            instruction = instruction.take(96),
            distance = distanceText?.take(20),
            source = source,
        )
    }
}
