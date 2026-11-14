package com.everyonesoftware.hermespocket

enum class Screen(val label: String) { CHAT("Chat"), SESSIONS("Sessions"), ACTIVITY("Activity"), CONNECT("Connect") }
enum class ConnectionMode { OFFLINE, DEMO, CONNECTED }
data class ChatMessage(val id: String, val role: String, val text: String)
data class AgentSession(val id: String, val title: String, val preview: String = "", val updatedAt: String = "")
data class ToolActivity(val id: String, val title: String, val detail: String, val status: String = "completed")
data class Approval(val id: String, val command: String, val description: String, val decision: String? = null)
data class PocketState(
    val screen: Screen = Screen.SESSIONS,
    val mode: ConnectionMode = ConnectionMode.OFFLINE,
    val serverName: String = "Home server",
    val endpoint: String = "",
    val profile: String = "default",
    val sessions: List<AgentSession> = emptyList(),
    val selectedSessionId: String? = null,
    val sessionTitle: String = "New conversation",
    val messages: List<ChatMessage> = emptyList(),
    val activity: List<ToolActivity> = emptyList(),
    val approval: Approval? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val connectionNote: String? = null,
)
sealed interface PocketAction {
    data class Navigate(val screen: Screen) : PocketAction
    data object Demo : PocketAction
    data object NewSession : PocketAction
    data class SelectSession(val id: String) : PocketAction
    data class Send(val text: String) : PocketAction
    data class Connect(val endpoint: String, val credential: String) : PocketAction
    data object Disconnect : PocketAction
    data class DecideApproval(val id: String, val allow: Boolean) : PocketAction
    data object Refresh : PocketAction
    data object DismissError : PocketAction
}
