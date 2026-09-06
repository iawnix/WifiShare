package io.iaw.lanshare

import java.io.IOException
import java.io.Reader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogReaderTest {
    @Test fun absentAndEmptyLogsHaveExplicitPlaceholders() {
        assertEquals("[No saved record]", DiagnosticLogReader.read { null })
        assertEquals("[Empty record]", DiagnosticLogReader.read { StringReader("") })
    }

    @Test fun existingCrashTextIsPreservedAndReaderClosed() {
        var closed = false
        val reader = object : StringReader("old-version-stack\n  at MainActivity.onCreate") {
            override fun close() {
                closed = true
                super.close()
            }
        }
        assertEquals("old-version-stack\n  at MainActivity.onCreate", DiagnosticLogReader.read { reader })
        assertTrue(closed)
    }

    @Test fun largeRecordIsBoundedAndMarked() {
        val source = "x".repeat(DiagnosticLogReader.MAX_CHARACTERS + 100)
        assertEquals(
            "x".repeat(DiagnosticLogReader.MAX_CHARACTERS) + "\n[Truncated]",
            DiagnosticLogReader.read { StringReader(source) },
        )
    }

    @Test fun exactLimitDoesNotClaimTruncation() {
        val source = "x".repeat(DiagnosticLogReader.MAX_CHARACTERS)
        assertEquals(source, DiagnosticLogReader.read { StringReader(source) })
    }

    @Test fun openFailureDoesNotLeakMessageOrBlockNextRecord() {
        val unavailable = DiagnosticLogReader.read { throw IOException("private-path-and-token") }
        assertEquals("[Record unavailable: IOException]", unavailable)
        assertEquals("other stack", DiagnosticLogReader.read { StringReader("other stack") })
    }

    @Test fun readFailureClosesStreamAndKeepsErrorMessagePrivate() {
        var closed = false
        val reader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                throw IOException("private-filename")
            override fun close() { closed = true }
        }
        assertEquals("[Record unavailable: IOException]", DiagnosticLogReader.read { reader })
        assertTrue(closed)
    }
}
