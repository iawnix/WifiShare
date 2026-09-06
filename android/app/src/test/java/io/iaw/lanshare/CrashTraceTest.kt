package io.iaw.lanshare

import org.junit.Assert.*
import org.junit.Test

class CrashTraceTest {
    @Test fun reportKeepsStackAndCauseWithoutExportingMessages() {
        val error = IllegalStateException("secret-server-token", IllegalArgumentException("private-file.pdf"))
        val report = CrashTrace.format(error)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("IllegalArgumentException"))
        assertTrue(report.contains("CrashTraceTest"))
        assertFalse(report.contains("secret-server-token"))
        assertFalse(report.contains("private-file.pdf"))
    }

    @Test fun cyclicCausesAreBounded() {
        val first = Exception()
        val second = Exception(first)
        first.initCause(second)
        assertTrue(CrashTrace.format(first).length in 1..16_384)
    }
}
