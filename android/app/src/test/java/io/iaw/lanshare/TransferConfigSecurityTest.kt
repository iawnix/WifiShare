package io.iaw.lanshare

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferConfigSecurityTest {
    @Test
    fun acceptsPinnedHttpsEndpoint() {
        assertTrue(config("https://192.168.1.23:8443").isComplete())
        assertTrue(config("https://server.local").isComplete())
    }

    @Test
    fun rejectsUnsafeOrAmbiguousEndpoint() {
        assertFalse(config("http://192.168.1.23:8443").isComplete())
        assertFalse(config("https://user:password@server.local").isComplete())
        assertFalse(config("https://server.local/api").isComplete())
        assertFalse(config("https://server.local?redirect=other").isComplete())
        assertFalse(config("https://server.local:0").isComplete())
    }

    @Test
    fun rejectsWeakCredentials() {
        assertFalse(config("https://server.local", token = "short").isComplete())
        assertFalse(config("https://server.local", fingerprint = "a".repeat(63)).isComplete())
    }

    private fun config(
        baseUrl: String,
        token: String = "wfs_" + "t".repeat(43),
        fingerprint: String = "a".repeat(64),
    ) = TransferConfig(
        serverName = "Test server",
        baseUrl = baseUrl,
        authToken = token,
        certificateSha256 = fingerprint,
    )
}
