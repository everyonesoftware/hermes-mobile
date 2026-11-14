package com.everyonesoftware.hermespocket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

/** Canonical, token-only gateway. Credentials exist only in the live transport's memory. */
class HermesGateway internal constructor(
    private val factory: WebSocket.Factory,
    private val rpcTimeoutMs: Long = 30_000,
    private val turnTimeoutMs: Long = 300_000,
) : GatewayBackend {
    constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).pingInterval(15, java.util.concurrent.TimeUnit.SECONDS).build())

    @Volatile private var connection: RpcConnection? = null
    override val profile: String get() = connection?.profile ?: "default"
    override suspend fun connect(endpoint: GatewayEndpoint, credential: String): List<AgentSession> {
        close()
        val next = RpcConnection(factory, rpcTimeoutMs, turnTimeoutMs)
        connection = next
        return next.connect(endpoint, credential)
    }
    private fun current(): RpcConnection = connection ?: throw GatewayFailure(GatewayFailure.Reason.CONNECTION)
    override suspend fun sessions() = current().sessions()
    override suspend fun history(sessionId: String) = current().history(sessionId)
    override suspend fun createSession() = current().createSession()
    override suspend fun send(sessionId: String, text: String, onUpdate: (GatewayUpdate) -> Unit) = current().send(sessionId, text, onUpdate)
    override fun close() { val old = connection; connection = null; old?.close() }
}

