package com.videocall.mobile.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import com.videocall.mobile.session.SessionManager

private val ssoErrorMessages = mapOf(
    "no_linked_account" to "No account here is linked to that SSO identity yet. Ask an admin to link your account, or sign in with your username and password.",
    "account_disabled" to "This account is disabled.",
    "access_denied" to "SSO sign-in was cancelled.",
)

private fun ssoErrorMessage(code: String) = ssoErrorMessages[code] ?: "SSO sign-in failed. Please try again."

/**
 * SSO login: runs the server's normal OIDC redirect flow inside a WebView
 * (identical to what a browser does — the server has no API-only variant of
 * this, it's a 302-chain to the IdP and back). On the final redirect back to
 * the server root we copy the session/CSRF cookies the WebView collected
 * into our own OkHttp cookie jar and verify with GET /api/me.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OidcLoginScreen(onLoggedIn: () -> Unit, onCancel: () -> Unit) {
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val api = SessionManager.api
    val host = Uri.parse(api.baseUrl).host ?: ""
    val context = LocalContext.current

    fun fail(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        onCancel()
    }

    Box(Modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val uri = Uri.parse(url)
                        if (uri.host == host && (uri.path.isNullOrEmpty() || uri.path == "/") && !loading) {
                            val ssoError = uri.getQueryParameter("sso-error")
                            if (ssoError != null) {
                                fail(ssoErrorMessage(ssoError))
                                return
                            }
                            loading = true
                            val raw = CookieManager.getInstance().getCookie(api.baseUrl) ?: ""
                            api.cookieJar.importRawCookies(host, raw)
                            scope.launch {
                                val ok = SessionManager.tryResume()
                                if (ok) onLoggedIn() else fail("SSO sign-in failed. Please try again.")
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        // Only treat a failure of the top-level navigation as fatal —
                        // a failing sub-resource (analytics pixel, missing favicon on
                        // the IdP's page) shouldn't abort the whole sign-in flow.
                        if (request.isForMainFrame) {
                            fail("Couldn't reach the sign-in page. Check your connection and try again.")
                        }
                    }
                }
                loadUrl(api.oidcLoginUrl())
            }
        },
    )
    if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
}
