package io.iaw.lanshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStatusMachineTest {
    @Test
    fun receiveLifecycleTracksItemAndByteProgress() {
        val started = TransferStatusMachine.begin("op", "server-a", 7, 1_000L)
        val receiving = TransferStatusMachine.receiving(
            started,
            "paper.pdf",
            itemIndex = 2,
            completedItems = 1,
            bytesReceived = 512L,
            totalBytes = 1_024L,
            now = 2_000L,
        )
        val completed = TransferStatusMachine.complete(receiving, 2, 3_000L)

        assertEquals(TransferPhase.RECEIVING, receiving.phase)
        assertEquals("paper.pdf", receiving.currentItemName)
        assertEquals(512L, receiving.bytesReceived)
        assertEquals(TransferPhase.SUCCESS, completed.phase)
        assertEquals(2, completed.completedItems)
    }

    @Test
    fun emptyQueueHasDistinctTerminalState() {
        val started = TransferStatusMachine.begin("op", "server-a", -1, 1_000L)

        assertEquals(TransferPhase.EMPTY, TransferStatusMachine.complete(started, 0, 2_000L).phase)
    }

    @Test
    fun staleOperationRecoversAsInterruptedThenExpiresToIdle() {
        val started = TransferStatusMachine.begin("op", "server-a", -1, 1_000L)
        val interruptedAt = 1_000L + TransferStatusMachine.STALE_AFTER_MILLIS
        val interrupted = TransferStatusMachine.reconcile(started, interruptedAt)

        assertEquals(TransferPhase.INTERRUPTED, interrupted.phase)
        assertFalse(interrupted.isActive())
        assertEquals(
            TransferPhase.IDLE,
            TransferStatusMachine.reconcile(
                interrupted,
                interruptedAt + TransferStatusMachine.RESULT_VISIBLE_MILLIS,
            ).phase,
        )
    }

    @Test
    fun anotherBoundServerRendersBusyWithoutReplacingActiveOperation() {
        val active = TransferStatusMachine.begin("op", "server-a", 10, 1_000L)

        val other = TransferStatusMachine.forServer(active, "server-b")

        assertEquals(TransferPhase.BUSY, other.phase)
        assertEquals("server-a", active.serverId)
        assertTrue(active.isActive())
    }

    @Test
    fun statusSerializationRoundTrips() {
        val status = TransferStatusMachine.receiving(
            TransferStatusMachine.begin("op", "server-a", 3, 1_000L),
            "data.bin",
            1,
            0,
            123L,
            456L,
            2_000L,
        )

        assertEquals(status, TransferStatusJson.decode(TransferStatusJson.encode(status)))
    }
}
