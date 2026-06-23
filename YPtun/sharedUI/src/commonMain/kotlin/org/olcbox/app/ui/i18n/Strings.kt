package org.olcbox.app.ui.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Russian plural picker: (one, few, many) by the standard ru pluralization rules. */
internal fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** Supported UI languages. [System] follows the device locale (resolved per platform). */
enum class AppLanguage(val id: String) {
    System("system"),
    English("en"),
    Russian("ru"),
    Persian("fa");

    companion object {
        fun fromId(id: String?): AppLanguage = entries.firstOrNull { it.id == id } ?: System
    }
}

/** Global selected language. Set from prefs at startup; changing it recomposes the UI tree. */
object LocalizationState {
    /** The user's explicit choice (System/English/Russian). */
    var language by mutableStateOf(AppLanguage.System)
    /** What [AppLanguage.System] resolves to on this device (set by the platform layer). */
    var systemLanguage by mutableStateOf(AppLanguage.Russian)

    val effective: AppLanguage
        get() = if (language == AppLanguage.System) systemLanguage else language
}

val LocalStrings = staticCompositionLocalOf<Strings> { RuStrings }

fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.English -> EnStrings
    AppLanguage.Persian -> FaStrings
    else -> RuStrings
}

/** All localized UI strings used by the migrated screens. */
interface Strings {
    val languageName: String

    // Bottom navigation
    val navConnection: String
    val navSettings: String

    // Home
    val connectionTime: String
    val refreshSubscriptions: String
    val configurations: String
    val labelSetup: String
    val labelStop: String
    val labelStart: String
    val addCustomLocation: String
    val addSubscription: String

    // Subscription group menu
    val groupPinToTop: String
    val groupUnpinFromTop: String
    val groupSortByPing: String
    val groupAutoUpdate: String
    val visitSubscriptionPage: String
    val telegramProxyTitle: String
    val telegramProxySubtitle: String
    val telegramProxyGenerating: String
    val telegramProxyRunning: String
    val telegramProxyError: String
    val groupDelete: String

    // Delete dialogs
    val delete: String
    val cancel: String
    fun selectedCount(count: Int): String
    val deleteSubscriptionTitle: String
    fun deleteSubscriptionMessage(count: Int): String
    val deleteAllSubscriptionsTitle: String
    val deleteAllSubscriptionsMessage: String
    val deleteAllConfigsTitle: String
    val deleteAllConfigsMessage: String
    val menuDeleteAllSubscriptions: String
    val menuDeleteAllConfigs: String
    val menuDeleteUnreachable: String
    val menuDeleteDuplicates: String
    // Folders (user-created groups)
    val createFolder: String
    val folderEmpty: String
    val folderRename: String
    val folderDelete: String
    val folderDeleteTitle: String
    val folderDeleteMessage: String
    val newFolderTitle: String
    val folderNameHint: String
    val moveToFolder: String
    val removeFromFolder: String
    val chooseFolderTitle: String
    val newFolderOption: String
    val folderCreate: String
    val folderSave: String
    val deleteUnreachableTitle: String
    val deleteUnreachableMessage: String
    val deleteDuplicatesTitle: String
    val deleteDuplicatesMessage: String
    fun unreachableDeleted(count: Int): String
    fun duplicatesDeleted(count: Int): String
    val noUnreachableFound: String
    val noDuplicatesFound: String

    // Settings hub
    val settings: String
    val dynamicTheme: String
    val dynamicThemeOn: String
    val dynamicThemeOff: String
    val routing: String
    val routingSubtitle: String
    val trafficSettings: String
    val trafficSettingsSubtitle: String
    val connectionSettings: String
    val connectionSettingsSubtitle: String
    val subscriptionsSharing: String
    val updates: String
    val applicationSettings: String
    val applicationSettingsSubtitle: String
    val urlSchemes: String
    val urlSchemesSubtitle: String
    val logs: String
    val logsSubtitle: String
    val info: String
    fun version(v: String): String
    fun xrayVersion(v: String): String
    fun singboxVersion(v: String): String
    fun vkturnVersion(v: String): String
    fun olcrtcVersion(v: String): String
    /** Localized label for a TrafficSettings domain strategy (prefer_ipv4/prefer_ipv6/ipv4_only/ipv6_only). */
    fun domainStrategyName(v: String): String
    fun hwid(v: String): String
    val community: String
    val howToConnect: String

    // Application behavior
    val autoConnectTitle: String
    val autoConnectSubtitle: String
    val energySaverTitle: String
    val energySaverSubtitle: String
    val confirmDeleteTitle: String
    val confirmDeleteSubtitle: String
    val language: String

    // Traffic settings
    val dns: String
    val remoteDnsLabel: String
    val directDnsLabel: String
    val domainStrategy: String
    val multiplexing: String
    val useMux: String
    val useMuxSubtitle: String
    val muxMaxConnections: String
    val fragmentation: String
    val useFragment: String
    val useFragmentSubtitle: String
    val fragmentPackets: String
    val fragmentLength: String
    val fragmentInterval: String
    val mtuLabel: String
    val blockRuDomains: String
    val blockRuDomainsSubtitle: String
    val fakeDnsTitle: String
    val fakeDnsSubtitle: String
    val saveAndApply: String

    // Routing
    val routingTitle: String
    val bypassLan: String
    val bypassLanSubtitle: String
    val showSystemApps: String
    val bypassRussia: String
    val bypassRussiaSubtitle: String
    val blockAds: String
    val blockAdsSubtitle: String
    val directDomains: String
    val blockedDomains: String
    val domainsPlaceholder: String
    val presets: String
    val presetRuDirect: String
    val presetAdsBlock: String
    val presetRuAds: String
    val presetAllVpn: String
    val presetReset: String
    val sbRoutingAdvanced: String
    val sbRoutingAdvancedDesc: String
    val sbRouteRulesLabel: String
    val sbRuleSetLabel: String
    val sbInvalidJsonArray: String

    // Routing tabs + manual (v2rayNG-style) rules
    val routingTabSimple: String
    val routingTabRules: String
    val routingTabHapp: String
    val routingShareAllButton: String
    val routingShareAllTitle: String
    val routingShareAllDesc: String
    val routingExportCopy: String
    val routingImportPaste: String
    val routingImportApply: String
    val routingImportInvalid: String
    val routingRulesTitle: String
    val routingRulesDesc: String
    val routingRulesEmpty: String
    val routingRuleAdd: String
    val routingRuleEdit: String
    val routingRuleDelete: String
    val routingRuleName: String
    val routingRuleOutbound: String
    val routingRuleDomains: String
    val routingRuleIps: String
    val routingRuleSource: String
    val routingRulePort: String
    val routingRuleSourcePort: String
    val routingRuleNetwork: String
    val routingRuleNetworkType: String
    val netTypeWifi: String
    val netTypeCellular: String
    val netTypeEthernet: String
    val netTypeOther: String
    val routingRuleProtocol: String
    val routingRuleClient: String
    val routingRuleMetered: String
    val routingRuleClashMode: String
    val routingRuleApps: String
    val routingRulePackageRegex: String
    val routingRuleEnabled: String
    val routingRuleNetworkAny: String
    val routingOutProxy: String
    val routingOutDirect: String
    val routingOutBlock: String
    val routingRuleAction: String
    val routingActionRoute: String
    val routingActionRouteOptions: String
    val routingActionSniff: String
    val routingActionResolve: String
    val routingActionHijackDns: String
    val routingActionReject: String

    // Routing profiles (Happ-style)
    val routingProfiles: String
    val routingProfilesSubtitle: String
    val routingProfileGlobal: String
    val routingProfileNone: String
    val routingProfileGlobalHint: String
    val routingProfileAdd: String
    val routingProfileImportLink: String
    val routingProfilePasteHint: String
    val routingProfileNewName: String
    val routingProfileName: String
    val routingProfileDelete: String
    val routingProfileShare: String
    val routingProfileGlobalProxy: String
    val routingProfileGlobalProxyDesc: String
    val routingProfileRouteOrder: String
    val routingProfileDomainStrategy: String
    val routingExpert: String
    val routingExpertDesc: String
    val routingExpertInherit: String
    val routingExpertSniffing: String
    val routingExpertSniffingDesc: String
    val routingExpertRouteOnly: String
    val routingExpertRouteOnlyDesc: String
    val routingExpertResolve: String
    val routingExpertResolveDesc: String
    val routingProxySites: String
    val routingProxyIp: String
    val routingDirectSites: String
    val routingDirectIp: String
    val routingBlockSites: String
    val routingBlockIp: String
    val routingSelectorsHint: String
    val routingIpSelectorsHint: String
    val routingGeoDatabases: String
    val routingGeoDatabasesDesc: String
    val routingGeoUpdate: String
    val routingGeoUpdating: String
    val routingGeoNever: String
    val routingGeoipUrl: String
    val routingGeositeUrl: String
    val routingProfileSaved: String
    val routingProfileImported: String
    val routingProfileInvalidLink: String
    val routingProfileEmpty: String
    fun routingGeoUpdated(ts: String): String
    fun routingProfileRuleCount(n: Int): String
    val locationRoutingProfile: String
    val locationRoutingGlobalDefault: String

    // URL schemes
    val urlSchemeAddConfig: String
    val urlSchemeAddSubscription: String
    val urlSchemeControl: String
    val copy: String

    // Add configuration sheet
    val addConnection: String
    val addConnectionSubtitle: String
    val scanQr: String
    val scanQrSubtitle: String
    val pasteLink: String
    val pasteLinkSubtitle: String
    val importFile: String
    val importFileSubtitle: String
    val updateSubscriptionsAction: String
    val updateSubscriptionsSubtitle: String
    val createCustomLocation: String
    val createCustomLocationSubtitle: String

    // Connection / split tunneling
    val connectionMode: String
    val socks5Proxy: String
    val splitTunneling: String
    val routingBehavior: String
    val appsUsingYptun: String
    val bypassedApps: String
    val search: String
    val appListSection: String
    val endpointSection: String
    val credentialsSection: String

    // Logs / updates / subscriptions
    val applicationLogsTitle: String
    val updatesTitle: String
    fun currentVersion(v: String): String
    val checkInterval: String
    val copyFullConfig: String
    val currentConfig: String
    val subscriptionsSection: String
    val noSubscriptions: String
    val noSubscriptionsSubtitle: String

    // SOCKS5 form
    val listenAddress: String
    val listenAddressRequired: String
    val savingRestarts: String
    val unsavedChange: String
    val port: String
    val portRequired: String
    val username: String
    val password: String
    val generatedPassword: String
    val passwordRequired: String
    val usernameRequired: String
    val regeneratePassword: String
    val save: String
    val share: String

    // App list
    val searchApps: String
    val noApps: String
    val noMatchingApps: String
    val noAppsHint: String
    val noMatchHint: String
    val noAppListNeeded: String
    val everyAppSameRoute: String

    // Updates extra
    val lastCheck: String
    val notCheckedYet: String
    val checkNow: String

    // Connection mode / split tunnel descriptions
    val fullTunnel: String
    val localSocksProxy: String
    val systemVpnInterface: String
    val localSocksEndpoint: String
    val allApps: String
    val selectedAppsOnly: String
    val bypassSelected: String
    val everyAppUsesYptun: String
    val chooseAppsUseYptun: String
    val chooseAppsBypass: String
    // Split-tunnel counts / status / detail (раздельное туннелирование)
    fun appsCount(n: Int): String
    fun useYptunCount(n: Int): String
    fun onlyUseYptunCount(n: Int): String
    fun onlyCount(n: Int): String
    fun bypassedCount(n: Int): String
    fun bypassYptunCount(n: Int): String
    val allAppsUseYptunStatus: String
    val noAppsSelectedStatus: String
    val noAppsBypassStatus: String
    val selectionRequired: String
    val noBypassedAppsValue: String
    val savedForTunMode: String
    val appliesWhenSettingsClose: String
    val tunModeRoutingRule: String
    // "Bypass RU apps" preset
    val bypassRuApps: String
    val bypassRuOn: String
    val ruBypassAccuracy: String
    val ruBypassNoMatches: String
    fun ruBypassMatchedByPackage(n: Int): String
    val ruBypassNoneSelected: String
    fun ruBypassAlreadySelected(n: Int): String
    fun ruBypassAutoBypassed(n: Int): String
    fun ruBypassAutoManual(auto: Int, manual: Int): String
    // Update download status
    val releaseChannelLabel: String
    fun downloadingAsset(name: String): String
    fun downloadFailed(error: String): String
    fun installingAsset(name: String): String
    /** Short hours label, e.g. "6 ч" / "6h" / "۶ ساعت". */
    fun hoursShort(n: Int): String

    // Theme color picker
    val themeColor: String
    val elementColor: String
    val textColor: String
    val customColorRgb: String

    // Subscriptions sharing extra
    val qrShare: String
    val refresh: String
    // Panel-advertised links (Remnawave/Happ profile-web-page-url / support-url headers)
    val subscriptionWebPage: String
    val subscriptionSupport: String

    // Experimental section
    val experimental: String
    val experimentalSubtitle: String
    val experimentalUnlocked: String
    val notifSpeed: String
    val notifSpeedSubtitle: String
    val showSubscriptionExpiryTitle: String
    val showSubscriptionExpirySubtitle: String
    val subscriptionUserAgentLabel: String
    val subscriptionUserAgentSubtitle: String
    val globalEngineLabel: String
    val globalEngineSubtitle: String
    val globalEngineAuto: String
    val telemostCookiesDescription: String
    val useTelemostCookies: String
    val useTelemostCookiesSubtitle: String
    val hideTunTitle: String
    val hideTunSubtitle: String
    val hideTunDisclaimer: String
    val hideModuleRebootTitle: String
    val hideModuleRebootMessage: String
    val rebootNow: String
    val rebootLater: String
    val hideModuleInstalled: String
    val hideModuleActive: String
    val hideModuleFailed: String
    val hideModuleDisabled: String
    val shareHotspotTitle: String
    val shareHotspotSubtitle: String
    val shareHotspotDisclaimer: String
    val rootGranted: String
    val rootDenied: String
    val telemostCookieHeader: String
    val cookiesLoaded: String
    val cookiesReadFailed: String
    val loadFromFile: String

    // Notification / toasts
    val notifConnected: String
    fun notifConnectedMode(mode: String): String
    val notifWaitingNetwork: String
    val notifReconnecting: String
    val notifWaitingTransport: String
    val notifConnecting: String
    val notifAddLocation: String
    val notifAddProxy: String
    val notifAddVkLink: String
    val notifConnectionFailed: String
    val notifTunnelFailed: String
    val notifVpnTunnelError: String
    val notifSplitTunnelError: String
    val notifStop: String
    val noFileSelected: String
    val qrImported: String
    fun cannotOpenFilePicker(msg: String): String
    val configCopied: String
    val copied: String
    val qrTooLarge: String

    // VK call link dialog
    val vkCallLink: String
    fun vkCallLinkBody(name: String): String
    val next: String
    val later: String
    val download: String
    val updateAvailable: String
    val sizeUnknown: String

    // Logs detail
    val noLogEntries: String
    fun logEntriesCount(n: Int): String

