package com.videocall.mobile.ui.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Every WebRTC capture call (LocalMedia.start/startVideo, in DirectCall and
 * RoomClient) throws/crashes natively if invoked before RECORD_AUDIO (and
 * CAMERA, for video calls) is actually granted. None of the call-start call
 * sites used to check this, so calling only "worked" if permission happened
 * to already be granted from a prior grant — otherwise it crashed or the
 * call silently never connected. Every call entry point must go through
 * this helper instead of invoking CallRepository directly.
 *
 * Returns a function you call with `video = true/false`; it requests
 * whatever's missing and only invokes [onStart] once granted. If the user
 * denies, [onDenied] fires instead (default: no-op — caller may show a toast).
 */
@Composable
fun rememberCallLauncher(onDenied: () -> Unit = {}, onStart: (video: Boolean) -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    var pendingVideo by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val micOk = results[Manifest.permission.RECORD_AUDIO] == true
        val camOk = !pendingVideo || results[Manifest.permission.CAMERA] == true
        if (micOk && camOk) onStart(pendingVideo) else onDenied()
    }
    return { video ->
        pendingVideo = video
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) onStart(video) else launcher.launch(needed.toTypedArray())
    }
}

/**
 * This app has no push service (LAN/VPN intranet only, deliberately no
 * FCM), so a backgrounded process staying alive is the only way it can
 * still receive an incoming call — see ConnectionService. Doze/App Standby
 * battery optimization is exactly what stops that on stock Android and
 * every OEM's own battery manager, so the app needs this exemption.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Android 14+ only lets an app show a full-screen call screen over the lock
 * screen with the "full-screen notifications" permission, which the user can
 * turn off. Without it, incoming calls only show as a notification.
 */
fun canUseFullScreenIntent(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return true
    val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return true
    return nm.canUseFullScreenIntent()
}

fun requestFullScreenIntent(context: Context) {
    if (Build.VERSION.SDK_INT < 34) return
    val intent = Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
}

fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
}
