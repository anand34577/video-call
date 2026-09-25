package com.videocall.mobile.net

import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList

typealias WsHandler = (JsonElement) -> Unit

/**
 * Mirrors web/src/lib/ws.ts: one WebSocket for all realtime traffic
 * (presence, chat, call signaling), auto-reconnect with capped backoff,
 * typed handler registration by message "type".
 */
class WsClient(private val api: Api, private val deviceId: String) {
    private var ws: WebSocket? = null
    private val handlers = mutableMapOf<String, CopyOnWriteArrayList<WsHandler>>()
    private var backoffMs = 1000L
    private var wantConnected = false
    private val main = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    var replaced = false
        private set

    val connected: Boolean get() = ws != null

    fun connect() {
        if (ws != null) return
        wantConnected = true
        replaced = false
        val wsScheme = if (api.baseUrl.startsWith("https")) "wss" else "ws"
        val url = api.baseUrl.replaceFirst(Regex("^https?"), wsScheme) + "/ws?device=$deviceId"
        val req = Request.Builder().url(url).build()
        val socket = api.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000
                emit("ws:open", JsonNull)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
                val obj = el as? JsonObject ?: return
                val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
                val data = obj["data"] ?: JsonNull
                if (type == "ws:replaced") {
                    wantConnected = false
                    replaced = true
                }
                emit(type, data)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onGone()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onGone()
            }
        })
        ws = socket
    }

    private fun onGone() {
        ws = null
        emit("ws:close", JsonNull)
        if (wantConnected) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectRunnable != null) return
        val r = Runnable {
            reconnectRunnable = null
            connect()
        }
        reconnectRunnable = r
        main.postDelayed(r, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(15000)
    }

    fun disconnect() {
        wantConnected = false
        reconnectRunnable?.let { main.removeCallbacks(it) }
        reconnectRunnable = null
        ws?.close(1000, null)
        ws = null
    }

    fun send(type: String, data: Map<String, Any?> = emptyMap()): Boolean {
        val socket = ws ?: return false
        val obj = JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive(type), "data" to toJson(data)))
        return socket.send(obj.toString())
    }

    fun on(type: String, handler: WsHandler): () -> Unit {
        val list = handlers.getOrPut(type) { CopyOnWriteArrayList() }
        list.add(handler)
        return { list.remove(handler) }
    }

    private fun emit(type: String, data: JsonElement) {
        handlers[type]?.forEach { h ->
            main.post { runCatching { h(data) } }
        }
    }

    private fun toJson(map: Map<String, Any?>): JsonObject {
        val entries = map.mapValues { (_, v) -> anyToJsonEl(v) }
        return JsonObject(entries)
    }

    private fun anyToJsonEl(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> kotlinx.serialization.json.JsonPrimitive(v)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
        is Int -> kotlinx.serialization.json.JsonPrimitive(v)
        is Long -> kotlinx.serialization.json.JsonPrimitive(v)
        is Double -> kotlinx.serialization.json.JsonPrimitive(v)
        is List<*> -> kotlinx.serialization.json.JsonArray(v.map { anyToJsonEl(it) })
        is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to anyToJsonEl(value) })
        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
    }
}

// Convenience accessors used all over the call/chat code below.
fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonElement.str(key: String): String? = (obj()[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonElement.long(key: String): Long? = (obj()[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
fun JsonElement.bool(key: String): Boolean? = (obj()[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
