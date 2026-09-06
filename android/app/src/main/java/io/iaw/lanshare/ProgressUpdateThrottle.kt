package io.iaw.lanshare

/** Rate limit UI and notification work using a monotonic clock supplied by the caller. */
internal class ProgressUpdateThrottle(private val intervalMillis: Long = 1_000L) {
    private var lastPublishedAt: Long? = null

    @Synchronized
    fun shouldPublish(now: Long): Boolean {
        val previous = lastPublishedAt
        if (previous != null && now >= previous && now - previous < intervalMillis) return false
        lastPublishedAt = now
        return true
    }

    @Synchronized
    fun reset() {
        lastPublishedAt = null
    }
}