    // Location settings
    val locationSettingsTitle: String
    val proxyLinkOrConfig: String
    val proxyLink: String
    val fieldName: String
    val locationNamePlaceholder: String
    val engineSection: String
    val proxySection: String
    val proxySectionSubtitle: String
    // Standard/Chain cascade: a main proxy (always on) + an optional second proxy chained on top.
    val mainProxySection: String
    val additionalProxySection: String
    val additionalProxySubtitle: String
    val enableAdditionalProxy: String
    val freeturnTransportSection: String
    val freeturnTransportSubtitle: String
    val wireguardSubtitle: String
    val proxyOverVkturn: String
    val proxyOverVkturnSubtitle: String
    val enableProxy: String
    val vkCallLinksSection: String
    val vkCallLinksSubtitle: String
    fun vkCallLinkNumbered(n: Int): String
    val additionalCalls: String
    val coreSection: String
    val coreSubtitle: String
    val coreSubtitleXrayOnly: String
    val connectionType: String
    val vp8Options: String
    val vp8OptionsSubtitle: String

    // Snackbars
    fun subscriptionsUpdatedCount(n: Int): String
    val subscriptionsUpdated: String
    val subscriptionDeleted: String
    val subscriptionsDeleted: String
    val configsDeleted: String

    // Home location picker
    val customLocations: String
    val addRelaySetup: String
    val importHint: String
    val importedFromClipboard: String

    // Ping / connectivity
    val pingOffline: String
    val pingOnline: String
    val pingChecking: String
    val pingVerify: String
    val connectivityCheck: String

    // Ping settings (how inbounds are probed)
    val pingSettings: String
    val pingSettingsSubtitle: String
    val pingMethod: String
    val pingTarget: String
    val pingTargetHint: String
    val savePingResultsTitle: String
    val savePingResultsSubtitle: String
    val showAliveCountTitle: String
    val showAliveCountSubtitle: String
    val pingThreadsTitle: String
    val pingThreadsSubtitle: String
    val pingModeAuto: String
    val pingModeTcp: String
    val pingModeIcmp: String
    val pingModeProxyGet: String
    val pingModeProxyHead: String
    val pingResultLabel: String
    val pingResultTime: String
    val pingResultIcon: String

    // QR scanner
    val scanQrTitle: String
    val readyToScan: String
    val subscriptionOrLocationUri: String
    val cameraPermissionDenied: String
    val cameraUnavailable: String

    // Updates status / share
    val upToDate: String
    fun latestAlreadyDownloaded(channel: String): String
    fun channelUpdateAvailable(channel: String, version: String): String
    fun checkingChannel(channel: String): String
    val updateServiceUnavailable: String
    val updateCheckFailed: String
    val allowInstallUpdates: String
    val locationQr: String
    val subscriptionQr: String
    val subscriptionUpdated: String
    val subscriptionNotUpdated: String
    /** Subscription end-date line, e.g. "до 03.05.2099 20:59:00 · осталось 26630 дн.". */
    fun subscriptionExpiry(dateTime: String, daysLeft: Long): String
    /** Auto-refresh interval taken from the subscription, e.g. "Обновление каждые 6 ч". */
    fun subscriptionEvery(hours: Int): String
    /** Second header line showing the last successful refresh, e.g. "обновлена 05.06.2026 14:30:00". */
    fun subscriptionUpdatedAt(dateTime: String): String
    /** Optional expiry line under the refresh line, e.g. "до 03.05.2099". */
    fun subscriptionUntil(date: String): String
    /** Accessibility label for the red "expiring soon" warning badge. */
    val subscriptionExpiringSoon: String
    /** Full expiry detail shown when tapping the warning badge, e.g. "до 06.06.2026 14:30:00 · через 1 дн.". */
    fun subscriptionExpiryFull(dateTime: String, daysLeft: Long): String
    /** Banner above the nav bar when a newer GitHub release exists. */
    val updateBannerTitle: String
    val updateBannerAction: String
    /** Offer-sheet button: download the APK by hand from the GitHub release page. */
    val updateManual: String

    // Connect blocked reasons
    val addLocationFirst: String
    val completeActiveLocationFirst: String
    val addValidLocationFirst: String

    // Custom-location fields & advanced toggles
    val encryptionKey: String
    val serverHost: String
    val transportToRelay: String
    val modeTunnelPayload: String
    val obfuscationProfile: String
    val obfuscationKey: String
    val streamsParallel: String
    val bondingMultipath: String
    val privateKey: String
    val peerPublicKey: String
    val addressField: String
    val listenPort: String
    val allowedIps: String
    val muxMultiplex: String
    val muxProtocol: String
    val maxStreamsField: String
    val sniffDestination: String
    val tlsFragmentXray: String
    val coreAuto: String
    fun advancedCoreSettings(core: String): String
}

