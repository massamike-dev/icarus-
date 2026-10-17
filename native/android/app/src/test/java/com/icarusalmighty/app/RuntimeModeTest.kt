package com.icarusalmighty.app

import org.junit.Assert.*
import org.junit.Test

class RuntimeModeTest {
    @Test fun freshAndInvalidStoredModesDefaultToLocal() {
        for (value in listOf(null, "", "auto", "fallback", "LOCAL", "local")) assertEquals("local", RuntimeMode.normalize(value))
        assertEquals("cloud", RuntimeMode.normalize("cloud"))
    }

    @Test fun onlyTheCurrentRuntimeGenerationMayExecute() {
        val current = RuntimeMode.generation()
        assertTrue(RuntimeMode.isCurrent(current))
        assertFalse(RuntimeMode.isCurrent(current - 1))
        assertFalse(RuntimeMode.isCurrent(current + 1))
    }

    @Test fun localAndHostedOriginsNeverInterchangeTrust() {
        val cloud = "https://icarusassistant.com"
        assertTrue(TrustedWebPolicy.isTrustedUrl(RuntimeMode.LOCAL_URL, RuntimeMode.LOCAL_ORIGIN))
        assertFalse(TrustedWebPolicy.isTrustedUrl(RuntimeMode.LOCAL_URL, cloud))
        assertFalse(TrustedWebPolicy.isTrustedUrl(cloud, RuntimeMode.LOCAL_URL))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://appassets.androidplatform.net.attacker.example/assets/web/index.html", RuntimeMode.LOCAL_URL))
        assertFalse(TrustedWebPolicy.isTrustedUrl("file:///android_asset/web/index.html", RuntimeMode.LOCAL_URL))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://appassets.androidplatform.net:8443/", RuntimeMode.LOCAL_URL))
    }
}
