package io.iaw.lanshare

import org.json.JSONObject

data class PendingDownload(
    val transferId: String,
    val filename: String,
    val sha256: String,
    val size: Long,
    val contentPath: String,
    val ackPath: String,
) {
    companion object {
        fun fromJson(payload: String): PendingDownload {
            val json = JSONObject(payload)
            return PendingDownload(
                transferId = json.getString("transfer_id"),
                filename = json.getString("filename"),
                sha256 = json.getString("sha256"),
                size = json.getLong("size"),
                contentPath = json.getString("content_path"),
                ackPath = json.getString("ack_path"),
            )
        }
    }
}