object RuStrings : Strings {
    override val languageName = "Русский"
    override val navConnection = "Подключение"
    override val navSettings = "Настройки"
    override val connectionTime = "Время подключения"
    override val refreshSubscriptions = "Обновить подписки"
    override val configurations = "КОНФИГУРАЦИИ"
    override val labelSetup = "НАСТРОИТЬ"
    override val labelStop = "СТОП"
    override val labelStart = "СТАРТ"
    override val addCustomLocation = "Добавить локацию"
    override val addSubscription = "Добавить подписку"
    override val groupPinToTop = "Закрепить наверху"
    override val groupUnpinFromTop = "Открепить"
    override val groupSortByPing = "Сортировать по пингу"
    override val groupAutoUpdate = "Автообновление"
    override val visitSubscriptionPage = "Посетить страницу подписки"
    override val telegramProxyTitle = "Прокси Telegram (WARP)"
    override val telegramProxySubtitle = "Фоновый SOCKS5 через Cloudflare WARP для Telegram. При первом включении нужен интернет — затем пропишите адрес в настройках прокси Telegram."
    override val telegramProxyGenerating = "Генерация конфигурации… (нужен интернет)"
    override val telegramProxyRunning = "Активен"
    override val telegramProxyError = "Ошибка"
    override val groupDelete = "Удалить подписку"
    override val delete = "Удалить"
    override val cancel = "Отмена"
    override fun selectedCount(count: Int) = "Выбрано: $count"
    override val deleteSubscriptionTitle = "Удалить подписку?"
    override fun deleteSubscriptionMessage(count: Int) =
        "Будут удалены все конфигурации этой подписки ($count)."
    override val deleteAllSubscriptionsTitle = "Удалить все подписки?"
    override val deleteAllSubscriptionsMessage =
        "Будут удалены все конфигурации из подписок. Собственные локации сохранятся."
    override val deleteAllConfigsTitle = "Удалить все конфигурации?"
    override val deleteAllConfigsMessage =
        "Будут удалены все конфигурации и подписки. Это действие необратимо."
    override val menuDeleteAllSubscriptions = "Удалить все подписки"
    override val menuDeleteAllConfigs = "Удалить все конфигурации"
    override val menuDeleteUnreachable = "Удалить недоступные"
    override val menuDeleteDuplicates = "Удалить дубликаты"
    override val createFolder = "Создать группу"
    override val folderEmpty = "Группа пуста — добавьте локации через долгое нажатие"
    override val folderRename = "Переименовать"
    override val folderDelete = "Удалить группу"
    override val folderDeleteTitle = "Удалить группу?"
    override val folderDeleteMessage = "Группа будет удалена. Локации и подписки из неё не удаляются — они вернутся в общий список."
    override val newFolderTitle = "Новая группа"
    override val folderNameHint = "Название группы"
    override val moveToFolder = "В группу"
    override val removeFromFolder = "Убрать из групп"
    override val chooseFolderTitle = "Выберите группу"
    override val newFolderOption = "Новая группа…"
    override val folderCreate = "Создать"
    override val folderSave = "Сохранить"
    override val deleteUnreachableTitle = "Удалить недоступные?"
    override val deleteUnreachableMessage =
        "Будут удалены собственные локации, которые при последнем пинге оказались недоступны. Подписки не затрагиваются."
    override val deleteDuplicatesTitle = "Удалить дубликаты?"
    override val deleteDuplicatesMessage =
        "Будут удалены повторяющиеся собственные локации с одинаковой конфигурацией (останется по одной). Подписки не затрагиваются."
    override fun unreachableDeleted(count: Int) = "Удалено недоступных: $count"
    override fun duplicatesDeleted(count: Int) = "Удалено дубликатов: $count"
    override val noUnreachableFound = "Недоступные локации не найдены"
    override val noDuplicatesFound = "Дубликаты не найдены"
    override val settings = "Настройки"
    override val dynamicTheme = "Динамическая тема"
    override val dynamicThemeOn = "Системные цвета Android"
    override val dynamicThemeOff = "Цвета YPtun"
    override val routing = "Маршрутизация"
    override val routingSubtitle = "Обход LAN/России, блокировка рекламы, домены"
    override val trafficSettings = "Настройки трафика"
    override val trafficSettingsSubtitle = "DNS, мультиплексирование, стратегия доменов"
    override val connectionSettings = "Настройки подключения"
    override val connectionSettingsSubtitle = "Режим, SOCKS5-прокси, маршрутизация приложений"
    override val subscriptionsSharing = "Подписки и обмен"
    override val updates = "Обновления"
    override val applicationSettings = "Настройки приложения"
    override val applicationSettingsSubtitle = "Автоподключение, подтверждение удаления"
    override val urlSchemes = "Схемы URL"
    override val urlSchemesSubtitle = "Deep-link импорт и управление"
    override val logs = "Журнал"
    override val logsSubtitle = "Диагностика и экспорт"
    override val info = "ИНФОРМАЦИЯ"
    override fun version(v: String) = "Версия: $v"
    override fun xrayVersion(v: String) = "Xray: $v"
    override fun singboxVersion(v: String) = "sing-box: $v"
    override fun vkturnVersion(v: String) = "VK-TURN (freeturn): $v"
    override fun olcrtcVersion(v: String) = "OLCRTC: $v"
    override fun domainStrategyName(v: String) = when (v) {
        "prefer_ipv4" -> "Предпочитать IPv4"
        "prefer_ipv6" -> "Предпочитать IPv6"
        "ipv4_only" -> "Только IPv4"
        "ipv6_only" -> "Только IPv6"
        else -> v
    }
    override fun hwid(v: String) = "HWID: $v"
    override val community = "Сообщество"
    override val howToConnect = "Как подключиться?"
    override val autoConnectTitle = "Автоподключение при запуске"
    override val autoConnectSubtitle = "Подключаться к выбранному конфигу при открытии приложения"
    override val energySaverTitle = "Режим энергоэффективности"
    override val energySaverSubtitle = "Меньше расход батареи: реже проверки соединения, без журнала. Может замедлить авто-восстановление; применяется при следующем подключении"
    override val confirmDeleteTitle = "Подтверждение удаления"
    override val confirmDeleteSubtitle = "Запрашивать подтверждение перед удалением подписок и конфигураций"
    override val language = "Язык"
    override val dns = "DNS"
    override val remoteDnsLabel = "Удалённый DNS (через прокси)"
    override val directDnsLabel = "Прямой DNS (bootstrap)"
    override val domainStrategy = "Доменная стратегия"
    override val multiplexing = "Мультиплексирование"
    override val useMux = "Использовать Mux"
    override val useMuxSubtitle = "Быстрее, но может снизить стабильность соединения"
    override val muxMaxConnections = "Макс. соединений (1–64)"
    override val fragmentation = "Фрагментирование (Xray)"
    override val useFragment = "Включить фрагментирование"
    override val useFragmentSubtitle = "Дробит TLS-пакеты для обхода DPI. Работает на ядре Xray."
    override val fragmentPackets = "Пакеты (tlshello или 1-3)"
    override val fragmentLength = "Длина (напр. 50-100)"
    override val fragmentInterval = "Интервал, мс (напр. 10-20)"
    override val mtuLabel = "MTU (1280–9000)"
    override val blockRuDomains = "Блокировать РФ-домены"
    override val blockRuDomainsSubtitle = "Встроенный список доменов РФ → 0.0.0.0. Работает на ядре Xray."
    override val fakeDnsTitle = "FakeDNS"
    override val fakeDnsSubtitle = "Подменяет ответы DNS фейковыми IP — приложения не видят реальные адреса, домен резолвится за прокси. Конфиг подписки со своим fakedns используется как есть."
    override val saveAndApply = "Сохранить и применить"
    override val routingTitle = "Маршрутизация и правила"
    override val bypassLan = "Обход LAN"
    override val bypassLanSubtitle = "Локальные/приватные адреса идут напрямую"
    override val showSystemApps = "Системные приложения"
    override val bypassRussia = "Обход России"
    override val bypassRussiaSubtitle = "RU-сайты и IP идут напрямую (geoip + geosite)"
    override val blockAds = "Блокировка рекламы"
    override val blockAdsSubtitle = "Блокировать рекламные и трекинговые домены"
    override val directDomains = "Прямые домены"
    override val blockedDomains = "Заблокированные домены"
    override val domainsPlaceholder = "example.com, по одному на строку"
    override val presets = "Пресеты"
    override val presetRuDirect = "Россия напрямую"
    override val presetAdsBlock = "Блок рекламы"
    override val presetRuAds = "Россия + без рекламы"
    override val presetAllVpn = "Всё через VPN"
    override val presetReset = "Сбросить"
    override val sbRoutingAdvanced = "Маршрутизация sing-box (продвинутое)"
    override val sbRoutingAdvancedDesc =
        "Дословный JSON sing-box. Выполняется до переключателей выше. " +
            "Пример правил: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"
    override val sbRouteRulesLabel = "route.rules (JSON-массив)"
    override val routingTabSimple = "Простой"
    override val routingTabRules = "Правила"
    override val routingTabHapp = "Профили"
    override val routingShareAllButton = "Импорт / экспорт всех настроек"
    override val routingShareAllTitle = "Настройки маршрутизации"
    override val routingShareAllDesc = "Скопируйте все профили одной ссылкой yptun://routing или вставьте её, чтобы импортировать."
    override val routingExportCopy = "Скопировать ссылку"
    override val routingImportPaste = "Вставьте yptun://routing"
    override val routingImportApply = "Импортировать"
    override val routingImportInvalid = "Неверная ссылка"
    override val routingRulesTitle = "Правила маршрутизации"
    override val routingRulesDesc = "Свой список правил в стиле v2rayNG. Работает на движке sing-box; правила применяются по порядку сверху вниз."
    override val routingRulesEmpty = "Правил пока нет. Добавьте первое."
    override val routingRuleAdd = "Добавить правило"
    override val routingRuleEdit = "Правило"
    override val routingRuleDelete = "Удалить правило"
    override val routingRuleName = "Название маршрута"
    override val routingRuleOutbound = "Исходящий"
    override val routingRuleDomains = "Домены"
    override val routingRuleIps = "IP / geoip"
    override val routingRuleSource = "Источник (IP)"
    override val routingRulePort = "Порт"
    override val routingRuleSourcePort = "Порт источника"
    override val routingRuleNetwork = "Сеть"
    override val routingRuleNetworkType = "Тип сети"
    override val netTypeWifi = "Wi-Fi"
    override val netTypeCellular = "Сотовая"
    override val netTypeEthernet = "Ethernet"
    override val netTypeOther = "Другое"
    override val routingRuleProtocol = "Протокол"
    override val routingRuleClient = "Клиент (TLS)"
    override val routingRuleMetered = "Платная сеть"
    override val routingRuleClashMode = "Режим Clash"
    override val routingRuleApps = "Приложения (пакеты)"
    override val routingRulePackageRegex = "Регулярное выражение пакетов"
    override val routingRuleEnabled = "Включено"
    override val routingRuleNetworkAny = "Любая"
    override val routingOutProxy = "Через прокси"
    override val routingOutDirect = "Напрямую"
    override val routingOutBlock = "Блокировать"
    override val routingRuleAction = "Действие маршрутизации"
    override val routingActionRoute = "Маршрут"
    override val routingActionRouteOptions = "Опции маршрута"
    override val routingActionSniff = "Сниффинг"
    override val routingActionResolve = "Резолвинг"
    override val routingActionHijackDns = "Перехват DNS"
    override val routingActionReject = "Отклонить"
    override val sbRuleSetLabel = "rule_set (JSON-массив)"
    override val sbInvalidJsonArray = "Некорректный JSON-массив"
    override val routingProfiles = "Профили маршрутизации"
    override val routingProfilesSubtitle = "Happ-совместимые правила — глобально или для отдельной локации"
    override val routingProfileGlobal = "Глобальный профиль"
    override val routingProfileNone = "Без профиля"
    override val routingProfileGlobalHint = "Применяется ко всем подключениям, если у локации не задан свой профиль"
    override val routingProfileAdd = "Создать профиль"
    override val routingProfileImportLink = "Импорт по ссылке happ://"
    override val routingProfilePasteHint = "happ://routing/add/…"
    override val routingProfileNewName = "Новый профиль"
    override val routingProfileName = "Название"
    override val routingProfileDelete = "Удалить профиль"
    override val routingProfileShare = "Поделиться ссылкой happ://"
    override val routingProfileGlobalProxy = "Весь трафик через прокси"
    override val routingProfileGlobalProxyDesc = "Иначе — напрямую, кроме списков «через прокси»"
    override val routingProfileRouteOrder = "Порядок правил"
    override val routingProfileDomainStrategy = "Стратегия доменов"
    override val routingExpert = "Экспертные настройки"
    override val routingExpertDesc = "Тонкая настройка маршрутизации отдельно для Xray и sing-box"
    override val routingExpertInherit = "По умолчанию"
    override val routingExpertSniffing = "Анализ домена (sniffing)"
    override val routingExpertSniffingDesc = "Нужен, чтобы правила domain:/geosite: срабатывали по SNI/Host. Без него .ru-правила не действуют."
    override val routingExpertRouteOnly = "Только для маршрутизации (routeOnly)"
    override val routingExpertRouteOnlyDesc = "Определять домен только для правил, но соединяться по исходному адресу."
    override val routingExpertResolve = "Резолвить домен (resolve)"
    override val routingExpertResolveDesc = "Преобразовывать домен в IP, чтобы срабатывали geoip:/ip-правила."
    override val routingProxySites = "Через прокси: сайты"
    override val routingProxyIp = "Через прокси: IP"
    override val routingDirectSites = "Напрямую: сайты"
    override val routingDirectIp = "Напрямую: IP"
    override val routingBlockSites = "Блокировать: сайты"
    override val routingBlockIp = "Блокировать: IP"
    override val routingSelectorsHint = "geosite:ru, domain:vk.com — по одному на строку"
    override val routingIpSelectorsHint = "geoip:ru, 10.0.0.0/8 — по одному на строку"
    override val routingGeoDatabases = "Геобазы (geoip.dat / geosite.dat)"
    override val routingGeoDatabasesDesc = "Нужны для селекторов geoip:/geosite: на ядре Xray. Загружаются из указанных ниже источников."
    override val routingGeoUpdate = "Обновить геобазы"
    override val routingGeoUpdating = "Загрузка…"
    override val routingGeoNever = "Не загружены"
    override val routingGeoipUrl = "URL geoip.dat"
    override val routingGeositeUrl = "URL geosite.dat"
    override val routingProfileSaved = "Профиль сохранён"
    override val routingProfileImported = "Профиль маршрутизации импортирован"
    override val routingProfileInvalidLink = "Неверная ссылка маршрутизации"
    override val routingProfileEmpty = "Профилей пока нет"
    override fun routingGeoUpdated(ts: String) = "Обновлено: $ts"
    override fun routingProfileRuleCount(n: Int) = "правил: $n"
    override val locationRoutingProfile = "Профиль маршрутизации"
    override val locationRoutingGlobalDefault = "Глобальный (по умолчанию)"
    override val urlSchemeAddConfig = "ДОБАВИТЬ КОНФИГ"
    override val urlSchemeAddSubscription = "ДОБАВИТЬ ПОДПИСКУ"
    override val urlSchemeControl = "УПРАВЛЕНИЕ"
    override val copy = "Копировать"
    override val addConnection = "Добавить подключение"
    override val addConnectionSubtitle = "Подписка или своя локация"
    override val scanQr = "Сканировать QR-код"
    override val scanQrSubtitle = "Подписка или olcrtc URI"
    override val pasteLink = "Вставить ссылку или URI"
    override val pasteLinkSubtitle = "Подписка, olcrtc или конфигурация sing-box"
    override val importFile = "Импорт из файла"
    override val importFileSubtitle = "Подписка, olcrtc или конфигурация sing-box"
    override val updateSubscriptionsAction = "Обновить подписки"
    override val updateSubscriptionsSubtitle = "Обновить локации импортированных подписок"
    override val createCustomLocation = "Создать собственную локацию"
    override val createCustomLocationSubtitle = "Комната, ключ, провайдер и транспорт"
    override val connectionMode = "Режим подключения"
    override val socks5Proxy = "SOCKS5-прокси"
    override val splitTunneling = "Раздельное туннелирование"
    override val routingBehavior = "Поведение маршрутизации"
    override val appsUsingYptun = "Приложения через YPtun"
    override val bypassedApps = "Приложения в обход"
    override val search = "Поиск"
    override val appListSection = "Список приложений"
    override val endpointSection = "Адрес"
    override val credentialsSection = "Учётные данные"
    override val applicationLogsTitle = "Журнал"
    override val updatesTitle = "Обновления"
    override fun currentVersion(v: String) = "Текущая версия $v"
    override val checkInterval = "Интервал проверки"
    override val copyFullConfig = "Скопировать конфигурацию"
    override val currentConfig = "Текущая конфигурация"
    override val subscriptionsSection = "Подписки"
    override val noSubscriptions = "Нет подписок"
    override val noSubscriptionsSubtitle = "Импортированные HTTPS-подписки появятся здесь."
    override val listenAddress = "Адрес прослушивания"
    override val listenAddressRequired = "Укажите адрес"
    override val savingRestarts = "Сохранение перезапустит активное подключение"
    override val unsavedChange = "Есть несохранённые изменения"
    override val port = "Порт"
    override val portRequired = "Укажите порт"
    override val username = "Имя пользователя"
    override val password = "Пароль"
    override val generatedPassword = "Сгенерированный пароль"
    override val passwordRequired = "Укажите пароль"
    override val usernameRequired = "Укажите имя пользователя"
    override val regeneratePassword = "Сгенерировать пароль"
    override val save = "Сохранить"
    override val share = "Поделиться"
    override val searchApps = "Поиск приложений"
    override val noApps = "Приложений нет"
    override val noMatchingApps = "Ничего не найдено"
    override val noAppsHint = "Установите приложения, чтобы настроить правила."
    override val noMatchHint = "Попробуйте другое имя или пакет."
    override val noAppListNeeded = "Список приложений не нужен"
    override val everyAppSameRoute = "Все приложения идут одним маршрутом TUN"
    override val lastCheck = "Последняя проверка"
    override val notCheckedYet = "Ещё не проверялось"
    override val checkNow = "Проверить сейчас"
    override val fullTunnel = "Полный туннель"
    override val localSocksProxy = "Локальный SOCKS5-прокси"
    override val systemVpnInterface = "Системный VPN-интерфейс"
    override val localSocksEndpoint = "Локальный SOCKS-эндпоинт"
    override val allApps = "Все приложения"
    override val selectedAppsOnly = "Только выбранные"
    override val bypassSelected = "Обход выбранных"
    override val everyAppUsesYptun = "Все приложения через YPtun"
    override val chooseAppsUseYptun = "Выберите приложения через YPtun"
    override val chooseAppsBypass = "Выберите приложения в обход YPtun"
    override fun appsCount(n: Int) = "$n ${ruPlural(n, "приложение", "приложения", "приложений")}"
    override fun useYptunCount(n: Int) = "${appsCount(n)} через YPtun"
    override fun onlyUseYptunCount(n: Int) = "Только ${appsCount(n)} через YPtun"
    override fun onlyCount(n: Int) = "Только ${appsCount(n)}"
    override fun bypassedCount(n: Int) = "${appsCount(n)} в обход"
    override fun bypassYptunCount(n: Int) = "${appsCount(n)} в обход YPtun"
    override val allAppsUseYptunStatus = "Все приложения через YPtun"
    override val noAppsSelectedStatus = "Приложения не выбраны"
    override val noAppsBypassStatus = "Нет приложений в обход YPtun"
    override val selectionRequired = "Требуется"
    override val noBypassedAppsValue = "Нет приложений в обход"
    override val savedForTunMode = "Сохранено для режима TUN"
    override val appliesWhenSettingsClose = "Применится при закрытии настроек"
    override val tunModeRoutingRule = "Правило маршрутизации режима TUN"
    override val bypassRuApps = "Обход RU-приложений"
    override val bypassRuOn = "Обход RU включён"
    override val ruBypassAccuracy = "Автоопределение может быть неточным."
    override val ruBypassNoMatches = "Нет подходящих установленных приложений"
    override fun ruBypassMatchedByPackage(n: Int) = "${appsCount(n)} по пакету"
    override val ruBypassNoneSelected = "RU-приложения не выбраны"
    override fun ruBypassAlreadySelected(n: Int) = "${appsCount(n)} уже выбрано"
    override fun ruBypassAutoBypassed(n: Int) = "${appsCount(n)} авто-обход"
    override fun ruBypassAutoManual(auto: Int, manual: Int) = "$auto авто · $manual вручную"
    override val releaseChannelLabel = "Релиз"
    override fun downloadingAsset(name: String) = "Загрузка $name…"
    override fun downloadFailed(error: String) = "Ошибка загрузки: $error"
    override fun installingAsset(name: String) = "Установка $name"
    override fun hoursShort(n: Int) = "$n ч"
    override val themeColor = "Цвет темы"
    override val elementColor = "Цвет элементов"
    override val textColor = "Цвет текста"
    override val customColorRgb = "Произвольный цвет (RGB)"
    override val qrShare = "QR / поделиться"
    override val refresh = "Обновить"
    override val subscriptionWebPage = "Страница"
    override val subscriptionSupport = "Поддержка"
    override val experimental = "Экспериментальные"
    override val experimentalSubtitle = "Cookies Telemost и прочее"
    override val experimentalUnlocked = "Экспериментальные настройки разблокированы"
    override val notifSpeed = "Скорость в уведомлении"
    override val notifSpeedSubtitle = "Показывать загрузку ↓ и отдачу ↑ в шторке"
    override val showSubscriptionExpiryTitle = "Показывать срок подписки"
    override val showSubscriptionExpirySubtitle = "Под датой обновления выводить «до дд.мм.гггг»"
    override val subscriptionUserAgentLabel = "User-Agent подписки"
    override val subscriptionUserAgentSubtitle = "Happ/1.0 запрашивает полный конфиг (FakeDNS, dns.hosts); YPtun — обычно только ссылки"
    override val globalEngineLabel = "Движок для VLESS (глобально)"
    override val globalEngineSubtitle = "Ядро для VLESS и похожих транспортов, когда в настройках сервера выбрано «Авто». Настройка сервера важнее этой. xhttp всегда использует Xray."
    override val globalEngineAuto = "Авто"
    override val telemostCookiesDescription =
        "Cookies авторизованного аккаунта Яндекса (заголовок Cookie, напр. " +
            "«Session_id=…; yandexuid=…») — для приватных конференций. Запустить кастомное ядро " +
            "отдельным бинарём на Android невозможно, поэтому эта функция встроена в штатное ядро."
    override val useTelemostCookies = "Использовать cookies Telemost"
    override val useTelemostCookiesSubtitle = "Подставлять cookies при подключении к Telemost"
    override val hideTunTitle = "Скрывать интерфейс tun0 (root)"
    override val hideTunSubtitle = "Устанавливает Zygisk-модуль, скрывающий VPN-интерфейс от других приложений. Нужен root (Magisk) и перезагрузка."
    override val hideTunDisclaimer = "Внимание: функция использует root-доступ (su). Автор ПО не несёт ответственности за любые повреждения, потерю данных или причинение вреда устройству вследствие использования root."
    override val hideModuleRebootTitle = "Требуется перезагрузка"
    override val hideModuleRebootMessage = "Модуль скрытия установлен. Чтобы скрытие заработало, нужно перезагрузить устройство. Перезагрузить сейчас?"
    override val rebootNow = "Перезагрузить"
    override val rebootLater = "Позже"
    override val hideModuleInstalled = "Модуль скрытия установлен"
    override val hideModuleActive = "Скрытие уже активно"
    override val hideModuleFailed = "Не удалось установить модуль (нужен root и Zygisk)"
    override val hideModuleDisabled = "Скрытие отключено (применится после перезагрузки)"
    override val shareHotspotTitle = "Раздавать VPN на точку доступа (root)"
    override val shareHotspotSubtitle = "Пропускает трафик устройств, подключённых к вашей точке доступа, через VPN. Включите точку доступа и подключитесь к VPN. Требуется root."
    override val shareHotspotDisclaimer = "Внимание: функция использует root-доступ (su) и меняет правила маршрутизации/iptables. Автор ПО не несёт ответственности за любые повреждения."
    override val rootGranted = "Root-доступ получен"
    override val rootDenied = "Root-доступ не предоставлен"
    override val telemostCookieHeader = "Заголовок Cookie для Telemost"
    override val cookiesLoaded = "Cookies загружены"
    override val cookiesReadFailed = "Не удалось прочитать файл"
    override val loadFromFile = "Загрузить из файла (cookies.txt)"
    override val notifConnected = "Подключено"
    override fun notifConnectedMode(mode: String) = "$mode подключён"
    override val notifWaitingNetwork = "Ожидание сети…"
    override val notifReconnecting = "Переподключение…"
    override val notifWaitingTransport = "Ожидание транспорта…"
    override val notifConnecting = "Подключение…"
    override val notifAddLocation = "Сначала добавьте локацию"
    override val notifAddProxy = "Сначала добавьте прокси"
    override val notifAddVkLink = "Сначала добавьте ссылку на звонок VK"
    override val notifConnectionFailed = "Не удалось подключиться"
    override val notifTunnelFailed = "Сбой туннеля"
    override val notifVpnTunnelError = "Ошибка VPN-туннеля"
    override val notifSplitTunnelError = "Ошибка раздельного туннелирования"
    override val notifStop = "Стоп"
    override val noFileSelected = "Файл не выбран"
    override val qrImported = "QR-код импортирован"
    override fun cannotOpenFilePicker(msg: String) = "Не удалось открыть выбор файла: $msg"
    override val configCopied = "Конфигурация скопирована"
    override val copied = "Скопировано"
    override val qrTooLarge = "Конфигурация слишком большая для QR-кода. Используйте «Копировать» или «Поделиться»."
    override val vkCallLink = "Ссылка на звонок VK"
    override fun vkCallLinkBody(name: String) =
        "Вставьте ссылку-приглашение VK Звонков для «$name». Можно вставить несколько ссылок " +
            "(по одной на строку), чтобы распределить туннель между звонками и повысить скорость."
    override val next = "Далее"
    override val later = "Позже"
    override val download = "Скачать"
    override val updateAvailable = "Доступно обновление"
    override val sizeUnknown = "Размер неизвестен"
    override val noLogEntries = "Нет записей"
    override fun logEntriesCount(n: Int) = "Записей: $n"
    override val locationSettingsTitle = "Настройки локации"
    override val proxyLinkOrConfig = "Ссылка прокси или конфигурация"
    override val proxyLink = "Ссылка прокси"
    override val fieldName = "Название"
    override val locationNamePlaceholder = "Название локации"
    override val engineSection = "Движок"
    override val proxySection = "Прокси"
    override val proxySectionSubtitle =
        "Вставьте ссылку vless/vmess/trojan/ss, конфигурацию AmneziaWG или JSON-исходящего sing-box"
    override val mainProxySection = "Основной прокси"
    override val additionalProxySection = "Второй прокси"
    override val additionalProxySubtitle =
        "Поверх основного: трафик → основной прокси → этот"
    override val enableAdditionalProxy = "Дополнительный прокси (каскад)"
    override val freeturnTransportSection = "Транспорт Freeturn"
    override val freeturnTransportSubtitle = "Адрес ретранслятора VK TURN и обфускация"
    override val wireguardSubtitle = "Туннель sing-box подключается через локальный слушатель freeturn"
    override val proxyOverVkturn = "Прокси поверх VK-TURN (необязательно)"
    override val proxyOverVkturnSubtitle =
        "Добавить прокси vless/vmess/trojan/ss поверх туннеля WireGuard"
    override val enableProxy = "Включить прокси"
    override val vkCallLinksSection = "Ссылки на звонки VK"
    override val vkCallLinksSubtitle =
        "Личная ссылка-приглашение VK Звонков (обязательно). Можно добавить до 5 — каждый " +
            "дополнительный звонок прибавляет пропускную способность (туннель распределяется между ними)."
    override fun vkCallLinkNumbered(n: Int) = "Ссылка на звонок VK $n (необязательно)"
    override val additionalCalls = "Дополнительные звонки"
    override val coreSection = "Ядро"
    override val coreSubtitle = "«Авто» выбирает Xray для xhttp, иначе sing-box"
    override val coreSubtitleXrayOnly = "xhttp — только Xray, sing-box недоступен"
    override val connectionType = "Тип подключения"
    override val vp8Options = "Параметры VP8"
    override val vp8OptionsSubtitle = "Тонкая настройка производительности потока"
    override fun subscriptionsUpdatedCount(n: Int) = "Подписки обновлены: $n"
    override val subscriptionsUpdated = "Подписки обновлены"
    override val subscriptionDeleted = "Подписка удалена"
    override val subscriptionsDeleted = "Подписки удалены"
    override val configsDeleted = "Конфигурации удалены"
    override val customLocations = "Свои локации"
    override val addRelaySetup = "Настройка подключения"
    override val importHint = "Сканируйте QR, вставьте URI или импортируйте файл"
    override val importedFromClipboard = "Импортировано из буфера обмена"
    override val pingOffline = "Не в сети"
    override val pingOnline = "В сети"
    override val pingChecking = "Проверка…"
    override val pingVerify = "Нажмите для проверки доступности"
    override val connectivityCheck = "Проверка соединения"
    override val pingSettings = "Пинг"
    override val pingSettingsSubtitle = "Способ проверки инбаундов"
    override val pingMethod = "Способ пинга"
    override val pingTarget = "Сайт для пинга"
    override val pingTargetHint = "Например: https://google.com"
    override val savePingResultsTitle = "Сохранять результаты пингов"
    override val savePingResultsSubtitle =
        "Показывать последние результаты при повторном открытии приложения"
    override val showAliveCountTitle = "Счётчик доступных серверов"
    override val showAliveCountSubtitle =
        "В шапке подписки показывать «живые/всего» по последнему пингу"
    override val pingThreadsTitle = "Потоки пинга"
    override val pingThreadsSubtitle = "Сколько локаций пинговать одновременно (1–20)"
    override val pingModeAuto = "Авто"
    override val pingModeTcp = "TCP"
    override val pingModeIcmp = "ICMP"
    override val pingModeProxyGet = "Через прокси GET"
    override val pingModeProxyHead = "Через прокси HEAD"
    override val pingResultLabel = "Результат пинга"
    override val pingResultTime = "Время"
    override val pingResultIcon = "Значок"
    override val scanQrTitle = "Сканирование QR"
    override val readyToScan = "Готово к сканированию"
    override val subscriptionOrLocationUri = "Подписка или URI локации"
    override val cameraPermissionDenied = "Доступ к камере запрещён"
    override val cameraUnavailable = "Камера недоступна"
    override val upToDate = "Установлена последняя версия"
    override fun latestAlreadyDownloaded(channel: String) = "Последняя сборка $channel уже скачана"
    override fun channelUpdateAvailable(channel: String, version: String) = "Доступно обновление $channel: $version"
    override fun checkingChannel(channel: String) = "Проверка $channel…"
    override val updateServiceUnavailable = "Служба обновлений недоступна"
    override val updateCheckFailed = "Не удалось проверить обновления"
    override val allowInstallUpdates = "Разрешите YPtun устанавливать обновления и нажмите «Скачать» снова"
    override val locationQr = "QR локации"
    override val subscriptionQr = "QR подписки"
    override val subscriptionUpdated = "Подписка обновлена"
    override val subscriptionNotUpdated = "Подписка не обновлена"
    override fun subscriptionExpiry(dateTime: String, daysLeft: Long) =
        if (daysLeft < 0) "истекла $dateTime" else "до $dateTime"
    override fun subscriptionEvery(hours: Int) = "Обновление каждые $hours ч"
    override fun subscriptionUpdatedAt(dateTime: String) = "Обновлена $dateTime"
    override fun subscriptionUntil(date: String) = "до $date"
    override val subscriptionExpiringSoon = "Подписка скоро закончится"
    override fun subscriptionExpiryFull(dateTime: String, daysLeft: Long) = when {
        daysLeft < 0 -> "истекла $dateTime"
        daysLeft == 0L -> "до $dateTime · сегодня"
        else -> "до $dateTime · через $daysLeft дн."
    }
    override val updateBannerTitle = "Обновите приложение"
    override val updateBannerAction = "Обновить"
    override val updateManual = "Скачать с GitHub"
    override val addLocationFirst = "Сначала добавьте локацию"
    override val completeActiveLocationFirst = "Сначала завершите настройку активной локации"
    override val addValidLocationFirst = "Сначала добавьте корректную локацию"
    override val encryptionKey = "Ключ шифрования"
    override val serverHost = "Хост сервера"
    override val transportToRelay = "Транспорт (до TURN-ретранслятора)"
    override val modeTunnelPayload = "Режим (нагрузка туннеля)"
    override val obfuscationProfile = "Профиль обфускации"
    override val obfuscationKey = "Ключ обфускации"
    override val streamsParallel = "Потоки (параллельные ретрансляторы)"
    override val bondingMultipath = "Агрегация каналов (multipath)"
    override val privateKey = "Приватный ключ"
    override val peerPublicKey = "Публичный ключ пира"
    override val addressField = "Адрес"
    override val listenPort = "Порт прослушивания"
    override val allowedIps = "Разрешённые IP"
    override val muxMultiplex = "Mux (мультиплексирование)"
    override val muxProtocol = "Протокол Mux"
    override val maxStreamsField = "Макс. потоков"
    override val sniffDestination = "Определение назначения (sniff)"
    override val tlsFragmentXray = "Фрагментация TLS (анти-DPI, Xray)"
    override val coreAuto = "Авто"
    override fun advancedCoreSettings(core: String) = "Дополнительные настройки $core"
}

