package io.iaw.lanshare

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.security.MessageDigest

class DownloadClient(
    private val context: Context,
    private val config: TransferConfig,
) {
    fun fetchAllPending(): Int {
        var received = 0
        while (true) {
            val pending = fetchNext() ?: return received
            downloadToPhone(pending)
            acknowledge(pending)
            received += 1
        }
    }

    private fun fetchNext(): PendingDownload? {
        val connection = PinnedTls.open(config, "/api/v1/outbox/next").apply {
            requestMethod = "GET"
        }
        return try {
            when (val responseCode = connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    PendingDownload.fromJson(body)
                }
                204 -> null
                else -> {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IOException("Queue check failed ($responseCode): $errorBody")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToPhone(pending: PendingDownload) {
        val connection = PinnedTls.open(config, pending.contentPath).apply {
            requestMethod = "GET"
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw IOException("Download failed ($responseCode): $errorBody")
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
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create download destination")

        try {
            var total = 0L
            resolver.openOutputStream(destination)?.use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read.toLong()
                    }
                    output.flush()
                }
            } ?: throw IOException("Unable to open destination stream")

            val actualDigest = digest.digest().joinToString(separator = "") { "%02x".format(it) }
            if (actualDigest != pending.sha256) {
                throw IOException("SHA-256 mismatch while receiving ${pending.filename}")
            }
            if (total != pending.size) {
                throw IOException("Size mismatch while receiving ${pending.filename}")
            }

            resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (exc: Exception) {
            resolver.delete(destination, null, null)
            throw exc
        } finally {
            connection.disconnect()
        }
    }

    private fun acknowledge(pending: PendingDownload) {
        val connection = PinnedTls.open(config, pending.ackPath).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(0)
        }

        try {
            connection.outputStream.use { }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Ack failed ($responseCode): $errorBody")
            }
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }
}
