package com.videocall.mobile.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.videocall.mobile.chat.ChatRepository
import com.videocall.mobile.session.SessionManager

private enum class Tab(val label: String, val selected: ImageVector, val unselected: ImageVector) {
    CHATS("Chats", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    CALLS("Calls", Icons.Filled.Call, Icons.Outlined.Call),
    ROOMS("Rooms", Icons.Filled.MeetingRoom, Icons.Outlined.MeetingRoom),
    PEOPLE("People", Icons.Filled.People, Icons.Outlined.People),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/** Same five destinations as the web app's navigation. */
@Composable
fun HomeScreen(
    onOpenDm: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSaved: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHATS) }
    val me by SessionManager.me.collectAsState()
    val unread by ChatRepository.unread.collectAsState()
    val totalUnread = unread.values.sum()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            BadgedBox(badge = {
                                if (t == Tab.CHATS && totalUnread > 0) Badge { Text(if (totalUnread > 99) "99+" else "$totalUnread") }
                            }) { Icon(if (tab == t) t.selected else t.unselected, null) }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            modifier = Modifier.fillMaxSize(),
            label = "tab",
        ) { current ->
            when (current) {
                Tab.CHATS -> ChatsListScreen(onOpenDm = onOpenDm, onOpenGroup = onOpenGroup, onOpenSearch = onOpenSearch, onOpenSaved = onOpenSaved, onOpenPeople = { tab = Tab.PEOPLE }, contentPadding = padding)
                Tab.CALLS -> CallHistoryScreen(contentPadding = padding)
                Tab.ROOMS -> RoomsScreen(contentPadding = padding)
                Tab.PEOPLE -> DirectoryScreen(onOpenDm = onOpenDm, contentPadding = padding)
                Tab.SETTINGS -> SettingsScreen(
                    isAdmin = me?.role == "admin",
                    onOpenAdmin = onOpenAdmin,
                    onLoggedOut = onLoggedOut,
                    contentPadding = padding,
                )
            }
        }
    }
}