object EnStrings : Strings {
    override val languageName = "English"
    override val navConnection = "Connection"
    override val navSettings = "Settings"
    override val connectionTime = "Connection time"
    override val refreshSubscriptions = "Refresh subscriptions"
    override val configurations = "CONFIGURATIONS"
    override val labelSetup = "SETUP"
    override val labelStop = "STOP"
    override val labelStart = "START"
    override val addCustomLocation = "Add custom location"
    override val addSubscription = "Add subscription"
    override val groupPinToTop = "Pin to top"
    override val groupUnpinFromTop = "Unpin"
    override val groupSortByPing = "Sort by ping"
    override val groupAutoUpdate = "Auto-update"
    override val visitSubscriptionPage = "Visit subscription page"
    override val telegramProxyTitle = "Telegram proxy (WARP)"
    override val telegramProxySubtitle = "Background SOCKS5 over Cloudflare WARP for Telegram. First enable needs internet — then set the address in Telegram's proxy settings."
    override val telegramProxyGenerating = "Generating config… (internet required)"
    override val telegramProxyRunning = "Active"
    override val telegramProxyError = "Error"
    override val groupDelete = "Delete subscription"
    override val delete = "Delete"
    override val cancel = "Cancel"
    override fun selectedCount(count: Int) = "Selected: $count"
    override val deleteSubscriptionTitle = "Delete subscription?"
    override fun deleteSubscriptionMessage(count: Int) =
        "All configurations of this subscription ($count) will be removed."
    override val deleteAllSubscriptionsTitle = "Delete all subscriptions?"
    override val deleteAllSubscriptionsMessage =
        "All subscription configurations will be removed. Custom locations are kept."
    override val deleteAllConfigsTitle = "Delete all configs?"
    override val deleteAllConfigsMessage =
        "All configurations and subscriptions will be removed. This cannot be undone."
    override val menuDeleteAllSubscriptions = "Delete all subscriptions"
    override val menuDeleteAllConfigs = "Delete all configs"
    override val menuDeleteUnreachable = "Delete unreachable"
    override val menuDeleteDuplicates = "Delete duplicates"
    override val createFolder = "Create group"
    override val folderEmpty = "Group is empty — add locations via long-press"
    override val folderRename = "Rename"
    override val folderDelete = "Delete group"
    override val folderDeleteTitle = "Delete group?"
    override val folderDeleteMessage = "The group will be removed. Its locations and subscriptions are not deleted — they return to the main list."
    override val newFolderTitle = "New group"
    override val folderNameHint = "Group name"
    override val moveToFolder = "To group"
    override val removeFromFolder = "Remove from groups"
    override val chooseFolderTitle = "Choose a group"
    override val newFolderOption = "New group…"
    override val folderCreate = "Create"
    override val folderSave = "Save"
    override val deleteUnreachableTitle = "Delete unreachable?"
    override val deleteUnreachableMessage =
        "Custom locations that were unreachable on the last ping will be removed. Subscriptions are not touched."
    override val deleteDuplicatesTitle = "Delete duplicates?"
    override val deleteDuplicatesMessage =
        "Duplicate custom locations with identical configuration will be removed (one copy kept). Subscriptions are not touched."
    override fun unreachableDeleted(count: Int) = "Unreachable removed: $count"
    override fun duplicatesDeleted(count: Int) = "Duplicates removed: $count"
    override val noUnreachableFound = "No unreachable locations found"
    override val noDuplicatesFound = "No duplicates found"
    override val settings = "Settings"
    override val dynamicTheme = "Dynamic theme"
    override val dynamicThemeOn = "Using Android system colors"
    override val dynamicThemeOff = "Using YPtun colors"
    override val routing = "Routing"
    override val routingSubtitle = "Bypass LAN/Russia, block ads, custom domains"
    override val trafficSettings = "Traffic settings"
    override val trafficSettingsSubtitle = "DNS, multiplexing, domain strategy"
    override val connectionSettings = "Connection settings"
    override val connectionSettingsSubtitle = "Mode, SOCKS5 proxy, per-app routing"
    override val subscriptionsSharing = "Subscriptions & sharing"
    override val updates = "Updates"
    override val applicationSettings = "Application settings"
    override val applicationSettingsSubtitle = "Auto-connect, delete confirmation"
    override val urlSchemes = "URL schemes"
    override val urlSchemesSubtitle = "Deep-link import and control"
    override val logs = "Logs"
    override val logsSubtitle = "Diagnostics and export"
    override val info = "INFORMATION"
    override fun version(v: String) = "Version: $v"
    override fun xrayVersion(v: String) = "Xray: $v"
    override fun singboxVersion(v: String) = "sing-box: $v"
    override fun vkturnVersion(v: String) = "VK-TURN (freeturn): $v"
    override fun olcrtcVersion(v: String) = "OLCRTC: $v"
    override fun domainStrategyName(v: String) = when (v) {
        "prefer_ipv4" -> "Prefer IPv4"
        "prefer_ipv6" -> "Prefer IPv6"
        "ipv4_only" -> "IPv4 only"
        "ipv6_only" -> "IPv6 only"
        else -> v
    }
    override fun hwid(v: String) = "HWID: $v"
    override val community = "Community"
    override val howToConnect = "How to connect?"
    override val autoConnectTitle = "Auto-connect on launch"
    override val autoConnectSubtitle = "Connect to the selected config when the app opens"
    override val energySaverTitle = "Energy-saver mode"
    override val energySaverSubtitle = "Lower battery use: less frequent health checks, no journal. May slow auto-recovery; applied on next connect"
    override val confirmDeleteTitle = "Delete confirmation"
    override val confirmDeleteSubtitle = "Ask before deleting subscriptions and configs"
    override val language = "Language"
    override val dns = "DNS"
    override val remoteDnsLabel = "Remote DNS (via proxy)"
    override val directDnsLabel = "Direct DNS (bootstrap)"
    override val domainStrategy = "Domain strategy"
    override val multiplexing = "Multiplexing"
    override val useMux = "Use Mux"
    override val useMuxSubtitle = "Faster, but may reduce connection stability"
    override val muxMaxConnections = "Max connections (1–64)"
    override val fragmentation = "Fragmentation (Xray)"
    override val useFragment = "Enable fragmentation"
    override val useFragmentSubtitle = "Splits TLS packets to evade DPI. Requires the Xray core."
    override val fragmentPackets = "Packets (tlshello or 1-3)"
    override val fragmentLength = "Length (e.g. 50-100)"
    override val fragmentInterval = "Interval, ms (e.g. 10-20)"
    override val mtuLabel = "MTU (1280–9000)"
    override val blockRuDomains = "Block RU domains"
    override val blockRuDomainsSubtitle = "Bundled list of Russian domains → 0.0.0.0. Requires the Xray core."
    override val fakeDnsTitle = "FakeDNS"
    override val fakeDnsSubtitle = "Answers DNS with synthetic IPs — apps never see the real address; the domain is resolved behind the proxy. A subscription config with its own fakedns is used as-is."
    override val saveAndApply = "Save & apply"
    override val routingTitle = "Routing & rules"
    override val bypassLan = "Bypass LAN"
    override val bypassLanSubtitle = "Local/private addresses go direct"
    override val showSystemApps = "System apps"
    override val bypassRussia = "Bypass Russia"
    override val bypassRussiaSubtitle = "RU sites & IPs go direct (geoip + geosite)"
    override val blockAds = "Block ads"
    override val blockAdsSubtitle = "Reject ad / tracker domains"
    override val directDomains = "Direct domains"
    override val blockedDomains = "Blocked domains"
    override val domainsPlaceholder = "example.com, one per line"
    override val presets = "Presets"
    override val presetRuDirect = "Russia direct"
    override val presetAdsBlock = "Block ads"
    override val presetRuAds = "Russia + no ads"
    override val presetAllVpn = "All via VPN"
    override val presetReset = "Reset"
    override val sbRoutingAdvanced = "Sing-box routing (advanced)"
    override val sbRoutingAdvancedDesc =
        "Verbatim sing-box JSON. These run before the toggles above. " +
            "Rules example: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"
    override val sbRouteRulesLabel = "route.rules (JSON array)"
    override val sbRuleSetLabel = "rule_set (JSON array)"
    override val sbInvalidJsonArray = "Invalid JSON array"
    override val routingTabSimple = "Simple"
    override val routingTabRules = "Rules"
    override val routingTabHapp = "Profiles"
    override val routingShareAllButton = "Import / export all settings"
    override val routingShareAllTitle = "Routing settings"
    override val routingShareAllDesc = "Copy every profile as one yptun://routing link, or paste one to import."
    override val routingExportCopy = "Copy link"
    override val routingImportPaste = "Paste yptun://routing"
    override val routingImportApply = "Import"
    override val routingImportInvalid = "Invalid link"
    override val routingRulesTitle = "Routing rules"
    override val routingRulesDesc = "A custom v2rayNG-style rule list. Runs on the sing-box core; rules apply top to bottom."
    override val routingRulesEmpty = "No rules yet. Add the first one."
    override val routingRuleAdd = "Add rule"
    override val routingRuleEdit = "Rule"
    override val routingRuleDelete = "Delete rule"
    override val routingRuleName = "Route name"
    override val routingRuleOutbound = "Outbound"
    override val routingRuleDomains = "Domains"
    override val routingRuleIps = "IP / geoip"
    override val routingRuleSource = "Source (IP)"
    override val routingRulePort = "Port"
    override val routingRuleSourcePort = "Source port"
    override val routingRuleNetwork = "Network"
    override val routingRuleNetworkType = "Network type"
    override val netTypeWifi = "Wi-Fi"
    override val netTypeCellular = "Cellular"
    override val netTypeEthernet = "Ethernet"
    override val netTypeOther = "Other"
    override val routingRuleProtocol = "Protocol"
    override val routingRuleClient = "Client (TLS)"
    override val routingRuleMetered = "Metered network"
    override val routingRuleClashMode = "Clash mode"
    override val routingRuleApps = "Apps (packages)"
    override val routingRulePackageRegex = "Package name regex"
    override val routingRuleEnabled = "Enabled"
    override val routingRuleNetworkAny = "Any"
    override val routingOutProxy = "Via proxy"
    override val routingOutDirect = "Direct"
    override val routingOutBlock = "Block"
    override val routingRuleAction = "Routing action"
    override val routingActionRoute = "Route"
    override val routingActionRouteOptions = "Route options"
    override val routingActionSniff = "Sniff"
    override val routingActionResolve = "Resolve"
    override val routingActionHijackDns = "Hijack DNS"
    override val routingActionReject = "Reject"
    override val routingProfiles = "Routing profiles"
    override val routingProfilesSubtitle = "Happ-compatible rules — globally or per location"
    override val routingProfileGlobal = "Global profile"
    override val routingProfileNone = "No profile"
    override val routingProfileGlobalHint = "Applied to every connection unless a location sets its own profile"
    override val routingProfileAdd = "Create profile"
    override val routingProfileImportLink = "Import happ:// link"
    override val routingProfilePasteHint = "happ://routing/add/…"
    override val routingProfileNewName = "New profile"
    override val routingProfileName = "Name"
    override val routingProfileDelete = "Delete profile"
    override val routingProfileShare = "Share happ:// link"
    override val routingProfileGlobalProxy = "All traffic via proxy"
    override val routingProfileGlobalProxyDesc = "Otherwise direct, except the “via proxy” lists"
    override val routingProfileRouteOrder = "Rule order"
    override val routingProfileDomainStrategy = "Domain strategy"
    override val routingExpert = "Expert settings"
    override val routingExpertDesc = "Fine-tune routing separately for Xray and sing-box"
    override val routingExpertInherit = "Default"
    override val routingExpertSniffing = "Domain sniffing"
    override val routingExpertSniffingDesc = "Required for domain:/geosite: rules to match by SNI/Host. Without it .ru rules do nothing."
    override val routingExpertRouteOnly = "Route only (routeOnly)"
    override val routingExpertRouteOnlyDesc = "Detect the domain for routing only, but still dial the original address."
    override val routingExpertResolve = "Resolve domain (resolve)"
    override val routingExpertResolveDesc = "Resolve the domain to an IP so geoip:/ip rules can match."
    override val routingProxySites = "Via proxy: sites"
    override val routingProxyIp = "Via proxy: IPs"
    override val routingDirectSites = "Direct: sites"
    override val routingDirectIp = "Direct: IPs"
    override val routingBlockSites = "Block: sites"
    override val routingBlockIp = "Block: IPs"
    override val routingSelectorsHint = "geosite:ru, domain:vk.com — one per line"
    override val routingIpSelectorsHint = "geoip:ru, 10.0.0.0/8 — one per line"
    override val routingGeoDatabases = "Geo databases (geoip.dat / geosite.dat)"
    override val routingGeoDatabasesDesc = "Needed for geoip:/geosite: selectors on the Xray core. Downloaded from the sources below."
    override val routingGeoUpdate = "Update geo databases"
    override val routingGeoUpdating = "Downloading…"
    override val routingGeoNever = "Not downloaded"
    override val routingGeoipUrl = "geoip.dat URL"
    override val routingGeositeUrl = "geosite.dat URL"
    override val routingProfileSaved = "Profile saved"
    override val routingProfileImported = "Routing profile imported"
    override val routingProfileInvalidLink = "Invalid routing link"
    override val routingProfileEmpty = "No profiles yet"
    override fun routingGeoUpdated(ts: String) = "Updated: $ts"
    override fun routingProfileRuleCount(n: Int) = "$n rules"
    override val locationRoutingProfile = "Routing profile"
    override val locationRoutingGlobalDefault = "Global (default)"
    override val urlSchemeAddConfig = "ADD CONFIG"
    override val urlSchemeAddSubscription = "ADD SUBSCRIPTION"
    override val urlSchemeControl = "CONTROL"
    override val copy = "Copy"
    override val addConnection = "Add connection"
    override val addConnectionSubtitle = "Subscription or custom location"
    override val scanQr = "Scan QR code"
    override val scanQrSubtitle = "Subscription or olcrtc URI"
    override val pasteLink = "Paste link or URI"
    override val pasteLinkSubtitle = "Subscription, olcrtc or sing-box config"
    override val importFile = "Import from file"
    override val importFileSubtitle = "Subscription, olcrtc or sing-box config"
    override val updateSubscriptionsAction = "Update subscriptions"
    override val updateSubscriptionsSubtitle = "Refresh imported subscription locations"
    override val createCustomLocation = "Create custom location"
    override val createCustomLocationSubtitle = "Enter room, key, provider, and transport"
    override val connectionMode = "Connection Mode"
    override val socks5Proxy = "SOCKS5 Proxy"
    override val splitTunneling = "Split Tunneling"
    override val routingBehavior = "Routing Behavior"
    override val appsUsingYptun = "Apps Using YPtun"
    override val bypassedApps = "Bypassed Apps"
    override val search = "Search"
    override val appListSection = "App List"
    override val endpointSection = "Endpoint"
    override val credentialsSection = "Credentials"
    override val applicationLogsTitle = "Application Logs"
    override val updatesTitle = "Updates"
    override fun currentVersion(v: String) = "Current version $v"
    override val checkInterval = "Check Interval"
    override val copyFullConfig = "Copy Full Config"
    override val currentConfig = "Current Config"
    override val subscriptionsSection = "Subscriptions"
    override val noSubscriptions = "No subscriptions"
    override val noSubscriptionsSubtitle = "Imported HTTPS subscriptions will appear here."
    override val listenAddress = "Listen address"
    override val listenAddressRequired = "Listen address is required"
    override val savingRestarts = "Saving restarts the active connection"
    override val unsavedChange = "Unsaved change"
    override val port = "Port"
    override val portRequired = "Port is required"
    override val username = "Username"
    override val password = "Password"
    override val generatedPassword = "Generated password"
    override val passwordRequired = "Password is required"
    override val usernameRequired = "Username is required"
    override val regeneratePassword = "Regenerate password"
    override val save = "Save"
    override val share = "Share"
    override val searchApps = "Search apps"
    override val noApps = "No apps found"
    override val noMatchingApps = "No matching apps"
    override val noAppsHint = "Install launchable apps to configure routing rules."
    override val noMatchHint = "Try another app name or package."
    override val noAppListNeeded = "No app list needed"
    override val everyAppSameRoute = "Every app follows the same TUN route"
    override val lastCheck = "Last check"
    override val notCheckedYet = "Not checked yet"
    override val checkNow = "Check now"
    override val fullTunnel = "Full tunnel"
    override val localSocksProxy = "Local SOCKS5 proxy"
    override val systemVpnInterface = "System VPN interface"
    override val localSocksEndpoint = "Local SOCKS endpoint"
    override val allApps = "All apps"
    override val selectedAppsOnly = "Selected apps only"
    override val bypassSelected = "Bypass selected"
    override val everyAppUsesYptun = "Every app uses YPtun"
    override val chooseAppsUseYptun = "Choose apps that use YPtun"
    override val chooseAppsBypass = "Choose apps that bypass YPtun"
    override fun appsCount(n: Int) = if (n == 1) "1 app" else "$n apps"
    override fun useYptunCount(n: Int) = "${appsCount(n)} use YPtun"
    override fun onlyUseYptunCount(n: Int) = "Only ${appsCount(n)} use YPtun"
    override fun onlyCount(n: Int) = "Only ${appsCount(n)}"
    override fun bypassedCount(n: Int) = "${appsCount(n)} bypassed"
    override fun bypassYptunCount(n: Int) = "${appsCount(n)} bypass YPtun"
    override val allAppsUseYptunStatus = "All apps use YPtun"
    override val noAppsSelectedStatus = "No apps selected"
    override val noAppsBypassStatus = "No apps bypass YPtun"
    override val selectionRequired = "Required"
    override val noBypassedAppsValue = "No bypassed apps"
    override val savedForTunMode = "Saved for TUN mode"
    override val appliesWhenSettingsClose = "Applies when settings closes"
    override val tunModeRoutingRule = "TUN mode routing rule"
    override val bypassRuApps = "Bypass RU apps"
    override val bypassRuOn = "RU bypass on"
    override val ruBypassAccuracy = "Auto-detection may be inaccurate."
    override val ruBypassNoMatches = "No matching installed apps"
    override fun ruBypassMatchedByPackage(n: Int) = "${appsCount(n)} matched by package"
    override val ruBypassNoneSelected = "No RU apps selected"
    override fun ruBypassAlreadySelected(n: Int) = "${appsCount(n)} already selected"
    override fun ruBypassAutoBypassed(n: Int) = "${appsCount(n)} auto-bypassed"
    override fun ruBypassAutoManual(auto: Int, manual: Int) = "$auto auto · $manual manual"
    override val releaseChannelLabel = "Release"
    override fun downloadingAsset(name: String) = "Downloading $name…"
    override fun downloadFailed(error: String) = "Download failed: $error"
    override fun installingAsset(name: String) = "Installing $name"
    override fun hoursShort(n: Int) = "${n}h"
    override val themeColor = "Theme color"
    override val elementColor = "Element color"
    override val textColor = "Text color"
    override val customColorRgb = "Custom color (RGB)"
    override val qrShare = "QR / share"
    override val refresh = "Refresh"
    override val subscriptionWebPage = "Web page"
    override val subscriptionSupport = "Support"
    override val experimental = "Experimental"
    override val experimentalSubtitle = "Telemost cookies and more"
    override val experimentalUnlocked = "Experimental settings unlocked"
    override val notifSpeed = "Speed in notification"
    override val notifSpeedSubtitle = "Show download ↓ and upload ↑ in the shade"
    override val showSubscriptionExpiryTitle = "Show subscription expiry"
    override val showSubscriptionExpirySubtitle = "Show \"until dd.mm.yyyy\" under the refresh date"
    override val subscriptionUserAgentLabel = "Subscription User-Agent"
    override val subscriptionUserAgentSubtitle = "Happ/1.0 fetches the full config (FakeDNS, dns.hosts); YPtun usually returns only links"
    override val globalEngineLabel = "VLESS engine (global)"
    override val globalEngineSubtitle = "Core for VLESS-like transports when a server's setting is \"Auto\". The per-server choice overrides this. xhttp always uses Xray."
    override val globalEngineAuto = "Auto"
    override val telemostCookiesDescription =
        "Cookies of a signed-in Yandex account (the Cookie header, e.g. " +
            "\"Session_id=…; yandexuid=…\") — for private conferences. A custom core cannot be " +
            "launched as a separate binary on Android, so this feature is built into the stock core."
    override val useTelemostCookies = "Use Telemost cookies"
    override val useTelemostCookiesSubtitle = "Attach cookies when connecting to Telemost"
    override val hideTunTitle = "Hide tun0 interface (root)"
    override val hideTunSubtitle = "Installs a Zygisk module that hides the VPN interface from other apps. Requires root (Magisk) and a reboot."
    override val hideTunDisclaimer = "Warning: this uses root access (su). The software author is NOT liable for any damage, data loss, or harm to your device resulting from root usage."
    override val hideModuleRebootTitle = "Reboot required"
    override val hideModuleRebootMessage = "The hide module is installed. A reboot is required for hiding to take effect. Reboot now?"
    override val rebootNow = "Reboot"
    override val rebootLater = "Later"
    override val hideModuleInstalled = "Hide module installed"
    override val hideModuleActive = "Hiding already active"
    override val hideModuleFailed = "Couldn't install the module (root + Zygisk required)"
    override val hideModuleDisabled = "Hiding disabled (applies after reboot)"
    override val shareHotspotTitle = "Share VPN over hotspot (root)"
    override val shareHotspotSubtitle = "Routes devices connected to your hotspot through the VPN. Turn on the hotspot and connect the VPN. Requires root."
    override val shareHotspotDisclaimer = "Warning: this uses root access (su) and changes routing/iptables rules. The software author is NOT liable for any damage."
    override val rootGranted = "Root access granted"
    override val rootDenied = "Root access denied"
    override val telemostCookieHeader = "Telemost Cookie header"
    override val cookiesLoaded = "Cookies loaded"
    override val cookiesReadFailed = "Couldn't read the file"
    override val loadFromFile = "Load from file (cookies.txt)"
    override val notifConnected = "Connected"
    override fun notifConnectedMode(mode: String) = "$mode Connected"
    override val notifWaitingNetwork = "Waiting for network..."
    override val notifReconnecting = "Reconnecting..."
    override val notifWaitingTransport = "Waiting for transport..."
    override val notifConnecting = "Connecting..."
    override val notifAddLocation = "Add a location first"
    override val notifAddProxy = "Add a proxy first"
    override val notifAddVkLink = "Add a VK call link first"
    override val notifConnectionFailed = "Connection failed"
    override val notifTunnelFailed = "Tunnel failed"
    override val notifVpnTunnelError = "VPN tunnel error"
    override val notifSplitTunnelError = "Split tunneling error"
    override val notifStop = "Stop"
    override val noFileSelected = "No file selected"
    override val qrImported = "QR imported"
    override fun cannotOpenFilePicker(msg: String) = "Cannot open file picker: $msg"
    override val configCopied = "Config copied"
    override val copied = "Copied"
    override val qrTooLarge = "Config is too large for a QR code. Use Copy or Share instead."
    override val vkCallLink = "VK call link"
    override fun vkCallLinkBody(name: String) =
        "Paste your VK Calls join link for \"$name\". You can paste several links " +
            "(one per line) to spread the tunnel across calls for more speed."
    override val next = "Next"
    override val later = "Later"
    override val download = "Download"
    override val updateAvailable = "Update available"
    override val sizeUnknown = "Size unknown"
    override val noLogEntries = "No entries"
    override fun logEntriesCount(n: Int) = "$n entries"
    override val locationSettingsTitle = "Location settings"
    override val proxyLinkOrConfig = "Proxy link or config"
    override val proxyLink = "Proxy link"
    override val fieldName = "Name"
    override val locationNamePlaceholder = "Location name"
    override val engineSection = "Engine"
    override val proxySection = "Proxy"
    override val proxySectionSubtitle =
        "Paste a vless/vmess/trojan/ss link, an AmneziaWG config, or a sing-box outbound JSON"
    override val mainProxySection = "Main proxy"
    override val additionalProxySection = "Second proxy"
    override val additionalProxySubtitle =
        "On top of the main: traffic → main proxy → this"
    override val enableAdditionalProxy = "Additional proxy (cascade)"
    override val freeturnTransportSection = "Freeturn transport"
    override val freeturnTransportSubtitle = "VK TURN relay endpoint and obfuscation"
    override val wireguardSubtitle = "The tunnel sing-box dials through the local freeturn listener"
    override val proxyOverVkturn = "Proxy over VK-TURN (optional)"
    override val proxyOverVkturnSubtitle =
        "Chain a vless/vmess/trojan/ss proxy on top of the WireGuard tunnel"
    override val enableProxy = "Enable proxy"
    override val vkCallLinksSection = "VK call link(s)"
    override val vkCallLinksSubtitle =
        "Personal VK Calls join link (required). Add up to 5 — each extra call " +
            "adds bandwidth (the tunnel is spread across them)."
    override fun vkCallLinkNumbered(n: Int) = "VK call link $n (optional)"
    override val additionalCalls = "Additional calls"
    override val coreSection = "Core"
    override val coreSubtitle = "Auto picks Xray for xhttp, otherwise sing-box"
    override val coreSubtitleXrayOnly = "xhttp — Xray only, sing-box unavailable"
    override val connectionType = "Connection type"
    override val vp8Options = "VP8 options"
    override val vp8OptionsSubtitle = "Fine-tune stream performance"
    override fun subscriptionsUpdatedCount(n: Int) = "Subscriptions updated: $n"
    override val subscriptionsUpdated = "Subscriptions updated"
    override val subscriptionDeleted = "Subscription deleted"
    override val subscriptionsDeleted = "Subscriptions deleted"
    override val configsDeleted = "Configurations deleted"
    override val customLocations = "Custom locations"
    override val addRelaySetup = "Add relay setup"
    override val importHint = "Scan QR, paste URI, or import file"
    override val importedFromClipboard = "Imported from clipboard"
    override val pingOffline = "Offline"
    override val pingOnline = "Online"
    override val pingChecking = "Checking..."
    override val pingVerify = "Click to verify reachability"
    override val connectivityCheck = "Connectivity check"
    override val pingSettings = "Ping"
    override val pingSettingsSubtitle = "How inbounds are probed"
    override val pingMethod = "Ping method"
    override val pingTarget = "Ping target site"
    override val pingTargetHint = "e.g. https://google.com"
    override val savePingResultsTitle = "Save ping results"
    override val savePingResultsSubtitle =
        "Show the last results again when the app is reopened"
    override val showAliveCountTitle = "Reachable server counter"
    override val showAliveCountSubtitle =
        "Show \"live/total\" in the subscription header from the last ping pass"
    override val pingThreadsTitle = "Ping threads"
    override val pingThreadsSubtitle = "How many locations to ping at once (1–20)"
    override val pingModeAuto = "Auto"
    override val pingModeTcp = "TCP"
    override val pingModeIcmp = "ICMP"
    override val pingModeProxyGet = "Via proxy GET"
    override val pingModeProxyHead = "Via proxy HEAD"
    override val pingResultLabel = "Ping result"
    override val pingResultTime = "Time"
    override val pingResultIcon = "Icon"
    override val scanQrTitle = "Scan QR"
    override val readyToScan = "Ready to scan"
    override val subscriptionOrLocationUri = "Subscription or location URI"
    override val cameraPermissionDenied = "Camera permission denied"
    override val cameraUnavailable = "Camera unavailable"
    override val upToDate = "YPtun is up to date"
    override fun latestAlreadyDownloaded(channel: String) = "Latest $channel is already downloaded"
    override fun channelUpdateAvailable(channel: String, version: String) = "$channel update available: $version"
    override fun checkingChannel(channel: String) = "Checking $channel..."
    override val updateServiceUnavailable = "Update service unavailable"
    override val updateCheckFailed = "Update check failed"
    override val allowInstallUpdates = "Allow YPtun to install updates, then tap Download again"
    override val locationQr = "Location QR"
    override val subscriptionQr = "Subscription QR"
    override val subscriptionUpdated = "Subscription updated"
    override val subscriptionNotUpdated = "Subscription not updated"
    override fun subscriptionExpiry(dateTime: String, daysLeft: Long) =
        if (daysLeft < 0) "expired $dateTime" else "until $dateTime"
    override fun subscriptionEvery(hours: Int) = "Updates every ${hours}h"
    override fun subscriptionUpdatedAt(dateTime: String) = "updated $dateTime"
    override fun subscriptionUntil(date: String) = "until $date"
    override val subscriptionExpiringSoon = "Subscription expiring soon"
    override fun subscriptionExpiryFull(dateTime: String, daysLeft: Long) = when {
        daysLeft < 0 -> "expired $dateTime"
        daysLeft == 0L -> "until $dateTime · today"
        else -> "until $dateTime · in $daysLeft days"
    }
    override val updateBannerTitle = "Update available"
    override val updateBannerAction = "Update"
    override val updateManual = "Download from GitHub"
    override val addLocationFirst = "Add a location first"
    override val completeActiveLocationFirst = "Complete active location first"
    override val addValidLocationFirst = "Add a valid location first"
    override val encryptionKey = "Encryption key"
    override val serverHost = "Server host"
    override val transportToRelay = "Transport (to TURN relay)"
    override val modeTunnelPayload = "Mode (tunnel payload)"
    override val obfuscationProfile = "Obfuscation profile"
    override val obfuscationKey = "Obfuscation key"
    override val streamsParallel = "Streams (parallel relays)"
    override val bondingMultipath = "Bonding (multipath)"
    override val privateKey = "Private key"
    override val peerPublicKey = "Peer public key"
    override val addressField = "Address"
    override val listenPort = "Listen port"
    override val allowedIps = "Allowed IPs"
    override val muxMultiplex = "Mux (multiplex)"
    override val muxProtocol = "Mux protocol"
    override val maxStreamsField = "Max streams"
    override val sniffDestination = "Sniff destination"
    override val tlsFragmentXray = "TLS fragment (anti-DPI, Xray)"
    override val coreAuto = "Auto"
    override fun advancedCoreSettings(core: String) = "Advanced $core settings"
}

