package com.everyonesoftware.hermespocket

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HermesGatewayTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().build()
    private val requests = LinkedBlockingQueue<JsonObject>()
    private var peer: WebSocket? = null
    private var backend: GatewayBackend? = null
    private var canonical = true
    private var capabilities = listOf("durable-admission-v1", "event-replay-v1")
    private var sources = listOf("gui")
    private var handler: (WebSocket, JsonObject) -> Unit = { ws, request ->
        when (request["method"]?.jsonPrimitive?.content) {
            "runtime.describe" -> reply(ws, request, buildJsonObject {
                put("profile_id", "default"); put("authority_epoch", 3)
                put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
                putJsonObject("session_create") {
                    put("sources", JsonArray(sources.map(::JsonPrimitive)))
                    put("parameters", JsonArray(listOf("request_id", "source", "title").map(::JsonPrimitive)))
                }
            })
            "session.list" -> reply(ws, request, buildJsonObject { put("sessions", JsonArray(emptyList())) })
        }
    }

    private fun open(timeout: Long = 1500): GatewayBackend {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                peer = webSocket
                event("gateway.ready", buildJsonObject { put("session_authority", canonical) }, webSocket = webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                requests.put(request)
                handler(webSocket, request)
            }
        }))
        server.start()
        val factory = WebSocket.Factory { request, listener ->
            client.newWebSocket(request.newBuilder().url(server.url(request.url.encodedPath + "?" + request.url.encodedQuery)).build(), listener)
        }
        // Reflection allows the initial RED to be an assertion, not a compiler failure.
        val klass = runCatching { Class.forName("com.everyonesoftware.hermespocket.HermesGateway") }.getOrNull()
        assertNotNull("Canonical HermesGateway is not implemented", klass)
        return (klass!!.getDeclaredConstructor(WebSocket.Factory::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .also { it.isAccessible = true }.newInstance(factory, timeout, timeout) as GatewayBackend).also { backend = it }
    }
    private fun reply(ws: WebSocket, request: JsonObject, result: JsonObject) {
        ws.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result) }.toString())
    }
    private fun event(type: String, payload: JsonObject = buildJsonObject {}, session: String? = null, seq: Int? = null, webSocket: WebSocket = peer!!) {
        webSocket.send(frame(type, payload, session, seq))
    }
    private fun frame(type: String, payload: JsonObject, session: String? = null, seq: Int? = null): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", "event")
        putJsonObject("params") {
            put("type", type); put("payload", payload)
            session?.let { put("session_id", it) }
            seq?.let { put("seq", it); put("replay_epoch", "e1") }
        }
    }.toString()
    private suspend fun connect(gateway: GatewayBackend) = gateway.connect(GatewayEndpoint.parse("https://gateway.example"), "a +/?&secret")
    private fun next(method: String): JsonObject {
        while (true) {
            val r = requests.poll(2, TimeUnit.SECONDS) ?: error("No request: $method")
            if (r["method"]?.jsonPrimitive?.content == method) return r
        }
    }
    @After fun cleanup() {
        backend?.close(); peer?.close(1000, "test finished"); server.shutdown()
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }

    private fun snapshot(running: Boolean = false, pending: Boolean = false): JsonObject = buildJsonObject {
        put("session_id", "s1"); put("running", running); put("execution_generation", 7)
        put("authority_epoch", 3); put("replay_epoch", "e1"); put("last_sequence", 0)
        putJsonObject("info") { put("title", "Real session") }
        put("pending", if (pending) JsonArray(listOf(buildJsonObject { put("status", "queued") })) else JsonArray(emptyList()))
        put("messages", JsonArray(listOf(buildJsonObject {
            put("role", "assistant"); put("id", "m1")
            put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "Persisted 🦉") })))
        })))
    }

    @Test fun createsCanonicalSessionAndResumesStructuredPersistedHistory() = runBlocking {
        val original = handler
        handler = { ws, r -> when (r["method"]?.jsonPrimitive?.content) {
            "session.create", "session.resume" -> reply(ws, r, snapshot())
            else -> original(ws, r)
        } }
        val gateway = open(); connect(gateway)
        assertEquals(AgentSession("s1", "Real session"), gateway.createSession())
        val create = next("session.create")["params"]!!.jsonObject
        assertEquals(setOf("request_id", "source", "title"), create.keys)
        assertEquals("gui", create["source"]!!.jsonPrimitive.content)
        java.util.UUID.fromString(create["request_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(ChatMessage("m1", "assistant", "Persisted 🦉")), gateway.history("s1"))
        assertEquals("s1", next("session.resume")["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)
    }

    private fun chatHandler() {
        val original = handler
        handler = { ws, r -> when (r["method"]?.jsonPrimitive?.content) {
            "session.resume" -> reply(ws, r, snapshot())
            "prompt.submit" -> reply(ws, r, buildJsonObject {
                put("admission_id", "a1"); put("execution_generation", 7); put("authority_epoch", 3); put("status", "started")
            })
            else -> original(ws, r)
        } }
    }
    @Test fun streamsToolsCoalescedFramesAndOnlyCompletesOnFinalEvent() = runBlocking {
        chatHandler()
        val gateway = open(); connect(gateway)
        val updates = java.util.concurrent.CopyOnWriteArrayList<GatewayUpdate>()
        val job = async(Dispatchers.Default) { gateway.send("s1", "hello", updates::add) }
        val submit = next("prompt.submit")["params"]!!.jsonObject
        assertEquals(setOf("session_id", "text", "input_id"), submit.keys)
        java.util.UUID.fromString(submit["input_id"]!!.jsonPrimitive.content)
        delay(80)
        assertFalse("Admission ACK must not complete chat", job.isCompleted)
        peer!!.send(listOf(
            frame("message.delta", buildJsonObject { put("text", "Wrong session") }, "other", 1),
            frame("message.delta", buildJsonObject { put("text", "Hel") }, "s1", 1),
            frame("message.delta", buildJsonObject { put("text", "Hel") }, "s1", 1),
            frame("tool.start", buildJsonObject { put("tool_id", "t1"); put("name", "terminal") }, "s1", 2),
            frame("tool.complete", buildJsonObject { put("tool_id", "t1"); put("name", "terminal"); put("result_text", "ok") }, "s1", 3),
            frame("message.delta", buildJsonObject { put("text", "lo") }, "s1", 4),
            frame("message.complete", buildJsonObject { put("text", "Hello!"); put("status", "complete") }, "s1", 5),
        ).joinToString("\n"))
        assertEquals("Hello!", withTimeout(2000) { job.await() })
        assertEquals(listOf("Hel", "Hello", "Hello!"), updates.filterIsInstance<GatewayUpdate.Text>().map { it.text })
        assertEquals(listOf("running", "completed"), updates.filterIsInstance<GatewayUpdate.Tool>().map { it.activity.status })
    }

    @Test fun negotiatesCanonicalAndListsStoredLatest100WithEncodedToken() = runBlocking {
        assertTrue(connect(open()).isEmpty())
        val upgrade = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/ws", upgrade.requestUrl!!.encodedPath)
        assertEquals("a +/?&secret", upgrade.requestUrl!!.queryParameter("token"))
        val list = next("session.list")["params"]!!.jsonObject
        assertEquals("stored", list["scope"]!!.jsonPrimitive.content)
        assertEquals(100, list["limit"]!!.jsonPrimitive.int)
        assertEquals(false, list["include_hidden"]!!.jsonPrimitive.boolean)
    }

    @Test fun rejectsLegacyGatewayWithExplicitCompatibilityError() = runBlocking {
        canonical = false
        val error = runCatching { connect(open()) }.exceptionOrNull()
        assertEquals(GatewayFailure.Reason.UNSUPPORTED_PROTOCOL, (error as GatewayFailure).reason)
    }

    @Test fun rejectsMissingCanonicalCapabilities() = runBlocking {
        capabilities = emptyList()
        val error = runCatching { connect(open()) }.exceptionOrNull()
        assertEquals(GatewayFailure.Reason.UNSUPPORTED_PROTOCOL, (error as GatewayFailure).reason)
    }

    @Test fun gatewayCanConnectAfterPriorCloseAsStoreRequires() = runBlocking {
        val gateway = open()
        gateway.close()
        assertTrue(connect(gateway).isEmpty())
        assertEquals("default", gateway.profile)
    }

    @Test fun runningSessionDoesNotReceiveAnotherSubmission() = runBlocking {
        val original = handler
        handler = { ws, r -> if (r["method"]?.jsonPrimitive?.content == "session.resume") reply(ws, r, snapshot(running = true)) else original(ws, r) }
        val gateway = open(); connect(gateway)
        val error = runCatching { gateway.send("s1", "do not duplicate", {}) }.exceptionOrNull()
        assertEquals(GatewayFailure.Reason.UNKNOWN_OUTCOME, (error as GatewayFailure).reason)
        assertFalse(requests.any { it["method"]?.jsonPrimitive?.content == "prompt.submit" })
    }

    @Test fun failedCompletionNeverBecomesSuccessfulAssistantReply() = runBlocking {
        chatHandler()
        val gateway = open(); connect(gateway)
        val result = async(Dispatchers.Default) { runCatching { gateway.send("s1", "hello", {}) } }
        next("prompt.submit")
        event("message.complete", buildJsonObject { put("status", "error"); put("text", "sensitive backend failure") }, "s1", 1)
        val error = withTimeout(2000) { result.await() }.exceptionOrNull() as GatewayFailure
        assertEquals(GatewayFailure.Reason.TURN_FAILED, error.reason)
        assertFalse(error.message.orEmpty().contains("sensitive"))
    }

    @Test fun approvalRequiresDesktopAndDoesNotAutomaticallyAnswer() = runBlocking {
        chatHandler()
        val gateway = open(); connect(gateway)
        val updates = java.util.concurrent.CopyOnWriteArrayList<GatewayUpdate>()
        val result = async(Dispatchers.Default) { runCatching { gateway.send("s1", "hello", updates::add) } }
        next("prompt.submit")
        event("approval.request", buildJsonObject { put("prompt_id", "p1"); put("command", "dangerous-command") }, "s1", 1)
        val error = withTimeout(2000) { result.await() }.exceptionOrNull() as GatewayFailure
        assertEquals(GatewayFailure.Reason.INTERACTION_REQUIRED, error.reason)
        assertEquals("waiting", updates.filterIsInstance<GatewayUpdate.Tool>().single().activity.status)
        assertFalse(requests.any { it["method"]?.jsonPrimitive?.content == "approval.respond" })
    }

    @Test fun closeUnblocksAnActiveTurnWithoutSubmittingAgain() = runBlocking {
        chatHandler()
        val gateway = open(); connect(gateway)
        val result = async(Dispatchers.Default) { runCatching { gateway.send("s1", "hello", {}) } }
        next("prompt.submit")
        gateway.close()
        assertTrue(withTimeout(2000) { result.await() }.isFailure)
        assertFalse(requests.any { it["method"]?.jsonPrimitive?.content == "prompt.submit" })
    }

    @Test fun eventsBeforeAdmissionReceiptAreBufferedAndUnicodeOffsetsAreUtf16() = runBlocking {
        chatHandler()
        val original = handler
        handler = { ws, r -> if (r["method"]?.jsonPrimitive?.content == "prompt.submit") {
            event("message.delta", buildJsonObject { put("text", "🦉") }, "s1", 1, ws)
            event("message.delta", buildJsonObject { put("text", " hi"); put("offset", 2) }, "s1", 2, ws)
            event("message.complete", buildJsonObject { put("text", "🦉 hi"); put("status", "complete") }, "s1", 3, ws)
            original(ws, r)
        } else original(ws, r) }
        val gateway = open(); connect(gateway)
        assertEquals("🦉 hi", gateway.send("s1", "hello", {}))
    }
}
