package io.iaw.lanshare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfilePersistenceTest {
    @Test
    fun legacyProfilesGainDeterministicIdsAndKeepRollbackFields() {
        val legacy = JSONArray().put(
            JSONObject()
                .put("server_name", "Lab")
                .put("base_url", "https://192.168.1.10:8443")
                .put("auth_token", TOKEN)
                .put("cert_sha256", FINGERPRINT),
        ).toString()

        val first = ServerProfileJson.decode(legacy)
        val second = ServerProfileJson.decode(legacy)

        assertTrue(first.needsMigration)
        assertEquals(first.profiles.single().id, second.profiles.single().id)
        assertTrue(ServerProfilePolicy.isUuid(first.profiles.single().id))

        val encoded = JSONArray(ServerProfileJson.encode(first.profiles)).getJSONObject(0)
        assertEquals("Lab", encoded.getString("server_name"))
        assertEquals("https://192.168.1.10:8443", encoded.getString("base_url"))
        assertEquals(TOKEN, encoded.getString("auth_token"))
        assertEquals(FINGERPRINT, encoded.getString("cert_sha256"))
        assertEquals(first.profiles.single().id, encoded.getString("id"))
        assertFalse(ServerProfileJson.decode(encoded.let { JSONArray().put(it).toString() }).needsMigration)
    }

    @Test
    fun editingEndpointPreservesSelectedProfileId() {
        val original = config("Lab", "https://old:8443").copy(
            id = "11111111-1111-1111-1111-111111111111",
        )
        val edited = config("Lab renamed", "https://new:8443")

        val update = ServerProfilePolicy.upsert(listOf(original), edited, original.id)!!

        assertEquals(original.id, update.saved.id)
        assertEquals("https://new:8443", update.saved.baseUrl)
        assertEquals(1, update.profiles.size)
        assertNotEquals(original.profileKey(), update.saved.profileKey())
    }

    @Test
    fun legacyActiveKeyStillResolvesAfterMigration() {
        val first = config("One", "https://one:8443")
        val second = config("Two", "https://two:8443")
        val profiles = ServerProfilePolicy.ensureStableIds(listOf(first, second))

        val active = ServerProfilePolicy.resolveActive(profiles, null, second.profileKey())

        assertEquals("Two", active?.serverName)
    }

    @Test
    fun activeMetadataMigrationStopsAfterLegacyKeyIsRemoved() {
        val active = ServerProfilePolicy.ensureStableIds(listOf(config("One", "https://one:8443"))).single()

        assertFalse(ServerProfilePolicy.activeMetadataNeedsMigration(active, active.id, false))
        assertTrue(ServerProfilePolicy.activeMetadataNeedsMigration(active, active.id, true))
        assertTrue(ServerProfilePolicy.activeMetadataNeedsMigration(active, null, false))
    }

    private fun config(name: String, url: String): TransferConfig {
        return TransferConfig(name, url, TOKEN, FINGERPRINT)
    }

    private companion object {
        private const val FINGERPRINT =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val TOKEN = "wfs_abcdefghijklmnopqrstuvwxyz1234567890ABCDEFG"
    }
}
