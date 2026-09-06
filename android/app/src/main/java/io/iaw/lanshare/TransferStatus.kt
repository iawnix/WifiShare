package io.iaw.lanshare

import java.util.concurrent.atomic.AtomicReference

enum class TransferPhase {
    UNCONFIGURED,
    IDLE,
    CHECKING,
    RECEIVING,
    SUCCESS,
    EMPTY,
    ERROR,
    BUSY,
    INTERRUPTED,
}

enum class TransferErrorCode {
    NONE,
    NO_CONFIG,
    NETWORK_UNREACHABLE,
    TLS_MISMATCH,
    AUTH_FAILED,
    STORAGE_FAILED,
    PARTIAL_FAILURE,
    UNKNOWN,
}

data class TransferStatus(
    val operationId: String = "",
    val serverId: String = "",
    val sourceAppWidgetId: Int = -1,
    val phase: TransferPhase = TransferPhase.IDLE,
    val currentItemName: String = "",
    val currentItemIndex: Int = 0,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val bytesReceived: Long = 0L,
    val totalBytes: Long = 0L,
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L,
    val heartbeatAtMillis: Long = 0L,
    val errorCode: TransferErrorCode = TransferErrorCode.NONE,
) {
    fun isActive(): Boolean = phase == TransferPhase.CHECKING || phase == TransferPhase.RECEIVING

    fun isTerminal(): Boolean = phase in setOf(
        TransferPhase.SUCCESS,
        TransferPhase.EMPTY,
        TransferPhase.ERROR,
        TransferPhase.BUSY,
        TransferPhase.INTERRUPTED,
    )

    companion object {
        fun idle(serverId: String = ""): TransferStatus = TransferStatus(serverId = serverId)
    }
}

internal object TransferStatusMachine {
    const val STALE_AFTER_MILLIS = 5 * 60 * 1000L
    const val RESULT_VISIBLE_MILLIS = 30 * 1000L

    fun begin(operationId: String, serverId: String, sourceAppWidgetId: Int, now: Long): TransferStatus {
        return TransferStatus(
            operationId = operationId,
            serverId = serverId,
            sourceAppWidgetId = sourceAppWidgetId,
            phase = TransferPhase.CHECKING,
            startedAtMillis = now,
            heartbeatAtMillis = now,
        )
    }

    fun bindServer(current: TransferStatus, serverId: String): TransferStatus {
        if (!current.isActive() || current.serverId.isNotBlank()) return current
        return current.copy(serverId = serverId)
    }

    fun checking(current: TransferStatus, completedItems: Int, now: Long): TransferStatus {
        return current.copy(
            phase = TransferPhase.CHECKING,
            currentItemName = "",
            currentItemIndex = completedItems + 1,
            completedItems = completedItems,
            bytesReceived = 0L,
            totalBytes = 0L,
            heartbeatAtMillis = now,
        )
    }

    fun receiving(
        current: TransferStatus,
        itemName: String,
        itemIndex: Int,
        completedItems: Int,
        bytesReceived: Long,
        totalBytes: Long,
        now: Long,
    ): TransferStatus {
        return current.copy(
            phase = TransferPhase.RECEIVING,
            currentItemName = itemName,
            currentItemIndex = itemIndex,
            completedItems = completedItems,
            bytesReceived = bytesReceived.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            heartbeatAtMillis = now,
        )
    }

    fun complete(current: TransferStatus, receivedItems: Int, now: Long): TransferStatus {
        return current.copy(
            phase = if (receivedItems > 0) TransferPhase.SUCCESS else TransferPhase.EMPTY,
            currentItemName = "",
            completedItems = receivedItems,
            totalItems = receivedItems,
            bytesReceived = 0L,
            totalBytes = 0L,
            completedAtMillis = now,
            heartbeatAtMillis = now,
            errorCode = TransferErrorCode.NONE,
        )
    }

    fun fail(current: TransferStatus, errorCode: TransferErrorCode, now: Long): TransferStatus {
        return current.copy(
            phase = TransferPhase.ERROR,
            completedAtMillis = now,
            heartbeatAtMillis = now,
            errorCode = errorCode,
        )
    }

    fun interrupt(current: TransferStatus, now: Long): TransferStatus {
        return current.copy(
            phase = TransferPhase.INTERRUPTED,
            completedAtMillis = now,
            heartbeatAtMillis = now,
            errorCode = TransferErrorCode.UNKNOWN,
        )
    }

    fun reconcile(current: TransferStatus, now: Long): TransferStatus {
        if (current.isActive() && now - current.heartbeatAtMillis >= STALE_AFTER_MILLIS) {
            return interrupt(current, now)
        }
        if (
            current.isTerminal() &&
            current.completedAtMillis > 0L &&
            now - current.completedAtMillis >= RESULT_VISIBLE_MILLIS
        ) {
            return TransferStatus.idle(current.serverId)
        }
        return current
    }

    fun forServer(current: TransferStatus, serverId: String?): TransferStatus {
        if (serverId.isNullOrBlank()) {
            return TransferStatus(phase = TransferPhase.UNCONFIGURED)
        }
        if (current.isActive() && current.serverId != serverId) {
            return TransferStatus(serverId = serverId, phase = TransferPhase.BUSY)
        }
        return if (current.serverId == serverId) current else TransferStatus.idle(serverId)
    }

    fun nextRefreshDelayMillis(current: TransferStatus, now: Long): Long? {
        val deadline = when {
            current.isActive() -> current.heartbeatAtMillis + STALE_AFTER_MILLIS
            current.isTerminal() && current.completedAtMillis > 0L ->
                current.completedAtMillis + RESULT_VISIBLE_MILLIS
            else -> return null
        }
        return (deadline - now).coerceAtLeast(1_000L)
    }
}

internal class ReceiveOperationGate {
    private val activeOperationId = AtomicReference<String?>(null)

    fun tryAcquire(operationId: String): Boolean {
        return operationId.isNotBlank() && activeOperationId.compareAndSet(null, operationId)
    }

    fun release(operationId: String): Boolean {
        return activeOperationId.compareAndSet(operationId, null)
    }

    fun isHeldBy(operationId: String): Boolean = activeOperationId.get() == operationId
}
