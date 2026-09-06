package io.iaw.lanshare

enum class DownloadProgressPhase {
    CHECKING,
    ITEM_STARTED,
    BYTES_RECEIVED,
    ITEM_COMPLETED,
}

data class DownloadProgress(
    val phase: DownloadProgressPhase,
    val itemName: String = "",
    val itemIndex: Int = 0,
    val completedItems: Int = 0,
    val bytesReceived: Long = 0L,
    val totalBytes: Long = 0L,
)
