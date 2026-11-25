package org.olcbox.app.vpn.zapret

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.ZapretConfig

actual suspend fun probeZapretAltStrategies(
    config: ZapretConfig,
    forceRefresh: Boolean,
): ZapretAutoTuneResult = withContext(Dispatchers.IO) {
    ZapretSocksServer.probeAltStrategies(
        baseConfig = config,
        forceRefresh = forceRefresh,
    )
}
