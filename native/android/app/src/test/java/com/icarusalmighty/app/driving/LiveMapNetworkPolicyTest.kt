package com.icarusalmighty.app.driving

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMapNetworkPolicyTest {
    private val tile = "https://tile.openstreetmap.org/3/2/1.png"
    @Test fun consentAndExactMapOriginAreRequired() {
        assertTrue(LiveMapNetworkPolicy.allows(tile, true))
        assertFalse(LiveMapNetworkPolicy.allows(tile, false))
        for (bad in listOf(tile.replace("https:", "http:"), tile.replace(".org", ".org.evil.test"), tile + "?token=x", tile.replace("/3/2", "/3/20"), tile.replace(".org/", ".org:443/"), "https://tile.openstreetmap.org/app.js"))
            assertFalse(bad, LiveMapNetworkPolicy.allows(bad, true))
    }
    @Test fun routingAllowsOnlyFixedGeometryRequests() {
        val route = "https://routing.openstreetmap.de/routed-car/route/v1/driving/-122.330000,47.600000;-122.340000,47.610000?overview=full&geometries=geojson&steps=false&alternatives=false"
        assertTrue(LiveMapNetworkPolicy.allows(route, true))
        assertFalse(LiveMapNetworkPolicy.allows(route, false))
        assertFalse(LiveMapNetworkPolicy.allows(route + "&token=secret", true))
        assertFalse(LiveMapNetworkPolicy.allows("https://routing.openstreetmap.de/script.js", true))
    }
}
