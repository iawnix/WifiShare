package io.iaw.lanshare

import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadStatusMachineTest {
    @Test
    fun lifecycleTracksProgressAndCompletion() {
        val started = UploadStatusMachine.begin("op", "server", "kali", 3, 1_000L)
        val uploading = UploadStatusMachine.uploading(
            current = started,
            itemName = "paper.pdf",
            itemIndex = 2,
            completedItems = 1,
            bytesSent = 512L,
            totalBytes = 1_024L,
            now = 2_000L,
        )
        val completed = UploadStatusMachine.complete(uploading, 3_000L)

        assertEquals(UploadPhase.UPLOADING, uploading.phase)
        assertEquals(1, uploading.completedItems)
        assertEquals(512L, uploading.bytesSent)
        assertEquals(UploadPhase.SUCCESS, completed.phase)
        assertEquals(3, completed.completedItems)
    }

    @Test
    fun cancellationKeepsCompletedFileCount() {
        val started = UploadStatusMachine.begin("op", "server", "kali", 4, 1_000L)
        val requested = UploadStatusMachine.requestCancel(started, 2_000L)
        val cancelled = UploadStatusMachine.cancelled(requested, 2, 3_000L)

        assertTrue(requested.isActive())
        assertEquals(UploadPhase.CANCEL_REQUESTED, requested.phase)
        assertEquals(UploadPhase.CANCELLED, cancelled.phase)
        assertEquals(2, cancelled.completedItems)
        assertEquals(4, cancelled.totalItems)
    }

    @Test
    fun staleAndExpiredStatusesAreReconciled() {
        val started = UploadStatusMachine.begin("op", "server", "kali", 1, 1_000L)
        val interruptedAt = 1_000L + UploadStatusMachine.STALE_AFTER_MILLIS
        val interrupted = UploadStatusMachine.reconcile(started, interruptedAt)

        assertEquals(UploadPhase.INTERRUPTED, interrupted.phase)
        assertFalse(interrupted.isActive())
        assertEquals(
            UploadPhase.IDLE,
            UploadStatusMachine.reconcile(
                interrupted,
                interruptedAt + UploadStatusMachine.RESULT_VISIBLE_MILLIS,
            ).phase,
        )
    }

    @Test
    fun heartbeatKeepsALongRunningUploadActive() {
        val started = UploadStatusMachine.begin("op", "server", "kali", 1, 1_000L)
        val heartbeatAt = 1_000L + UploadStatusMachine.STALE_AFTER_MILLIS - 1L
        val alive = UploadStatusMachine.heartbeat(started, heartbeatAt)

        assertEquals(
            UploadPhase.PREFLIGHT,
            UploadStatusMachine.reconcile(alive, heartbeatAt + 1L).phase,
        )
    }

    @Test
    fun onlyEventsForThePersistedOperationAreCurrent() {
        val current = UploadStatusMachine.begin("new", "server", "kali", 1, 2_000L)
        val stale = UploadStatusMachine.begin("old", "server", "kali", 1, 1_000L)

        assertTrue(UploadStatusMachine.isCurrentEvent(current, current))
        assertFalse(UploadStatusMachine.isCurrentEvent(current, stale))
        assertFalse(UploadStatusMachine.isCurrentEvent(UploadStatus(), UploadStatus()))
    }

    @Test
    fun classifierDoesNotExposeTransportMessages() {
        assertEquals(
            UploadErrorCode.AUTH_FAILED,
            UploadFailureClassifier.classify(HttpResponseException(401, "secret")),
        )
        assertEquals(
            UploadErrorCode.TLS_MISMATCH,
            UploadFailureClassifier.classify(SSLHandshakeException("fingerprint")),
        )
        assertEquals(
            UploadErrorCode.NETWORK_UNREACHABLE,
            UploadFailureClassifier.classify(ConnectException("offline")),
        )
        assertEquals(
            UploadErrorCode.FILE_UNAVAILABLE,
            UploadFailureClassifier.classify(SharedFileUnavailableException("gone")),
        )
        assertEquals(
            UploadErrorCode.FILE_TOO_LARGE,
            UploadFailureClassifier.classify(HttpResponseException(413, "limit")),
        )
        assertEquals(
            UploadErrorCode.SERVER_STORAGE_FULL,
            UploadFailureClassifier.classify(HttpResponseException(507, "capacity")),
        )
    }
}
