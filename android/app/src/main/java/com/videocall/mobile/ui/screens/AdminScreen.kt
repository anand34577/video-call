package com.videocall.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.videocall.mobile.net.AdminStats
import com.videocall.mobile.net.AuditEntry
import com.videocall.mobile.net.SettingView
import com.videocall.mobile.net.User
import com.videocall.mobile.session.SessionManager
import com.videocall.mobile.ui.components.Avatar
import com.videocall.mobile.ui.components.AppListRow
import com.videocall.mobile.ui.components.ConfirmDialog
import com.videocall.mobile.ui.components.EmptyState
import com.videocall.mobile.ui.components.SectionCard

private enum class AdminTab { USERS, SETTINGS, AUDIT }

/** Full admin console: server stats + user CRUD, live settings editor, audit log. */
@Composable
fun AdminScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(AdminTab.USERS) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    val showError: (String) -> Unit = { msg -> snackScope.launch { snackbarHostState.showSnackbar(msg) } }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                    title = { Text("Admin") },
                )
                TabRow(selectedTabIndex = tab.ordinal) {
                    Tab(selected = tab == AdminTab.USERS, onClick = { tab = AdminTab.USERS }, text = { Text("Users") })
                    Tab(selected = tab == AdminTab.SETTINGS, onClick = { tab = AdminTab.SETTINGS }, text = { Text("Settings") })
                    Tab(selected = tab == AdminTab.AUDIT, onClick = { tab = AdminTab.AUDIT }, text = { Text("Audit log") })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                AdminTab.USERS -> AdminUsersTab(showError)
                AdminTab.SETTINGS -> AdminSettingsTab(showError)
                AdminTab.AUDIT -> AdminAuditTab()
            }
        }
    }
}

@Composable
private fun AdminUsersTab(showError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<AdminStats?>(null) }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<User?>(null) }

    suspend fun refresh() {
        stats = runCatching { SessionManager.api.adminStats() }.getOrNull()
        users = runCatching { SessionManager.api.users() }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { refresh(); loading = false }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp)) {
            item {
                stats?.let { s ->
                    SectionCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("Server", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatTile("Users", "${s.users}")
                                StatTile("Online", "${s.online}")
                                StatTile("Active calls", "${s.active_calls}")
                            }
                            Spacer(Modifier.height(12.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                            Text("Version ${s.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("DB: ${s.db_driver}  ·  Storage: ${s.storage.total_bytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text("Users", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            }
            items(users) { user ->
                AppListRow(
                    leading = { Avatar(user.display_name, user.avatar_file_id) },
                    title = user.display_name + if (user.disabled) " (disabled)" else "",
                    subtitle = "@${user.username} · ${user.role}",
                    trailing = {
                        Switch(checked = !user.disabled, onCheckedChange = { enabled ->
                            scope.launch {
                                runCatching { SessionManager.api.updateUser(user.id, disabled = !enabled) }
                                    .onFailure { showError(it.message ?: "Failed to update user") }
                                refresh()
                            }
                        })
                        IconButton(onClick = { deleteTarget = user }) { Icon(Icons.Default.Delete, "Delete") }
                    },
                )
            }
        }
        FloatingActionButton(onClick = { showCreate = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.PersonAdd, "Add user")
        }
    }

    if (showCreate) {
        CreateUserDialog(
            onDismiss = { showCreate = false },
            onCreate = { username, displayName, password, role ->
                scope.launch {
                    runCatching { SessionManager.api.createUser(username, displayName, password, role) }
                        .onFailure { showError(it.message ?: "Failed to create user") }
                    showCreate = false
                    refresh()
                }
            },
        )
    }
    deleteTarget?.let { user ->
        ConfirmDialog(
            title = "Delete ${user.display_name}?",
            message = "This permanently removes the user's account. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    runCatching { SessionManager.api.deleteUser(user.id) }
                        .onFailure { showError(it.message ?: "Failed to delete user") }
                    refresh()
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun AdminSettingsTab(showError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<List<SettingView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<SettingView?>(null) }

    suspend fun refresh() {
        settings = runCatching { SessionManager.api.listSettings() }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { refresh(); loading = false }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        settings.groupBy { it.group }.forEach { (group, items) ->
            item { Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), color = MaterialTheme.colorScheme.primary) }
            items(items) { s ->
                ListItem(
                    modifier = if (s.editable) Modifier.clickableRow { editing = s } else Modifier,
                    headlineContent = { Text(s.label) },
                    supportingContent = {
                        Column {
                            Text(s.description, style = MaterialTheme.typography.bodySmall)
                            Text(if (s.kind == "secret" && s.value.isNotBlank()) "••••••••" else s.value.ifBlank { "(default)" }, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!s.editable) Icon(Icons.Default.Lock, "Locked by environment", modifier = Modifier.size(16.dp))
                            if (s.editable && s.source == "db") {
                                IconButton(onClick = { scope.launch {
                                    runCatching { SessionManager.api.resetSetting(s.key) }
                                        .onFailure { showError(it.message ?: "Failed to reset setting") }
                                    refresh()
                                } }) {
                                    Icon(Icons.Default.Restore, "Reset to default")
                                }
                            }
                        }
                    },
                )
                Divider()
            }
        }
    }
    editing?.let { s ->
        var value by remember(s.key) { mutableStateOf(if (s.kind == "secret") "" else s.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(s.label) },
            text = {
                Column {
                    Text(s.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (s.kind == "bool") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = value == "true", onCheckedChange = { value = if (it) "true" else "false" })
                            Text("Enabled")
                        }
                    } else {
                        OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (s.kind == "secret" && value.isBlank()) { editing = null; return@TextButton }
                    scope.launch {
                        runCatching { SessionManager.api.updateSetting(s.key, value) }
                            .onFailure { showError(it.message ?: "Failed to save setting") }
                        refresh()
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AdminAuditTab() {
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        entries = runCatching { SessionManager.api.auditLog() }.getOrDefault(emptyList())
        loading = false
    }
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (entries.isEmpty()) {
        EmptyState(icon = Icons.Default.History, title = "No audit entries yet", subtitle = "Admin actions will be logged here")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries) { e ->
            AppListRow(
                leading = {},
                title = "${e.actor_name} · ${e.action}",
                subtitle = e.detail.ifBlank { "${e.target_type} #${e.target_id ?: ""}" } + "  ·  ${e.ip}",
                trailing = { Text(e.created_at.take(16).replace("T", " "), style = MaterialTheme.typography.labelSmall) },
            )
            Divider()
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun StatTile(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreateUserDialog(onDismiss: () -> Unit, onCreate: (String, String, String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isAdmin by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New user") },
        text = {
            Column {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAdmin, onCheckedChange = { isAdmin = it })
                    Text("Admin")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && displayName.isNotBlank() && password.length >= 8,
                onClick = { onCreate(username.trim(), displayName.trim(), password, if (isAdmin) "admin" else "user") },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
