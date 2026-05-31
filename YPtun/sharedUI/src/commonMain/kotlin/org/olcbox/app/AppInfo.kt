package org.olcbox.app

data class AppInfo(
    val name: String,
    val version: String
)

object CurrentAppInfo {
    val value: AppInfo = AppInfo(
        name = GeneratedAppInfo.NAME,
        version = GeneratedAppInfo.VERSION
    )

    val userAgent: String = "${value.name}/${value.version}"
}
