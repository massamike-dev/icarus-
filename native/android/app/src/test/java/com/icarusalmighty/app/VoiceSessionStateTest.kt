package com.icarusalmighty.app

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionStateTest {
    @Test fun tokenOnlyRefreshPreservesSelectedConversationAndTemporaryMode() {
        val selected = VoiceSessionState("account", "chat", revision = 2)
        assertEquals(selected, selected.configure("account"))
        val temporary = selected.configure("account", hasTemporary = true, temporary = true)
        assertNull(temporary.conversationId)
        assertTrue(temporary.temporary)
        assertEquals(temporary, temporary.configure("account"))
    }

    @Test fun accountChangeAndLogoutRemovePriorContext() {
        val previous = VoiceSessionState("old-account", "private-chat", temporary = true, revision = 7)
        val changed = previous.configure("new-account")
        assertNull(changed.conversationId)
        assertFalse(changed.temporary)
        assertTrue(changed.revision > previous.revision)
        val cleared = previous.cleared()
        assertEquals("", cleared.token)
        assertNull(cleared.conversationId)
        assertNull(cleared.acceptConversation(previous, "late-chat"))
    }

    @Test fun lateReplyCannotOverwriteAnotherChatOrAnAccountSwitchBack() {
        val request = VoiceSessionState("account", "chat-a", revision = 1)
        val switched = request.configure("account", hasConversation = true, conversationId = "chat-b")
        assertNull(switched.acceptConversation(request, "chat-a"))
        val switchedBack = switched.configure("account", hasConversation = true, conversationId = "chat-a")
        assertNull(switchedBack.acceptConversation(request, "chat-a"))
        val accountBack = request.configure("different-account").configure("account", hasConversation = true, conversationId = "chat-a")
        assertNull(accountBack.acceptConversation(request, "chat-a"))
    }

    @Test fun serverConversationIsAcceptedOnlyForCurrentNonTemporarySnapshot() {
        val request = VoiceSessionState("account", revision = 3)
        val adopted = request.acceptConversation(request, "new-chat")!!
        assertEquals("new-chat", adopted.conversationId)
        assertTrue(adopted.revision > request.revision)
        val temporary = request.configure("account", hasTemporary = true, temporary = true)
        assertEquals(temporary, temporary.acceptConversation(temporary, "must-not-save"))
        assertNull(temporary.configure("account", hasTemporary = true, temporary = false).conversationId)
    }

    @Test fun explicitNewChatClearsContextWithoutLosingAuthentication() {
        val previous = VoiceSessionState("account", "old-chat", revision = 9)
        val next = previous.configure("account", hasConversation = true, conversationId = null)
        assertEquals("account", next.token)
        assertNull(next.conversationId)
        assertEquals(10L, next.revision)
        assertNull(next.acceptConversation(previous, "old-chat"))
    }

    @Test fun explicitNewChatInvalidatesFirstVoiceRequestBeforeItHasAnId() {
        val request = VoiceSessionState("account", revision = 4)
        val reset = request.configure("account", hasConversation = true, conversationId = null)
        assertEquals(5L, reset.revision)
        assertNull(reset.conversationId)
        assertNull(reset.acceptConversation(request, "late-first-chat"))
        assertEquals(reset, reset.configure("account"))

        // The web bridge serializes its explicit empty selection as "".
        val resetAgain = reset.configure("account", hasConversation = true, conversationId = "")
        assertEquals(6L, resetAgain.revision)
        assertNull(resetAgain.acceptConversation(reset, "another-late-chat"))
    }
}
