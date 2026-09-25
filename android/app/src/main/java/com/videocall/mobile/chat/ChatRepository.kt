package com.videocall.mobile.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.videocall.mobile.net.Group
import com.videocall.mobile.net.Message
import com.videocall.mobile.net.json
import com.videocall.mobile.net.long
import com.videocall.mobile.net.obj
import com.videocall.mobile.net.str
import com.videocall.mobile.session.SessionManager

sealed class Convo {
    data class Dm(val peerId: Long) : Convo()
    data class GroupChat(val groupId: Long) : Convo()
    val key: String get() = when (this) { is Dm -> "dm:$peerId"; is GroupChat -> "g:$groupId" }
}

/**
 * Mirrors web/src/store/chats.ts: realtime messages, unread counts, typing,
 * edit/delete/react/pin, per-conversation E2E encryption toggle (via
 * chat/Crypto.kt), search/pinned/saved lists.
 */
object ChatRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var appContext: Context

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups

    private val _typing = MutableStateFlow<Map<String, String>>(emptyMap()) // convoKey -> "name is typing"

    private val _encryptedConvos = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val encryptedConvos: StateFlow<Map<String, Boolean>> = _encryptedConvos

    private val _savedIds = MutableStateFlow<Set<Long>>(emptySet())
    val savedIds: StateFlow<Set<Long>> = _savedIds

    // convoKey -> whether older history exists beyond what's loaded
    private val _hasMore = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hasMore: StateFlow<Map<String, Boolean>> = _hasMore
    private val _loadingOlder = MutableStateFlow<Set<String>>(emptySet())
    val loadingOlder: StateFlow<Set<String>> = _loadingOlder

    // Last send failure worth telling the user about (shown once, then cleared).
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError
    fun clearSendError() { _sendError.value = null }

    // clientId -> (convo, optimistic id, timeout job) for sends awaiting message:sent.
    private val pendingAcks = mutableMapOf<String, Triple<Convo, Long, kotlinx.coroutines.Job>>()

    // Monotonic source for optimistic message ids — System.currentTimeMillis()
    // collided when two messages were sent within the same millisecond,
    // silently overwriting the first optimistic bubble until its ack arrived.
    private val optimisticIdSeq = AtomicLong(0)

    private var activeConvo: Convo? = null

    private fun e2ePrefs(context: Context) = context.getSharedPreferences("vc_e2e_flags", Context.MODE_PRIVATE)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Called once a session exists (login or resumed session). */
    fun onSignedIn() {
        scope.launch {
            if (::appContext.isInitialized) Crypto.ensureDeviceRegistered(appContext)
        }
        scope.launch { loadGroups(); fetchRecent(); fetchSaved() }
    }

    /** Drops everything the previous account could see (shared devices). */
    fun clear() {
        pendingAcks.values.forEach { it.third.cancel() }
        pendingAcks.clear()
        _messages.value = emptyMap()
        _unread.value = emptyMap()
        _groups.value = emptyList()
        _typing.value = emptyMap()
        _encryptedConvos.value = emptyMap()
        _savedIds.value = emptySet()
        _hasMore.value = emptyMap()
        activeConvo = null
    }

    fun isEncrypted(convo: Convo): Boolean = _encryptedConvos.value[convo.key] ?: false

    fun setEncrypted(convo: Convo, on: Boolean) {
        _encryptedConvos.value = _encryptedConvos.value.toMutableMap().apply { put(convo.key, on) }
        if (::appContext.isInitialized) e2ePrefs(appContext).edit().putBoolean(convo.key, on).apply()
    }

    private fun loadEncryptedFlag(convo: Convo) {
        if (_encryptedConvos.value.containsKey(convo.key)) return
        if (!::appContext.isInitialized) return
        val stored = e2ePrefs(appContext).contains(convo.key)
        val value = if (stored) e2ePrefs(appContext).getBoolean(convo.key, false) else false
        _encryptedConvos.value = _encryptedConvos.value.toMutableMap().apply { put(convo.key, value) }
    }

    fun setActive(convo: Convo?) {
        activeConvo = convo
        if (convo != null) {
            markRead(convo)
            if (::appContext.isInitialized) {
                runCatching { androidx.core.app.NotificationManagerCompat.from(appContext).cancel(convo.key.hashCode()) }
            }
        }
    }

    // Re-attaches handlers to the current SessionManager.ws; called on every
    // SessionManager.bind() (including server switches), since each bind()
    // creates a brand-new WsClient that needs its own listeners.
    fun registerOnce() {
        val ws = SessionManager.ws

        // Anything sent while the socket was down never reached us (group
        // messages in particular are not re-delivered), so resync on reconnect.
        ws.on("ws:open") { _ ->
            if (SessionManager.me.value == null) return@on
            scope.launch {
                loadGroups()
                fetchRecent()
                activeConvo?.let { loadHistory(it) }
            }
        }
        // A rejected send (not a group member, too long, rate-limited...) comes
        // back as an "error" carrying our client_id; fail that bubble now
        // instead of leaving it "Sending..." forever.
        ws.on("error") { d ->
            val clientId = d.str("client_id") ?: return@on
            val pending = pendingAcks.remove(clientId) ?: return@on
            pending.third.cancel()
            markFailed(pending.first, pending.second)
            _sendError.value = d.str("message") ?: "Message not sent"
        }

        ws.on("message:new") { d ->
            val msg = decodeMessage(d.obj()["message"] ?: return@on) ?: return@on
            val me = SessionManager.me.value ?: return@on
            val convo: Convo = when {
                msg.recipient_id == me.id -> Convo.Dm(msg.sender_id)
                msg.group_id != null -> Convo.GroupChat(msg.group_id)
                else -> return@on
            }
            appendMessage(convo, msg)
            decryptPending(convo, listOf(msg))
            if (activeConvo != convo) {
                _unread.value = _unread.value.toMutableMap().apply { put(convo.key, (get(convo.key) ?: 0) + 1) }
                if (::appContext.isInitialized) ChatNotifier.notifyNewMessage(appContext, convo, msg, me, _groups.value)
            } else {
                markRead(convo)
            }
        }
        ws.on("message:sent") { d ->
            val msg = decodeMessage(d.obj()["message"] ?: return@on) ?: return@on
            val convo: Convo = if (msg.group_id != null) Convo.GroupChat(msg.group_id) else Convo.Dm(msg.recipient_id ?: return@on)
            d.str("client_id")?.let { pendingAcks.remove(it)?.third?.cancel() }
            replaceOptimistic(convo, d.str("client_id"), msg)
            decryptPending(convo, listOf(msg))
        }
        ws.on("message:unread") { d ->
            val counts = d.obj()["counts"]?.obj() ?: JsonObject(emptyMap())
            val groupCounts = d.obj()["group_counts"]?.obj() ?: JsonObject(emptyMap())
            val map = mutableMapOf<String, Int>()
            for ((k, v) in counts) (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { map["dm:$k"] = it }
            for ((k, v) in groupCounts) (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { map["g:$k"] = it }
            _unread.value = map
        }
        ws.on("message:typing") { d ->
            val name = d.str("from_name") ?: "Someone"
            val key = d.long("group_id")?.let { "g:$it" } ?: d.long("from")?.let { "dm:$it" } ?: return@on
            _typing.value = _typing.value.toMutableMap().apply { put(key, name) }
            scope.launch {
                kotlinx.coroutines.delay(3000)
                _typing.value = _typing.value.toMutableMap().apply { remove(key) }
            }
        }
        ws.on("message:read") { d ->
            val from = d.long("from") ?: return@on
            val me = SessionManager.me.value ?: return@on
            val key = "dm:$from"
            val list = (_messages.value[key] ?: emptyList()).map {
                if (it.sender_id == me.id && it.recipient_id == from && it.read_at == null) it.copy(read_at = "now") else it
            }
            _messages.value = _messages.value.toMutableMap().apply { put(key, list) }
        }
        ws.on("message:deleted") { d ->
            val id = d.long("id") ?: return@on
            mutateEverywhere(id) { it.copy(deleted_at = "now", content = "") }
        }
        ws.on("message:edited") { d ->
            val msg = decodeMessage(d.obj()["message"] ?: return@on) ?: return@on
            mutateEverywhere(msg.id) { msg }
        }
        ws.on("message:pinned") { d ->
            val id = d.long("id") ?: return@on
            val pinnedAt = d.str("pinned_at")
            mutateEverywhere(id) { it.copy(pinned_at = pinnedAt) }
        }
        ws.on("message:reaction") { d ->
            val id = d.long("id") ?: return@on
            val userId = d.long("user_id") ?: return@on
            val emoji = d.str("emoji") ?: return@on
            val added = d.bool2("added")
            mutateEverywhere(id) { m ->
                val without = (m.reactions ?: emptyList()).filterNot { it.user_id == userId && it.emoji == emoji }
                m.copy(reactions = if (added) without + com.videocall.mobile.net.MessageReaction(id, userId, emoji) else without)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonElement.bool2(key: String): Boolean =
        (this as? JsonObject)?.get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: false

    private fun decodeMessage(el: kotlinx.serialization.json.JsonElement): Message? =
        runCatching { json.decodeFromJsonElement<Message>(el) }.getOrNull()

    private fun appendMessage(convo: Convo, msg: Message) {
        val list = (_messages.value[convo.key] ?: emptyList()).filter { it.id != msg.id } + msg
        _messages.value = _messages.value.toMutableMap().apply { put(convo.key, list.sortedWith(::messageOrder)) }
    }

    private fun replaceOptimistic(convo: Convo, clientId: String?, msg: Message) {
        val list = (_messages.value[convo.key] ?: emptyList()).filter { it.clientId != clientId && it.id != msg.id } + msg
        _messages.value = _messages.value.toMutableMap().apply { put(convo.key, list.sortedWith(::messageOrder)) }
    }

    // Mirrors web's messageOrder: chronological by sent_at, falling back to
    // id when timestamps tie or are missing (e.g. a not-yet-acked optimistic
    // message) — sorting raw negative optimistic ids ascending would shove
    // an in-flight message to the top of the list instead of the bottom.
    private fun messageOrder(a: Message, b: Message): Int {
        val ta = runCatching { java.time.Instant.parse(a.sent_at) }.getOrNull()
        val tb = runCatching { java.time.Instant.parse(b.sent_at) }.getOrNull()
        if (ta != null && tb != null) {
            val cmp = ta.compareTo(tb)
            if (cmp != 0) return cmp
        }
        return a.id.compareTo(b.id)
    }

    private fun mutateEverywhere(id: Long, fn: (Message) -> Message) {
        _messages.value = _messages.value.mapValues { (_, list) -> list.map { if (it.id == id) fn(it) else it } }
    }

    fun typingIn(convo: Convo): StateFlow<Map<String, String>> = _typing

    private suspend fun fetchPage(convo: Convo, before: Long?): List<Message>? {
        val api = SessionManager.api
        return runCatching {
            when (convo) {
                is Convo.Dm -> api.directMessages(convo.peerId, before)
                is Convo.GroupChat -> api.groupMessages(convo.groupId, before)
            }
        }.getOrNull()
    }

    /** Merges fetched messages into a conversation, keeping in-flight/failed optimistic bubbles. */
    private fun merge(convo: Convo, fetched: List<Message>) {
        val byId = LinkedHashMap<Long, Message>()
        (_messages.value[convo.key] ?: emptyList()).forEach { byId[it.id] = it }
        fetched.forEach { m -> byId[m.id] = m.copy(decryptedContent = byId[m.id]?.decryptedContent) }
        _messages.value = _messages.value.toMutableMap().apply { put(convo.key, byId.values.sortedWith(::messageOrder)) }
    }

    suspend fun loadHistory(convo: Convo) {
        loadEncryptedFlag(convo)
        val list = fetchPage(convo, null) ?: return
        merge(convo, list)
        _hasMore.value = _hasMore.value + (convo.key to (list.size >= 50))
        // default the toggle from whether the most recent message was encrypted, same as web
        if (!e2ePrefsHas(convo)) list.lastOrNull()?.let { setEncrypted(convo, it.is_encrypted) }
        decryptPending(convo, list)
    }

    /** Pages in the 50 messages before the oldest one loaded. */
    suspend fun loadOlder(convo: Convo) {
        if (_hasMore.value[convo.key] != true || convo.key in _loadingOlder.value) return
        val oldest = (_messages.value[convo.key] ?: emptyList()).firstOrNull { it.id > 0 }?.id ?: return
        _loadingOlder.value = _loadingOlder.value + convo.key
        val list = fetchPage(convo, oldest)
        _loadingOlder.value = _loadingOlder.value - convo.key
        if (list == null) return
        merge(convo, list)
        _hasMore.value = _hasMore.value + (convo.key to (list.size >= 50))
        decryptPending(convo, list)
    }

    /** Seeds each conversation with its latest message for chat-list previews. */
    suspend fun fetchRecent() {
        val me = SessionManager.me.value ?: return
        val recent = runCatching { SessionManager.api.recentConversations() }.getOrNull() ?: return
        val all = (recent.dms ?: emptyList()) + (recent.groups ?: emptyList())
        for (m in all) {
            val convo = when {
                m.group_id != null -> Convo.GroupChat(m.group_id)
                m.sender_id == me.id -> Convo.Dm(m.recipient_id ?: continue)
                else -> Convo.Dm(m.sender_id)
            }
            if ((_messages.value[convo.key] ?: emptyList()).none { it.id == m.id }) merge(convo, listOf(m))
            decryptPending(convo, listOf(m))
        }
    }

    private fun e2ePrefsHas(convo: Convo): Boolean = ::appContext.isInitialized && e2ePrefs(appContext).contains(convo.key)

    private fun decryptPending(convo: Convo, msgs: List<Message>) {
        val encrypted = msgs.filter { it.is_encrypted && it.decryptedContent == null }
        if (encrypted.isEmpty() || !::appContext.isInitialized) return
        scope.launch {
            for (m in encrypted) {
                val plain = runCatching {
                    Crypto.decryptMessageContent(appContext, m.id, m.is_encrypted, m.content, m.enc_iv, m.enc_keys)
                }.getOrNull()
                mutateEverywhere(m.id) { it.copy(decryptedContent = plain ?: "🔒 Cannot decrypt (not sent to this device)") }
            }
        }
    }

    suspend fun loadGroups() {
        _groups.value = runCatching { SessionManager.api.groups() }.getOrDefault(emptyList())
    }

    fun sendMessage(convo: Convo, content: String, fileId: Long? = null, replyToId: Long? = null) {
        val text = content.trim()
        if (text.isEmpty() && fileId == null) return
        val me = SessionManager.me.value ?: return
        val clientId = "c-${UUID.randomUUID()}"
        val optimistic = Message(
            id = -optimisticIdSeq.incrementAndGet(),
            sender_id = me.id,
            recipient_id = (convo as? Convo.Dm)?.peerId,
            group_id = (convo as? Convo.GroupChat)?.groupId,
            file_id = fileId,
            content = text,
            sent_at = java.time.Instant.now().toString(),
            reply_to_id = replyToId,
            clientId = clientId,
            pending = true,
            decryptedContent = text,
        )
        appendMessage(convo, optimistic)
        // Same 10 s ack deadline as the web client: no message:sent by then means it failed.
        pendingAcks[clientId] = Triple(convo, optimistic.id, scope.launch {
            kotlinx.coroutines.delay(10_000)
            if (pendingAcks.remove(clientId) != null) {
                markFailed(convo, optimistic.id)
                _sendError.value = "Message not sent. Check your connection."
            }
        })

        if (text.isNotEmpty() && isEncrypted(convo) && ::appContext.isInitialized) {
            scope.launch {
                val recipientIds = when (convo) {
                    is Convo.Dm -> listOf(me.id, convo.peerId)
                    is Convo.GroupChat -> listOf(me.id) + (_groups.value.firstOrNull { it.id == convo.groupId }?.members?.map { it.id } ?: emptyList())
                }
                // Re-checked every send (like web): a recipient with no device
                // key would otherwise be silently left out of the message.
                val missing = Crypto.usersMissingKeys(recipientIds.filter { it != me.id }.distinct())
                if (missing.isNotEmpty()) {
                    pendingAcks.remove(clientId)?.third?.cancel()
                    markFailed(convo, optimistic.id)
                    _sendError.value = if (missing.size == 1) "Not sent: a recipient has not set up encryption on any device yet"
                        else "Not sent: ${missing.size} recipients have not set up encryption yet"
                    return@launch
                }
                val enc = runCatching { Crypto.encryptForRecipients(appContext, text, recipientIds) }.getOrNull()
                if (enc == null) {
                    pendingAcks.remove(clientId)?.third?.cancel()
                    markFailed(convo, optimistic.id)
                    _sendError.value = "Couldn't encrypt the message"
                    return@launch
                }
                val ok = SessionManager.ws.send("message:send", mapOf(
                    "client_id" to clientId,
                    "recipient_id" to (convo as? Convo.Dm)?.peerId,
                    "group_id" to (convo as? Convo.GroupChat)?.groupId,
                    "file_id" to fileId,
                    "reply_to_id" to replyToId,
                    "content" to enc.ciphertext,
                    "encrypted" to true,
                    "enc_iv" to enc.iv,
                    "enc_keys" to enc.encKeys,
                ))
                if (!ok) {
                    pendingAcks.remove(clientId)?.third?.cancel()
                    markFailed(convo, optimistic.id)
                }
            }
            return
        }

        val ok = SessionManager.ws.send("message:send", mapOf(
            "client_id" to clientId,
            "recipient_id" to (convo as? Convo.Dm)?.peerId,
            "group_id" to (convo as? Convo.GroupChat)?.groupId,
            "file_id" to fileId,
            "reply_to_id" to replyToId,
            "content" to text,
        ))
        if (!ok) {
            pendingAcks.remove(clientId)?.third?.cancel()
            markFailed(convo, optimistic.id)
        }
    }

    private fun markFailed(convo: Convo, optimisticId: Long) {
        val list = (_messages.value[convo.key] ?: emptyList()).map { if (it.id == optimisticId) it.copy(pending = false, failed = true) else it }
        _messages.value = _messages.value.toMutableMap().apply { put(convo.key, list) }
    }

    fun retryMessage(convo: Convo, msg: Message) {
        _messages.value = _messages.value.toMutableMap().apply {
            put(convo.key, (get(convo.key) ?: emptyList()).filter { it.id != msg.id })
        }
        sendMessage(convo, msg.decryptedContent ?: msg.content, msg.file_id, msg.reply_to_id)
    }

    // Each of these only applies its optimistic local mutation if the WS send
    // actually went out; otherwise (e.g. mid-reconnect) the local state would
    // silently diverge from the server's until the next full resync.
    fun deleteMessage(msg: Message) {
        if (!SessionManager.ws.send("message:delete", mapOf("id" to msg.id))) return
        mutateEverywhere(msg.id) { it.copy(deleted_at = "now", content = "") }
    }

    fun editMessage(msg: Message, content: String) {
        if (msg.is_encrypted) return // the server can't re-wrap keys; delete and resend instead
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed == (msg.decryptedContent ?: msg.content)) return
        if (!SessionManager.ws.send("message:edit", mapOf("id" to msg.id, "content" to trimmed))) return
        mutateEverywhere(msg.id) { it.copy(content = trimmed, decryptedContent = trimmed, edited_at = "now") }
    }

    fun reactToMessage(msg: Message, emoji: String) {
        val me = SessionManager.me.value ?: return
        if (!SessionManager.ws.send("message:react", mapOf("id" to msg.id, "emoji" to emoji))) return
        val already = (msg.reactions ?: emptyList()).any { it.user_id == me.id && it.emoji == emoji }
        mutateEverywhere(msg.id) { m ->
            m.copy(reactions = if (already) (m.reactions ?: emptyList()).filterNot { it.user_id == me.id && it.emoji == emoji }
            else (m.reactions ?: emptyList()) + com.videocall.mobile.net.MessageReaction(msg.id, me.id, emoji))
        }
    }

    fun pinMessage(msg: Message, pinned: Boolean) {
        if (!SessionManager.ws.send("message:pin", mapOf("id" to msg.id, "pinned" to pinned))) return
        mutateEverywhere(msg.id) { it.copy(pinned_at = if (pinned) "now" else null) }
    }

    suspend fun fetchSaved() {
        _savedIds.value = runCatching { SessionManager.api.savedMessages() }.getOrDefault(emptyList()).map { it.id }.toSet()
    }

    suspend fun toggleSave(msg: Message) {
        val already = _savedIds.value.contains(msg.id)
        runCatching {
            if (already) SessionManager.api.unsaveMessage(msg.id) else SessionManager.api.saveMessage(msg.id)
        }.onSuccess {
            _savedIds.value = if (already) _savedIds.value - msg.id else _savedIds.value + msg.id
        }
    }

    private var lastTypingSent = 0L
    fun sendTyping(convo: Convo) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 2500) return
        lastTypingSent = now
        SessionManager.ws.send("message:typing", mapOf(
            "recipient_id" to (convo as? Convo.Dm)?.peerId,
            "group_id" to (convo as? Convo.GroupChat)?.groupId,
        ))
    }

    fun markRead(convo: Convo) {
        _unread.value = _unread.value.toMutableMap().apply { remove(convo.key) }
        SessionManager.ws.send("message:read", mapOf(
            "peer_id" to (convo as? Convo.Dm)?.peerId,
            "group_id" to (convo as? Convo.GroupChat)?.groupId,
        ))
    }
}
