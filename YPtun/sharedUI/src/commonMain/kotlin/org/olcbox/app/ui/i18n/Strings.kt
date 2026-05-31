package org.olcbox.app.ui.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Supported UI languages. [System] follows the device locale (resolved per platform). */
enum class AppLanguage(val id: String) {
    System("system"),
    English("en"),
    Russian("ru");

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

    // Delete dialogs
    val delete: String
    val cancel: String
    val deleteSubscriptionTitle: String
    fun deleteSubscriptionMessage(count: Int): String
    val deleteAllSubscriptionsTitle: String
    val deleteAllSubscriptionsMessage: String
    val deleteAllConfigsTitle: String
    val deleteAllConfigsMessage: String
    val menuDeleteAllSubscriptions: String
    val menuDeleteAllConfigs: String

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
    fun hwid(v: String): String
    val community: String
    val howToConnect: String

    // Application behavior
    val autoConnectTitle: String
    val autoConnectSubtitle: String
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
    val saveAndApply: String

    // Routing
    val routingTitle: String
    val bypassLan: String
    val bypassLanSubtitle: String
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
    override val addCustomLocation = "Добавить свою локацию"
    override val addSubscription = "Добавить подписку"
    override val delete = "Удалить"
    override val cancel = "Отмена"
    override val deleteSubscriptionTitle = "Удалить подписку?"
    override fun deleteSubscriptionMessage(count: Int) =
        "Будут удалены все конфигурации этой подписки ($count)."
    override val deleteAllSubscriptionsTitle = "Удалить все подписки?"
    override val deleteAllSubscriptionsMessage =
        "Будут удалены все конфигурации из подписок. Кастомные локации останутся."
    override val deleteAllConfigsTitle = "Удалить все конфиги?"
    override val deleteAllConfigsMessage =
        "Будут удалены все конфигурации и подписки. Это действие необратимо."
    override val menuDeleteAllSubscriptions = "Удалить все подписки"
    override val menuDeleteAllConfigs = "Удалить все конфиги"
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
    override fun hwid(v: String) = "HWID: $v"
    override val community = "Сообщество"
    override val howToConnect = "Как подключиться?"
    override val autoConnectTitle = "Автоподключение при запуске"
    override val autoConnectSubtitle = "Подключаться к выбранному конфигу при открытии приложения"
    override val confirmDeleteTitle = "Подтверждение удаления"
    override val confirmDeleteSubtitle = "Спрашивать перед удалением подписок и конфигов"
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
    override val saveAndApply = "Сохранить и применить"
    override val routingTitle = "Маршрутизация и правила"
    override val bypassLan = "Обход LAN"
    override val bypassLanSubtitle = "Локальные/приватные адреса идут напрямую"
    override val bypassRussia = "Обход России"
    override val bypassRussiaSubtitle = "RU-сайты и IP идут напрямую (geoip + geosite)"
    override val blockAds = "Блокировка рекламы"
    override val blockAdsSubtitle = "Резать рекламные / трекерные домены"
    override val directDomains = "Прямые домены"
    override val blockedDomains = "Заблокированные домены"
    override val domainsPlaceholder = "example.com, по одному на строку"
    override val presets = "Пресеты"
    override val presetRuDirect = "Россия напрямую"
    override val presetAdsBlock = "Блок рекламы"
    override val presetRuAds = "Россия + без рекламы"
    override val presetAllVpn = "Всё через VPN"
    override val presetReset = "Сбросить"
    override val urlSchemeAddConfig = "ДОБАВИТЬ КОНФИГ"
    override val urlSchemeAddSubscription = "ДОБАВИТЬ ПОДПИСКУ"
    override val urlSchemeControl = "УПРАВЛЕНИЕ"
    override val copy = "Копировать"
    override val addConnection = "Добавить подключение"
    override val addConnectionSubtitle = "Подписка или своя локация"
    override val scanQr = "Сканировать QR-код"
    override val scanQrSubtitle = "Подписка или olcrtc URI"
    override val pasteLink = "Вставить ссылку или URI"
    override val pasteLinkSubtitle = "Подписка, olcrtc или sing-box конфиг"
    override val importFile = "Импорт из файла"
    override val importFileSubtitle = "Подписка, olcrtc или sing-box конфиг"
    override val updateSubscriptionsAction = "Обновить подписки"
    override val updateSubscriptionsSubtitle = "Обновить локации импортированных подписок"
    override val createCustomLocation = "Создать свою локацию"
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
    override val copyFullConfig = "Скопировать конфиг"
    override val currentConfig = "Текущий конфиг"
    override val subscriptionsSection = "Подписки"
    override val noSubscriptions = "Нет подписок"
    override val noSubscriptionsSubtitle = "Импортированные HTTPS-подписки появятся здесь."
    override val listenAddress = "Адрес прослушивания"
    override val listenAddressRequired = "Укажите адрес"
    override val savingRestarts = "Сохранение перезапустит активное подключение"
    override val unsavedChange = "Несохранённое изменение"
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
    override val delete = "Delete"
    override val cancel = "Cancel"
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
    override fun hwid(v: String) = "HWID: $v"
    override val community = "Community"
    override val howToConnect = "How to connect?"
    override val autoConnectTitle = "Auto-connect on launch"
    override val autoConnectSubtitle = "Connect to the selected config when the app opens"
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
    override val saveAndApply = "Save & apply"
    override val routingTitle = "Routing & rules"
    override val bypassLan = "Bypass LAN"
    override val bypassLanSubtitle = "Local/private addresses go direct"
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
}
