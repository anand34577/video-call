package com.videocall.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** A file picked for sending, waiting in the preview. */
data class StagedFile(val uri: Uri, val name: String, val mime: String, val size: Long)

fun stagedFileOf(context: Context, uri: Uri): StagedFile {
    var name = "file"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
    }
    if (uri.scheme == "file") uri.path?.let { File(it) }?.let { name = it.name; size = it.length() }
    val mime = context.contentResolver.getType(uri) ?: when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        else -> "application/octet-stream"
    }
    return StagedFile(uri, name, mime, size)
}

/** Copies a picked file into the cache and uploads it, reporting progress. */
suspend fun uploadStaged(context: Context, file: StagedFile, onProgress: (Float) -> Unit): Long {
    val safeName = file.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val tmp = File(context.cacheDir, "upload_${System.nanoTime()}_$safeName")
    try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.contentResolver.openInputStream(file.uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Couldn't read ${file.name}")
        }
        return com.videocall.mobile.session.SessionManager.api.uploadFile(tmp, file.mime, onProgress).id
    } finally {
        tmp.delete()
    }
}

fun fileSizeLabel(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024f / 1024f)
}

/** The attach menu: gallery, camera or any document. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(onDismiss: () -> Unit, onGallery: () -> Unit, onCamera: () -> Unit, onDocument: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AttachOption(Icons.Default.PhotoLibrary, "Gallery", Color(0xFF8B5CF6)) { onDismiss(); onGallery() }
            AttachOption(Icons.Default.PhotoCamera, "Camera", Color(0xFFEC4899)) { onDismiss(); onCamera() }
            AttachOption(Icons.Default.Description, "Document", Color(0xFF3B82F6)) { onDismiss(); onDocument() }
        }
    }
}

@Composable
private fun AttachOption(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) { Icon(icon, label, Modifier.size(28.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Full-screen preview of the files about to be sent, WhatsApp style: see
 * them, remove any, add a caption, and watch the upload progress.
 */
@Composable
fun AttachmentPreviewDialog(
    files: List<StagedFile>,
    encrypted: Boolean,
    sending: Boolean,
    progress: Float?,
    onRemove: (Int) -> Unit,
    onAddMore: () -> Unit,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = { if (!sending) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0F1A)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel, enabled = !sending) { Icon(Icons.Default.Close, "Cancel", tint = Color.White) }
                    Text(
                        if (files.size == 1) files[0].name else "${files.size} files",
                        color = Color.White, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onAddMore, enabled = !sending) { Icon(Icons.Default.AddPhotoAlternate, "Add more", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (files.size == 1) {
                        PreviewTile(files[0], big = true, onRemove = null)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(files, key = { i, f -> "${f.uri}-$i" }) { i, f ->
                                Box(Modifier.aspectRatio(1f)) { PreviewTile(f, big = false, onRemove = if (sending) null else ({ onRemove(i) })) }
                            }
                        }
                    }
                }
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text(
                        "Uploading… ${(progress * 100).roundToInt()}%",
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        placeholder = { Text(if (encrypted) "Add a caption (encrypted)" else "Add a caption") },
                        enabled = !sending,
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White.copy(alpha = 0.5f), unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                            focusedPlaceholderColor = Color.White.copy(alpha = 0.5f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    FilledIconButton(onClick = { onSend(caption) }, enabled = !sending && files.isNotEmpty(), modifier = Modifier.size(52.dp)) {
                        if (sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTile(f: StagedFile, big: Boolean, onRemove: (() -> Unit)?) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(if (big) 0.dp else 12.dp)).background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        if (f.mime.startsWith("image/")) {
            AsyncImage(
                model = f.uri, contentDescription = f.name,
                contentScale = if (big) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Icon(
                    when {
                        f.mime.startsWith("video/") -> Icons.Default.Movie
                        f.mime.startsWith("audio/") -> Icons.Default.AudioFile
                        else -> Icons.Default.Description
                    },
                    null, tint = Color.White, modifier = Modifier.size(if (big) 72.dp else 36.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(f.name, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fileSizeLabel(f.size), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (onRemove != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(15.dp)) }
            }
        }
    }
}

/**
 * Swipe a message to the right to reply to it, like WhatsApp. A reply
 * arrow fades in behind it and the phone ticks once past the threshold.
 */
@Composable
fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val threshold = with(density) { 64.dp.toPx() }
    val max = with(density) { 96.dp.toPx() }
    var armed by remember { mutableStateOf(false) }

    Box {
        val progress = (offset.value / threshold).coerceIn(0f, 1f)
        Icon(
            Icons.AutoMirrored.Filled.Reply, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(22.dp).alpha(progress).scale(0.6f + 0.4f * progress),
        )
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offset.value >= threshold) onReply()
                            armed = false
                            scope.launch { offset.animateTo(0f) }
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { offset.animateTo(0f) }
                        },
                    ) { change, dx ->
                        val next = (offset.value + dx).coerceIn(0f, max)
                        if (next != offset.value) change.consume()
                        scope.launch { offset.snapTo(next) }
                        if (!armed && next >= threshold) {
                            armed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (armed && next < threshold) {
                            armed = false
                        }
                    }
                },
        ) { content() }
    }
}