object FaStrings : Strings {
    override val languageName = "فارسی"
    override val navConnection = "اتصال"
    override val navSettings = "تنظیمات"
    override val connectionTime = "مدت اتصال"
    override val refreshSubscriptions = "بازآوری اشتراک‌ها"
    override val configurations = "پیکربندی‌ها"
    override val labelSetup = "تنظیم"
    override val labelStop = "توقف"
    override val labelStart = "شروع"
    override val addCustomLocation = "افزودن موقعیت سفارشی"
    override val addSubscription = "افزودن اشتراک"
    override val groupPinToTop = "سنجاق به بالا"
    override val groupUnpinFromTop = "برداشتن سنجاق"
    override val groupSortByPing = "مرتب‌سازی بر اساس پینگ"
    override val groupAutoUpdate = "به‌روزرسانی خودکار"
    override val visitSubscriptionPage = "مشاهده صفحه اشتراک"
    override val telegramProxyTitle = "پروکسی تلگرام (WARP)"
    override val telegramProxySubtitle = "SOCKS5 پس‌زمینه از طریق Cloudflare WARP برای تلگرام. اولین فعال‌سازی به اینترنت نیاز دارد — سپس آدرس را در تنظیمات پروکسی تلگرام وارد کنید."
    override val telegramProxyGenerating = "در حال تولید پیکربندی… (به اینترنت نیاز است)"
    override val telegramProxyRunning = "فعال"
    override val telegramProxyError = "خطا"
    override val groupDelete = "حذف اشتراک"
    override val delete = "حذف"
    override val cancel = "انصراف"
    override fun selectedCount(count: Int) = "انتخاب‌شده: $count"
    override val deleteSubscriptionTitle = "اشتراک حذف شود؟"
    override fun deleteSubscriptionMessage(count: Int) =
        "همهٔ پیکربندی‌های این اشتراک ($count) حذف خواهند شد."
    override val deleteAllSubscriptionsTitle = "همهٔ اشتراک‌ها حذف شوند؟"
    override val deleteAllSubscriptionsMessage =
        "همهٔ پیکربندی‌های اشتراک‌ها حذف می‌شوند. موقعیت‌های سفارشی حفظ خواهند شد."
    override val deleteAllConfigsTitle = "همهٔ پیکربندی‌ها حذف شوند؟"
    override val deleteAllConfigsMessage =
        "همهٔ پیکربندی‌ها و اشتراک‌ها حذف می‌شوند. این کار بازگشت‌ناپذیر است."
    override val menuDeleteAllSubscriptions = "حذف همهٔ اشتراک‌ها"
    override val menuDeleteAllConfigs = "حذف همهٔ پیکربندی‌ها"
    override val menuDeleteUnreachable = "حذف موارد در دسترس‌نبودن"
    override val menuDeleteDuplicates = "حذف موارد تکراری"
    override val createFolder = "ساخت گروه"
    override val folderEmpty = "گروه خالی است — با فشار طولانی موقعیت‌ها را اضافه کنید"
    override val folderRename = "تغییر نام"
    override val folderDelete = "حذف گروه"
    override val folderDeleteTitle = "گروه حذف شود؟"
    override val folderDeleteMessage = "گروه حذف می‌شود. موقعیت‌ها و اشتراک‌های آن حذف نمی‌شوند و به فهرست اصلی بازمی‌گردند."
    override val newFolderTitle = "گروه جدید"
    override val folderNameHint = "نام گروه"
    override val moveToFolder = "به گروه"
    override val removeFromFolder = "حذف از گروه‌ها"
    override val chooseFolderTitle = "یک گروه انتخاب کنید"
    override val newFolderOption = "گروه جدید…"
    override val folderCreate = "ساختن"
    override val folderSave = "ذخیره"
    override val deleteUnreachableTitle = "موارد در دسترس‌نبودن حذف شوند؟"
    override val deleteUnreachableMessage =
        "موقعیت‌های سفارشی که در آخرین پینگ در دسترس نبودند حذف می‌شوند. اشتراک‌ها دست‌نخورده می‌مانند."
    override val deleteDuplicatesTitle = "موارد تکراری حذف شوند؟"
    override val deleteDuplicatesMessage =
        "موقعیت‌های سفارشی تکراری با پیکربندی یکسان حذف می‌شوند (یک نسخه باقی می‌ماند). اشتراک‌ها دست‌نخورده می‌مانند."
    override fun unreachableDeleted(count: Int) = "موارد در دسترس‌نبودن حذف‌شده: $count"
    override fun duplicatesDeleted(count: Int) = "موارد تکراری حذف‌شده: $count"
    override val noUnreachableFound = "موقعیت در دسترس‌نبودنی یافت نشد"
    override val noDuplicatesFound = "موردی تکراری یافت نشد"
    override val settings = "تنظیمات"
    override val dynamicTheme = "پوستهٔ پویا"
    override val dynamicThemeOn = "استفاده از رنگ‌های سیستم اندروید"
    override val dynamicThemeOff = "استفاده از رنگ‌های YPtun"
    override val routing = "مسیریابی"
    override val routingSubtitle = "دور زدن LAN/روسیه، مسدودسازی تبلیغات، دامنه‌ها"
    override val trafficSettings = "تنظیمات ترافیک"
    override val trafficSettingsSubtitle = "DNS، چندتکثیری، راهبرد دامنه"
    override val connectionSettings = "تنظیمات اتصال"
    override val connectionSettingsSubtitle = "حالت، پراکسی SOCKS5، مسیریابی هر برنامه"
    override val subscriptionsSharing = "اشتراک‌ها و هم‌رسانی"
    override val updates = "به‌روزرسانی‌ها"
    override val applicationSettings = "تنظیمات برنامه"
    override val applicationSettingsSubtitle = "اتصال خودکار، تأیید حذف"
    override val urlSchemes = "طرح‌های URL"
    override val urlSchemesSubtitle = "ورود و کنترل از طریق پیوند عمیق"
    override val logs = "گزارش‌ها"
    override val logsSubtitle = "عیب‌یابی و برون‌بری"
    override val info = "اطلاعات"
    override fun domainStrategyName(v: String) = when (v) {
        "prefer_ipv4" -> "ترجیح IPv4"
        "prefer_ipv6" -> "ترجیح IPv6"
        "ipv4_only" -> "فقط IPv4"
        "ipv6_only" -> "فقط IPv6"
        else -> v
    }
    override fun version(v: String) = "نسخه: $v"
    override fun xrayVersion(v: String) = "Xray: $v"
    override fun singboxVersion(v: String) = "sing-box: $v"
    override fun vkturnVersion(v: String) = "VK-TURN (freeturn): $v"
    override fun olcrtcVersion(v: String) = "OLCRTC: $v"
    override fun hwid(v: String) = "HWID: $v"
    override val community = "انجمن"
    override val howToConnect = "چگونه متصل شویم؟"
    override val autoConnectTitle = "اتصال خودکار هنگام اجرا"
    override val autoConnectSubtitle = "هنگام باز شدن برنامه به پیکربندی انتخاب‌شده متصل شود"
    override val energySaverTitle = "حالت صرفه‌جویی در انرژی"
    override val energySaverSubtitle = "مصرف باتری کمتر: بررسی‌های کمتر اتصال، بدون گزارش. ممکن است بازیابی خودکار را کند کند؛ در اتصال بعدی اعمال می‌شود"
    override val confirmDeleteTitle = "تأیید حذف"
    override val confirmDeleteSubtitle = "پیش از حذف اشتراک‌ها و پیکربندی‌ها پرسیده شود"
    override val language = "زبان"
    override val dns = "DNS"
    override val remoteDnsLabel = "DNS راه‌دور (از طریق پراکسی)"
    override val directDnsLabel = "DNS مستقیم (راه‌انداز)"
    override val domainStrategy = "راهبرد دامنه"
    override val multiplexing = "چندتکثیری (Multiplexing)"
    override val useMux = "استفاده از Mux"
    override val useMuxSubtitle = "سریع‌تر، اما ممکن است پایداری اتصال را کاهش دهد"
    override val muxMaxConnections = "حداکثر اتصال‌ها (۱ تا ۶۴)"
    override val fragmentation = "تکه‌تکه‌سازی (Xray)"
    override val useFragment = "فعال‌سازی تکه‌تکه‌سازی"
    override val useFragmentSubtitle = "بسته‌های TLS را برای دور زدن DPI تکه‌تکه می‌کند. به هستهٔ Xray نیاز دارد."
    override val fragmentPackets = "بسته‌ها (tlshello یا ۱-۳)"
    override val fragmentLength = "طول (مثلاً ۵۰-۱۰۰)"
    override val fragmentInterval = "بازه، میلی‌ثانیه (مثلاً ۱۰-۲۰)"
    override val mtuLabel = "MTU (۱۲۸۰ تا ۹۰۰۰)"
    override val blockRuDomains = "مسدودسازی دامنه‌های روسیه"
    override val blockRuDomainsSubtitle = "فهرست داخلی دامنه‌های روسیه ← 0.0.0.0. به هستهٔ Xray نیاز دارد."
    override val fakeDnsTitle = "FakeDNS"
    override val fakeDnsSubtitle = "پاسخ‌های DNS را با IPهای ساختگی جایگزین می‌کند — برنامه‌ها نشانی واقعی را نمی‌بینند و دامنه پشت پراکسی حل می‌شود. پیکربندی اشتراک با fakedns خودش بدون تغییر استفاده می‌شود."
    override val saveAndApply = "ذخیره و اعمال"
    override val routingTitle = "مسیریابی و قواعد"
    override val bypassLan = "دور زدن LAN"
    override val bypassLanSubtitle = "نشانی‌های محلی/خصوصی مستقیم می‌روند"
    override val showSystemApps = "برنامه‌های سیستمی"
    override val bypassRussia = "دور زدن روسیه"
    override val bypassRussiaSubtitle = "سایت‌ها و IPهای روسیه مستقیم می‌روند (geoip + geosite)"
    override val blockAds = "مسدودسازی تبلیغات"
    override val blockAdsSubtitle = "رد کردن دامنه‌های تبلیغاتی/ردیاب"
    override val directDomains = "دامنه‌های مستقیم"
    override val blockedDomains = "دامنه‌های مسدود"
    override val domainsPlaceholder = "example.com، هر کدام در یک خط"
    override val presets = "پیش‌تنظیم‌ها"
    override val presetRuDirect = "روسیه مستقیم"
    override val presetAdsBlock = "مسدودسازی تبلیغات"
    override val presetRuAds = "روسیه + بدون تبلیغات"
    override val presetAllVpn = "همه از طریق VPN"
    override val presetReset = "بازنشانی"
    override val sbRoutingAdvanced = "مسیریابی sing-box (پیشرفته)"
    override val sbRoutingAdvancedDesc =
        "JSON خام sing-box. پیش از کلیدهای بالا اجرا می‌شود. " +
            "نمونهٔ قواعد: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"
    override val sbRouteRulesLabel = "route.rules (آرایهٔ JSON)"
    override val sbRuleSetLabel = "rule_set (آرایهٔ JSON)"
    override val sbInvalidJsonArray = "آرایهٔ JSON نامعتبر"
    override val routingTabSimple = "ساده"
    override val routingTabRules = "قوانین"
    override val routingTabHapp = "پروفایل‌ها"
    override val routingShareAllButton = "ورود / خروج همهٔ تنظیمات"
    override val routingShareAllTitle = "تنظیمات مسیریابی"
    override val routingShareAllDesc = "همهٔ پروفایل‌ها را با یک پیوند yptun://routing کپی کنید یا برای ورود، پیوند را بچسبانید."
    override val routingExportCopy = "کپی پیوند"
    override val routingImportPaste = "پیوند yptun://routing را بچسبانید"
    override val routingImportApply = "ورود"
    override val routingImportInvalid = "پیوند نامعتبر"
    override val routingRulesTitle = "قوانین مسیریابی"
    override val routingRulesDesc = "فهرست قوانین سفارشی به سبک v2rayNG. روی هستهٔ sing-box کار می‌کند؛ قوانین از بالا به پایین اعمال می‌شوند."
    override val routingRulesEmpty = "هنوز قانونی نیست. اولین را اضافه کنید."
    override val routingRuleAdd = "افزودن قانون"
    override val routingRuleEdit = "قانون"
    override val routingRuleDelete = "حذف قانون"
    override val routingRuleName = "نام مسیر"
    override val routingRuleOutbound = "خروجی"
    override val routingRuleDomains = "دامنه‌ها"
    override val routingRuleIps = "IP / geoip"
    override val routingRuleSource = "مبدأ (IP)"
    override val routingRulePort = "درگاه"
    override val routingRuleSourcePort = "درگاه مبدأ"
    override val routingRuleNetwork = "شبکه"
    override val routingRuleNetworkType = "نوع شبکه"
    override val netTypeWifi = "Wi-Fi"
    override val netTypeCellular = "سلولی"
    override val netTypeEthernet = "اترنت"
    override val netTypeOther = "دیگر"
    override val routingRuleProtocol = "پروتکل"
    override val routingRuleClient = "کلاینت (TLS)"
    override val routingRuleMetered = "شبکهٔ پولی"
    override val routingRuleClashMode = "حالت Clash"
    override val routingRuleApps = "برنامه‌ها (بسته‌ها)"
    override val routingRulePackageRegex = "عبارت منظم نام بسته"
    override val routingRuleEnabled = "فعال"
    override val routingRuleNetworkAny = "هر کدام"
    override val routingOutProxy = "از طریق پراکسی"
    override val routingOutDirect = "مستقیم"
    override val routingOutBlock = "مسدود کردن"
    override val routingRuleAction = "اقدام مسیریابی"
    override val routingActionRoute = "مسیر"
    override val routingActionRouteOptions = "گزینه‌های مسیر"
    override val routingActionSniff = "شناسایی"
    override val routingActionResolve = "تفکیک"
    override val routingActionHijackDns = "ربودن DNS"
    override val routingActionReject = "رد کردن"
    override val routingProfiles = "نمایه‌های مسیریابی"
    override val routingProfilesSubtitle = "قواعد سازگار با Happ — سراسری یا برای هر موقعیت"
    override val routingProfileGlobal = "نمایهٔ سراسری"
    override val routingProfileNone = "بدون نمایه"
    override val routingProfileGlobalHint = "برای همهٔ اتصال‌ها اعمال می‌شود مگر موقعیت نمایهٔ خود را داشته باشد"
    override val routingProfileAdd = "ساخت نمایه"
    override val routingProfileImportLink = "درون‌ریزی پیوند ‎happ://"
    override val routingProfilePasteHint = "happ://routing/add/…"
    override val routingProfileNewName = "نمایهٔ جدید"
    override val routingProfileName = "نام"
    override val routingProfileDelete = "حذف نمایه"
    override val routingProfileShare = "اشتراک پیوند ‎happ://"
    override val routingProfileGlobalProxy = "همهٔ ترافیک از پراکسی"
    override val routingProfileGlobalProxyDesc = "در غیر این صورت مستقیم، به‌جز فهرست‌های «از پراکسی»"
    override val routingProfileRouteOrder = "ترتیب قواعد"
    override val routingProfileDomainStrategy = "راهبرد دامنه"
    override val routingExpert = "تنظیمات پیشرفته"
    override val routingExpertDesc = "تنظیم دقیق مسیریابی جداگانه برای Xray و sing-box"
    override val routingExpertInherit = "پیش‌فرض"
    override val routingExpertSniffing = "تشخیص دامنه (sniffing)"
    override val routingExpertSniffingDesc = "برای تطبیق قواعد domain:/geosite: با SNI/Host لازم است."
    override val routingExpertRouteOnly = "فقط مسیریابی (routeOnly)"
    override val routingExpertRouteOnlyDesc = "دامنه فقط برای مسیریابی تشخیص داده شود، اتصال به نشانی اصلی."
    override val routingExpertResolve = "تبدیل دامنه (resolve)"
    override val routingExpertResolveDesc = "دامنه به IP تبدیل شود تا قواعد geoip:/ip اعمال شوند."
    override val routingProxySites = "از پراکسی: سایت‌ها"
    override val routingProxyIp = "از پراکسی: IP"
    override val routingDirectSites = "مستقیم: سایت‌ها"
    override val routingDirectIp = "مستقیم: IP"
    override val routingBlockSites = "مسدود: سایت‌ها"
    override val routingBlockIp = "مسدود: IP"
    override val routingSelectorsHint = "geosite:ru, domain:vk.com — هر کدام در یک خط"
    override val routingIpSelectorsHint = "geoip:ru, 10.0.0.0/8 — هر کدام در یک خط"
    override val routingGeoDatabases = "پایگاه‌های جغرافیایی (geoip.dat / geosite.dat)"
    override val routingGeoDatabasesDesc = "برای گزینشگرهای geoip:/geosite: روی هستهٔ Xray لازم است. از منابع زیر دانلود می‌شود."
    override val routingGeoUpdate = "به‌روزرسانی پایگاه‌های جغرافیایی"
    override val routingGeoUpdating = "در حال دانلود…"
    override val routingGeoNever = "دانلود نشده"
    override val routingGeoipUrl = "نشانی geoip.dat"
    override val routingGeositeUrl = "نشانی geosite.dat"
    override val routingProfileSaved = "نمایه ذخیره شد"
    override val routingProfileImported = "نمایهٔ مسیریابی درون‌ریزی شد"
    override val routingProfileInvalidLink = "پیوند مسیریابی نامعتبر"
    override val routingProfileEmpty = "هنوز نمایه‌ای نیست"
    override fun routingGeoUpdated(ts: String) = "به‌روزرسانی: $ts"
    override fun routingProfileRuleCount(n: Int) = "$n قاعده"
    override val locationRoutingProfile = "نمایهٔ مسیریابی"
    override val locationRoutingGlobalDefault = "سراسری (پیش‌فرض)"
    override val urlSchemeAddConfig = "افزودن پیکربندی"
    override val urlSchemeAddSubscription = "افزودن اشتراک"
    override val urlSchemeControl = "کنترل"
    override val copy = "رونوشت"
    override val addConnection = "افزودن اتصال"
    override val addConnectionSubtitle = "اشتراک یا موقعیت سفارشی"
    override val scanQr = "پویش کد QR"
    override val scanQrSubtitle = "اشتراک یا نشانی olcrtc"
    override val pasteLink = "چسباندن پیوند یا URI"
    override val pasteLinkSubtitle = "اشتراک، olcrtc یا پیکربندی sing-box"
    override val importFile = "ورود از پرونده"
    override val importFileSubtitle = "اشتراک، olcrtc یا پیکربندی sing-box"
    override val updateSubscriptionsAction = "به‌روزرسانی اشتراک‌ها"
    override val updateSubscriptionsSubtitle = "بازآوری موقعیت‌های اشتراک‌های واردشده"
    override val createCustomLocation = "ساخت موقعیت سفارشی"
    override val createCustomLocationSubtitle = "اتاق، کلید، ارائه‌دهنده و حامل"
    override val connectionMode = "حالت اتصال"
    override val socks5Proxy = "پراکسی SOCKS5"
    override val splitTunneling = "تونل‌سازی تفکیکی"
    override val routingBehavior = "رفتار مسیریابی"
    override val appsUsingYptun = "برنامه‌های استفاده‌کننده از YPtun"
    override val bypassedApps = "برنامه‌های دور زده‌شده"
    override val search = "جستجو"
    override val appListSection = "فهرست برنامه‌ها"
    override val endpointSection = "نقطهٔ پایانی"
    override val credentialsSection = "اعتبارنامه‌ها"
    override val applicationLogsTitle = "گزارش‌های برنامه"
    override val updatesTitle = "به‌روزرسانی‌ها"
    override fun currentVersion(v: String) = "نسخهٔ کنونی $v"
    override val checkInterval = "بازهٔ بررسی"
    override val copyFullConfig = "رونوشت پیکربندی کامل"
    override val currentConfig = "پیکربندی کنونی"
    override val subscriptionsSection = "اشتراک‌ها"
    override val noSubscriptions = "اشتراکی نیست"
    override val noSubscriptionsSubtitle = "اشتراک‌های HTTPS واردشده اینجا نمایش داده می‌شوند."
    override val listenAddress = "نشانی شنود"
    override val listenAddressRequired = "نشانی شنود الزامی است"
    override val savingRestarts = "ذخیره‌سازی اتصال فعال را راه‌اندازی مجدد می‌کند"
    override val unsavedChange = "تغییر ذخیره‌نشده"
    override val port = "درگاه"
    override val portRequired = "درگاه الزامی است"
    override val username = "نام کاربری"
    override val password = "گذرواژه"
    override val generatedPassword = "گذرواژهٔ تولیدشده"
    override val passwordRequired = "گذرواژه الزامی است"
    override val usernameRequired = "نام کاربری الزامی است"
    override val regeneratePassword = "تولید مجدد گذرواژه"
    override val save = "ذخیره"
    override val share = "هم‌رسانی"
    override val searchApps = "جستجوی برنامه‌ها"
    override val noApps = "برنامه‌ای یافت نشد"
    override val noMatchingApps = "برنامهٔ همخوانی یافت نشد"
    override val noAppsHint = "برنامه‌های قابل‌اجرا را نصب کنید تا قواعد مسیریابی را تنظیم کنید."
    override val noMatchHint = "نام یا بستهٔ دیگری را امتحان کنید."
    override val noAppListNeeded = "نیازی به فهرست برنامه‌ها نیست"
    override val everyAppSameRoute = "همهٔ برنامه‌ها از یک مسیر TUN پیروی می‌کنند"
    override val lastCheck = "آخرین بررسی"
    override val notCheckedYet = "هنوز بررسی نشده"
    override val checkNow = "همین حالا بررسی کن"
    override val fullTunnel = "تونل کامل"
    override val localSocksProxy = "پراکسی محلی SOCKS5"
    override val systemVpnInterface = "رابط VPN سیستمی"
    override val localSocksEndpoint = "نقطهٔ پایانی محلی SOCKS"
    override val allApps = "همهٔ برنامه‌ها"
    override val selectedAppsOnly = "فقط برنامه‌های انتخاب‌شده"
    override val bypassSelected = "دور زدن انتخاب‌شده‌ها"
    override val everyAppUsesYptun = "همهٔ برنامه‌ها از YPtun استفاده می‌کنند"
    override val chooseAppsUseYptun = "برنامه‌هایی را که از YPtun استفاده می‌کنند انتخاب کنید"
    override val chooseAppsBypass = "برنامه‌هایی را که YPtun را دور می‌زنند انتخاب کنید"
    override fun appsCount(n: Int) = if (n == 1) "۱ برنامه" else "$n برنامه"
    override fun useYptunCount(n: Int) = "${appsCount(n)} از YPtun استفاده می‌کنند"
    override fun onlyUseYptunCount(n: Int) = "فقط ${appsCount(n)} از YPtun استفاده می‌کنند"
    override fun onlyCount(n: Int) = "فقط ${appsCount(n)}"
    override fun bypassedCount(n: Int) = "${appsCount(n)} دور زده‌شده"
    override fun bypassYptunCount(n: Int) = "${appsCount(n)} YPtun را دور می‌زنند"
    override val allAppsUseYptunStatus = "همهٔ برنامه‌ها از YPtun استفاده می‌کنند"
    override val noAppsSelectedStatus = "هیچ برنامه‌ای انتخاب نشده"
    override val noAppsBypassStatus = "هیچ برنامه‌ای YPtun را دور نمی‌زند"
    override val selectionRequired = "الزامی"
    override val noBypassedAppsValue = "هیچ برنامهٔ دور زده‌شده‌ای نیست"
    override val savedForTunMode = "برای حالت TUN ذخیره شد"
    override val appliesWhenSettingsClose = "هنگام بستن تنظیمات اعمال می‌شود"
    override val tunModeRoutingRule = "قانون مسیریابی حالت TUN"
    override val bypassRuApps = "دور زدن برنامه‌های روسی"
    override val bypassRuOn = "دور زدن روسی روشن"
    override val ruBypassAccuracy = "تشخیص خودکار ممکن است دقیق نباشد."
    override val ruBypassNoMatches = "برنامهٔ نصب‌شدهٔ منطبقی یافت نشد"
    override fun ruBypassMatchedByPackage(n: Int) = "${appsCount(n)} بر اساس بسته"
    override val ruBypassNoneSelected = "هیچ برنامهٔ روسی انتخاب نشده"
    override fun ruBypassAlreadySelected(n: Int) = "${appsCount(n)} از قبل انتخاب شده"
    override fun ruBypassAutoBypassed(n: Int) = "${appsCount(n)} دور زدن خودکار"
    override fun ruBypassAutoManual(auto: Int, manual: Int) = "$auto خودکار · $manual دستی"
    override val releaseChannelLabel = "نسخه"
    override fun downloadingAsset(name: String) = "در حال دانلود $name…"
    override fun downloadFailed(error: String) = "دانلود ناموفق بود: $error"
    override fun installingAsset(name: String) = "در حال نصب $name"
    override fun hoursShort(n: Int) = "$n ساعت"
    override val themeColor = "رنگ پوسته"
    override val elementColor = "رنگ عناصر"
    override val textColor = "رنگ متن"
    override val customColorRgb = "رنگ سفارشی (RGB)"
    override val qrShare = "QR / هم‌رسانی"
    override val refresh = "بازآوری"
    override val subscriptionWebPage = "صفحه"
    override val subscriptionSupport = "پشتیبانی"
    override val hideTunTitle = "پنهان‌کردن رابط tun0 (روت)"
    override val hideTunSubtitle = "یک ماژول Zygisk نصب می‌کند که رابط VPN را از سایر برنامه‌ها پنهان می‌کند. نیازمند روت (Magisk) و ری‌استارت."
    override val hideTunDisclaimer = "هشدار: این قابلیت از دسترسی روت (su) استفاده می‌کند. سازندهٔ نرم‌افزار مسئولیتی در قبال آسیب، از دست رفتن داده یا خرابی دستگاه ناشی از استفاده از روت ندارد."
    override val hideModuleRebootTitle = "نیاز به راه‌اندازی مجدد"
    override val hideModuleRebootMessage = "ماژول مخفی‌سازی نصب شد. برای فعال‌شدن مخفی‌سازی باید دستگاه را ری‌استارت کنید. اکنون ری‌استارت شود؟"
    override val rebootNow = "ری‌استارت"
    override val rebootLater = "بعداً"
    override val hideModuleInstalled = "ماژول مخفی‌سازی نصب شد"
    override val hideModuleActive = "مخفی‌سازی از قبل فعال است"
    override val hideModuleFailed = "نصب ماژول ناموفق بود (نیازمند روت و Zygisk)"
    override val hideModuleDisabled = "مخفی‌سازی غیرفعال شد (پس از ری‌استارت اعمال می‌شود)"
    override val shareHotspotTitle = "اشتراک VPN روی هات‌اسپات (روت)"
    override val shareHotspotSubtitle = "ترافیک دستگاه‌های متصل به هات‌اسپات شما را از طریق VPN عبور می‌دهد. هات‌اسپات را روشن و به VPN متصل شوید. نیازمند روت."
    override val shareHotspotDisclaimer = "هشدار: این قابلیت از دسترسی روت (su) و تغییر قوانین مسیریابی/iptables استفاده می‌کند. سازندهٔ نرم‌افزار مسئول هیچ آسیبی نیست."
    override val rootGranted = "دسترسی روت اعطا شد"
    override val rootDenied = "دسترسی روت رد شد"
    override val experimental = "آزمایشی"
    override val experimentalSubtitle = "کوکی‌های Telemost و موارد دیگر"
    override val experimentalUnlocked = "تنظیمات آزمایشی باز شد"
    override val notifSpeed = "سرعت در اعلان"
    override val notifSpeedSubtitle = "نمایش بارگیری ↓ و بارگذاری ↑ در کشوی اعلان"
    override val showSubscriptionExpiryTitle = "نمایش تاریخ انقضای اشتراک"
    override val showSubscriptionExpirySubtitle = "نمایش «تا dd.mm.yyyy» زیر تاریخ به‌روزرسانی"
    override val subscriptionUserAgentLabel = "User-Agent اشتراک"
    override val subscriptionUserAgentSubtitle = "Happ/1.0 پیکربندی کامل (FakeDNS، dns.hosts) را می‌گیرد؛ YPtun معمولاً فقط لینک‌ها"
    override val globalEngineLabel = "موتور VLESS (سراسری)"
    override val globalEngineSubtitle = "هسته برای ترنسپورت‌های مشابه VLESS وقتی تنظیم سرور روی «خودکار» است. تنظیم هر سرور بر این اولویت دارد. xhttp همیشه از Xray استفاده می‌کند."
    override val globalEngineAuto = "خودکار"
    override val telemostCookiesDescription =
        "کوکی‌های یک حساب واردشدهٔ یاندکس (سرایند Cookie، مثلاً " +
            "«Session_id=…; yandexuid=…») — برای کنفرانس‌های خصوصی. در اندروید نمی‌توان هستهٔ " +
            "سفارشی را به‌صورت یک باینری جداگانه اجرا کرد، بنابراین این قابلیت در هستهٔ استاندارد تعبیه شده است."
    override val useTelemostCookies = "استفاده از کوکی‌های Telemost"
    override val useTelemostCookiesSubtitle = "پیوست کوکی‌ها هنگام اتصال به Telemost"
    override val telemostCookieHeader = "سرایند Cookie برای Telemost"
    override val cookiesLoaded = "کوکی‌ها بارگذاری شد"
    override val cookiesReadFailed = "خواندن پرونده ممکن نشد"
    override val loadFromFile = "بارگذاری از پرونده (cookies.txt)"
    override val notifConnected = "متصل شد"
    override fun notifConnectedMode(mode: String) = "$mode متصل شد"
    override val notifWaitingNetwork = "در انتظار شبکه…"
    override val notifReconnecting = "در حال اتصال مجدد…"
    override val notifWaitingTransport = "در انتظار حامل…"
    override val notifConnecting = "در حال اتصال…"
    override val notifAddLocation = "ابتدا یک موقعیت اضافه کنید"
    override val notifAddProxy = "ابتدا یک پراکسی اضافه کنید"
    override val notifAddVkLink = "ابتدا یک پیوند تماس VK اضافه کنید"
    override val notifConnectionFailed = "اتصال ناموفق بود"
    override val notifTunnelFailed = "تونل ناموفق بود"
    override val notifVpnTunnelError = "خطای تونل VPN"
    override val notifSplitTunnelError = "خطای تونل‌سازی تفکیکی"
    override val notifStop = "توقف"
    override val noFileSelected = "پرونده‌ای انتخاب نشد"
    override val qrImported = "QR وارد شد"
    override fun cannotOpenFilePicker(msg: String) = "بازکردن انتخابگر پرونده ممکن نشد: $msg"
    override val configCopied = "پیکربندی رونوشت شد"
    override val copied = "رونوشت شد"
    override val qrTooLarge = "پیکربندی برای کد QR بیش از حد بزرگ است. از «رونوشت» یا «هم‌رسانی» استفاده کنید."
    override val vkCallLink = "پیوند تماس VK"
    override fun vkCallLinkBody(name: String) =
        "پیوند پیوستن VK Calls خود را برای «$name» بچسبانید. می‌توانید چند پیوند " +
            "(هر کدام در یک خط) بچسبانید تا تونل میان تماس‌ها پخش شده و سرعت بیشتر شود."
    override val next = "بعدی"
    override val later = "بعداً"
    override val download = "بارگیری"
    override val updateAvailable = "به‌روزرسانی در دسترس است"
    override val sizeUnknown = "اندازه نامشخص"
    override val noLogEntries = "ورودی‌ای نیست"
    override fun logEntriesCount(n: Int) = "$n ورودی"
    override val locationSettingsTitle = "تنظیمات موقعیت"
    override val proxyLinkOrConfig = "پیوند یا پیکربندی پراکسی"
    override val proxyLink = "پیوند پراکسی"
    override val fieldName = "نام"
    override val locationNamePlaceholder = "نام موقعیت"
    override val engineSection = "موتور"
    override val proxySection = "پراکسی"
    override val proxySectionSubtitle =
        "یک پیوند vless/vmess/trojan/ss، پیکربندی AmneziaWG یا JSON خروجی sing-box را بچسبانید"
    override val mainProxySection = "پراکسی اصلی"
    override val additionalProxySection = "پراکسی دوم"
    override val additionalProxySubtitle =
        "روی پراکسی اصلی: ترافیک ← پراکسی اصلی ← این"
    override val enableAdditionalProxy = "پراکسی اضافی (آبشاری)"
    override val freeturnTransportSection = "حامل Freeturn"
    override val freeturnTransportSubtitle = "نقطهٔ پایانی بازپخش VK TURN و مبهم‌سازی"
    override val wireguardSubtitle = "تونل sing-box از طریق شنودگر محلی freeturn شماره‌گیری می‌کند"
    override val proxyOverVkturn = "پراکسی روی VK-TURN (اختیاری)"
    override val proxyOverVkturnSubtitle =
        "زنجیر کردن یک پراکسی vless/vmess/trojan/ss روی تونل WireGuard"
    override val enableProxy = "فعال‌سازی پراکسی"
    override val vkCallLinksSection = "پیوند(های) تماس VK"
    override val vkCallLinksSubtitle =
        "پیوند شخصی پیوستن VK Calls (الزامی). تا ۵ مورد اضافه کنید — هر تماس اضافی " +
            "پهنای باند را افزایش می‌دهد (تونل میان آن‌ها پخش می‌شود)."
    override fun vkCallLinkNumbered(n: Int) = "پیوند تماس VK شمارهٔ $n (اختیاری)"
    override val additionalCalls = "تماس‌های اضافی"
    override val coreSection = "هسته"
    override val coreSubtitle = "«خودکار» برای xhttp از Xray و در غیر این صورت از sing-box استفاده می‌کند"
    override val coreSubtitleXrayOnly = "xhttp فقط Xray؛ sing-box در دسترس نیست"
    override val connectionType = "نوع اتصال"
    override val vp8Options = "گزینه‌های VP8"
    override val vp8OptionsSubtitle = "تنظیم دقیق کارایی جریان"
    override fun subscriptionsUpdatedCount(n: Int) = "اشتراک‌ها به‌روزرسانی شد: $n"
    override val subscriptionsUpdated = "اشتراک‌ها به‌روزرسانی شد"
    override val subscriptionDeleted = "اشتراک حذف شد"
    override val subscriptionsDeleted = "اشتراک‌ها حذف شد"
    override val configsDeleted = "پیکربندی‌ها حذف شد"
    override val customLocations = "موقعیت‌های سفارشی"
    override val addRelaySetup = "افزودن پیکربندی بازپخش"
    override val importHint = "QR را بپویید، URI را بچسبانید یا پرونده وارد کنید"
    override val importedFromClipboard = "از تخته‌گیره وارد شد"
    override val pingOffline = "آفلاین"
    override val pingOnline = "آنلاین"
    override val pingChecking = "در حال بررسی…"
    override val pingVerify = "برای بررسی دسترس‌پذیری کلیک کنید"
    override val connectivityCheck = "بررسی اتصال"
    override val pingSettings = "پینگ"
    override val pingSettingsSubtitle = "نحوه بررسی ورودی‌ها"
    override val pingMethod = "روش پینگ"
    override val pingTarget = "سایت هدف پینگ"
    override val pingTargetHint = "مثال: https://google.com"
    override val savePingResultsTitle = "ذخیرهٔ نتایج پینگ"
    override val savePingResultsSubtitle =
        "نمایش آخرین نتایج هنگام بازکردن دوبارهٔ برنامه"
    override val showAliveCountTitle = "شمارندهٔ سرورهای در دسترس"
    override val showAliveCountSubtitle =
        "نمایش «فعال/کل» در سربرگ اشتراک بر اساس آخرین پینگ"
    override val pingThreadsTitle = "رشته‌های پینگ"
    override val pingThreadsSubtitle = "چند موقعیت هم‌زمان پینگ شوند (۱ تا ۲۰)"
    override val pingModeAuto = "خودکار"
    override val pingModeTcp = "TCP"
    override val pingModeIcmp = "ICMP"
    override val pingModeProxyGet = "از طریق پروکسی GET"
    override val pingModeProxyHead = "از طریق پروکسی HEAD"
    override val pingResultLabel = "نتیجهٔ پینگ"
    override val pingResultTime = "زمان"
    override val pingResultIcon = "نشان"
    override val scanQrTitle = "پویش QR"
    override val readyToScan = "آمادهٔ پویش"
    override val subscriptionOrLocationUri = "اشتراک یا URI موقعیت"
    override val cameraPermissionDenied = "دسترسی به دوربین رد شد"
    override val cameraUnavailable = "دوربین در دسترس نیست"
    override val upToDate = "YPtun به‌روز است"
    override fun latestAlreadyDownloaded(channel: String) = "آخرین نسخهٔ $channel قبلاً بارگیری شده است"
    override fun channelUpdateAvailable(channel: String, version: String) = "به‌روزرسانی $channel در دسترس است: $version"
    override fun checkingChannel(channel: String) = "در حال بررسی $channel…"
    override val updateServiceUnavailable = "سرویس به‌روزرسانی در دسترس نیست"
    override val updateCheckFailed = "بررسی به‌روزرسانی ناموفق بود"
    override val allowInstallUpdates = "به YPtun اجازهٔ نصب به‌روزرسانی‌ها را بدهید، سپس دوباره «بارگیری» را بزنید"
    override val locationQr = "QR موقعیت"
    override val subscriptionQr = "QR اشتراک"
    override val subscriptionUpdated = "اشتراک به‌روزرسانی شد"
    override val subscriptionNotUpdated = "اشتراک به‌روزرسانی نشد"
    override fun subscriptionExpiry(dateTime: String, daysLeft: Long) =
        if (daysLeft < 0) "منقضی‌شده $dateTime" else "تا $dateTime"
    override fun subscriptionEvery(hours: Int) = "به‌روزرسانی هر $hours ساعت"
    override fun subscriptionUpdatedAt(dateTime: String) = "به‌روزرسانی‌شده $dateTime"
    override fun subscriptionUntil(date: String) = "تا $date"
    override val subscriptionExpiringSoon = "اشتراک به‌زودی منقضی می‌شود"
    override fun subscriptionExpiryFull(dateTime: String, daysLeft: Long) = when {
        daysLeft < 0 -> "منقضی‌شده $dateTime"
        daysLeft == 0L -> "تا $dateTime · امروز"
        else -> "تا $dateTime · $daysLeft روز دیگر"
    }
    override val updateBannerTitle = "به‌روزرسانی موجود است"
    override val updateBannerAction = "به‌روزرسانی"
    override val updateManual = "دانلود از گیت‌هاب"
    override val addLocationFirst = "ابتدا یک موقعیت اضافه کنید"
    override val completeActiveLocationFirst = "ابتدا موقعیت فعال را کامل کنید"
    override val addValidLocationFirst = "ابتدا یک موقعیت معتبر اضافه کنید"
    override val encryptionKey = "کلید رمزنگاری"
    override val serverHost = "میزبان سرور"
    override val transportToRelay = "حامل (تا بازپخش TURN)"
    override val modeTunnelPayload = "حالت (بار تونل)"
    override val obfuscationProfile = "نمایهٔ مبهم‌سازی"
    override val obfuscationKey = "کلید مبهم‌سازی"
    override val streamsParallel = "جریان‌ها (بازپخش‌های موازی)"
    override val bondingMultipath = "تجمیع (چندمسیره)"
    override val privateKey = "کلید خصوصی"
    override val peerPublicKey = "کلید عمومی همتا"
    override val addressField = "نشانی"
    override val listenPort = "درگاه شنود"
    override val allowedIps = "IPهای مجاز"
    override val muxMultiplex = "Mux (چندتکثیری)"
    override val muxProtocol = "پروتکل Mux"
    override val maxStreamsField = "حداکثر جریان‌ها"
    override val sniffDestination = "شناسایی مقصد (sniff)"
    override val tlsFragmentXray = "تکه‌تکه‌سازی TLS (ضد DPI، Xray)"
    override val coreAuto = "خودکار"
    override fun advancedCoreSettings(core: String) = "تنظیمات پیشرفتهٔ $core"
}
