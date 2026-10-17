package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionKeyRenameTest {
    @Test
    fun pinsCollapseSortAndFolderMembershipFollowTheNewKey() {
        val old = "Panel|https://one.test/sub"
        val new = "Panel|https://moved.test/sub"
        val other = "Other|https://two.test/sub"
        val before = AppBehaviorSettings(
            collapsedSubscriptionGroups = setOf(old),
            pinnedSubscriptionGroups = listOf(other, old),
            pingSortedSubscriptionGroups = setOf(old),
            pingSortDescendingSubscriptionGroups = setOf(old, other),
            customGroups = listOf(CustomGroup("f", "Folder", members = listOf(CustomGroup.subMember(old), CustomGroup.subMember(other)))),
        )
        val after = before.withSubscriptionKeyRenamed(old, new)
        assertEquals(setOf(new), after.collapsedSubscriptionGroups)
        assertEquals(listOf(other, new), after.pinnedSubscriptionGroups)
        assertEquals(setOf(new), after.pingSortedSubscriptionGroups)
        assertEquals(setOf(new, other), after.pingSortDescendingSubscriptionGroups)
        assertEquals(listOf(CustomGroup.subMember(new), CustomGroup.subMember(other)), after.customGroups.single().members)
        assertEquals(before, before.withSubscriptionKeyRenamed(old, old))
    }
}
