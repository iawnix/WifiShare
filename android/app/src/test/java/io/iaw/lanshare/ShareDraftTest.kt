package io.iaw.lanshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareDraftTest {
    @Test
    fun removeAndClearPreserveTheRemainingOrder() {
        val draft = ShareDraft(listOf("one", "two", "three"))

        assertEquals("two", draft.removeAt(1))
        assertEquals(listOf("one", "three"), draft.snapshot())
        assertNull(draft.removeAt(9))

        draft.clear()
        assertTrue(draft.isEmpty)
    }

    @Test
    fun snapshotIsFrozenFromLaterDraftChanges() {
        val draft = ShareDraft(listOf("one", "two"))

        val frozen = draft.snapshot()
        draft.replace(listOf("three"))

        assertEquals(listOf("one", "two"), frozen)
        assertEquals(listOf("three"), draft.snapshot())
    }
}
