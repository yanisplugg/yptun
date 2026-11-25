package org.olcbox.app.vpn.zapret

import org.olcbox.app.data.model.ZapretConfig

actual suspend fun probeZapretAltStrategies(
    config: ZapretConfig,
    forceRefresh: Boolean,
): ZapretAutoTuneResult {
    val norm = config.normalized()
    return ZapretAutoTuneResult(
        selectedStrategy = if (norm.strategy == ZapretConfig.STRATEGY_AUTO) ZapretConfig.STRATEGY_ALT1 else norm.strategy,
        resolvedConfig = if (norm.strategy == ZapretConfig.STRATEGY_AUTO) norm.withPreset(ZapretConfig.STRATEGY_ALT1) else norm,
        probes = emptyList(),
    )
}
