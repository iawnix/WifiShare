package io.iaw.lanshare

import android.content.Context
import android.os.Build
import java.io.IOException
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class UploadClient(
    private val context: Context,
    private val config: TransferConfig,
) {
    fun upload(
        item: SharedItem,
        cancellation: TransferCancellationToken,
        onPrepared: (Long) -> Unit,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit,
    ) {
        cancellation.throwIfCancelled()
        val (sha256, sizeBytes) = computeSha256AndSize(item, cancellation)
        cancellation.throwIfCancelled()
        onPrepared(sizeBytes)
        val connection = PinnedTls.open(config, "/api/v1/uploads").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("X-File-Name", item.displayName)
            setRequestProperty("X-Content-SHA256", sha256)
            setRequestProperty("X-Device-Name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            setRequestProperty("Content-Type", item.mimeType ?: "application/octet-stream")
            setFixedLengthStreamingMode(sizeBytes)
        }
        cancellation.attach(connection)

        try {
            val input = try {
                context.contentResolver.openInputStream(item.uri)
            } catch (error: Exception) {
                throw SharedFileUnavailableException("Unable to open shared file", error)
            } ?: throw SharedFileUnavailableException("Unable to open shared file")
            input.use {
                cancellation.attach(input)
                try {
                    val output = try {
                        connection.outputStream
                    } catch (error: Exception) {
                        cancellation.throwIfCancelled()
                        throw error
                    }
                    output.use {
                        cancellation.attach(output)
                        try {
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesSent = 0L
                            while (true) {
                                cancellation.throwIfCancelled()
                                val read = input.read(buffer)
                                if (read < 0) {
                                    break
                                }
                                cancellation.throwIfCancelled()
                                output.write(buffer, 0, read)
                                bytesSent += read.toLong()
                                onProgress(bytesSent, sizeBytes)
                            }
                            output.flush()
                        } finally {
                            cancellation.detach(output)
                        }
                    }
                } finally {
                    cancellation.detach(input)
                }
            }

            cancellation.throwIfCancelled()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                connection.errorStream?.use { }
                throw HttpResponseException(responseCode, "Upload rejected")
            }
            connection.inputStream.use { }
        } catch (error: Exception) {
            cancellation.throwIfCancelled()
            throw responseFailureOrOriginal(connection, error)
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }

    private fun computeSha256AndSize(
        item: SharedItem,
        cancellation: TransferCancellationToken,
    ): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = try {
            context.contentResolver.openInputStream(item.uri)
        } catch (error: Exception) {
            throw SharedFileUnavailableException("Unable to open shared file", error)
        } ?: throw SharedFileUnavailableException("Unable to open shared file")
        input.use {
            cancellation.attach(input)
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    cancellation.throwIfCancelled()
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                    total += read.toLong()
                }
            } finally {
                cancellation.detach(input)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) } to total
    }

    private fun responseFailureOrOriginal(
        connection: HttpsURLConnection,
        error: Exception,
    ): Exception {
        if (error is HttpResponseException || error is SharedFileUnavailableException) {
            return error
        }
        val responseCode = try {
            connection.responseCode
        } catch (_: Exception) {
            return error
        }
        if (responseCode in 200..299) {
            return error
        }
        runCatching { connection.errorStream?.close() }
        return HttpResponseException(responseCode, "Upload rejected")
    }
}
