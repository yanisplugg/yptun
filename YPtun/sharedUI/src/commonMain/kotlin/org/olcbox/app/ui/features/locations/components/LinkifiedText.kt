package org.olcbox.app.ui.features.locations.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

// Order matters (leftmost match, then first alternative): scheme URLs, then e-mail, then bare domains,
// so "user@host.com" becomes mailto: and is never half-eaten as the domain "host.com".
private val LINK_REGEX = Regex(
    """(tg://\S+|https?://\S+)|([A-Z0-9._%+-]+@[A-Z0-9-]+(?:\.[A-Z0-9-]+)*\.[A-Z]{2,})|((?:[A-Z0-9-]+\.)+[A-Z]{2,}(?::\d+)?(?:/\S*)?)""",
    RegexOption.IGNORE_CASE
)
private const val TRAILING_PUNCT = ".,;:!?)]}»\"'"

private fun toUri(raw: String, isMail: Boolean): String = when {
    isMail -> "mailto:$raw"
    raw.startsWith("tg://", true) || raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
    else -> "https://$raw" // bare domain / t.me/... → browser (t.me hands off to Telegram itself)
}

/**
 * Description text with links made tappable: t.me/tg:// (Telegram), e-mail (mailto:), http(s) and bare
 * domains (browser). Only the link spans consume taps — a tap on plain text falls through to the parent
 * (group collapse / row select).
 */
@Composable
fun rememberLinkified(text: String): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    return remember(text) {
        buildAnnotatedString {
            var last = 0
            for (m in LINK_REGEX.findAll(text)) {
                val raw = m.value.trimEnd { it in TRAILING_PUNCT }
                if (raw.isEmpty()) continue
                append(text.substring(last, m.range.first))
                val uri = toUri(raw, isMail = m.groups[2] != null)
                withLink(
                    LinkAnnotation.Clickable(
                        tag = uri,
                        styles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { runCatching { uriHandler.openUri(uri) } }
                    )
                ) { append(raw) }
                last = m.range.first + raw.length
            }
            append(text.substring(last))
        }
    }
}
