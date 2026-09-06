package io.iaw.lanshare

import android.content.Context
import org.json.JSONObject

internal object TransferStatusJson {
    fun decode(raw: String?): TransferStatus {
        if (raw.isNullOrBlank()) {
            return TransferStatus()
        }
        return try {
            val json = JSONObject(raw)
            TransferStatus(
                operationId = json.optString("operation_id"),
                serverId = json.optString("server_id"),
                sourceAppWidgetId = json.optInt("source_widget_id", -1),
                phase = enumValueOrDefault(json.optString("phase"), TransferPhase.IDLE),
                currentItemName = json.optString("current_item_name"),
                currentItemIndex = json.optInt("current_item_index"),
                completedItems = json.optInt("completed_items"),
                totalItems = json.optInt("total_items"),
                bytesReceived = json.optLong("bytes_received"),
                totalBytes = json.optLong("total_bytes"),
                startedAtMillis = json.optLong("started_at"),
                completedAtMillis = json.optLong("completed_at"),
                heartbeatAtMillis = json.optLong("heartbeat_at"),
                errorCode = enumValueOrDefault(json.optString("error_code"), TransferErrorCode.NONE),
            )
        } catch (_: Exception) {
            TransferStatus()
        }
    }

    fun encode(status: TransferStatus): String {
        return JSONObject()
            .put("operation_id", status.operationId)
            .put("server_id", status.serverId)
            .put("source_widget_id", status.sourceAppWidgetId)
            .put("phase", status.phase.name)
            .put("current_item_name", status.currentItemName)
            .put("current_item_index", status.currentItemIndex)
            .put("completed_items", status.completedItems)
            .put("total_items", status.totalItems)
            .put("bytes_received", status.bytesReceived)
            .put("total_bytes", status.totalBytes)
            .put("started_at", status.startedAtMillis)
            .put("completed_at", status.completedAtMillis)
            .put("heartbeat_at", status.heartbeatAtMillis)
            .put("error_code", status.errorCode.name)
            .toString()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T {
        return enumValues<T>().firstOrNull { it.name == value } ?: fallback
    }
}

class TransferStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(now: Long = System.currentTimeMillis()): TransferStatus = synchronized(LOCK) {
        val current = loadRaw()
        val reconciled = TransferStatusMachine.reconcile(current, now)
        if (reconciled != current) {
            write(reconciled)
        }
        reconciled
    }

    fun begin(
        operationId: String,
        serverId: String,
        sourceAppWidgetId: Int,
        now: Long,
    ): TransferStatus = synchronized(LOCK) {
        val status = TransferStatusMachine.begin(operationId, serverId, sourceAppWidgetId, now)
        write(status)
        status
    }

    fun updateChecking(operationId: String, completedItems: Int, now: Long): TransferStatus? =
        synchronized(LOCK) {
            updateOperation(operationId) { TransferStatusMachine.checking(it, completedItems, now) }
        }

    fun updateReceiving(
        operationId: String,
        itemName: String,
        itemIndex: Int,
        completedItems: Int,
        bytesReceived: Long,
        totalBytes: Long,
        now: Long,
    ): TransferStatus? = synchronized(LOCK) {
        updateOperation(operationId) {
            TransferStatusMachine.receiving(
                current = it,
                itemName = itemName,
                itemIndex = itemIndex,
                completedItems = completedItems,
                bytesReceived = bytesReceived,
                totalBytes = totalBytes,
                now = now,
            )
        }
    }

    fun complete(operationId: String, receivedItems: Int, now: Long): TransferStatus? =
        synchronized(LOCK) {
            updateOperation(operationId) { TransferStatusMachine.complete(it, receivedItems, now) }
        }

    fun fail(operationId: String, errorCode: TransferErrorCode, now: Long): TransferStatus? =
        synchronized(LOCK) {
            updateOperation(operationId) { TransferStatusMachine.fail(it, errorCode, now) }
        }

    fun interruptActive(now: Long = System.currentTimeMillis()): TransferStatus? = synchronized(LOCK) {
        val current = loadRaw()
        if (!current.isActive()) {
            null
        } else {
            TransferStatusMachine.interrupt(current, now).also(::write)
        }
    }

    private fun updateOperation(
        operationId: String,
        transform: (TransferStatus) -> TransferStatus,
    ): TransferStatus? {
        val current = loadRaw()
        if (current.operationId != operationId || !current.isActive()) {
            return null
        }
        return transform(current).also(::write)
    }

    private fun loadRaw(): TransferStatus {
        return TransferStatusJson.decode(preferences.getString(KEY_STATUS, null))
    }

    private fun write(status: TransferStatus) {
        preferences.edit().putString(KEY_STATUS, TransferStatusJson.encode(status)).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "wifishare_transfer_status"
        private const val KEY_STATUS = "status"
        private val LOCK = Any()
    }
}