private class RpcConnection(
    private val factory: WebSocket.Factory,
    private val rpcTimeoutMs: Long,
    private val turnTimeoutMs: Long,
) : GatewayBackend {

    private val lock = Any()
    private var socket: WebSocket? = null
    private var closed = false
    private var negotiated = false
    private val ready = CompletableDeferred<JsonObject>()
    private val requests = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private val snapshots = mutableMapOf<String, JsonObject>()
    private var createParameters = emptySet<String>()
    @Volatile override var profile: String = "default"
        private set
    private val aliases = mutableMapOf<String, String>()
    private var active: Turn? = null

    private class Turn(val sessionId: String, val onUpdate: (GatewayUpdate) -> Unit, val epoch: String?, var sequence: Long) {
        val result = CompletableDeferred<String>()
        val buffered = mutableListOf<JsonObject>()
        val text = StringBuilder()
        var segment: Int? = null
        var admission: String? = null
        var generation: Long? = null
        var acknowledged = false
    }

    override suspend fun connect(endpoint: GatewayEndpoint, credential: String): List<AgentSession> {
        val origin = GatewayEndpoint.parse(endpoint.baseUrl).baseUrl.toHttpUrl()
        require(credential.isNotBlank()) { "A gateway session token is required." }
        synchronized(lock) {
            check(socket == null && !closed) { "Use a new gateway connection." }
            socket = factory.newWebSocket(Request.Builder().url(origin.newBuilder()
                .addPathSegments("api/ws").addQueryParameter("token", credential).build()).build(), listener)
        }
        try {
            val greeting = await(ready)
            if (greeting["session_authority"]?.jsonPrimitive?.booleanOrNull != true) throw GatewayFailure(GatewayFailure.Reason.UNSUPPORTED_PROTOCOL)
            val descriptor = rpc("runtime.describe")
            val caps = descriptor["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val create = descriptor["session_create"]?.jsonObject
            if (!caps.containsAll(listOf("durable-admission-v1", "event-replay-v1")) ||
                create?.get("sources")?.jsonArray?.none { it.jsonPrimitive.content == "gui" } != false ||
                create["parameters"]?.jsonArray?.map { it.jsonPrimitive.content }?.containsAll(listOf("request_id", "source")) != true) {
                throw GatewayFailure(GatewayFailure.Reason.UNSUPPORTED_PROTOCOL)
            }
            synchronized(lock) {
                createParameters = create["parameters"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                profile = descriptor.string("profile_id") ?: "default"
                negotiated = true
            }
            return sessions()
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw safe(e)
        }
    }

    override suspend fun sessions(): List<AgentSession> {
        checkConnected()
        val result = rpc("session.list", buildJsonObject {
            put("scope", "stored"); put("limit", 100); put("include_hidden", false)
        })
        return result["sessions"]?.jsonArray.orEmpty().take(100).map { row ->
            val item = row.jsonObject
            AgentSession(item.string("session_id") ?: item.string("id") ?: throw failure(), item.string("title") ?: "Untitled chat")
        }
    }

    override suspend fun history(sessionId: String): List<ChatMessage> {
        checkConnected()
        val snapshot = resume(sessionId)
        return snapshot["messages"]?.jsonArray.orEmpty().mapIndexed { index, row ->
            val message = row.jsonObject
            ChatMessage(message.string("id") ?: "$sessionId:$index", message.string("role") ?: "unknown", contentText(message["content"] ?: message["text"]))
        }
    }
    private suspend fun resume(sessionId: String): JsonObject = rpc("session.resume", buildJsonObject { put("session_id", sessionId) }).also {
        remember(it)
        synchronized(lock) { aliases[sessionId] = it.string("session_id") ?: throw failure() }
    }
    private fun remember(snapshot: JsonObject) {
        val id = snapshot.string("session_id") ?: throw failure()
        synchronized(lock) { snapshots[id] = snapshot }
    }
    private fun contentText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") { contentText(it) }
        is JsonObject -> if (content.string("type") in listOf("text", "input_text", "output_text")) contentText(content["text"]) else ""
        else -> ""
    }
    override suspend fun createSession(): AgentSession {
        checkConnected()
        val requestId = UUID.randomUUID().toString()
        val snapshot = rpc("session.create", buildJsonObject {
            put("request_id", requestId); put("source", "gui")
            if ("title" in createParameters) put("title", "New chat")
        })
        remember(snapshot)
        return AgentSession(snapshot.string("session_id") ?: throw failure(), snapshot["info"]?.jsonObject?.string("title") ?: "New chat")
    }
    override suspend fun send(sessionId: String, text: String, onUpdate: (GatewayUpdate) -> Unit): String {
        checkConnected()
        require(text.isNotBlank())
        val snapshot = resume(sessionId)
        if (hasPending(snapshot["prompts"]) || hasPending(snapshot["open_requests"])) throw GatewayFailure(GatewayFailure.Reason.INTERACTION_REQUIRED)
        if (snapshot["running"]?.jsonPrimitive?.booleanOrNull == true || hasPending(snapshot["pending"])) throw GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME)
        val canonicalId = snapshot.string("session_id") ?: throw failure()
        val turn = Turn(canonicalId, onUpdate, snapshot.string("replay_epoch"), snapshot["last_sequence"]?.jsonPrimitive?.longOrNull ?: 0L)
        synchronized(lock) {
            if (active != null) throw GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME)
            active = turn
        }
        try {
            val receipt = rpc("prompt.submit", buildJsonObject {
                put("session_id", canonicalId); put("text", text); put("input_id", UUID.randomUUID().toString())
            })
            synchronized(lock) {
                turn.admission = receipt.string("admission_id") ?: throw GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME)
                turn.generation = receipt["execution_generation"]?.jsonPrimitive?.longOrNull
                if (receipt.string("status") !in listOf("started", "queued", "terminal")) throw GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME)
                turn.acknowledged = true
                turn.buffered.toList().forEach { processEvent(it, turn) }
                turn.buffered.clear()
            }
            return try { withTimeout(turnTimeoutMs) { turn.result.await() } }
            catch (_: TimeoutCancellationException) { throw GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw if (e is GatewayFailure) e else GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME)
        } finally {
            synchronized(lock) { if (active === turn) active = null }
        }
    }

    private fun hasPending(value: JsonElement?): Boolean = when (value) {
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
        is JsonPrimitive -> value.booleanOrNull == true || (value.intOrNull ?: 0) > 0
        else -> false
    }

    private fun processEvent(event: JsonObject, turn: Turn) {
        if (turn.result.isCompleted || event.string("session_id") != turn.sessionId) return
        val admission = event.string("admission_id")
        if (admission != null && admission != turn.admission) return
        val generation = event["execution_generation"]?.jsonPrimitive?.longOrNull
        if (generation != null && turn.generation != null && generation != turn.generation) return
        val epoch = event.string("replay_epoch")
        if (turn.epoch != null && epoch != null && epoch != turn.epoch) {
            turn.result.completeExceptionally(GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME)); return
        }
        val sequence = event["seq"]?.jsonPrimitive?.longOrNull
        if (sequence != null) { if (sequence <= turn.sequence) return; turn.sequence = sequence }
        val payload = event["payload"] as? JsonObject ?: buildJsonObject {}
        when (event.string("type")) {
            "message.delta" -> {
                val text = payload.string("text") ?: return
                val segment = payload["segment"]?.jsonPrimitive?.intOrNull
                if (segment != null && segment != turn.segment) { turn.text.clear(); turn.segment = segment }
                val offset = payload["offset"]?.jsonPrimitive?.intOrNull
                if (offset != null) {
                    if (offset !in 0..turn.text.length) { turn.result.completeExceptionally(GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME)); return }
                    turn.text.setLength(offset)
                }
                turn.text.append(text)
                turn.onUpdate(GatewayUpdate.Text(turn.text.toString()))
            }
            "message.complete" -> {
                val status = payload.string("status") ?: "complete"
                if (status != "complete" || payload["error"]?.let { it != JsonNull && it.toString() != "\"\"" } == true) {
                    turn.result.completeExceptionally(GatewayFailure(if (status == "interrupted") GatewayFailure.Reason.TURN_INTERRUPTED else GatewayFailure.Reason.TURN_FAILED))
                } else {
                    val text = payload.string("text") ?: turn.text.toString()
                    turn.onUpdate(GatewayUpdate.Text(text)); turn.result.complete(text)
                }
            }
            "tool.start", "tool.progress", "tool.complete" -> {
                val type = event.string("type")
                turn.onUpdate(GatewayUpdate.Tool(ToolActivity(payload.string("tool_id") ?: payload.string("name") ?: "tool",
                    payload.string("name") ?: "Tool", payload.string("result_text") ?: payload.string("summary") ?: payload.string("message") ?: payload["args"]?.toString() ?: "",
                    if (type == "tool.complete") "completed" else "running")))
            }
            "approval.request", "clarify.request" -> {
                turn.onUpdate(GatewayUpdate.Tool(ToolActivity(payload.string("prompt_id") ?: "interaction", "Desktop interaction required", "Review this run on your desktop, then reopen the session.", "waiting")))
                turn.result.completeExceptionally(GatewayFailure(GatewayFailure.Reason.INTERACTION_REQUIRED))
            }
            "error" -> turn.result.completeExceptionally(GatewayFailure(GatewayFailure.Reason.TURN_FAILED))
            "session.replay_gap" -> turn.result.completeExceptionally(GatewayFailure(GatewayFailure.Reason.UNKNOWN_OUTCOME))
        }
    }

    private fun checkConnected() = synchronized(lock) { if (!negotiated || closed) throw failure("Gateway is disconnected.") }
    private suspend fun rpc(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonObject>()
        synchronized(lock) {
            if (closed) throw failure("Gateway is disconnected.")
            requests[id] = result
            if (socket?.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()) != true) {
                requests.remove(id)
                throw failure("Gateway is disconnected.")
            }
        }
        return try { await(result) } finally { synchronized(lock) { requests.remove(id) } }
    }
    private suspend fun <T> await(result: CompletableDeferred<T>): T = try {
        withTimeout(rpcTimeoutMs) { result.await() }
    } catch (_: TimeoutCancellationException) { throw failure("Gateway request timed out. Its remote outcome may be unknown.") }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                for (line in text.lineSequence().filter { it.isNotBlank() }) {
                    val frame = Json.parseToJsonElement(line).jsonObject
                    if (frame.string("method") == "event") {
                        val event = frame["params"]!!.jsonObject
                        if (event.string("type") == "gateway.ready") ready.complete(event["payload"]!!.jsonObject)
                        else synchronized(lock) { active?.let { if (it.acknowledged) processEvent(event, it) else if (event.string("session_id") == it.sessionId) { if (it.buffered.size >= 4096) throw failure(); it.buffered.add(event) } } }
                    } else if (frame["method"] != null && frame["id"] != null) {
                        webSocket.send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", frame.getValue("id"))
                            putJsonObject("error") { put("code", -32601); put("message", "Interactive requests are unsupported on this client.") }
                        }.toString())
                        synchronized(lock) { active?.result?.completeExceptionally(GatewayFailure(GatewayFailure.Reason.INTERACTION_REQUIRED)) }
                    } else {
                        val pending = synchronized(lock) { requests.remove(frame.string("id")) }
                        if (frame["error"] != null) pending?.completeExceptionally(failure("Gateway request was rejected."))
                        else pending?.complete(frame["result"]?.jsonObject ?: buildJsonObject {})
                    }
                }
            } catch (_: Exception) { shutdown("Gateway sent an unsupported response.") }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            shutdown("Gateway connection failed.", if (response?.code in listOf(401, 403)) GatewayFailure.Reason.AUTHENTICATION else GatewayFailure.Reason.CONNECTION)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { shutdown("Gateway disconnected.") }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = shutdown("Gateway disconnected.")
    }

    override fun close() = shutdown("Gateway disconnected.")
    private fun shutdown(message: String, reason: GatewayFailure.Reason = GatewayFailure.Reason.CONNECTION) = synchronized(lock) {
        closed = true; negotiated = false
        ready.completeExceptionally(GatewayFailure(reason))
        requests.values.forEach { it.completeExceptionally(GatewayFailure(reason)) }; requests.clear()
        active?.result?.completeExceptionally(GatewayFailure(reason)); active = null
        snapshots.clear(); aliases.clear(); createParameters = emptySet()
        socket?.cancel(); socket = null
    }
    private fun safe(error: Exception): Exception = if (error is GatewayFailure) error else failure()
    private fun failure(@Suppress("UNUSED_PARAMETER") message: String = "Gateway operation failed.") = GatewayFailure(GatewayFailure.Reason.CONNECTION)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull


}
