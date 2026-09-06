package io.iaw.lanshare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadOperationGateTest {
    @Test
    fun secondOperationWaitsUntilTheOwnerReleases() {
        val gate = UploadOperationGate()

        assertTrue(gate.tryAcquire("first"))
        assertFalse(gate.tryAcquire("second"))
        assertFalse(gate.release("second"))
        assertTrue(gate.release("first"))
        assertTrue(gate.tryAcquire("second"))
    }
}
