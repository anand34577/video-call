package com.videocall.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.videocall.mobile.session.SessionManager

/**
 * Two steps, same screen: request an email with a reset token, then paste
 * that token back in with a new password (GET /api/password-reset/confirm
 * takes the raw token — there's no app deep link registered for the
 * server's emailed link, so pasting it is the straightforward path).
 */
@Composable
fun PasswordResetScreen(onBack: () -> Unit, onReset: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var requested by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, title = { Text("Reset password") })
    }) { padding ->
        Column(Modifier.padding(padding).padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!requested) {
                Text("Enter your account email — if password reset is enabled on this server, you'll get a message with a reset code.")
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !loading && email.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        loading = true
                        scope.launch {
                            val result = runCatching { SessionManager.api.requestPasswordReset(email.trim()) }
                            loading = false
                            result.onSuccess { message = it["message"] ?: "If that email exists, a reset code was sent."; requested = true }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text("Send reset code") }
            } else {
                message?.let { Text(it); Spacer(Modifier.height(16.dp)) }
                OutlinedTextField(token, { token = it }, label = { Text("Reset code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(newPassword, { newPassword = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !loading && token.isNotBlank() && newPassword.length >= 8,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        loading = true
                        scope.launch {
                            val result = runCatching { SessionManager.api.confirmPasswordReset(token.trim(), newPassword) }
                            loading = false
                            result.onSuccess { onReset() }.onFailure { error = it.message }
                        }
                    },
                ) { Text("Set new password") }
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
