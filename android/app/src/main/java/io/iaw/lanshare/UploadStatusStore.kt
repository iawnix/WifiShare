package io.iaw.lanshare

import android.content.Context
import org.json.JSONObject

internal object UploadStatusJson {
    fun decode(raw: String?): UploadStatus {
        if (raw.isNullOrBlank()) {
            return UploadStatus()
        }
        return try {
            val json = JSONObject(raw)
            UploadStatus(
                operationId = json.optString("operation_id"),
                serverId = json.optString("server_id"),
                serverName = json.optString("server_name"),
                phase = enumValueOrDefault(json.optString("phase"), UploadPhase.IDLE),
                currentItemName = json.optString("current_item_name"),
                currentItemIndex = json.optInt("current_item_index"),
                completedItems = json.optInt("completed_items"),
                totalItems = json.optInt("total_items"),
                bytesSent = json.optLong("bytes_sent"),
                totalBytes = json.optLong("total_bytes"),
                startedAtMillis = json.optLong("started_at"),
                completedAtMillis = json.optLong("completed_at"),
                heartbeatAtMillis = json.optLong("heartbeat_at"),
                errorCode = enumValueOrDefault(json.optString("error_code"), UploadErrorCode.NONE),
            )
        } catch (_: Exception) {
            UploadStatus()
        }
    }

    fun encode(status: UploadStatus): String {
        return JSONObject()
            .put("operation_id", status.operationId)
            .put("server_id", status.serverId)
            .put("server_name", status.serverName)
            .put("phase", status.phase.name)
            .put("current_item_name", status.currentItemName)
            .put("current_item_index", status.currentItemIndex)
            .put("completed_items", status.completedItems)
            .put("total_items", status.totalItems)
            .put("bytes_sent", status.bytesSent)
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

class UploadStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(now: Long = System.currentTimeMillis()): UploadStatus = synchronized(LOCK) {
        val current = loadRaw()
        val reconciled = UploadStatusMachine.reconcile(current, now)
        if (reconciled != current) {
            write(reconciled)
        }
        reconciled
    }

    fun begin(
        operationId: String,
        serverId: String,
        serverName: String,
        totalItems: Int,
        now: Long,
    ): UploadStatus = synchronized(LOCK) {
        UploadStatusMachine.begin(operationId, serverId, serverName, totalItems, now).also(::write)
    }

    fun updatePreflight(
        operationId: String,
        itemName: String,
        itemIndex: Int,
        completedItems: Int,
        now: Long,
    ): UploadStatus? = synchronized(LOCK) {
        updateOperation(operationId, setOf(UploadPhase.PREFLIGHT, UploadPhase.UPLOADING)) {
            UploadStatusMachine.preflight(it, itemName, itemIndex, completedItems, now)
        }
    }

    fun updateUploading(
        operationId: String,
        itemName: String,
        itemIndex: Int,
        completedItems: Int,
        bytesSent: Long,
        totalBytes: Long,
        now: Long,
    ): UploadStatus? = synchronized(LOCK) {
        updateOperation(operationId, setOf(UploadPhase.PREFLIGHT, UploadPhase.UPLOADING)) {
            UploadStatusMachine.uploading(
                it,
                itemName,
                itemIndex,
                completedItems,
                bytesSent,
                totalBytes,
                now,
            )
        }
    }

    fun requestCancel(operationId: String, now: Long): UploadStatus? = synchronized(LOCK) {
        updateOperation(operationId, setOf(UploadPhase.PREFLIGHT, UploadPhase.UPLOADING)) {
            UploadStatusMachine.requestCancel(it, now)
        }
    }

    fun heartbeat(operationId: String, now: Long): UploadStatus? = synchronized(LOCK) {
        updateOperation(
            operationId,
            setOf(UploadPhase.PREFLIGHT, UploadPhase.UPLOADING, UploadPhase.CANCEL_REQUESTED),
        ) {
            UploadStatusMachine.heartbeat(it, now)
        }
    }

    fun complete(operationId: String, now: Long): UploadStatus? = synchronized(LOCK) {
        updateOperation(operationId, setOf(UploadPhase.PREFLIGHT, UploadPhase.UPLOADING)) {
            UploadStatusMachine.complete(it, now)
        }
    }

    fun fail(operationId: String, errorCode: UploadErrorCode, now: Long): UploadStatus? =
        synchronized(LOCK) {
            updateOperation(operationId, setOf(UploadPhase.PREFLIGHT, UploadPhase.UPLOADING)) {
                UploadStatusMachine.fail(it, errorCode, now)
            }
        }

    fun cancel(operationId: String, completedItems: Int, now: Long): UploadStatus? =
        synchronized(LOCK) {
            updateOperation(
                operationId,
                setOf(UploadPhase.PREFLIGHT, UploadPhase.UPLOADING, UploadPhase.CANCEL_REQUESTED),
            ) {
                UploadStatusMachine.cancelled(it, completedItems, now)
            }
        }

    fun interruptActive(now: Long = System.currentTimeMillis()): UploadStatus? = synchronized(LOCK) {
        val current = loadRaw()
        if (!current.isActive()) {
            null
        } else {
            UploadStatusMachine.interrupt(current, now).also(::write)
        }
    }

    fun dismiss(operationId: String): Boolean = synchronized(LOCK) {
        val current = loadRaw()
        if (current.operationId != operationId || !current.isTerminal()) {
            return@synchronized false
        }
        write(UploadStatus())
        true
    }

    private fun updateOperation(
        operationId: String,
        allowedPhases: Set<UploadPhase>,
        transform: (UploadStatus) -> UploadStatus,
    ): UploadStatus? {
        val current = loadRaw()
        if (current.operationId != operationId || current.phase !in allowedPhases) {
            return null
        }
        return transform(current).also(::write)
    }

    private fun loadRaw(): UploadStatus {
        return UploadStatusJson.decode(preferences.getString(KEY_STATUS, null))
    }

    private fun write(status: UploadStatus) {
        preferences.edit().putString(KEY_STATUS, UploadStatusJson.encode(status)).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "wifishare_upload_status"
        private const val KEY_STATUS = "status"
        private val LOCK = Any()
    }
}
