package io.iaw.lanshare

import java.util.Collections
import java.util.IdentityHashMap

/** Exception messages can contain URLs, tokens, and filenames; export stack frames only. */
internal object CrashTrace {
    fun format(error: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        return buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth++ < 8 && seen.add(current)) {
                appendLine(current.javaClass.name)
                current.stackTrace.take(24).forEach { appendLine("  at $it") }
                current = current.cause
            }
        }.take(16_384)
    }
}
