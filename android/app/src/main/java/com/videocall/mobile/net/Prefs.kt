package com.videocall.mobile.net

import android.content.Context
import java.util.UUID

/**
 * Plain SharedPreferences — no DataStore. Two small values (server URL, a
 * random device id) don't need a reactive store; screens re-read on demand.
 */
object Prefs {
    private const val FILE = "vc_prefs"
    private const val KEY_SERVER = "server_url"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_STATUS = "my_status"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(ctx: Context): String? = prefs(ctx).getString(KEY_SERVER, null)

    /** Normalizes to a scheme + host[:port] with no trailing slash, e.g. https://192.168.1.50:8443 */
    fun setServerUrl(ctx: Context, raw: String) {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        prefs(ctx).edit().putString(KEY_SERVER, url).apply()
    }

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        var id = p.getString(KEY_DEVICE, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            p.edit().putString(KEY_DEVICE, id).apply()
        }
        return id
    }

    fun clearServer(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SERVER).apply()
    }

    /** One of "system" (default), "light", "dark". */
    fun themeMode(ctx: Context): String = prefs(ctx).getString(KEY_THEME, "system") ?: "system"

    fun setThemeMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_THEME, mode).apply()
    }

    /** User's last explicitly-chosen presence status, e.g. "online"/"dnd". Survives WS reconnects and app restarts. */
    fun myStatus(ctx: Context): String = prefs(ctx).getString(KEY_STATUS, "online") ?: "online"

    fun setMyStatus(ctx: Context, status: String) {
        prefs(ctx).edit().putString(KEY_STATUS, status).apply()
    }

}

