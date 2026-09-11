package org.olcbox.app.ios

/** The Swift bridges, for code that can't have it passed in (the shared settings screens). */
internal object IosPlatformHooks {
    var bridge: IosPlatformBridge? = null
    var core: IosCoreBridge? = null
}
