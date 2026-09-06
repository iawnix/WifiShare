package io.iaw.lanshare

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class DecodedServerProfiles(
    val profiles: List<TransferConfig>,
    val needsMigration: Boolean,
)

internal data class ServerProfileUpdate(
    val profiles: List<TransferConfig>,
    val saved: TransferConfig,
)

internal object ServerProfilePolicy {
    fun ensureStableIds(profiles: List<TransferConfig>): List<TransferConfig> {
        val seenKeys = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        return buildList {
            profiles.forEachIndexed { index, rawConfig ->
                val config = rawConfig.normalized()
                if (!config.isComplete() || !seenKeys.add(config.profileKey())) {
                    return@forEachIndexed
                }

                var candidate = config.id.takeIf(::isUuid) ?: migratedId(config.profileKey())
                var collisionIndex = index
                while (!seenIds.add(candidate)) {
                    collisionIndex += 1
                    candidate = migratedId("${config.profileKey()}#$collisionIndex")
                }
                add(config.copy(id = candidate))
            }
        }
    }

    fun upsert(
        profiles: List<TransferConfig>,
        rawConfig: TransferConfig,
        replaceProfileId: String? = null,
    ): ServerProfileUpdate? {
        val config = rawConfig.normalized()
        if (!config.isComplete()) {
            return null
        }

        val normalizedProfiles = ensureStableIds(profiles)
        val replacement = normalizedProfiles.firstOrNull { it.id == replaceProfileId }
        val sameEndpoint = normalizedProfiles.firstOrNull { it.profileKey() == config.profileKey() }
        val stableId = when {
            replacement != null -> replacement.id
            isUuid(config.id) -> config.id
            sameEndpoint != null -> sameEndpoint.id
            else -> UUID.randomUUID().toString()
        }
        val saved = config.copy(id = stableId)
        val updated = normalizedProfiles.filter {
            it.id != stableId &&
                it.id != replacement?.id &&
                it.profileKey() != saved.profileKey()
        } + saved
        return ServerProfileUpdate(updated, saved)
    }

    fun resolveActive(
        profiles: List<TransferConfig>,
        activeProfileId: String?,
        legacyActiveKey: String?,
    ): TransferConfig? {
        return profiles.firstOrNull { it.id == activeProfileId }
            ?: profiles.firstOrNull { it.profileKey() == legacyActiveKey }
            ?: profiles.firstOrNull()
    }

    fun activeMetadataNeedsMigration(
        active: TransferConfig?,
        activeProfileId: String?,
        hasLegacyActiveKey: Boolean,
    ): Boolean {
        return active != null && (activeProfileId != active.id || hasLegacyActiveKey)
    }

    fun migratedId(legacyProfileKey: String): String {
        val source = "wifishare-profile:$legacyProfileKey"
        return UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    fun isUuid(value: String): Boolean {
        return runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    }
}

internal object ServerProfileJson {
    private const val KEY_ID = "id"
    private const val KEY_SERVER_NAME = "server_name"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_CERT_SHA256 = "cert_sha256"

    fun decode(raw: String?): DecodedServerProfiles {
        if (raw.isNullOrBlank()) {
            return DecodedServerProfiles(emptyList(), needsMigration = false)
        }

        return try {
            val array = JSONArray(raw)
            val parsed = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TransferConfig(
                            id = item.optString(KEY_ID),
                            serverName = item.optString(KEY_SERVER_NAME),
                            baseUrl = item.optString(KEY_BASE_URL),
                            authToken = item.optString(KEY_AUTH_TOKEN),
                            certificateSha256 = item.optString(KEY_CERT_SHA256),
                        ).normalized(),
                    )
                }
            }
            val normalized = ServerProfilePolicy.ensureStableIds(parsed)
            DecodedServerProfiles(
                profiles = normalized,
                needsMigration = parsed != normalized || parsed.size != array.length(),
            )
        } catch (_: Exception) {
            DecodedServerProfiles(emptyList(), needsMigration = false)
        }
    }

    fun encode(profiles: List<TransferConfig>): String {
        val array = JSONArray()
        ServerProfilePolicy.ensureStableIds(profiles).forEach { config ->
            array.put(
                JSONObject()
                    .put(KEY_ID, config.id)
                    .put(KEY_SERVER_NAME, config.serverName)
                    .put(KEY_BASE_URL, config.baseUrl)
                    .put(KEY_AUTH_TOKEN, config.authToken)
                    .put(KEY_CERT_SHA256, config.certificateSha256),
            )
        }
        return array.toString()
    }
}
