package com.everyonesoftware.hermespocket

import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun `accepts HTTPS origin and normalizes trailing slash`() {
        assertEquals("https://hermes.example", GatewayEndpoint.parse(" https://hermes.example/ ").baseUrl)
    }

    @Test fun `rejects insecure ambiguous and credential-bearing addresses`() {
        listOf(
            "http://hermes.example", "ws://hermes.example", "not a URL", "",
            "https://user:secret@hermes.example", "https://hermes.example/api",
            "https://hermes.example/?token=secret", "https://hermes.example/#token",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { GatewayEndpoint.parse(value) }
        }
    }

    @Test fun `preserves nondefault TLS ports`() {
        assertEquals("https://hermes.example:8443", GatewayEndpoint.parse("https://hermes.example:8443/").baseUrl)
    }
}
