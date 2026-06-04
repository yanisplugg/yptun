package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

/** General application behavior toggles (the "Настройки приложения" screen). */
@Serializable
data class AppBehaviorSettings(
    /** Auto-connect the selected configuration when the app is launched. */
    val autoConnectOnLaunch: Boolean = false,
    /** Ask for confirmation before deleting subscriptions / configs. */
    val confirmBeforeDelete: Boolean = true,
    /** Show live download/upload speed in the foreground notification. */
    val showSpeedInNotification: Boolean = false,
    /** Hidden "Experimental" section unlocked by tapping the connection timer 5×. */
    val experimentalUnlocked: Boolean = false,
    /** Yandex auth cookie header for Telemost (e.g. "Session_id=…; yandexuid=…"). */
    val telemostCookies: String = "",
    /** Whether the stored Telemost cookies are applied on connect. */
    val telemostCookiesEnabled: Boolean = false,
    /** Subscription group keys whose server list is collapsed (chevron). */
    val collapsedSubscriptionGroups: Set<String> = emptySet(),
    /** Subscription group keys pinned to the top, in pin order. */
    val pinnedSubscriptionGroups: List<String> = emptyList(),
    /** Subscription group keys whose servers are sorted by ping. */
    val pingSortedSubscriptionGroups: Set<String> = emptySet(),
)
