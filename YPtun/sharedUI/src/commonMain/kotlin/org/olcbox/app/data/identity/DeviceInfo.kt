package org.olcbox.app.data.identity

/**
 * Lightweight, platform-provided device descriptors sent alongside the HWID so that a
 * Remnawave-style panel can label devices in its HWID device-limit list.
 *  - [os]        e.g. "Android", "Windows", "iOS"
 *  - [osVersion] e.g. "14", "10.0.19045"
 *  - [model]     e.g. "Xiaomi 23021RAA2Y"
 */
expect object DeviceInfo {
    val os: String
    val osVersion: String
    val model: String
}
