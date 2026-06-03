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

    // Theme color picker
    val themeColor: String
    val elementColor: String
    val textColor: String
    val customColorRgb: String

    // Subscriptions sharing extra
    val qrShare: String
    val refresh: String

    // Experimental section
    val experimental: String
    val experimentalSubtitle: String
    val experimentalUnlocked: String
    val notifSpeed: String
    val notifSpeedSubtitle: String
    val telemostCookiesDescription: String
    val useTelemostCookies: String
    val useTelemostCookiesSubtitle: String
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
    val freeturnTransportSection: String
    val freeturnTransportSubtitle: String
    val wireguardSubtitle: String
    val proxyOverVkturn: String
    val proxyOverVkturnSubtitle: String
    val vkCallLinksSection: String
    val vkCallLinksSubtitle: String
    fun vkCallLinkNumbered(n: Int): String
    val additionalCalls: String
    val coreSection: String
    val coreSubtitle: String
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
    val pingChecking: String
    val pingVerify: String
    val connectivityCheck: String

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
    override val delete = "Удалить"
    override val cancel = "Отмена"
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
    override val saveAndApply = "Сохранить и применить"
    override val routingTitle = "Маршрутизация и правила"
    override val bypassLan = "Обход LAN"
    override val bypassLanSubtitle = "Локальные/приватные адреса идут напрямую"
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
    override val themeColor = "Цвет темы"
    override val elementColor = "Цвет элементов"
    override val textColor = "Цвет текста"
    override val customColorRgb = "Произвольный цвет (RGB)"
    override val qrShare = "QR / поделиться"
    override val refresh = "Обновить"
    override val experimental = "Экспериментальные"
    override val experimentalSubtitle = "Cookies Telemost и прочее"
    override val experimentalUnlocked = "Экспериментальные настройки разблокированы"
    override val notifSpeed = "Скорость в уведомлении"
    override val notifSpeedSubtitle = "Показывать загрузку ↓ и отдачу ↑ в шторке"
    override val telemostCookiesDescription =
        "Cookies авторизованного аккаунта Яндекса (заголовок Cookie, напр. " +
            "«Session_id=…; yandexuid=…») — для приватных конференций. Запустить кастомное ядро " +
            "отдельным бинарём на Android невозможно, поэтому эта функция встроена в штатное ядро."
    override val useTelemostCookies = "Использовать cookies Telemost"
    override val useTelemostCookiesSubtitle = "Подставлять cookies при подключении к Telemost"
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
    override val freeturnTransportSection = "Транспорт Freeturn"
    override val freeturnTransportSubtitle = "Адрес ретранслятора VK TURN и обфускация"
    override val wireguardSubtitle = "Туннель sing-box подключается через локальный слушатель freeturn"
    override val proxyOverVkturn = "Прокси поверх VK-TURN (необязательно)"
    override val proxyOverVkturnSubtitle =
        "Добавить прокси vless/vmess/trojan/ss поверх туннеля WireGuard"
    override val vkCallLinksSection = "Ссылки на звонки VK"
    override val vkCallLinksSubtitle =
        "Личная ссылка-приглашение VK Звонков (обязательно). Можно добавить до 5 — каждый " +
            "дополнительный звонок прибавляет пропускную способность (туннель распределяется между ними)."
    override fun vkCallLinkNumbered(n: Int) = "Ссылка на звонок VK $n (необязательно)"
    override val additionalCalls = "Дополнительные звонки"
    override val coreSection = "Ядро"
    override val coreSubtitle = "«Авто» выбирает Xray для xhttp, иначе sing-box"
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
    override val pingChecking = "Проверка…"
    override val pingVerify = "Нажмите для проверки доступности"
    override val connectivityCheck = "Проверка соединения"
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
    override val themeColor = "Theme color"
    override val elementColor = "Element color"
    override val textColor = "Text color"
    override val customColorRgb = "Custom color (RGB)"
    override val qrShare = "QR / share"
    override val refresh = "Refresh"
    override val experimental = "Experimental"
    override val experimentalSubtitle = "Telemost cookies and more"
    override val experimentalUnlocked = "Experimental settings unlocked"
    override val notifSpeed = "Speed in notification"
    override val notifSpeedSubtitle = "Show download ↓ and upload ↑ in the shade"
    override val telemostCookiesDescription =
        "Cookies of a signed-in Yandex account (the Cookie header, e.g. " +
            "\"Session_id=…; yandexuid=…\") — for private conferences. A custom core cannot be " +
            "launched as a separate binary on Android, so this feature is built into the stock core."
    override val useTelemostCookies = "Use Telemost cookies"
    override val useTelemostCookiesSubtitle = "Attach cookies when connecting to Telemost"
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
    override val freeturnTransportSection = "Freeturn transport"
    override val freeturnTransportSubtitle = "VK TURN relay endpoint and obfuscation"
    override val wireguardSubtitle = "The tunnel sing-box dials through the local freeturn listener"
    override val proxyOverVkturn = "Proxy over VK-TURN (optional)"
    override val proxyOverVkturnSubtitle =
        "Chain a vless/vmess/trojan/ss proxy on top of the WireGuard tunnel"
    override val vkCallLinksSection = "VK call link(s)"
    override val vkCallLinksSubtitle =
        "Personal VK Calls join link (required). Add up to 5 — each extra call " +
            "adds bandwidth (the tunnel is spread across them)."
    override fun vkCallLinkNumbered(n: Int) = "VK call link $n (optional)"
    override val additionalCalls = "Additional calls"
    override val coreSection = "Core"
    override val coreSubtitle = "Auto picks Xray for xhttp, otherwise sing-box"
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
    override val pingChecking = "Checking..."
    override val pingVerify = "Click to verify reachability"
    override val connectivityCheck = "Connectivity check"
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
}
