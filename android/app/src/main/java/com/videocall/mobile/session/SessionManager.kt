package com.videocall.mobile.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.videocall.mobile.net.Api
import com.videocall.mobile.net.Prefs
import com.videocall.mobile.net.User
import com.videocall.mobile.net.WsClient
import com.videocall.mobile.net.long
import com.videocall.mobile.net.obj
import com.videocall.mobile.net.str

/**
 * App-wide singleton: the logged-in user, the REST client, and the realtime
 * WebSocket. Everything else (chat repo, call repo) reads from this instead
 * of each owning its own connection.
 */
object SessionManager {
    private lateinit var appContext: Context
    lateinit var api: Api
        private set
    lateinit var ws: WsClient
        private set

    private val _me = MutableStateFlow<User?>(null)
    val me: StateFlow<User?> = _me

    // user_id -> status ("online"/"away"/"dnd"/"offline")
    private val _presence = MutableStateFlow<Map<Long, String>>(emptyMap())
    val presence: StateFlow<Map<Long, String>> = _presence

    // The user's own last explicitly-chosen status (e.g. "online"/"dnd"), kept
    // separately from _presence so a WS reconnect can re-assert it instead of
    // always forcing "online" and silently clearing a user-set DND.
    private val _myStatus = MutableStateFlow("online")
    val myStatus: StateFlow<String> = _myStatus

    // Why the server signed us out, shown once on the sign-in screen.
    private var logoutReason: String? = null
    fun consumeLogoutReason(): String? = logoutReason.also { logoutReason = null }

    private val logoutMessages = mapOf(
        "signed_in_elsewhere" to "You were signed out because your account was signed in on another device.",
        "suspended" to "Your account has been suspended by an administrator.",
        "deleted" to "Your account has been removed by an administrator.",
        "password_changed" to "Your password was changed. Please sign in with the new password.",
        "signed_out" to "An administrator signed you out. Please sign in again.",
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        val server = Prefs.serverUrl(appContext) ?: return
        bind(server)
    }

    fun bind(serverUrl: String) {
        api = Api(appContext, serverUrl)
        ws = WsClient(api, Prefs.deviceId(appContext))
        _myStatus.value = Prefs.myStatus(appContext)
        api.onUnauthorized = { logoutLocal() }
        // Every bind() (e.g. switching servers mid-session) creates a fresh
        // WsClient, so handlers must be re-attached to it each time, else
        // incoming events keep being delivered to the orphaned old socket.
        registerWsHandlers()
        com.videocall.mobile.call.CallRepository.registerOnce(appContext)
        com.videocall.mobile.chat.ChatRepository.init(appContext)
        com.videocall.mobile.chat.ChatRepository.registerOnce()
    }

    val hasServer: Boolean get() = ::api.isInitialized

    suspend fun tryResume(): Boolean {
        if (!hasServer) return false
        return runCatching {
            val user = api.me()
            onSignedIn(user)
            true
        }.getOrDefault(false)
    }

    suspend fun login(username: String, password: String): Result<User> = runCatching {
        val user = api.login(username, password)
        onSignedIn(user)
        user
    }

    /** Shared by password login, OIDC login and a resumed session. */
    fun onSignedIn(user: User) {
        _me.value = user
        user.preferences?.let { com.videocall.mobile.ui.theme.ThemeState.applyServer(appContext, it) }
        ws.connect()
        ConnectionService.start(appContext)
        com.videocall.mobile.chat.ChatRepository.onSignedIn()
    }

    fun logoutLocal() {
        if (_me.value == null && !ws.connected) return
        _me.value = null
        ws.disconnect()
        if (::appContext.isInitialized) ConnectionService.stop(appContext)
        com.videocall.mobile.call.CallRepository.hangup()
        com.videocall.mobile.chat.ChatRepository.clear()
        com.videocall.mobile.chat.Crypto.clearOnLogout()
        _presence.value = emptyMap()
    }

    suspend fun logout() {
        runCatching { api.logout() }
        logoutLocal()
    }

    fun setMe(user: User) {
        // PATCH /api/users/me also returns preferences; keep the previous ones if absent.
        _me.value = if (user.preferences == null) user.copy(preferences = _me.value?.preferences) else user
    }

    fun setMyStatus(status: String) {
        _myStatus.value = status
        if (::appContext.isInitialized) Prefs.setMyStatus(appContext, status)
        ws.send("presence:update", mapOf("status" to status))
    }

    private fun registerWsHandlers() {
        ws.on("presence:sync") { data ->
            // Server sends {"users": [{"user_id":1,"status":"online"}, ...]}, not
            // a bare id->status map (see web/src/App.tsx's presence:sync handler).
            val users = data.obj()["users"] as? kotlinx.serialization.json.JsonArray ?: return@on
            val map = mutableMapOf<Long, String>()
            for (entry in users) {
                val o = entry as? kotlinx.serialization.json.JsonObject ?: continue
                val id = o.long("user_id") ?: continue
                val status = o.str("status") ?: continue
                map[id] = status
            }
            _presence.value = map
        }
        ws.on("presence:update") { data ->
            val id = data.long("user_id") ?: return@on
            val status = data.str("status") ?: return@on
            _presence.value = _presence.value.toMutableMap().apply { put(id, status) }
        }
        ws.on("force:logout") { data ->
            logoutReason = logoutMessages[data.str("reason")] ?: "You were signed out. Please sign in again."
            logoutLocal()
        }
        // An admin changed this account (for example its role): reload it.
        ws.on("account:updated") {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { api.me() }.onSuccess { setMe(it) }
            }
        }
        // Re-assert our last chosen status on (re)connect — must NOT hardcode
        // "online" here, or a user-set DND silently gets cleared on every
        // automatic reconnect (network blip, app foreground/background).
        ws.on("ws:open") { ws.send("presence:update", mapOf("status" to _myStatus.value)) }
    }
}
