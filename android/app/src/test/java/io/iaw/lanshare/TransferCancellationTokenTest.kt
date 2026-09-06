package io.iaw.lanshare

import java.io.Closeable
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferCancellationTokenTest {
    @Test
    fun cancellationIsImmediateButCleanupCanRunOffTheCallerThread() {
        val cleanup = mutableListOf<Runnable>()
        val token = TransferCancellationToken(Executor { cleanup += it })
        val resource = RecordingCloseable()
        token.attach(resource)
        token.cancel()
        token.cancel()
        assertTrue(token.isCancelled)
        assertFalse(resource.closed)
        assertEquals(1, cleanup.size)
        cleanup.single().run()
        assertTrue(resource.closed)
    }

    @Test
    fun cancelClosesAttachedResourcesAndBecomesObservable() {
        val token = TransferCancellationToken()
        val resource = RecordingCloseable()
        token.attach(resource)

        token.cancel()

        assertTrue(token.isCancelled)
        assertTrue(resource.closed)
    }

    @Test(expected = TransferCancelledException::class)
    fun resourcesAttachedAfterCancellationAreClosedAndRejected() {
        val token = TransferCancellationToken()
        val resource = RecordingCloseable()
        token.cancel()

        try {
            token.attach(resource)
        } finally {
            assertTrue(resource.closed)
        }
    }

    private class RecordingCloseable : Closeable {
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
