package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

/** General application behavior toggles (the "Настройки приложения" screen). */
@Serializable
data class AppBehaviorSettings(
    /** Auto-connect the selected configuration when the app is launched. */
    val autoConnectOnLaunch: Boolean = false,
    /** Ask for confirmation before deleting subscriptions / configs. */
    val confirmBeforeDelete: Boolean = true,
)
