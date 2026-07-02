package org.olcbox.app.ui.components

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.olcbox.app.ui.i18n.LocalStrings

/**
 * In-app WebView for the manual VK captcha of a VK-TURN (freeturn) connect. The freeturn client
 * proxies the VK captcha page through a localhost HTTP server (http://localhost:8765/…) and waits
 * for the user to solve it; once solved it grabs the auth token itself and the dialog is dismissed
 * by the presenter's Hide(). Dismissing manually just hides the page — freeturn keeps waiting for
 * its own 3-minute manual-captcha timeout, then that connect attempt fails.
 */
@Composable
fun VkCaptchaDialog(url: String, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(0.95f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = s.vkCaptchaTitle,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = s.cancel)
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Keep every hop (VK redirects included) inside this WebView; the local
                            // proxy rewrites them back through localhost:8765 anyway.
                            webViewClient = WebViewClient()
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            tag = url
                            loadUrl(url)
                        }
                    },
                    update = { webView ->
                        // Recomposition passes the same url — only reload on a genuinely new captcha.
                        if (webView.tag != url) {
                            webView.tag = url
                            webView.loadUrl(url)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}
