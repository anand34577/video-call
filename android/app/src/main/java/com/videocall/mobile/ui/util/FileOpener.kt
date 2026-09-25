package com.videocall.mobile.ui.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import com.videocall.mobile.net.FileBrief
import com.videocall.mobile.session.SessionManager

/** Downloads a chat attachment to the app cache and opens it with whatever app the user has for its type. */
suspend fun openAttachment(context: Context, file: FileBrief) {
    val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
    val dest = File(dir, "${file.id}_${file.name}")
    if (!dest.exists()) {
        SessionManager.api.downloadToFile(SessionManager.api.fileUrl(file.id), dest)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, file.mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun humanFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
