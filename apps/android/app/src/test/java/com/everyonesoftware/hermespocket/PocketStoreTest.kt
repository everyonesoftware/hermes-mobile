package com.everyonesoftware.hermespocket

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PocketStoreTest {
    private class Backend : GatewayBackend {
        var calls = 0
        var closed = false
        var historyGate: CompletableDeferred<Unit>? = null
        var sentSession: String? = null
        var failSend = false
        var failureReason: GatewayFailure.Reason? = null
        override suspend fun connect(endpoint: GatewayEndpoint, credential: String): List<AgentSession> { calls++; return listOf(AgentSession("remote-1", "Remote conversation")) }
        override suspend fun sessions() = listOf(AgentSession("remote-2", "Updated remote conversation"))
        override suspend fun history(sessionId: String): List<ChatMessage> { historyGate?.await(); return listOf(ChatMessage("h1", "assistant", "Actual history")) }
        override suspend fun createSession() = AgentSession("new-remote", "New conversation")
        override suspend fun send(sessionId: String, text: String, onUpdate: (GatewayUpdate) -> Unit): String { calls++; sentSession = sessionId; failureReason?.let { throw GatewayFailure(it) }; if (failSend) error("sensitive server detail"); onUpdate(GatewayUpdate.Text("Actual reply")); return "Actual reply" }
        override fun close() { closed = true }
    }

    @Test fun `demo is explicit and does not contact gateway`() = runTest {
        val backend = Backend()
        val store = PocketStore(backend)
        store.dispatch(PocketAction.Demo)
        assertEquals(ConnectionMode.DEMO, store.state.value.mode)
        assertTrue(store.state.value.sessions.isNotEmpty())
        assertEquals(0, backend.calls)
    }

    @Test fun `demo conversations and approvals stay local and preserve session messages`() = runTest {
        val backend = Backend()
        val store = PocketStore(backend)
        store.dispatch(PocketAction.Demo)
        store.dispatch(PocketAction.SelectSession("demo-backup"))
        assertEquals(Screen.CHAT, store.state.value.screen)
        assertTrue(store.state.value.messages.isNotEmpty())
        store.dispatch(PocketAction.Send("Keep this in the demo"))
        assertTrue(store.state.value.messages.last().text.contains("demo", ignoreCase = true))
        val count = store.state.value.messages.size
        val approval = requireNotNull(store.state.value.approval)
        store.dispatch(PocketAction.DecideApproval(approval.id, false))
        assertEquals("Denied — demo only. No command was executed.", store.state.value.approval?.decision)
        store.dispatch(PocketAction.NewSession)
        assertTrue(store.state.value.messages.isEmpty())
        store.dispatch(PocketAction.SelectSession("demo-backup"))
        assertEquals(count, store.state.value.messages.size)
        assertEquals(0, backend.calls)
    }

    @Test fun `connect replaces demo state with real sessions and disconnect clears private content`() = runTest {
        val backend = Backend()
        val store = PocketStore(backend)
        store.dispatch(PocketAction.Demo)
        store.dispatch(PocketAction.SelectSession("demo-backup"))
        store.dispatch(PocketAction.Connect("https://home.example", "session-credential"))
        assertEquals(ConnectionMode.CONNECTED, store.state.value.mode)
        assertEquals(listOf("remote-1"), store.state.value.sessions.map { it.id })
        assertTrue(store.state.value.messages.isEmpty())
        assertNull(store.state.value.approval)
        store.dispatch(PocketAction.SelectSession("remote-1"))
        assertEquals("Actual history", store.state.value.messages.single().text)
        store.dispatch(PocketAction.Disconnect)
        assertEquals(ConnectionMode.OFFLINE, store.state.value.mode)
        assertTrue(store.state.value.sessions.isEmpty())
        assertTrue(store.state.value.messages.isEmpty())
        assertTrue(backend.closed)
    }

    @Test fun `remote send creates session and uses actual streamed reply`() = runTest {
        val backend = Backend()
        val store = PocketStore(backend)
        store.dispatch(PocketAction.Connect("https://home.example", "session-credential"))
        store.dispatch(PocketAction.NewSession)
        store.dispatch(PocketAction.Send("Check something"))
        assertEquals("new-remote", store.state.value.selectedSessionId)
        assertEquals(listOf("user", "assistant"), store.state.value.messages.map { it.role })
        assertEquals("Check something", store.state.value.messages.first().text)
        assertEquals("Actual reply", store.state.value.messages.last().text)
        assertFalse(store.state.value.busy)
        assertEquals(2, backend.calls)
    }

    @Test fun `disconnect prevents late history from restoring private content`() = runTest {
        val backend = Backend()
        val store = PocketStore(backend)
        store.dispatch(PocketAction.Connect("https://home.example", "session-credential"))
        backend.historyGate = CompletableDeferred()
        val load = launch { store.dispatch(PocketAction.SelectSession("remote-1")) }
        runCurrent()
        assertTrue(store.state.value.busy)
        store.dispatch(PocketAction.Disconnect)
        backend.historyGate!!.complete(Unit)
        load.join()
        assertEquals(ConnectionMode.OFFLINE, store.state.value.mode)
        assertTrue(store.state.value.messages.isEmpty())
        assertNull(store.state.value.selectedSessionId)
    }

    @Test fun `offline sends and invalid connections never contact gateway`() = runTest {
        val backend = Backend()
        val store = PocketStore(backend)
        store.dispatch(PocketAction.Send("Do not send this"))
        assertNotNull(store.state.value.error)
        assertTrue(store.state.value.messages.isEmpty())
        store.dispatch(PocketAction.Connect("http://home.example", "secret"))
        assertEquals(ConnectionMode.OFFLINE, store.state.value.mode)
        assertTrue(store.state.value.error!!.contains("HTTPS"))
        store.dispatch(PocketAction.Connect("https://home.example", "\ninvalid\ncredential"))
        assertEquals(0, backend.calls)
        store.dispatch(PocketAction.DismissError)
        assertNull(store.state.value.error)
    }

    @Test fun `refresh retrieves current remote sessions`() = runTest {
        val store = PocketStore(Backend())
        store.dispatch(PocketAction.Connect("https://home.example", "session-credential"))
        store.dispatch(PocketAction.Refresh)
        assertEquals(listOf("remote-2"), store.state.value.sessions.map { it.id })
    }

    @Test fun `refresh never retargets an open conversation to a new session`() = runTest {
        val backend = Backend()
        val store = PocketStore(backend)
        store.dispatch(PocketAction.Connect("https://home.example", "session-credential"))
        store.dispatch(PocketAction.SelectSession("remote-1"))
        store.dispatch(PocketAction.Refresh)
        store.dispatch(PocketAction.Send("Stay in the original conversation"))
        assertEquals("remote-1", backend.sentSession)
    }

    @Test fun `desktop interaction requirements are explained without exposing server exceptions`() = runTest {
        val backend = Backend()
        val store = PocketStore(backend)
        store.dispatch(PocketAction.Connect("https://home.example", "session-credential"))
        backend.failureReason = GatewayFailure.Reason.INTERACTION_REQUIRED
        store.dispatch(PocketAction.Send("Run a task"))
        assertEquals(GatewayFailure.Reason.INTERACTION_REQUIRED.userMessage, store.state.value.error)
        assertFalse(store.state.value.busy)
        backend.failureReason = null
        backend.failSend = true
        store.dispatch(PocketAction.Send("Another task"))
        assertFalse(store.state.value.error.orEmpty().contains("sensitive server detail"))
    }
}
