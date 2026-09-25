package com.videocall.mobile.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import com.videocall.mobile.call.CallRepository
import com.videocall.mobile.chat.ChatRepository
import com.videocall.mobile.chat.Convo
import com.videocall.mobile.chat.Crypto
import com.videocall.mobile.net.FileBrief
import com.videocall.mobile.net.Group
import com.videocall.mobile.net.Message
import com.videocall.mobile.net.User
import com.videocall.mobile.net.UserBrief
import com.videocall.mobile.session.SessionManager
import com.videocall.mobile.ui.components.Avatar
import com.videocall.mobile.ui.components.ConfirmDialog
import com.videocall.mobile.ui.components.clockTime
import com.videocall.mobile.ui.components.dayLabel
import com.videocall.mobile.ui.components.localDateOf
import com.videocall.mobile.ui.components.presenceLabel
import com.videocall.mobile.ui.theme.VcColor
import com.videocall.mobile.ui.util.humanFileSize
import com.videocall.mobile.ui.util.openAttachment
import com.videocall.mobile.ui.util.rememberCallLauncher

private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** A row in the (bottom-anchored, reversed) message list. */
private sealed class ChatRow {
    data class Day(val label: String) : ChatRow()
    data class Msg(val msg: Message, val firstOfRun: Boolean, val lastOfRun: Boolean) : ChatRow()
}

/** Groups consecutive messages from one sender (within 5 min) and inserts day separators. */
private fun buildRows(messages: List<Message>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    fun time(m: Message) = runCatching { Instant.parse(m.sent_at).toEpochMilli() }.getOrDefault(0L)
    for ((i, m) in messages.withIndex()) {
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        if (prev == null || localDateOf(prev.sent_at) != localDateOf(m.sent_at)) rows += ChatRow.Day(dayLabel(m.sent_at))
        val sameAsPrev = prev != null && prev.sender_id == m.sender_id && localDateOf(prev.sent_at) == localDateOf(m.sent_at) && time(m) - time(prev) < 5 * 60_000
        val sameAsNext = next != null && next.sender_id == m.sender_id && localDateOf(next.sent_at) == localDateOf(m.sent_at) && time(next) - time(m) < 5 * 60_000
        rows += ChatRow.Msg(m, firstOfRun = !sameAsPrev, lastOfRun = !sameAsNext)
    }
    return rows
}

