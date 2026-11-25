package org.olcbox.app.vpn.zapret

import org.olcbox.app.data.model.ZapretConfig

/** Probe outcome for a single ALT strategy tested during Zapret Auto-tuning. */
data class ZapretAltProbeResult(
    val strategyKey: String,
    val label: String,
    val passedCount: Int,
    val totalCount: Int,
    val rttMs: Long?,
) {
    val succeeded: Boolean get() = passedCount > 0
}

/** Combined result of running [probeZapretAltStrategies]. */
data class ZapretAutoTuneResult(
    val selectedStrategy: String,
    val resolvedConfig: ZapretConfig,
    val probes: List<ZapretAltProbeResult>,
)

expect suspend fun probeZapretAltStrategies(
    config: ZapretConfig,
    forceRefresh: Boolean = true,
): ZapretAutoTuneResult
