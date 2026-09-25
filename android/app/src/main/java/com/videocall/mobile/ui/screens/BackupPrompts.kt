package com.videocall.mobile.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.videocall.mobile.chat.ChatRepository
import com.videocall.mobile.chat.Crypto
import com.videocall.mobile.session.SessionManager
import com.videocall.mobile.ui.components.SettingsRow
import com.videocall.mobile.ui.util.canUseFullScreenIntent
import com.videocall.mobile.ui.util.isIgnoringBatteryOptimizations
import com.videocall.mobile.ui.util.requestFullScreenIntent
import com.videocall.mobile.ui.util.requestIgnoreBatteryOptimizations
import kotlinx.coroutines.launch

private fun promptPrefs(context: Context) = context.getSharedPreferences("vc_prompts", Context.MODE_PRIVATE)

private enum class BackupMode { CREATE, RESTORE, CHANGE, DELETE }

/** Password entry for the backup dialogs; [confirm] asks for it twice. */
@Composable
private fun BackupPasswordDialog(
    title: String,
    message: String,
    confirm: Boolean,
    actionLabel: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val valid = if (confirm) pw.length >= 8 && pw == pw2 else pw.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pw, { pw = it }, label = { Text("Backup password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    supportingText = { if (confirm && pw.isNotEmpty() && pw.length < 8) Text("At least 8 characters") },
                )
                if (confirm) {
                    OutlinedTextField(
                        pw2, { pw2 = it }, label = { Text("Type it again") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        supportingText = { if (pw2.isNotEmpty() && pw != pw2) Text("The passwords don't match") },
                    )
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = valid && !busy, onClick = { onSubmit(pw) }) { Text(actionLabel) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Settings row for the chat backup, with its create/restore/change/delete dialogs. */
@Composable
fun KeyBackupSettings(showMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exists by remember { mutableStateOf<Boolean?>(null) }
    var onDevice by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<BackupMode?>(null) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        exists = runCatching { SessionManager.api.keyBackup().exists }.getOrNull() ?: false
        onDevice = Crypto.hasBackupKey(context)
    }
    LaunchedEffect(Unit) { refresh() }

    fun run(done: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            runCatching { block() }
                .onSuccess { mode = null; showMessage(done); refresh() }
                .onFailure { error = it.message ?: "Something went wrong" }
            busy = false
        }
    }

    val subtitle = when (exists) {
        null -> "Checking…"
        false -> "Off. Turn it on to read encrypted chats on a new phone"
        true -> if (onDevice) "On. This phone can read backed-up messages" else "On. Restore it to read older messages here"
    }
    Box {
        SettingsRow(Icons.Default.CloudUpload, "Chat backup", subtitle, onClick = {
            error = null
            when (exists) {
                false -> mode = BackupMode.CREATE
                true -> menu = true
                null -> {}
            }
        })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!onDevice) DropdownMenuItem(text = { Text("Restore on this phone") }, leadingIcon = { Icon(Icons.Default.Key, null) }, onClick = { menu = false; mode = BackupMode.RESTORE })
            if (onDevice) DropdownMenuItem(text = { Text("Change password") }, leadingIcon = { Icon(Icons.Default.Password, null) }, onClick = { menu = false; mode = BackupMode.CHANGE })
            DropdownMenuItem(text = { Text("Turn off") }, leadingIcon = { Icon(Icons.Default.CloudOff, null) }, onClick = { menu = false; mode = BackupMode.DELETE })
        }
    }

    when (mode) {
        BackupMode.CREATE -> BackupPasswordDialog(
            "Turn on chat backup",
            "Choose a password for your backup. You'll need it on any new phone or browser. Nobody, not even your administrator, can recover it if you forget it. It covers messages sent from now on.",
            confirm = true, actionLabel = "Turn on", busy = busy, error = error,
            onDismiss = { mode = null },
            onSubmit = { pw -> run("Chat backup is on") { Crypto.createKeyBackup(context, pw) } },
        )
        BackupMode.RESTORE -> BackupPasswordDialog(
            "Restore chat backup", "Enter your backup password.",
            confirm = false, actionLabel = "Restore", busy = busy, error = error,
            onDismiss = { mode = null },
            onSubmit = { pw -> run("Backup restored") { Crypto.restoreKeyBackup(context, pw); ChatRepository.redecryptAll() } },
        )
        BackupMode.CHANGE -> BackupPasswordDialog(
            "Change backup password", "Choose a new password for your backup.",
            confirm = true, actionLabel = "Change", busy = busy, error = error,
            onDismiss = { mode = null },
            onSubmit = { pw -> run("Backup password changed") { Crypto.changeKeyBackupPassword(context, pw) } },
        )
        BackupMode.DELETE -> AlertDialog(
            onDismissRequest = { if (!busy) mode = null },
            title = { Text("Turn off chat backup?") },
            text = { Text("The backup is deleted from the server. Phones and browsers that already restored it keep reading old messages, but new ones won't be able to.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { run("Chat backup is off") { Crypto.deleteKeyBackup(context) } }) { Text("Turn off") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { mode = null }) { Text("Cancel") } },
        )
        null -> {}
    }
}

/**
 * After signing in on a phone that doesn't have the backup yet: offer to
 * restore it, once per account.
 */
@Composable
fun KeyBackupRestorePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by SessionManager.me.collectAsState()
    val userId = me?.id ?: return
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val skipKey = "backup_prompt_skipped:$userId"

    LaunchedEffect(userId) {
        if (promptPrefs(context).getBoolean(skipKey, false) || Crypto.hasBackupKey(context)) return@LaunchedEffect
        show = runCatching { SessionManager.api.keyBackup().exists }.getOrDefault(false)
    }
    if (!show) return
    val skip = {
        promptPrefs(context).edit().putBoolean(skipKey, true).apply()
        show = false
    }
    BackupPasswordDialog(
        "Restore your chat backup?",
        "You have a chat backup on this server. Enter your backup password to read your encrypted messages on this phone. You can also do this later in Settings.",
        confirm = false, actionLabel = "Restore", busy = busy, error = error,
        onDismiss = skip,
        onSubmit = { pw ->
            scope.launch {
                busy = true
                error = null
                runCatching { Crypto.restoreKeyBackup(context, pw) }
                    .onSuccess { ChatRepository.redecryptAll(); show = false }
                    .onFailure { error = it.message ?: "Couldn't restore the backup" }
                busy = false
            }
        },
    )
}

/**
 * Once after signing in: explain and request what the phone needs to keep
 * receiving calls with the screen off (no push service on a private
 * network): no battery optimisation, and permission to show calls full screen.
 */
@Composable
fun StayReachablePrompt() {
    val context = LocalContext.current
    val askedKey = "reachability_asked"
    var show by remember {
        mutableStateOf(!promptPrefs(context).getBoolean(askedKey, false) && (!isIgnoringBatteryOptimizations(context) || !canUseFullScreenIntent(context)))
    }
    if (!show) return
    val done = {
        promptPrefs(context).edit().putBoolean(askedKey, true).apply()
        show = false
    }
    val needBattery = !isIgnoringBatteryOptimizations(context)
    AlertDialog(
        onDismissRequest = done,
        icon = { Icon(Icons.Default.NotificationsActive, null) },
        title = { Text("Stay reachable for calls") },
        text = {
            Text(
                "Vision Call runs on your own server, without a push service, so the app keeps its own connection open. " +
                    (if (needBattery) "Allow it to run in the background, or Android will cut it off a few minutes after the screen locks. " else "") +
                    "You can change this later in Settings.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (needBattery) requestIgnoreBatteryOptimizations(context) else requestFullScreenIntent(context)
                done()
            }) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Not now") } },
    )
}
