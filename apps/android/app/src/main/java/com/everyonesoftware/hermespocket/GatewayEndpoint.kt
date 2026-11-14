package com.everyonesoftware.hermespocket

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class GatewayEndpoint(val baseUrl: String) {
    companion object {
        fun parse(value: String): GatewayEndpoint {
            val url = value.trim().toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter a valid HTTPS gateway address.")
            require(url.scheme == "https") { "HTTPS is required. Use a trusted private network or tunnel." }
            require(url.username.isEmpty() && url.password.isEmpty()) { "Do not include credentials in the address." }
            require(url.query == null && url.fragment == null) { "Use the server origin without a query or fragment." }
            require(url.encodedPath == "/") { "Use the gateway origin without an additional path." }
            return GatewayEndpoint(url.toString().removeSuffix("/"))
        }
    }
}
