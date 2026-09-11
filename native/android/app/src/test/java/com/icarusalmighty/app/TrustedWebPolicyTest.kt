package com.icarusalmighty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedWebPolicyTest {
    private val base = "https://icarusassistant.com"

    @Test fun acceptsSameHttpsOrigin() {
        assertTrue(TrustedWebPolicy.isTrustedUrl("https://icarusassistant.com/chat?x=1#top", base))
        assertTrue(TrustedWebPolicy.isTrustedUrl("https://ICARUSASSISTANT.com/settings", base))
    }

    @Test fun rejectsDifferentHostsAndSubdomains() {
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://example.com", base))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://evil.icarusassistant.com", base))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://icarusassistant.com.evil.example", base))
    }

    @Test fun rejectsNonHttpsAndCredentials() {
        assertFalse(TrustedWebPolicy.isTrustedUrl("http://icarusassistant.com", base))
        assertFalse(TrustedWebPolicy.isTrustedUrl("javascript:alert(1)", base))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://user@icarusassistant.com", base))
    }

    @Test fun respectsExplicitPorts() {
        assertTrue(TrustedWebPolicy.isTrustedUrl("https://icarusassistant.com:443/chat", base))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://icarusassistant.com:8443/chat", base))
    }

    @Test fun rejectsMalformedOrMissingValues() {
        assertFalse(TrustedWebPolicy.isTrustedUrl(null, base))
        assertFalse(TrustedWebPolicy.isTrustedUrl("", base))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://icarusassistant.com", "not a url"))
    }
}
