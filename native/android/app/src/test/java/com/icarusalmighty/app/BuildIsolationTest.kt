package com.icarusalmighty.app

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** These assertions run against each variant's generated BuildConfig in CI. */
class BuildIsolationTest {
    @Test fun privateBuildCannotReplaceThePublicPackageOrHandleItsLinks() {
        if (BuildConfig.PRIVATE_TEST) {
            assertEquals("privateTest", BuildConfig.BUILD_TYPE)
            assertEquals("com.icarusalmighty.app.test", BuildConfig.APPLICATION_ID)
            assertEquals("icarus-test", BuildConfig.ICARUS_LINK_SCHEME)
            assertTrue(BuildConfig.VERSION_NAME.endsWith("-test"))
            assertFalse(BuildConfig.DEBUG)
        } else {
            assertEquals("com.icarusalmighty.app", BuildConfig.APPLICATION_ID)
            assertEquals("icarus", BuildConfig.ICARUS_LINK_SCHEME)
        }
    }

    @Test fun privateBuildHasNoPublicUpdateFeed() {
        if (BuildConfig.PRIVATE_TEST) assertEquals("", BuildConfig.UPDATE_NOTES_URL)
        else assertTrue(BuildConfig.UPDATE_NOTES_URL.startsWith("https://"))
    }

    @Test fun privateBridgeTrustsOnlyItsOwnHttpsOrigin() {
        if (!BuildConfig.PRIVATE_TEST) return
        val endpoint = URI(BuildConfig.ICARUS_WEB_URL)
        assertEquals("https", endpoint.scheme.lowercase())
        assertTrue(endpoint.rawPath.orEmpty() in setOf("", "/"))
        assertTrue(endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null)
        assertTrue(TrustedWebPolicy.isTrustedUrl(BuildConfig.ICARUS_WEB_URL, BuildConfig.ICARUS_WEB_URL))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://icarusassistant.com", BuildConfig.ICARUS_WEB_URL))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://www.icarusassistant.com", BuildConfig.ICARUS_WEB_URL))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://icarus-assistant.onrender.com", BuildConfig.ICARUS_WEB_URL))
        assertFalse(TrustedWebPolicy.isTrustedUrl("https://attacker.example", BuildConfig.ICARUS_WEB_URL))
    }
}
