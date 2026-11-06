package dev.krts.lyricvibes.spotify

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.krts.lyricvibes.LyricVibes
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

typealias CdpEvent = (cdp: Cdp, method: String, params: JsonObject, session: String?) -> Unit

class Cdp private constructor(private val onEvent: CdpEvent, private val events: Set<String>) : WebSocket.Listener {
    private lateinit var ws: WebSocket
    private val ids = AtomicInteger()
    private val calls = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val buf = StringBuilder()
    @Volatile var open = true
        private set

    fun call(method: String, params: JsonObject = JsonObject(), session: String? = null): CompletableFuture<JsonObject> {
        val f = CompletableFuture<JsonObject>()
        if (!open) return f.also { it.completeExceptionally(IOException("closed")) }
        val id = ids.incrementAndGet()
        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
            session?.let { addProperty("sessionId", it) }
        }
        calls[id] = f
        f.whenComplete { _, _ -> calls.remove(id) }
        runCatching { synchronized(this) { ws.sendText(msg.toString(), true).get(3, TimeUnit.SECONDS) } }
            .onFailure { calls.remove(id); f.completeExceptionally(it); close() }
        return f.orTimeout(5, TimeUnit.SECONDS)
    }

    fun close() {
        if (!open) return
        open = false
        runCatching { ws.abort() }
        calls.values.forEach { it.completeExceptionally(IOException("closed")) }
        calls.clear()
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        buf.append(data)
        if (last) {
            val text = buf.toString()
            buf.setLength(0)
            if (wanted(text)) runCatching { dispatch(JsonParser.parseString(text).asJsonObject) }
                .onFailure { LyricVibes.LOGGER.debug("CDP message failed", it) }
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
        close()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        close()
    }

    // skip unwanted events cheaply
    private fun wanted(text: String): Boolean {
        if (!text.startsWith(EVENT_PREFIX)) return true
        return events.any { text.startsWith(it, EVENT_PREFIX.length) && text.startsWith("\"", EVENT_PREFIX.length + it.length) }
    }

    private fun dispatch(o: JsonObject) {
        val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asInt
        if (id != null) {
            val f = calls.remove(id) ?: return
            val err = o.get("error")
            if (err != null && !err.isJsonNull) f.completeExceptionally(IOException(err.toString()))
            else f.complete(o.get("result")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject())
            return
        }
        val method = o.get("method")?.asString ?: return
        val params = o.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        onEvent(this, method, params, o.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString)
    }

    companion object {
        private const val EVENT_PREFIX = "{\"method\":\""
        private val http = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .build()

        fun targets(port: Int): JsonArray? = runCatching {
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/list"))
                .timeout(Duration.ofSeconds(3)).GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) null else JsonParser.parseString(res.body()).takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull()

        fun connect(url: String, events: Set<String>, onEvent: CdpEvent): Cdp {
            val c = Cdp(onEvent, events)
            c.ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create(url), c)
                .get(5, TimeUnit.SECONDS)
            return c
        }
    }
}
