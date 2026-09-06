package io.iaw.lanshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferHistoryTest {
    @Test
    fun historyJsonRoundTripsWithoutCredentialsOrFileContent() {
        val entry = TransferHistoryEntry(
            operationId = "operation-1",
            direction = TransferDirection.SEND,
            serverId = "server-1",
            serverName = "Studio",
            completedItems = 2,
            totalItems = 3,
            result = TransferHistoryResult.CANCELLED,
            completedAtMillis = 1234L,
        )

        val encoded = TransferHistoryJson.encode(listOf(entry))

        assertEquals(listOf(entry), TransferHistoryJson.decode(encoded))
        assertTrue("auth_token" !in encoded)
        assertTrue("certificate" !in encoded)
        assertTrue("file_name" !in encoded)
    }

    @Test
    fun malformedHistoryIsIgnored() {
        assertEquals(emptyList<TransferHistoryEntry>(), TransferHistoryJson.decode("not-json"))
    }
}
