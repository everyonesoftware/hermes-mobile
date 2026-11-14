package com.everyonesoftware.hermespocket

interface GatewayBackend {
    val profile: String get() = "default"
    suspend fun connect(endpoint: GatewayEndpoint, credential: String): List<AgentSession>
    suspend fun sessions(): List<AgentSession>
    suspend fun history(sessionId: String): List<ChatMessage>
    suspend fun createSession(): AgentSession
    suspend fun send(sessionId: String, text: String, onUpdate: (GatewayUpdate) -> Unit): String
    fun close()
}
sealed interface GatewayUpdate {
    data class Text(val text: String) : GatewayUpdate
    data class Tool(val activity: ToolActivity) : GatewayUpdate
}
