package io.iaw.lanshare

import org.junit.Assert.*
import org.junit.Test

class ProgressUpdateThrottleTest {
    @Test fun rapidFileAndProgressEventsCannotFloodNotifications() {
        val throttle = ProgressUpdateThrottle()
        assertTrue(throttle.shouldPublish(0))
        for (time in 1L..999L) assertFalse(throttle.shouldPublish(time))
        assertTrue(throttle.shouldPublish(1_000))
        assertFalse(throttle.shouldPublish(1_001))
    }

    @Test fun newOperationCanPublishImmediately() {
        val throttle = ProgressUpdateThrottle()
        assertTrue(throttle.shouldPublish(100))
        throttle.reset()
        assertTrue(throttle.shouldPublish(101))
    }
}
