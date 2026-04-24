package io.iaw.lanshare

import android.content.Context
import android.os.Build
import java.io.IOException
import java.security.MessageDigest

class UploadClient(
    private val context: Context,
    private val config: TransferConfig,
) {
    fun upload(item: SharedItem) {
        val (sha256, sizeBytes) = computeSha256AndSize(item)
        val connection = PinnedTls.open(config, "/api/v1/uploads").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("X-File-Name", item.displayName)
            setRequestProperty("X-Content-SHA256", sha256)
            setRequestProperty("X-Device-Name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            setRequestProperty("Content-Type", item.mimeType ?: "application/octet-stream")
            setFixedLengthStreamingMode(sizeBytes)
        }

        try {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("Unable to open shared file: ${item.displayName}")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Upload failed (${responseCode}): $errorBody")
            }
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun computeSha256AndSize(item: SharedItem): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
                total += read.toLong()
            }
        } ?: throw IOException("Unable to open shared file: ${item.displayName}")
        return digest.digest().joinToString(separator = "") { "%02x".format(it) } to total
    }
}
