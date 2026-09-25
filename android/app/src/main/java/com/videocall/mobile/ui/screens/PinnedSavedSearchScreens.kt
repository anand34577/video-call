package com.videocall.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.videocall.mobile.chat.Convo
import com.videocall.mobile.net.Message
import com.videocall.mobile.session.SessionManager
import com.videocall.mobile.ui.components.Avatar

@Composable
fun PinnedMessagesScreen(convo: Convo, onBack: () -> Unit) {
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        runCatching {
            when (convo) {
                is Convo.Dm -> SessionManager.api.pinnedMessages(peerId = convo.peerId)
                is Convo.GroupChat -> SessionManager.api.pinnedMessages(groupId = convo.groupId)
            }
        }.onSuccess { messages = it; error = null }
            .onFailure { error = it.message ?: "Couldn't load pinned messages" }
    }
    LaunchedEffect(convo) { load(); loading = false }
    MessageListScaffold("Pinned messages", messages, loading, error, onRetry = { scope.launch { loading = true; load(); loading = false } }, onBack)
}

@Composable
fun SavedMessagesScreen(onBack: () -> Unit) {
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        runCatching { SessionManager.api.savedMessages() }
            .onSuccess { messages = it; error = null }
            .onFailure { error = it.message ?: "Couldn't load saved messages" }
    }
    LaunchedEffect(Unit) { load(); loading = false }
    MessageListScaffold("Saved messages", messages, loading, error, onRetry = { scope.launch { loading = true; load(); loading = false } }, onBack)
}

@Composable
fun SearchMessagesScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        if (query.isBlank()) { results = emptyList(); error = null; return }
        loading = true
        scope.launch {
            runCatching { SessionManager.api.searchMessages(q = query.trim()) }
                .onSuccess { results = it; error = null }
                .onFailure { error = it.message ?: "Search failed" }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search messages") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                        trailingIcon = { IconButton(onClick = { runSearch() }) { Icon(Icons.Default.Search, "Search") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (error != null) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Search failed", style = MaterialTheme.typography.titleMedium)
                    Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp))
                    TextButton(onClick = { runSearch() }, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results) { m -> SearchResultRow(m) }
                }
            }
        }
    }
}

@Composable
private fun MessageListScaffold(title: String, messages: List<Message>, loading: Boolean, error: String?, onRetry: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = { Text(title) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (error != null && messages.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Couldn't load this", style = MaterialTheme.typography.titleMedium)
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp))
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                }
            } else if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here yet") }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(messages) { m -> SearchResultRow(m) }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(m: Message) {
    ListItem(
        leadingContent = { Avatar(m.sender?.display_name ?: "?", m.sender?.avatar_file_id) },
        headlineContent = { Text(m.sender?.display_name ?: "Unknown") },
        supportingContent = { Text(if (m.is_encrypted) "🔒 Encrypted message" else m.content, maxLines = 2) },
        trailingContent = { Text(m.sent_at.take(10)) },
    )
    Divider()
}
