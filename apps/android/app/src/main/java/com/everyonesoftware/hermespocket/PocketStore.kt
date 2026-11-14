package com.everyonesoftware.hermespocket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlinx.coroutines.CancellationException

class PocketStore(private val gateway: GatewayBackend, endpoint: String = "") {
    private val mutableState = MutableStateFlow(PocketState(endpoint = endpoint))
    val state = mutableState.asStateFlow()
    private val demoMessages = mutableMapOf<String, List<ChatMessage>>()
    private val demoApprovals = mutableMapOf<String, Approval>()
    private var generation = 0L
    suspend fun dispatch(action: PocketAction) {
        if (state.value.busy && action !is PocketAction.Navigate && action != PocketAction.Disconnect && action != PocketAction.DismissError) return
        when (action) {
            PocketAction.Demo -> {
                generation++
                gateway.close()
                demoMessages.clear()
                demoApprovals.clear()
                mutableState.value = PocketState(
                    mode = ConnectionMode.DEMO,
                    endpoint = mutableState.value.endpoint,
                    sessions = listOf(
                        AgentSession("demo-backup", "Home server check-in", "Sample backup report ready to review", "Demo"),
                        AgentSession("demo-android", "Android companion app", "Exploring navigation and connection flow", "Demo"),
                        AgentSession("demo-weekly", "Weekly planning", "Continue from your desktop", "Demo"),
                    ),
                    connectionNote = "Demo workspace — nothing is sent to a server.",
                )
            }
            is PocketAction.Navigate -> mutableState.update { it.copy(screen = action.screen) }
            PocketAction.DismissError -> mutableState.update { it.copy(error = null) }
            PocketAction.NewSession -> mutableState.update {
                it.copy(screen = Screen.CHAT, selectedSessionId = null, sessionTitle = "New conversation", messages = emptyList(), activity = emptyList(), approval = null)
            }
            is PocketAction.SelectSession -> {
                val session = state.value.sessions.find { it.id == action.id } ?: return
                if (state.value.mode == ConnectionMode.CONNECTED) {
                    request { stamp ->
                        val messages = gateway.history(session.id)
                        updateCurrent(stamp) { it.copy(screen = Screen.CHAT, selectedSessionId = session.id, sessionTitle = session.title, messages = messages, activity = emptyList(), approval = null) }
                    }
                    return
                }
                if (state.value.mode != ConnectionMode.DEMO) return
                val messages = demoMessages.getOrPut(session.id) {
                    listOf(
                        ChatMessage("${session.id}-user", "user", if (session.id == "demo-backup") "Check my home server backups. Don't change anything." else "Let's continue ${session.title.lowercase()}."),
                        ChatMessage("${session.id}-assistant", "assistant", if (session.id == "demo-backup") "Demo report: the sample backup completed successfully. This is illustrative content, not a check of your actual server." else "This is a sample conversation. No messages or files have been loaded from your server."),
                    )
                }
                val approval = if (session.id == "demo-backup") demoApprovals.getOrPut(session.id) {
                    Approval("demo-restart", "sudo systemctl restart backup.service", "Sample request outside the read-only task. Restarting could interrupt a backup. Demo only.")
                } else null
                mutableState.update { it.copy(screen = Screen.CHAT, selectedSessionId = session.id, sessionTitle = session.title, messages = messages, approval = approval, activity = if (session.id == "demo-backup") listOf(ToolActivity("demo-log", "Read backup log", "Sample read-only tool result · demo"), ToolActivity("demo-disk", "Inspect disk usage", "Sample read-only tool result · demo")) else emptyList()) }
            }
            is PocketAction.Send -> {
                val text = action.text.trim()
                if (text.isEmpty()) return
                if (state.value.mode == ConnectionMode.OFFLINE) {
                    mutableState.update { it.copy(error = "Connect to your gateway or open the demo before sending a message.") }
                    return
                }
                if (state.value.mode == ConnectionMode.CONNECTED) {
                    request { stamp ->
                        val session = state.value.selectedSessionId?.let { id -> state.value.sessions.find { it.id == id } ?: AgentSession(id, state.value.sessionTitle) }
                            ?: gateway.createSession()
                        if (stamp != generation) return@request
                        val assistantId = UUID.randomUUID().toString()
                        updateCurrent(stamp) { it.copy(selectedSessionId = session.id, sessionTitle = if (session.title == "New conversation") text.take(60) else session.title,
                            sessions = listOf(session) + it.sessions.filterNot { s -> s.id == session.id },
                            messages = it.messages + listOf(ChatMessage(UUID.randomUUID().toString(), "user", text), ChatMessage(assistantId, "assistant", "")), activity = emptyList(), approval = null) }
                        val result = gateway.send(session.id, text) { update ->
                            when (update) {
                                is GatewayUpdate.Text -> updateCurrent(stamp) { it.copy(messages = it.messages.map { m -> if (m.id == assistantId) m.copy(text = update.text) else m }) }
                                is GatewayUpdate.Tool -> updateCurrent(stamp) { it.copy(activity = it.activity.filterNot { a -> a.id == update.activity.id } + update.activity) }
                            }
                        }
                        updateCurrent(stamp) { it.copy(messages = it.messages.map { m -> if (m.id == assistantId) m.copy(text = result) else m }, sessions = it.sessions.map { s -> if (s.id == session.id) s.copy(title = it.sessionTitle, preview = text.take(90)) else s }) }
                    }
                    return
                }
                val id = state.value.selectedSessionId ?: "demo-${UUID.randomUUID()}"
                val title = if (state.value.selectedSessionId == null) text.take(60) else state.value.sessionTitle
                val messages = state.value.messages + listOf(
                    ChatMessage(UUID.randomUUID().toString(), "user", text),
                    ChatMessage(UUID.randomUUID().toString(), "assistant", "Local demo response: your message was not sent to a server. Connect to your Hermes gateway to have a real conversation."),
                )
                demoMessages[id] = messages
                mutableState.update { it.copy(selectedSessionId = id, sessionTitle = title, messages = messages, sessions = listOf(AgentSession(id, title, text.take(90), "Demo")) + it.sessions.filterNot { s -> s.id == id }) }
            }
            is PocketAction.DecideApproval -> {
                if (state.value.mode != ConnectionMode.DEMO) {
                    mutableState.update { it.copy(error = "Remote command approvals are not supported in this version. Review the request on your desktop.") }
                    return
                }
                val approval = state.value.approval?.takeIf { it.id == action.id && it.decision == null } ?: return
                val updated = approval.copy(decision = if (action.allow) "Allowed once — demo only. No command was executed." else "Denied — demo only. No command was executed.")
                state.value.selectedSessionId?.let { demoApprovals[it] = updated }
                mutableState.update { it.copy(approval = updated) }
            }
            is PocketAction.Connect -> {
                val endpoint = try {
                    require(action.credential.isNotBlank()) { "Enter a gateway session token. It is kept in memory only." }
                    require(action.credential.trim().none { it.isWhitespace() || it.isISOControl() }) { "The session token must not contain whitespace or control characters." }
                    require(action.credential.length <= 8192) { "The session token is too long." }
                    GatewayEndpoint.parse(action.endpoint)
                } catch (e: IllegalArgumentException) {
                    mutableState.update { it.copy(error = e.message) }
                    return
                }
                generation++
                gateway.close()
                val stamp = generation
                mutableState.value = PocketState(screen = Screen.CONNECT, endpoint = endpoint.baseUrl, busy = true)
                try {
                    val sessions = gateway.connect(endpoint, action.credential.trim())
                    updateCurrent(stamp) { PocketState(mode = ConnectionMode.CONNECTED, endpoint = endpoint.baseUrl, profile = gateway.profile, sessions = sessions, connectionNote = "Authenticated remote gateway. Session token is kept in memory only.") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (stamp == generation) gateway.close()
                    updateCurrent(stamp) { it.copy(mode = ConnectionMode.OFFLINE, busy = false, error = (e as? GatewayFailure)?.reason?.userMessage ?: "Could not connect. Check the gateway address and session token, then try again.") }
                }
            }
            PocketAction.Disconnect -> {
                generation++
                gateway.close()
                demoMessages.clear()
                demoApprovals.clear()
                mutableState.value = PocketState(screen = Screen.CONNECT, endpoint = state.value.endpoint, connectionNote = "Disconnected. Session token and conversation content cleared from memory.")
            }
            PocketAction.Refresh -> {
                if (state.value.mode == ConnectionMode.CONNECTED) request { stamp ->
                    val sessions = gateway.sessions()
                    updateCurrent(stamp) { it.copy(sessions = sessions) }
                }
            }
        }
    }

    private fun updateCurrent(stamp: Long, transform: (PocketState) -> PocketState) {
        mutableState.update { if (stamp == generation) transform(it) else it }
    }

    private suspend fun request(block: suspend (Long) -> Unit) {
        val stamp = generation
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            block(stamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? GatewayFailure)?.reason
            val disconnected = reason in listOf(GatewayFailure.Reason.CONNECTION, GatewayFailure.Reason.AUTHENTICATION)
            if (disconnected && stamp == generation) gateway.close()
            updateCurrent(stamp) { it.copy(mode = if (disconnected) ConnectionMode.OFFLINE else it.mode,
                error = reason?.userMessage ?: "Gateway request failed. The task may still be running. Review the session before retrying.",
                messages = it.messages.filterNot { message -> message.role == "assistant" && message.text.isEmpty() }) }
        } finally {
            updateCurrent(stamp) { it.copy(busy = false) }
        }
    }
}
