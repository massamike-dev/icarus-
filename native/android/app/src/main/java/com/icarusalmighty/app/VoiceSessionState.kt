package com.icarusalmighty.app

/** A revision also invalidates requests when the user switches away and back to the same chat. */
data class VoiceSessionState(
    val token: String = "",
    val conversationId: String? = null,
    val temporary: Boolean = false,
    val revision: Long = 0,
) {
    fun configure(
        token: String,
        hasConversation: Boolean = false,
        conversationId: String? = null,
        hasTemporary: Boolean = false,
        temporary: Boolean = false,
    ): VoiceSessionState {
        val accountChanged = token != this.token
        val base = if (accountChanged) VoiceSessionState(token = token, revision = revision) else this
        val nextTemporary = if (hasTemporary) temporary else base.temporary
        val nextConversation = when {
            token.isBlank() || nextTemporary -> null
            hasConversation -> conversationId?.takeIf { it.isNotBlank() }?.take(200)
            base.temporary != nextTemporary -> null
            else -> base.conversationId
        }
        val next = base.copy(conversationId = nextConversation, temporary = token.isNotBlank() && nextTemporary)
        return if (next != this) next.copy(revision = revision + 1) else this
    }

    fun acceptConversation(snapshot: VoiceSessionState, returnedId: String?): VoiceSessionState? {
        if (this != snapshot) return null
        if (temporary || returnedId.isNullOrBlank()) return this
        return configure(token, hasConversation = true, conversationId = returnedId)
    }

    fun cleared(): VoiceSessionState = VoiceSessionState(revision = revision + 1)
}
