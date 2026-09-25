package com.videocall.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videocall.mobile.call.CallRepository
import com.videocall.mobile.chat.ChatRepository
import com.videocall.mobile.net.Call
import com.videocall.mobile.session.SessionManager
import com.videocall.mobile.ui.components.Avatar
import com.videocall.mobile.ui.components.EmptyState
import com.videocall.mobile.ui.components.ScreenHeader
import com.videocall.mobile.ui.components.SectionHeader
import com.videocall.mobile.ui.components.dayLabel
import com.videocall.mobile.ui.components.localDateOf
import com.videocall.mobile.ui.components.clockTime
import com.videocall.mobile.ui.theme.VcColor
import com.videocall.mobile.ui.util.rememberCallLauncher
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private enum class CallFilter(val label: String) { ALL("All"), MISSED("Missed"), GROUP("Group") }

@Composable
fun CallHistoryScreen(contentPadding: PaddingValues = PaddingValues()) {
    val scope = rememberCoroutineScope()
    var calls by remember { mutableStateOf<List<Call>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(CallFilter.ALL) }
    val me by SessionManager.me.collectAsState()
    val callState by CallRepository.state.collectAsState()

    suspend fun refresh() {
        runCatching { SessionManager.api.calls() }
            .onSuccess { calls = it; loadError = null }
            .onFailure { loadError = it.message ?: "Couldn't load call history" }
    }

    LaunchedEffect(Unit) {
        refresh()
        loading = false
    }
    // A call just finished: show it without a manual refresh.
    LaunchedEffect(callState.status == com.videocall.mobile.call.CallStatus.IDLE) {
        if (!loading) refresh()
    }

    fun isMissed(call: Call) = call.participants?.firstOrNull { it.user_id == me?.id }?.missed == true
    val visible = when (filter) {
        CallFilter.ALL -> calls
        CallFilter.MISSED -> calls.filter(::isMissed)
        CallFilter.GROUP -> calls.filter { it.is_conference }
    }

    Column(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding())) {
        ScreenHeader("Calls")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (loadError != null && calls.isEmpty()) {
            EmptyState(
                icon = Icons.Default.CloudOff,
                title = "Couldn't load call history",
                subtitle = loadError,
                actionLabel = "Try again",
                onAction = { scope.launch { loading = true; refresh(); loading = false } },
            )
            return@Column
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; refresh(); refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (visible.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.History,
                    title = when (filter) { CallFilter.MISSED -> "No missed calls"; CallFilter.GROUP -> "No group calls yet"; else -> "No calls yet" },
                    subtitle = "Calls you make or receive will show up here",
                )
            } else {
                val byDay = visible.groupBy { localDateOf(it.started_at) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    byDay.forEach { (_, dayCalls) ->
                        item { SectionHeader(dayLabel(dayCalls.first().started_at)) }
                        items(dayCalls, key = { it.id }) { call -> CallRow(call, me?.id, isMissed(call)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallRow(call: Call, myId: Long?, missed: Boolean) {
    val context = LocalContext.current
    val outgoing = call.initiator_id == myId
    // The other party: whoever isn't me.
    val other = if (outgoing) call.participants?.firstOrNull { it.user_id != myId }?.user else call.initiator
    val group = if (call.room_id.startsWith("group:")) {
        val gid = call.room_id.removePrefix("group:").toLongOrNull()
        ChatRepository.groups.collectAsState().value.firstOrNull { it.id == gid }
    } else null
    val launchCall = rememberCallLauncher(
        onDenied = { android.widget.Toast.makeText(context, "Microphone/camera permission is needed to call", android.widget.Toast.LENGTH_SHORT).show() },
        onStart = { video ->
            when {
                group != null -> CallRepository.startGroupCall(context, group, video)
                !call.is_conference && other != null -> CallRepository.startDmCall(context, other, video)
            }
        },
    )
    val title = when {
        group != null -> group.name
        call.room_id.startsWith("priv:") -> "Private room"
        call.is_conference -> "Group call"
        else -> other?.display_name ?: "Unknown"
    }
    val directionIcon = when {
        missed -> Icons.AutoMirrored.Filled.CallMissed
        outgoing -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    val directionText = when {
        missed -> "Missed"
        outgoing -> "Outgoing"
        else -> "Incoming"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (call.is_conference) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(if (call.room_id.startsWith("priv:")) Icons.Default.MeetingRoom else Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            Avatar(other?.display_name ?: "?", other?.avatar_file_id, size = 48)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (missed) VcColor.Danger else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(directionIcon, null, Modifier.size(15.dp), tint = if (missed) VcColor.Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    " $directionText · ${clockTime(call.started_at)}${durationText(call, missed)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (group != null || (!call.is_conference && other != null)) {
            FilledTonalIconButton(onClick = { launchCall(false) }) { Icon(Icons.Default.Call, "Call back", Modifier.size(20.dp)) }
        }
    }
}

private fun durationText(call: Call, missed: Boolean): String {
    if (missed || call.ended_at == null) return ""
    return runCatching {
        val secs = Duration.between(Instant.parse(call.started_at), Instant.parse(call.ended_at)).seconds
        when {
            secs < 60 -> " · ${secs}s"
            secs < 3600 -> " · ${secs / 60}m ${secs % 60}s"
            else -> " · ${secs / 3600}h ${(secs % 3600) / 60}m"
        }
    }.getOrDefault("")
}
