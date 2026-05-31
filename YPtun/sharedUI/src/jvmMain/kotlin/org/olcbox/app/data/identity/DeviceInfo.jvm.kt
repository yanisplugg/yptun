package org.olcbox.app.data.identity

actual object DeviceInfo {
    actual val os: String = System.getProperty("os.name") ?: "Desktop"
    actual val osVersion: String = System.getProperty("os.version") ?: ""
    actual val model: String = System.getProperty("os.arch") ?: ""
}
