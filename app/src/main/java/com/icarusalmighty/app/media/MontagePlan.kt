package com.icarusalmighty.app.media

import android.net.Uri

/** Reviewable plan. Rendering never modifies the original MediaStore items. */
data class MontageClip(val source: Uri, val startMs: Long, val endMs: Long)
data class MontagePlan(
    val title: String,
    val clips: List<MontageClip>,
    val aspectRatio: String = "9:16",
    val maximumDurationMs: Long = 60_000,
    val music: Uri? = null
)

interface VideoClassifier {
    /** Sample frames locally or through an explicitly approved private backend. */
    suspend fun score(video: DeviceVideo, query: String): Float
}

class MontagePlanner(private val classifier: VideoClassifier) {
    suspend fun plan(videos: List<DeviceVideo>, query: String): MontagePlan {
        val matches = videos.map { it to classifier.score(it, query) }
            .filter { it.second >= 0.65f }
            .sortedByDescending { it.second }
            .take(20)
        val clipLength = if (matches.isEmpty()) 0L else 60_000L / matches.size
        return MontagePlan(
            title = query,
            clips = matches.map { (video, _) -> MontageClip(video.uri, 0, minOf(video.durationMs, clipLength)) }
        )
    }
}
