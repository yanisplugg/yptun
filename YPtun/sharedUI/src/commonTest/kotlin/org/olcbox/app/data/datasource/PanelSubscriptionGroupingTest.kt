package org.olcbox.app.data.datasource

import kotlin.test.Test
import kotlin.test.assertEquals
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata

class PanelSubscriptionGroupingTest {
    private val panel = LocationMetadata(subscription = SubscriptionMetadata(name = "YPtun", used = "1 GB", updateIntervalHours = 6))

    private fun entry(own: SubscriptionMetadata?) = LocationEntry(
        storageId = "a", metadata = own?.let { LocationMetadata(subscription = it) },
    )

    @Test
    fun olcRtcEntryWithoutTitleJoinsThePanelSubscription() {
        val own = SubscriptionMetadata(refresh = "10m", updateIntervalHours = 1)
        val merged = withPanelSubscription(entry(own), panel).metadata?.subscription
        assertEquals("YPtun", merged?.name)
        assertEquals("1 GB", merged?.used)
        assertEquals("10m", merged?.refresh) // its own header survives where the panel has nothing
    }

    @Test
    fun entryWithoutMetadataGetsThePanelOneAndATitledOneKeepsItsTitle() {
        assertEquals("YPtun", withPanelSubscription(entry(null), panel).metadata?.subscription?.name)
        assertEquals("Own", withPanelSubscription(entry(SubscriptionMetadata(name = "Own")), panel).metadata?.subscription?.name)
        assertEquals(null, withPanelSubscription(entry(null), null).metadata)
    }
}
