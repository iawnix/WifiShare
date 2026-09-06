package io.iaw.lanshare

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal class HttpResponseException(
    val statusCode: Int,
    message: String,
) : IOException(message)

internal class StorageWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class DownloadIntegrityException(message: String) : IOException(message)

internal object TransferFailureClassifier {
    fun classify(error: Throwable, completedItems: Int): TransferErrorCode {
        if (completedItems > 0) {
            return TransferErrorCode.PARTIAL_FAILURE
        }
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val response = causes.filterIsInstance<HttpResponseException>().firstOrNull()
        return when {
            response?.statusCode == 401 || response?.statusCode == 403 -> TransferErrorCode.AUTH_FAILED
            causes.any {
                it is SSLHandshakeException ||
                    it is SSLPeerUnverifiedException ||
                    it is CertificateException
            } -> TransferErrorCode.TLS_MISMATCH
            causes.any { it is StorageWriteException || it is SecurityException } -> TransferErrorCode.STORAGE_FAILED
            causes.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketTimeoutException
            } -> TransferErrorCode.NETWORK_UNREACHABLE
            error is IOException && error !is HttpResponseException && error !is DownloadIntegrityException ->
                TransferErrorCode.NETWORK_UNREACHABLE
            else -> TransferErrorCode.UNKNOWN
        }
    }
}
