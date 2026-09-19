package com.icarusalmighty.app

import android.content.Context
import org.json.JSONObject

/** Serializes WebView updates and service network replies within the application process. */
object VoiceSessionStore {
    private fun read(context: Context): VoiceSessionState {
        val prefs = context.getSharedPreferences("icarus_session", Context.MODE_PRIVATE)
        return VoiceSessionState(
            token = prefs.getString("token", "").orEmpty(),
            conversationId = prefs.getString("conversationId", null),
            temporary = prefs.getBoolean("temporary", false),
            revision = prefs.getLong("revision", 0),
        )
    }

    private fun write(context: Context, session: VoiceSessionState) {
        context.getSharedPreferences("icarus_session", Context.MODE_PRIVATE).edit()
            .putString("token", session.token)
            .putString("conversationId", session.conversationId)
            .putBoolean("temporary", session.temporary)
            .putLong("revision", session.revision)
            .apply()
    }

    @Synchronized fun snapshot(context: Context): VoiceSessionState = read(context)

    @Synchronized fun configure(context: Context, args: JSONObject) {
        val current = read(context)
        val next = current.configure(
            token = args.optString("token").take(4096),
            hasConversation = args.has("conversationId"),
            conversationId = if (args.isNull("conversationId")) null else args.optString("conversationId"),
            hasTemporary = args.has("temporary"),
            temporary = args.optBoolean("temporary", false),
        )
        if (next != current) write(context, next)
    }

    @Synchronized fun acceptConversation(context: Context, snapshot: VoiceSessionState, returnedId: String?): VoiceSessionState? {
        val current = read(context)
        val next = current.acceptConversation(snapshot, returnedId) ?: return null
        if (next != current) write(context, next)
        return next
    }

    @Synchronized fun isCurrent(context: Context, snapshot: VoiceSessionState): Boolean = read(context) == snapshot

    @Synchronized fun clearConversation(context: Context, snapshot: VoiceSessionState) {
        val current = read(context)
        if (current == snapshot) write(context, current.configure(current.token, hasConversation = true, conversationId = null))
    }

    @Synchronized fun clear(context: Context) = write(context, read(context).cleared())

    fun status(context: Context): JSONObject = snapshot(context).let {
        JSONObject().put("conversationId", it.conversationId ?: JSONObject.NULL).put("temporary", it.temporary)
    }
}
