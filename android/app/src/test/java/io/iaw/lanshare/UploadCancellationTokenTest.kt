package io.iaw.lanshare

import java.io.Closeable
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCancellationTokenTest {
    @Test
    fun cancelClosesAttachedResourcesAndBecomesObservable() {
        val token = UploadCancellationToken()
        val resource = RecordingCloseable()
        token.attach(resource)

        token.cancel()

        assertTrue(token.isCancelled)
        assertTrue(resource.closed)
    }

    @Test(expected = UploadCancelledException::class)
    fun resourcesAttachedAfterCancellationAreClosedAndRejected() {
        val token = UploadCancellationToken()
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
