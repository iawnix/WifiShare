package io.iaw.lanshare

import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferFailureClassifierTest {
    @Test
    fun classifiesStructuredFailuresWithoutExposingMessages() {
        assertEquals(
            TransferErrorCode.AUTH_FAILED,
            TransferFailureClassifier.classify(HttpResponseException(401, "token"), 0),
        )
        assertEquals(
            TransferErrorCode.TLS_MISMATCH,
            TransferFailureClassifier.classify(SSLHandshakeException("certificate"), 0),
        )
        assertEquals(
            TransferErrorCode.NETWORK_UNREACHABLE,
            TransferFailureClassifier.classify(ConnectException("offline"), 0),
        )
        assertEquals(
            TransferErrorCode.STORAGE_FAILED,
            TransferFailureClassifier.classify(StorageWriteException("disk"), 0),
        )
    }

    @Test
    fun completedItemsTurnAnyLaterFailureIntoPartialFailure() {
        assertEquals(
            TransferErrorCode.PARTIAL_FAILURE,
            TransferFailureClassifier.classify(ConnectException("offline"), 2),
        )
    }
}
