package io.iaw.lanshare

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class DownloadClient(
    private val context: Context,
    private val config: TransferConfig,
    private val cancellation: TransferCancellationToken = TransferCancellationToken(),
) {
    fun fetchAllPending(onProgress: (DownloadProgress) -> Unit = {}): Int {
        var received = 0
        while (true) {
            cancellation.throwIfCancelled()
            onProgress(
                DownloadProgress(
                    phase = DownloadProgressPhase.CHECKING,
                    itemIndex = received + 1,
                    completedItems = received,
                ),
            )
            val pending = fetchNext() ?: return received
            onProgress(
                DownloadProgress(
                    phase = DownloadProgressPhase.ITEM_STARTED,
                    itemName = pending.filename,
                    itemIndex = received + 1,
                    completedItems = received,
                    totalBytes = pending.size,
                ),
            )
            downloadToPhone(pending) { bytesReceived ->
                onProgress(
                    DownloadProgress(
                        phase = DownloadProgressPhase.BYTES_RECEIVED,
                        itemName = pending.filename,
                        itemIndex = received + 1,
                        completedItems = received,
                        bytesReceived = bytesReceived,
                        totalBytes = pending.size,
                    ),
                )
            }
            acknowledge(pending)
            received += 1
            onProgress(
                DownloadProgress(
                    phase = DownloadProgressPhase.ITEM_COMPLETED,
                    itemName = pending.filename,
                    itemIndex = received,
                    completedItems = received,
                    bytesReceived = pending.size,
                    totalBytes = pending.size,
                ),
            )
        }
    }

    private fun fetchNext(): PendingDownload? = withConnection("/api/v1/outbox/next", "GET") { connection ->
        when (val responseCode = connection.responseCode) {
            200 -> {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                PendingDownload.fromJson(body)
            }
            204 -> null
            else -> throw HttpResponseException(responseCode, "Queue check failed")
        }
    }

    private fun downloadToPhone(pending: PendingDownload, onBytesReceived: (Long) -> Unit) =
        withConnection(pending.contentPath, "GET") { connection ->
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw HttpResponseException(responseCode, "Download failed")
            }

            val mimeType = connection.contentType?.substringBefore(";") ?: "application/octet-stream"
            val digest = MessageDigest.getInstance("SHA-256")
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, pending.filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/WifiShare")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val destination = try {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw StorageWriteException("Unable to create download destination")
            } catch (exc: StorageWriteException) {
                throw exc
            } catch (exc: Exception) {
                throw StorageWriteException("Unable to create download destination", exc)
            }

            try {
                var total = 0L
                val outputStream = try {
                    resolver.openOutputStream(destination)
                        ?: throw StorageWriteException("Unable to open destination stream")
                } catch (exc: StorageWriteException) {
                    throw exc
                } catch (exc: Exception) {
                    throw StorageWriteException("Unable to open destination stream", exc)
                }
                outputStream.use { output ->
                    cancellation.attach(output)
                    try {
                        connection.inputStream.use { input ->
                            cancellation.attach(input)
                            try {
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    cancellation.throwIfCancelled()
                                    val read = input.read(buffer)
                                    if (read < 0) {
                                        break
                                    }
                                    try {
                                        output.write(buffer, 0, read)
                                    } catch (exc: Exception) {
                                        throw StorageWriteException("Unable to write destination file", exc)
                                    }
                                    digest.update(buffer, 0, read)
                                    total += read.toLong()
                                    onBytesReceived(total)
                                }
                                cancellation.throwIfCancelled()
                                try {
                                    output.flush()
                                } catch (exc: Exception) {
                                    throw StorageWriteException("Unable to finish destination file", exc)
                                }
                            } finally {
                                cancellation.detach(input)
                            }
                        }
                    } finally {
                        cancellation.detach(output)
                    }
                }

                val actualDigest = digest.digest().joinToString(separator = "") { "%02x".format(it) }
                if (actualDigest != pending.sha256) {
                    throw DownloadIntegrityException("SHA-256 mismatch while receiving ${pending.filename}")
                }
                if (total != pending.size) {
                    throw DownloadIntegrityException("Size mismatch while receiving ${pending.filename}")
                }

                cancellation.throwIfCancelled()
                try {
                    val updated = resolver.update(
                        destination,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    if (updated != 1) {
                        throw StorageWriteException("Unable to publish destination file")
                    }
                } catch (exc: StorageWriteException) {
                    throw exc
                } catch (exc: Exception) {
                    throw StorageWriteException("Unable to publish destination file", exc)
                }
            } catch (exc: Exception) {
                runCatching { resolver.delete(destination, null, null) }
                throw exc
            }
        }

    private fun acknowledge(pending: PendingDownload) {
        cancellation.throwIfCancelled()
        withConnection(pending.ackPath, "POST") { connection ->
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.use { }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw HttpResponseException(responseCode, "Ack failed")
            }
            connection.inputStream.close()
        }
    }

    private fun <T> withConnection(path: String, method: String, block: (HttpsURLConnection) -> T): T {
        cancellation.throwIfCancelled()
        val connection = PinnedTls.open(config, path).apply { requestMethod = method }
        cancellation.attach(connection)
        return try {
            block(connection)
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }
}
