package org.olcbox.app.data.identity

import android.os.Build

actual object DeviceInfo {
    actual val os: String = "Android"
    actual val osVersion: String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()
    actual val model: String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { !it.isNullOrBlank() }
        .joinToString(" ")
        .ifBlank { Build.MODEL ?: "Android" }
}
