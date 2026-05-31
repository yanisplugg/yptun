package org.olcbox.app.vpn

internal enum class UpstreamTransport {
    Wifi,
    Cellular,
    Other
}

internal data class UpstreamCandidate(
    val isActive: Boolean,
    val isValidated: Boolean,
    val transport: UpstreamTransport
)

internal object UpstreamNetworkSelector {
    fun selectIndex(candidates: List<UpstreamCandidate>): Int? {
        return candidates.indices.maxByOrNull { priority(candidates[it]) }
    }

    private fun priority(candidate: UpstreamCandidate): Int {
        var score = 0
        if (candidate.isActive) score += 100
        if (candidate.isValidated) score += 50
        score += when (candidate.transport) {
            UpstreamTransport.Wifi -> 20
            UpstreamTransport.Cellular -> 10
            UpstreamTransport.Other -> 0
        }
        return score
    }
}
