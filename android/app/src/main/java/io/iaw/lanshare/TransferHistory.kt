package io.iaw.lanshare

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class TransferDirection {
    SEND,
    RECEIVE,
}

enum class TransferHistoryResult {
    SUCCESS,
    CANCELLED,
    ERROR,
    INTERRUPTED,
}

data class TransferHistoryEntry(
    val operationId: String,
    val direction: TransferDirection,
    val serverId: String,
    val serverName: String,
    val completedItems: Int,
    val totalItems: Int,
    val result: TransferHistoryResult,
    val completedAtMillis: Long,
)

internal object TransferHistoryJson {
    fun decode(raw: String?): List<TransferHistoryEntry> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val operationId = json.optString("operation_id")
                    val direction = enumValueOrNull<TransferDirection>(json.optString("direction"))
                    val result = enumValueOrNull<TransferHistoryResult>(json.optString("result"))
                    if (operationId.isBlank() || direction == null || result == null) {
                        continue
                    }
                    add(
                        TransferHistoryEntry(
                            operationId = operationId,
                            direction = direction,
                            serverId = json.optString("server_id"),
                            serverName = json.optString("server_name"),
                            completedItems = json.optInt("completed_items").coerceAtLeast(0),
                            totalItems = json.optInt("total_items").coerceAtLeast(0),
                            result = result,
                            completedAtMillis = json.optLong("completed_at").coerceAtLeast(0L),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(entries: List<TransferHistoryEntry>): String {
        return JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("operation_id", entry.operationId)
                        .put("direction", entry.direction.name)
                        .put("server_id", entry.serverId)
                        .put("server_name", entry.serverName)
                        .put("completed_items", entry.completedItems)
                        .put("total_items", entry.totalItems)
                        .put("result", entry.result.name)
                        .put("completed_at", entry.completedAtMillis),
                )
            }
        }.toString()
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? {
        return enumValues<T>().firstOrNull { it.name == value }
    }
}

class TransferHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(limit: Int = MAX_ENTRIES): List<TransferHistoryEntry> = synchronized(LOCK) {
        TransferHistoryJson.decode(preferences.getString(KEY_HISTORY, null))
            .sortedByDescending { it.completedAtMillis }
            .take(limit.coerceAtLeast(0))
    }

    fun recordUpload(status: UploadStatus): Boolean {
        val result = when (status.phase) {
            UploadPhase.SUCCESS -> TransferHistoryResult.SUCCESS
            UploadPhase.CANCELLED -> TransferHistoryResult.CANCELLED
            UploadPhase.ERROR -> TransferHistoryResult.ERROR
            UploadPhase.INTERRUPTED -> TransferHistoryResult.INTERRUPTED
            else -> return false
        }
        return record(
            TransferHistoryEntry(
                operationId = status.operationId,
                direction = TransferDirection.SEND,
                serverId = status.serverId,
                serverName = status.serverName,
                completedItems = status.completedItems,
                totalItems = status.totalItems,
                result = result,
                completedAtMillis = status.completedAtMillis,
            ),
        )
    }

    fun recordReceive(status: TransferStatus, serverName: String): Boolean {
        val result = when (status.phase) {
            TransferPhase.SUCCESS -> TransferHistoryResult.SUCCESS
            TransferPhase.ERROR -> TransferHistoryResult.ERROR
            TransferPhase.INTERRUPTED -> TransferHistoryResult.INTERRUPTED
            else -> return false
        }
        return record(
            TransferHistoryEntry(
                operationId = status.operationId,
                direction = TransferDirection.RECEIVE,
                serverId = status.serverId,
                serverName = serverName,
                completedItems = status.completedItems,
                totalItems = status.totalItems,
                result = result,
                completedAtMillis = status.completedAtMillis,
            ),
        )
    }

    private fun record(entry: TransferHistoryEntry): Boolean = synchronized(LOCK) {
        if (entry.operationId.isBlank() || entry.completedAtMillis <= 0L) {
            return@synchronized false
        }
        val updated = listOf(entry) + TransferHistoryJson.decode(preferences.getString(KEY_HISTORY, null))
            .filterNot { it.operationId == entry.operationId }
        preferences.edit()
            .putString(KEY_HISTORY, TransferHistoryJson.encode(updated.take(MAX_ENTRIES)))
            .apply()
        true
    }

    private companion object {
        private const val PREFERENCES_NAME = "wifishare_transfer_history"
        private const val KEY_HISTORY = "history"
        private const val MAX_ENTRIES = 20
        private val LOCK = Any()
    }
}
