package org.olcbox.app.data.identity

import platform.UIKit.UIDevice

actual object DeviceInfo {
    actual val os: String = UIDevice.currentDevice.systemName
    actual val osVersion: String = UIDevice.currentDevice.systemVersion
    actual val model: String = UIDevice.currentDevice.model
}
