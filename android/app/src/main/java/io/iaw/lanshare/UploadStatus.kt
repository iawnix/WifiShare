package io.iaw.lanshare

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class UploadPhase {
    IDLE,
    PREFLIGHT,
    UPLOADING,
    CANCEL_REQUESTED,
    SUCCESS,
    ERROR,
    CANCELLED,
    BUSY,
    INTERRUPTED,
}

enum class UploadErrorCode {
    NONE,
    INVALID_CONFIG,
    NO_ITEMS,
    FILE_UNAVAILABLE,
    NETWORK_UNREACHABLE,
    TLS_MISMATCH,
    AUTH_FAILED,
    FILE_TOO_LARGE,
    SERVER_STORAGE_FULL,
    SERVER_REJECTED,
    UNKNOWN,
}

data class UploadStatus(
    val operationId: String = "",
    val serverId: String = "",
    val serverName: String = "",
    val phase: UploadPhase = UploadPhase.IDLE,
    val currentItemName: String = "",
    val currentItemIndex: Int = 0,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val bytesSent: Long = 0L,
    val totalBytes: Long = 0L,
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L,
    val heartbeatAtMillis: Long = 0L,
    val errorCode: UploadErrorCode = UploadErrorCode.NONE,
) {
    fun isActive(): Boolean = phase in ACTIVE_PHASES
    fun isTerminal(): Boolean = phase in TERMINAL_PHASES

    private companion object {
        val ACTIVE_PHASES = setOf(
            UploadPhase.PREFLIGHT,
            UploadPhase.UPLOADING,
            UploadPhase.CANCEL_REQUESTED,
        )
        val TERMINAL_PHASES = setOf(
            UploadPhase.SUCCESS,
            UploadPhase.ERROR,
            UploadPhase.CANCELLED,
            UploadPhase.BUSY,
            UploadPhase.INTERRUPTED,
        )
    }
}

internal object UploadStatusMachine {
    const val STALE_AFTER_MILLIS = 5 * 60 * 1000L
    const val RESULT_VISIBLE_MILLIS = 60 * 1000L

    fun begin(
        operationId: String,
        serverId: String,
        serverName: String,
        totalItems: Int,
        now: Long,
    ): UploadStatus {
        return UploadStatus(
            operationId = operationId,
            serverId = serverId,
            serverName = serverName,
            phase = UploadPhase.PREFLIGHT,
            totalItems = totalItems.coerceAtLeast(0),
            startedAtMillis = now,
            heartbeatAtMillis = now,
        )
    }

    fun preflight(
        current: UploadStatus,
        itemName: String,
        itemIndex: Int,
        completedItems: Int,
        now: Long,
    ): UploadStatus {
        return current.copy(
            phase = UploadPhase.PREFLIGHT,
            currentItemName = itemName,
            currentItemIndex = itemIndex,
            completedItems = completedItems,
            bytesSent = 0L,
            totalBytes = 0L,
            heartbeatAtMillis = now,
        )
    }

    fun uploading(
        current: UploadStatus,
        itemName: String,
        itemIndex: Int,
        completedItems: Int,
        bytesSent: Long,
        totalBytes: Long,
        now: Long,
    ): UploadStatus {
        return current.copy(
            phase = UploadPhase.UPLOADING,
            currentItemName = itemName,
            currentItemIndex = itemIndex,
            completedItems = completedItems,
            bytesSent = bytesSent.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            heartbeatAtMillis = now,
        )
    }

    fun requestCancel(current: UploadStatus, now: Long): UploadStatus {
        return current.copy(
            phase = UploadPhase.CANCEL_REQUESTED,
            heartbeatAtMillis = now,
        )
    }

    fun heartbeat(current: UploadStatus, now: Long): UploadStatus {
        return current.copy(heartbeatAtMillis = now)
    }

    fun complete(current: UploadStatus, now: Long): UploadStatus {
        return current.copy(
            phase = UploadPhase.SUCCESS,
            currentItemName = "",
            completedItems = current.totalItems,
            bytesSent = 0L,
            totalBytes = 0L,
            completedAtMillis = now,
            heartbeatAtMillis = now,
            errorCode = UploadErrorCode.NONE,
        )
    }

    fun fail(current: UploadStatus, errorCode: UploadErrorCode, now: Long): UploadStatus {
        return current.copy(
            phase = UploadPhase.ERROR,
            completedAtMillis = now,
            heartbeatAtMillis = now,
            errorCode = errorCode,
        )
    }

    fun cancelled(current: UploadStatus, completedItems: Int, now: Long): UploadStatus {
        return current.copy(
            phase = UploadPhase.CANCELLED,
            completedItems = completedItems.coerceIn(0, current.totalItems),
            bytesSent = 0L,
            totalBytes = 0L,
            completedAtMillis = now,
            heartbeatAtMillis = now,
            errorCode = UploadErrorCode.NONE,
        )
    }

    fun busy(operationId: String, serverId: String, serverName: String, totalItems: Int): UploadStatus {
        return UploadStatus(
            operationId = operationId,
            serverId = serverId,
            serverName = serverName,
            phase = UploadPhase.BUSY,
            totalItems = totalItems.coerceAtLeast(0),
        )
    }

    fun interrupt(current: UploadStatus, now: Long): UploadStatus {
        return current.copy(
            phase = UploadPhase.INTERRUPTED,
            completedAtMillis = now,
            heartbeatAtMillis = now,
            errorCode = UploadErrorCode.UNKNOWN,
        )
    }

    fun reconcile(current: UploadStatus, now: Long): UploadStatus {
        if (current.isActive() && now - current.heartbeatAtMillis >= STALE_AFTER_MILLIS) {
            return interrupt(current, now)
        }
        if (
            current.isTerminal() &&
            current.completedAtMillis > 0L &&
            now - current.completedAtMillis >= RESULT_VISIBLE_MILLIS
        ) {
            return UploadStatus()
        }
        return current
    }

    fun isCurrentEvent(current: UploadStatus, announced: UploadStatus): Boolean {
        return announced.operationId.isNotBlank() && current.operationId == announced.operationId
    }
}

internal class UploadOperationGate {
    private val activeOperationId = AtomicReference<String?>(null)

    fun tryAcquire(operationId: String): Boolean {
        return operationId.isNotBlank() && activeOperationId.compareAndSet(null, operationId)
    }

    fun release(operationId: String): Boolean = activeOperationId.compareAndSet(operationId, null)
    fun isHeldBy(operationId: String): Boolean = activeOperationId.get() == operationId
}

internal class SharedFileUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal object UploadFailureClassifier {
    fun classify(error: Throwable): UploadErrorCode {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val response = causes.filterIsInstance<HttpResponseException>().firstOrNull()
        return when {
            causes.any { it is SharedFileUnavailableException || it is SecurityException } ->
                UploadErrorCode.FILE_UNAVAILABLE
            response?.statusCode == 401 || response?.statusCode == 403 -> UploadErrorCode.AUTH_FAILED
            response?.statusCode == 413 -> UploadErrorCode.FILE_TOO_LARGE
            response?.statusCode == 507 -> UploadErrorCode.SERVER_STORAGE_FULL
            response != null -> UploadErrorCode.SERVER_REJECTED
            causes.any {
                it is SSLHandshakeException ||
                    it is SSLPeerUnverifiedException ||
                    it is CertificateException
            } -> UploadErrorCode.TLS_MISMATCH
            causes.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketTimeoutException
            } -> UploadErrorCode.NETWORK_UNREACHABLE
            error is IOException -> UploadErrorCode.NETWORK_UNREACHABLE
            else -> UploadErrorCode.UNKNOWN
        }
    }
}