@Composable
fun ChatScreen(convo: Convo, onBack: () -> Unit, onOpenPinned: () -> Unit = {}, onOpenGroupInfo: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val me by SessionManager.me.collectAsState()
    val presence by SessionManager.presence.collectAsState()
    val allMessages by ChatRepository.messages.collectAsState()
    val encryptedMap by ChatRepository.encryptedConvos.collectAsState()
    val savedIds by ChatRepository.savedIds.collectAsState()
    val hasMoreMap by ChatRepository.hasMore.collectAsState()
    val loadingOlder by ChatRepository.loadingOlder.collectAsState()
    val sendError by ChatRepository.sendError.collectAsState()
    val typingMap by ChatRepository.typingIn(convo).collectAsState()
    val groups by ChatRepository.groups.collectAsState()
    val messages = allMessages[convo.key] ?: emptyList()
    val encrypted = encryptedMap[convo.key] ?: false
    val typingName = typingMap[convo.key]
    var lightboxFile by remember { mutableStateOf<FileBrief?>(null) }
    var input by remember { mutableStateOf("") }
    var peer by remember { mutableStateOf<User?>(null) }
    val group: Group? = (convo as? Convo.GroupChat)?.let { c -> groups.firstOrNull { it.id == c.groupId } }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var deleteTarget by remember { mutableStateOf<Message?>(null) }
    var editingMsg by remember { mutableStateOf<Message?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val rows = remember(messages) { buildRows(messages).asReversed() }
    val knownUsernames = remember(groups, peer) {
        (group?.members?.map { it.username } ?: emptyList()) + listOfNotNull(peer?.username, me?.username)
    }

    val launchCall = rememberCallLauncher(
        onDenied = { scope.launch { snackbar.showSnackbar("Microphone/camera permission is needed to call") } },
        onStart = { video ->
            when (convo) {
                is Convo.Dm -> peer?.let { CallRepository.startDmCall(context, UserBrief(it.id, it.display_name, it.username, it.avatar_file_id), video) }
                is Convo.GroupChat -> group?.let { CallRepository.startGroupCall(context, it, video) }
            }
        },
    )

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            runCatching {
                val name = queryFileName(context, uri) ?: "file"
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val tmp = File(context.cacheDir, "upload_$name")
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val uploaded = try { SessionManager.api.uploadFile(tmp, mime) } finally { tmp.delete() }
                ChatRepository.sendMessage(convo, "", fileId = uploaded.id, replyToId = replyTo?.id)
                replyTo = null
            }.onFailure { snackbar.showSnackbar(it.message ?: "Upload failed") }
            uploading = false
        }
    }

    // "Active" (auto-read, no notifications) only while this screen is actually
    // visible — backgrounding the app with a chat open must not mark new
    // messages read or swallow their notifications.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(convo, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> ChatRepository.setActive(convo)
                Lifecycle.Event.ON_STOP -> ChatRepository.setActive(null)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ChatRepository.setActive(null)
        }
    }

    LaunchedEffect(convo) {
        ChatRepository.loadHistory(convo)
        ChatRepository.fetchSaved()
        when (convo) {
            is Convo.Dm -> peer = runCatching { SessionManager.api.users().firstOrNull { it.id == convo.peerId } }.getOrNull()
            is Convo.GroupChat -> ChatRepository.loadGroups()
        }
    }

    // New message at the bottom: follow it if we're already at the bottom, or if it's ours.
    val newestId = messages.lastOrNull()?.let { it.clientId ?: it.id.toString() }
    LaunchedEffect(newestId) {
        val newest = messages.lastOrNull() ?: return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 2 || newest.sender_id == me?.id) listState.animateScrollToItem(0)
    }
    // Reaching the oldest loaded message pages in the previous 50.
    val nearTop by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= rows.size - 3 } ?: false } }
    LaunchedEffect(nearTop, hasMoreMap[convo.key]) {
        if (nearTop && hasMoreMap[convo.key] == true) ChatRepository.loadOlder(convo)
    }
    LaunchedEffect(sendError) {
        sendError?.let { snackbar.showSnackbar(it); ChatRepository.clearSendError() }
    }

    val title = peer?.display_name ?: group?.name ?: ""
    val peerStatus = peer?.let { presence[it.id] ?: it.status }
    val subtitle = when {
        typingName != null -> if (convo is Convo.GroupChat) "$typingName is typing…" else "typing…"
        group != null -> "${group.members.size} members" + if (encrypted) " · encrypted" else ""
        peer != null -> presenceLabel(peerStatus) + if (encrypted) " · encrypted" else ""
        else -> ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Row(
                        Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = convo is Convo.GroupChat) { onOpenGroupInfo() }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(title, peer?.avatar_file_id ?: group?.avatar_file_id, size = 40, status = peerStatus)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (encrypted && typingName == null) Icon(Icons.Default.Lock, null, Modifier.size(12.dp).padding(end = 3.dp), tint = VcColor.Online)
                                    Text(
                                        subtitle, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                        color = if (typingName != null || peerStatus == "online") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { launchCall(false) }) { Icon(Icons.Default.Call, "Voice call") }
                    IconButton(onClick = { launchCall(true) }) { Icon(Icons.Default.Videocam, "Video call") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (encrypted) "Turn off encryption" else "Encrypt messages") },
                                leadingIcon = { Icon(if (encrypted) Icons.Default.LockOpen else Icons.Default.Lock, null) },
                                onClick = {
                                    menuOpen = false
                                    if (encrypted) {
                                        ChatRepository.setEncrypted(convo, false)
                                    } else scope.launch {
                                        // Only offer encryption when every recipient can actually read it (same gate as web).
                                        val others = when (convo) {
                                            is Convo.Dm -> listOf(convo.peerId)
                                            is Convo.GroupChat -> group?.members?.map { it.id }?.filter { it != me?.id } ?: emptyList()
                                        }
                                        val missing = Crypto.usersMissingKeys(others)
                                        if (missing.isEmpty()) {
                                            ChatRepository.setEncrypted(convo, true)
                                            snackbar.showSnackbar("New messages will be end-to-end encrypted")
                                        } else {
                                            snackbar.showSnackbar(
                                                if (convo is Convo.Dm) "$title hasn't signed in on an encryption-capable device yet"
                                                else "${missing.size} member(s) haven't set up encryption yet",
                                            )
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(text = { Text("Pinned messages") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = { menuOpen = false; onOpenPinned() })
                            if (convo is Convo.GroupChat) {
                                DropdownMenuItem(text = { Text("Group info") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menuOpen = false; onOpenGroupInfo() })
                            }
                            DropdownMenuItem(
                                text = { Text("Export chat") },
                                leadingIcon = { Icon(Icons.Default.IosShare, null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        runCatching { exportChat(context, convo) }.onFailure { snackbar.showSnackbar("Export failed") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Composer(
                input = input,
                onInput = { input = it; ChatRepository.sendTyping(convo) },
                encrypted = encrypted,
                uploading = uploading,
                replyTo = replyTo,
                editing = editingMsg,
                onCancelContext = { replyTo = null; if (editingMsg != null) { editingMsg = null; input = "" } },
                onAttach = { pickFile.launch("*/*") },
                onSend = {
                    val edit = editingMsg
                    if (edit != null) {
                        ChatRepository.editMessage(edit, input)
                        editingMsg = null
                    } else {
                        ChatRepository.sendMessage(convo, input, replyToId = replyTo?.id)
                        replyTo = null
                    }
                    input = ""
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            ) {
                if (typingName != null) item(key = "typing") { TypingBubble(typingName, convo is Convo.GroupChat) }
                items(rows, key = { r -> if (r is ChatRow.Msg) (r.msg.clientId ?: "m${r.msg.id}") else "d${(r as ChatRow.Day).label}" }) { row ->
                    when (row) {
                        is ChatRow.Day -> DayChip(row.label)
                        is ChatRow.Msg -> MessageBubble(
                            msg = row.msg,
                            isMine = row.msg.sender_id == me?.id,
                            isGroup = convo is Convo.GroupChat,
                            firstOfRun = row.firstOfRun,
                            lastOfRun = row.lastOfRun,
                            saved = savedIds.contains(row.msg.id),
                            myId = me?.id,
                            myUsername = me?.username ?: "",
                            knownUsernames = knownUsernames,
                            onClick = { if (row.msg.failed) ChatRepository.retryMessage(convo, row.msg) },
                            onLongClick = { if (row.msg.deleted_at == null && row.msg.id > 0) actionsFor = row.msg },
                            onReact = { emoji -> ChatRepository.reactToMessage(row.msg, emoji) },
                            onOpenFile = { f ->
                                if (f.mime.startsWith("image/")) lightboxFile = f
                                else scope.launch { runCatching { openAttachment(context, f) }.onFailure { snackbar.showSnackbar("Couldn't open the file") } }
                            },
                        )
                    }
                }
                if (convo.key in loadingOlder) {
                    item(key = "older") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    }
                }
                if (messages.isEmpty()) {
                    item(key = "empty") { EmptyChat(title, encrypted) }
                }
            }
            val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
            AnimatedVisibility(
                visible = showJump,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape,
                ) { Icon(Icons.Default.KeyboardArrowDown, "Jump to latest") }
            }
        }
    }

    val target = actionsFor
    if (target != null) {
        MessageActionsSheet(
            msg = target,
            isMine = target.sender_id == me?.id,
            saved = savedIds.contains(target.id),
            myId = me?.id,
            onDismiss = { actionsFor = null },
            onReact = { ChatRepository.reactToMessage(target, it); actionsFor = null },
            onReply = { replyTo = target; editingMsg = null; actionsFor = null },
            onCopy = {
                clipboard.setText(AnnotatedString(target.decryptedContent ?: target.content))
                actionsFor = null
                scope.launch { snackbar.showSnackbar("Copied") }
            },
            onEdit = { editingMsg = target; replyTo = null; input = target.decryptedContent ?: target.content; actionsFor = null },
            onPin = { ChatRepository.pinMessage(target, target.pinned_at == null); actionsFor = null },
            onSave = { scope.launch { ChatRepository.toggleSave(target) }; actionsFor = null },
            onDelete = { deleteTarget = target; actionsFor = null },
        )
    }

    val lightbox = lightboxFile
    if (lightbox != null) {
        Dialog(onDismissRequest = { lightboxFile = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { lightboxFile = null }) {
                AsyncImage(
                    model = SessionManager.api.fileUrl(lightbox.id),
                    contentDescription = lightbox.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { lightboxFile = null }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Text(lightbox.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { runCatching { openAttachment(context, lightbox) } } }) { Icon(Icons.Default.Download, "Save / open externally", tint = Color.White) }
                }
            }
        }
    }

    deleteTarget?.let { msg ->
        ConfirmDialog(
            title = "Delete message?",
            message = "It will be removed for everyone in this chat.",
            confirmLabel = "Delete",
            onConfirm = { ChatRepository.deleteMessage(msg) },
            onDismiss = { deleteTarget = null },
        )
    }
}

private suspend fun exportChat(context: android.content.Context, convo: Convo) {
    val url = when (convo) {
        is Convo.Dm -> SessionManager.api.exportUrl(peerId = convo.peerId)
        is Convo.GroupChat -> SessionManager.api.exportUrl(groupId = convo.groupId)
    }
    val dest = File(context.cacheDir, "downloads").apply { mkdirs() }.resolve("chat-export-${convo.key.replace(':', '-')}.json")
    SessionManager.api.downloadToFile(url, dest)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Export chat").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun EmptyChat(title: String, encrypted: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.WavingHand, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(12.dp))
        Text("Say hello to $title", style = MaterialTheme.typography.titleMedium)
        Text(
            if (encrypted) "Messages here are end-to-end encrypted." else "Messages stay on your own server.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun TypingBubble(name: String, isGroup: Boolean) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(3) { i ->
                val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i")
                Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
        if (isGroup) Text("  $name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun nameColor(name: String): Color {
    val palette = listOf(Color(0xFF60A5FA), Color(0xFFF472B6), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFA78BFA), Color(0xFFF87171), Color(0xFF22D3EE))
    return palette[kotlin.math.abs(name.hashCode()) % palette.size]
}

private fun displayText(m: Message): String = when {
    m.deleted_at != null -> "This message was deleted"
    m.is_encrypted -> m.decryptedContent ?: "Decrypting…"
    else -> m.content
}

private val tokenRegex = Regex("(https?://\\S+)|(@[a-zA-Z0-9._-]{2,32})")

/** Links and @mentions (of real members; your own is highlighted). */
private fun richText(text: String, myUsername: String, known: List<String>, linkColor: Color, mentionColor: Color): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (m in tokenRegex.findAll(text)) {
            append(text.substring(last, m.range.first))
            val v = m.value
            when {
                v.startsWith("http") -> withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(v) }
                known.any { it.equals(v.drop(1), true) } -> {
                    val mine = v.drop(1).equals(myUsername, true)
                    withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold, background = if (mine) mentionColor.copy(alpha = 0.18f) else Color.Unspecified)) { append(v) }
                }
                else -> append(v)
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }

@Composable
private fun MessageBubble(
    msg: Message,
    isMine: Boolean,
    isGroup: Boolean,
    firstOfRun: Boolean,
    lastOfRun: Boolean,
    saved: Boolean,
    myId: Long?,
    myUsername: String,
    knownUsernames: List<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReact: (String) -> Unit,
    onOpenFile: (FileBrief) -> Unit,
) {
    val context = LocalContext.current
    val big = 20.dp
    val small = 6.dp
    val shape = if (isMine) RoundedCornerShape(big, if (firstOfRun) big else small, if (lastOfRun) small else small, big)
    else RoundedCornerShape(if (firstOfRun) big else small, big, big, if (lastOfRun) small else small)
    val deleted = msg.deleted_at != null
    val bg = when {
        deleted -> MaterialTheme.colorScheme.surfaceContainer
        isMine -> MaterialTheme.colorScheme.primary
        // On light themes a tinted bubble disappears into the page; use white.
        MaterialTheme.colorScheme.background.luminance() > 0.5f -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (isMine && !deleted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val meta = fg.copy(alpha = 0.7f)
    val image = msg.file?.takeIf { it.mime.startsWith("image/") && it.mime != "image/svg+xml" }

    Column(
        Modifier.fillMaxWidth().padding(top = if (firstOfRun) 8.dp else 2.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (isGroup && !isMine) {
                if (lastOfRun) Avatar(msg.sender?.display_name ?: "?", msg.sender?.avatar_file_id, size = 30)
                else Spacer(Modifier.width(30.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(shape)
                    .background(bg)
                    .then(if (deleted) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(if (image != null && msg.reply_to == null) 4.dp else 0.dp),
            ) {
                if (isGroup && !isMine && firstOfRun) {
                    val name = msg.sender?.display_name ?: "Unknown"
                    Text(name, style = MaterialTheme.typography.labelLarge, color = nameColor(name), modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp))
                }
                msg.reply_to?.let { r ->
                    Row(
                        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).clip(MaterialTheme.shapes.small)
                            .background(if (isMine) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .height(IntrinsicSize.Min),
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(if (isMine) Color.White else MaterialTheme.colorScheme.primary))
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(r.sender?.display_name ?: "Message", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (isMine) fg else MaterialTheme.colorScheme.primary)
                            Text(
                                when {
                                    r.deleted -> "Deleted message"
                                    r.is_encrypted -> "Encrypted message"
                                    r.content.isBlank() && r.has_file -> "Attachment"
                                    else -> r.content
                                },
                                style = MaterialTheme.typography.bodySmall, color = meta, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (!deleted) msg.file?.let { f ->
                    if (image != null) {
                        AsyncImage(
                            model = SessionManager.api.fileUrl(f.id),
                            contentDescription = f.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.padding(top = if (msg.reply_to != null) 6.dp else 0.dp)
                                .widthIn(min = 180.dp, max = 292.dp).heightIn(min = 120.dp, max = 320.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .combinedClickable(onClick = { onOpenFile(f) }, onLongClick = onLongClick),
                        )
                    } else {
                        Row(
                            Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).widthIn(min = 200.dp).clip(MaterialTheme.shapes.medium)
                                .background(if (isMine) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .combinedClickable(onClick = { onOpenFile(f) }, onLongClick = onLongClick)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(38.dp).clip(MaterialTheme.shapes.small).background(if (isMine) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = if (isMine) fg else MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(humanFileSize(f.size), style = MaterialTheme.typography.labelSmall, color = meta)
                            }
                        }
                    }
                }
                val text = displayText(msg)
                val hasText = deleted || text.isNotBlank()
                Row(
                    Modifier.padding(start = 12.dp, end = 10.dp, top = if (hasText) 7.dp else 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (hasText) {
                        Text(
                            if (deleted) AnnotatedString(text) else richText(text, myUsername, knownUsernames, if (isMine) fg else MaterialTheme.colorScheme.primary, if (isMine) fg else MaterialTheme.colorScheme.primary),
                            color = if (deleted) MaterialTheme.colorScheme.onSurfaceVariant else fg,
                            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = if (deleted) androidx.compose.ui.text.font.FontStyle.Italic else null),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (msg.pinned_at != null) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(11.dp), tint = meta)
                        if (saved) Icon(Icons.Default.Bookmark, "Saved", Modifier.size(11.dp), tint = meta)
                        if (msg.is_encrypted && !deleted) Icon(Icons.Default.Lock, "Encrypted", Modifier.size(11.dp), tint = meta)
                        if (msg.edited_at != null && !deleted) Text("edited", style = MaterialTheme.typography.labelSmall, color = meta)
                        Text(clockTime(msg.sent_at), style = MaterialTheme.typography.labelSmall, color = meta)
                        if (isMine && !deleted) {
                            when {
                                msg.failed -> Icon(Icons.Default.ErrorOutline, "Failed", Modifier.size(14.dp), tint = if (isMine) fg else MaterialTheme.colorScheme.error)
                                msg.pending -> Icon(Icons.Default.Schedule, "Sending", Modifier.size(13.dp), tint = meta)
                                msg.read_at != null -> Icon(Icons.Default.DoneAll, "Read", Modifier.size(15.dp), tint = if (isMine) Color(0xFFBFF4FF) else MaterialTheme.colorScheme.primary)
                                msg.delivered_at != null || isGroup -> Icon(Icons.Default.DoneAll, "Delivered", Modifier.size(15.dp), tint = meta)
                                else -> Icon(Icons.Default.Done, "Sent", Modifier.size(15.dp), tint = meta)
                            }
                        }
                    }
                }
            }
        }
        if (msg.failed) {
            Text(
                "Not sent · tap to retry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 3.dp, end = 4.dp).clickable(onClick = onClick),
            )
        }
        val reactions = msg.reactions.orEmpty().groupBy { it.emoji }
        if (reactions.isNotEmpty() && !deleted) {
            Row(
                Modifier.padding(top = 3.dp, start = if (isGroup && !isMine) 36.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                reactions.forEach { (emoji, list) ->
                    val mine = list.any { it.user_id == myId }
                    Row(
                        Modifier.clip(CircleShape)
                            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, if (mine) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            .clickable { onReact(emoji) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(emoji, style = MaterialTheme.typography.bodyMedium)
                        if (list.size > 1) Text(" ${list.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    encrypted: Boolean,
    uploading: Boolean,
    replyTo: Message?,
    editing: Message?,
    onCancelContext: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            AnimatedVisibility(replyTo != null || editing != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val ctx = editing ?: replyTo
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            if (editing != null) "Editing message" else "Replying to ${ctx?.sender?.display_name ?: "message"}",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        )
                        Text(ctx?.let { displayText(it) } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onCancelContext) { Icon(Icons.Default.Close, "Cancel") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(onClick = onAttach, enabled = !uploading && editing == null) {
                        if (uploading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.AttachFile, "Attach file", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f).padding(top = 13.dp, bottom = 13.dp, end = 14.dp)) {
                        if (input.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (encrypted) Icon(Icons.Default.Lock, null, Modifier.size(14.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (encrypted) "Encrypted message" else "Message", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = onInput,
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank()
                FilledIconButton(
                    onClick = { if (canSend) onSend() },
                    enabled = canSend,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) { Icon(if (editing != null) Icons.Default.Check else Icons.AutoMirrored.Filled.Send, if (editing != null) "Save edit" else "Send") }
            }
        }
    }
}

@Composable
private fun MessageActionsSheet(
    msg: Message,
    isMine: Boolean,
    saved: Boolean,
    myId: Long?,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                QuickReactions.forEach { emoji ->
                    val mine = msg.reactions.orEmpty().any { it.emoji == emoji && it.user_id == myId }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                }
            }
            Spacer(Modifier.height(8.dp))
            SheetAction(Icons.AutoMirrored.Filled.Reply, "Reply", onReply)
            if ((msg.decryptedContent ?: msg.content).isNotBlank()) SheetAction(Icons.Default.ContentCopy, "Copy text", onCopy)
            if (isMine && !msg.is_encrypted && msg.file == null) SheetAction(Icons.Default.Edit, "Edit", onEdit)
            SheetAction(Icons.Outlined.PushPin, if (msg.pinned_at != null) "Unpin" else "Pin", onPin)
            SheetAction(if (saved) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, if (saved) "Remove from saved" else "Save", onSave)
            if (isMine) SheetAction(Icons.Default.DeleteOutline, "Delete", onDelete, danger = true)
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

private fun queryFileName(context: android.content.Context, uri: android.net.Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
    }
    return name
}
