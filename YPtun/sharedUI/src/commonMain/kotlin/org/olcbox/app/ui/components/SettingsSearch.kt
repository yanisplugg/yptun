package org.olcbox.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.Strings

/** The settings row the search jumped to: [target] is its title; the row scrolls into view and pulses, then this clears. */
class SettingsHighlight {
    var target by mutableStateOf<String?>(null)
}

/** One settings sheet is open at a time, so the highlight is global rather than passed down every page. */
val GlobalSettingsHighlight = SettingsHighlight()

/** Put on the outermost modifier of a settings row, passing the row's title. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.settingsHighlight(title: String): Modifier {
    val highlight = GlobalSettingsHighlight
    val active = highlight.target == title
    val bring = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        delay(350) // the page is still fading in
        bring.bringIntoView()
        repeat(2) {
            glow.animateTo(1f, tween(380))
            glow.animateTo(0.35f, tween(380))
        }
        glow.animateTo(1f, tween(300))
        delay(1400)
        glow.animateTo(0f, tween(700))
        if (highlight.target == title) highlight.target = null
    }
    val color = MaterialTheme.colorScheme.primary
    return this
        .bringIntoViewRequester(bring)
        .drawBehind { if (glow.value > 0f) drawRect(color.copy(alpha = 0.3f * glow.value)) }
}

/**
 * One searchable setting. [page] names the settings page to open (the app maps it to its own route),
 * [row] says whether a row of that page carries [title] to highlight.
 */
data class SettingsSearchEntry(
    val page: String,
    val title: String,
    val subtitle: String,
    val path: String,
    val row: Boolean,
    internal val haystack: String,
    /** A whole settings page; ranks below a single setting that matches equally well. */
    internal val top: Boolean = false,
)

object SettingsSearch {
    /** Every setting that has a page of its own or a row on one; the texts come from [s], so the search follows the app language. */
    fun index(s: Strings, experimentalUnlocked: Boolean): List<SettingsSearchEntry> = buildList {
        fun page(page: String, title: String, subtitle: String = "", keywords: String = "") =
            add(SettingsSearchEntry(page, title, subtitle, s.settings, false, "$title $subtitle $keywords", top = true))
        fun row(page: String, parent: String, title: String, subtitle: String = "", keywords: String = "", rowOnPage: Boolean = true) =
            add(SettingsSearchEntry(page, title, subtitle, "${s.settings} › $parent", rowOnPage, "$title $subtitle $keywords $parent"))

        page("ConnectionSettings", s.connectionSettings, "", "vpn tun connect подключение соединение автозапуск")
        row("ConnectionMode", s.connectionSettings, s.connectionMode, "", "vpn tun режим mode прокси proxy", rowOnPage = false)
        row("SocksProxy", s.connectionSettings, s.socks5Proxy, "", "socks5 socks порт логин пароль proxy", rowOnPage = false)
        row("SplitTunneling", s.connectionSettings, s.splitTunneling, "", "приложения apps исключения обход split bypass", rowOnPage = false)
        row("ConnectionSettings", s.connectionSettings, s.telegramProxyTitle, s.telegramProxySubtitle, "telegram tg телеграм mtproto")
        row("ConnectionSettings", s.connectionSettings, s.autoConnectTitle, s.autoConnectSubtitle, "автозапуск автоподключение старт launch startup")
        row("ConnectionSettings", s.connectionSettings, s.energySaverTitle, s.energySaverSubtitle, "батарея аккумулятор экономия battery power")

        page("RoutingProfiles", s.routingTitle, s.routingProfilesSubtitle, "маршрутизация правила routing rules geoip geosite профили")
        page("Traffic", s.trafficSettings, s.trafficSettingsSubtitle, "dns трафик traffic")
        row("Traffic", s.trafficSettings, s.useMux, s.useMuxSubtitle, "mux мультиплекс multiplex")
        row("Traffic", s.trafficSettings, s.useFragment, s.useFragmentSubtitle, "фрагментация fragment dpi tls")
        row("Traffic", s.trafficSettings, s.blockRuDomains, s.blockRuDomainsSubtitle, "блокировка block ru домены реклама")

        page("Language", s.language, "", "язык language русский english")
        page("Appearance", s.appearanceTitle, "", "тема оформление цвет theme color")
        row("Appearance", s.appearanceTitle, s.dynamicTheme, "", "monet material you обои цвет динамическая")
        row("Appearance", s.appearanceTitle, s.lightTheme, "", "светлая тёмная темная dark light ночная")

        page("Notifications", s.notificationsSettingsTitle, s.notificationsSettingsSubtitle, "уведомления notification шторка")
        row("Notifications", s.notificationsSettingsTitle, s.notifSpeed, s.notifSpeedSubtitle, "скорость speed")
        row("Notifications", s.notificationsSettingsTitle, s.notifPingTitle, s.notifPingSubtitle, "пинг ping задержка")
        row("Notifications", s.notificationsSettingsTitle, s.roomsInNotifTitle, s.roomsInNotifSubtitle, "комнаты rooms")
        row("Notifications", s.notificationsSettingsTitle, s.notifySubExpiryTitle, s.notifySubExpirySubtitle, "подписка срок истекает expiry subscription")
        row("Notifications", s.notificationsSettingsTitle, s.panelAnnouncementsTitle, s.panelAnnouncementsSubtitle, "объявления панель announcements")

        page("Privacy", s.privacy, s.privacySubtitle, "приватность privacy данные отчёт")
        row("Privacy", s.privacy, s.providerReportTitle, s.providerReportSubtitle, "провайдер отчёт report")
        row("Privacy", s.privacy, s.sendDeviceInfoTitle, s.sendDeviceInfoSubtitle, "устройство device информация hwid")

        page("Advanced", s.advancedSettingsTitle, s.advancedSettingsSubtitle, "дополнительно advanced")
        row("Advanced", s.advancedSettingsTitle, s.shareUniversalLinkTitle, s.shareUniversalLinkSubtitle, "поделиться ссылка share link qr")
        row("Advanced", s.advancedSettingsTitle, s.allowConfigEditingTitle, s.allowConfigEditingSubtitle, "редактирование конфигурации config edit")
        row("UrlSchemes", s.advancedSettingsTitle, s.urlSchemes, "", "схемы ссылки uri url scheme deeplink", rowOnPage = false)

        if (experimentalUnlocked) {
            page("Experimental", s.experimental, s.experimentalSubtitle, "экспериментальные experimental")
            row("Experimental", s.experimental, s.legacyUi, s.legacyUiSubtitle, "старый интерфейс legacy ui дизайн")
            row("Experimental", s.experimental, s.useTelemostCookies, s.useTelemostCookiesSubtitle, "телемост telemost cookie куки")
            row("Experimental", s.experimental, s.hideTunTitle, s.hideTunSubtitle, "скрыть tun hide")
            row("Experimental", s.experimental, s.shareHotspotTitle, s.shareHotspotSubtitle, "точка доступа hotspot раздача wifi")
        }

        page("About", s.aboutTitle, "", "о приложении about версия version")
        row("Updates", s.aboutTitle, s.updates, "", "обновления update версия release", rowOnPage = false)
        row("About", s.aboutTitle, s.howToConnect, "", "инструкция help как подключиться")
        row("About", s.aboutTitle, s.community, "", "сообщество telegram чат")
        row("ApplicationLogs", s.aboutTitle, s.logs, "", "журнал логи log", rowOnPage = false)
        row("About", s.aboutTitle, s.copyFullConfig, "", "конфиг скопировать copy config")
        row("About", s.aboutTitle, s.termsOfUse, "", "условия соглашение terms")
    }

