package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

class UpstreamNetworkSelectorTest {

    @Test
    fun prefersActiveUnvalidatedCellularOverInactiveValidatedWifi() {
        val candidates = listOf(
            UpstreamCandidate(
                isActive = false,
                isValidated = true,
                transport = UpstreamTransport.Wifi
            ),
            UpstreamCandidate(
                isActive = true,
                isValidated = false,
                transport = UpstreamTransport.Cellular
            )
        )

        assertEquals(1, UpstreamNetworkSelector.selectIndex(candidates))
    }

    @Test
    fun prefersValidatedWhenCandidatesHaveSameActivityAndTransport() {
        val candidates = listOf(
            UpstreamCandidate(
                isActive = false,
                isValidated = false,
                transport = UpstreamTransport.Cellular
            ),
            UpstreamCandidate(
                isActive = false,
                isValidated = true,
                transport = UpstreamTransport.Cellular
            )
        )

        assertEquals(1, UpstreamNetworkSelector.selectIndex(candidates))
    }
}
