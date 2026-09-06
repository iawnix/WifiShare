package io.iaw.lanshare

import java.io.Reader

/** Read-only and bounded: one damaged log must not prevent the other sections from being exported. */
internal object DiagnosticLogReader {
    const val MAX_CHARACTERS = 20_000

    fun read(openReader: () -> Reader?): String = try {
        val reader = openReader()
        if (reader == null) {
            "[No saved record]"
        } else {
            reader.use {
                val buffer = CharArray(1024)
                buildString {
                    while (length < MAX_CHARACTERS) {
                        val count = it.read(buffer, 0, minOf(buffer.size, MAX_CHARACTERS - length))
                        if (count <= 0) break
                        append(buffer, 0, count)
                    }
                    if (length == MAX_CHARACTERS && it.read() != -1) append("\n[Truncated]")
                }.ifEmpty { "[Empty record]" }
            }
        }
    } catch (error: Exception) {
        // Exception messages may disclose paths or credentials.
        "[Record unavailable: ${error.javaClass.simpleName}]"
    }
}