    /** Entries matching [query]: every word of it must hit a word of the entry (start, inside, or one typo away), best first. */
    fun search(entries: List<SettingsSearchEntry>, query: String, limit: Int = 12): List<SettingsSearchEntry> {
        val tokens = words(query)
        if (tokens.isEmpty()) return emptyList()
        return entries.mapNotNull { e ->
            val titleWords = words(e.title)
            val allWords = words(e.haystack)
            var score = 0.0
            for (t in tokens) {
                score += when {
                    titleWords.any { it.startsWith(t) } -> 3.0
                    titleWords.any { t.length >= 2 && it.contains(t) } -> 2.0
                    allWords.any { it.startsWith(t) } -> 1.5
                    allWords.any { t.length >= 2 && it.contains(t) } -> 1.0
                    t.length >= 4 && allWords.any { fuzzy(t, it) } -> 0.5
                    else -> return@mapNotNull null
                }
            }
            e to (if (e.top) score - 0.2 else score)
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.distinct()

    /** One typo (substitution, missing or extra letter) between [token] and the same-length start of [word]. */
    private fun fuzzy(token: String, word: String): Boolean {
        val w = word.take(token.length + 1)
        if (kotlin.math.abs(w.length - token.length) > 1) return false
        return distance(token, w) <= 1 || distance(token, word.take(token.length)) <= 1
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/** The search box on top of the settings hub. */
@Composable
fun SettingsSearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text(s.settingsSearchHint) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            }
        },
    )
}

/** What the search found, as rows of one card; picking one reports the entry. */
@Composable
fun SettingsSearchResults(results: List<SettingsSearchEntry>, onPick: (SettingsSearchEntry) -> Unit) {
    val s = LocalStrings.current
    if (results.isEmpty()) {
        Text(
            s.settingsSearchEmpty,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 12.dp),
        )
        return
    }
    SettingsCard {
        Column {
            results.forEachIndexed { i, e ->
                if (i > 0) SettingsCardDivider()
                SettingsCardRow(title = e.title, subtitle = e.path, onClick = { onPick(e) })
            }
        }
    }
}
