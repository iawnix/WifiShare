package io.iaw.lanshare

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeServerSelectionPolicyTest {
    @Test
    fun tappingTheCurrentServerIsANoOpEvenDuringTransfer() {
        assertEquals(
            HomeServerSelectionDecision.ALREADY_ACTIVE,
            HomeServerSelectionPolicy.decide("server-a", "server-a", transferActive = true),
        )
    }

    @Test
    fun transferBlocksChangingTheTarget() {
        assertEquals(
            HomeServerSelectionDecision.BLOCKED_BY_TRANSFER,
            HomeServerSelectionPolicy.decide("server-a", "server-b", transferActive = true),
        )
    }

    @Test
    fun idleStateAllowsChangingTheTarget() {
        assertEquals(
            HomeServerSelectionDecision.SELECT,
            HomeServerSelectionPolicy.decide("server-a", "server-b", transferActive = false),
        )
    }
}
