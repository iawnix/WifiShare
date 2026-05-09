package io.iaw.lanshare

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("lan_secure_share", Context.MODE_PRIVATE)

    fun load(): TransferConfig? {
        return loadActive()
    }

    fun loadActive(): TransferConfig? {
        val profiles = loadAll()
        if (profiles.isEmpty()) {
            return null
        }
        val activeKey = preferences.getString(KEY_ACTIVE_PROFILE, "") ?: ""
        return profiles.firstOrNull { it.profileKey() == activeKey } ?: profiles.first()
    }

    fun loadAll(): List<TransferConfig> {
        val profiles = parseProfiles(preferences.getString(KEY_PROFILES_JSON, null))
        if (profiles.isNotEmpty()) {
            return profiles
        }
        return loadLegacy()?.let { listOf(it) } ?: emptyList()
    }

    fun save(config: TransferConfig) {
        saveAndActivate(config)
    }

    fun saveAndActivate(config: TransferConfig, replaceProfileKey: String? = null): Boolean {
        val normalized = config.normalized()
        if (!normalized.isComplete()) {
            return false
        }

        val key = normalized.profileKey()
        val profiles = loadAll().filter {
            it.profileKey() != key && it.profileKey() != replaceProfileKey
        } + normalized
        writeProfiles(profiles, key)
        writeLegacy(normalized)
        return true
    }

    fun setActive(config: TransferConfig): Boolean {
        return setActive(config.profileKey())
    }

    fun setActive(profileKey: String): Boolean {
        val target = loadAll().firstOrNull { it.profileKey() == profileKey } ?: return false
        preferences.edit()
            .putString(KEY_ACTIVE_PROFILE, target.profileKey())
            .apply()
        writeLegacy(target)
        return true
    }

    fun delete(profileKey: String): Boolean {
        val currentProfiles = loadAll()
        val removed = currentProfiles.any { it.profileKey() == profileKey }
        if (!removed) {
            return false
        }

        val remaining = currentProfiles.filter { it.profileKey() != profileKey }
        val activeKey = preferences.getString(KEY_ACTIVE_PROFILE, "") ?: ""
        val nextActive = if (remaining.any { it.profileKey() == activeKey }) {
            activeKey
        } else {
            remaining.firstOrNull()?.profileKey()
        }
        writeProfiles(remaining, nextActive.orEmpty())
        remaining.firstOrNull { it.profileKey() == nextActive }?.let { writeLegacy(it) }
        if (remaining.isEmpty()) {
            clearLegacy()
        }
        return true
    }

    private fun parseProfiles(raw: String?): List<TransferConfig> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val config = TransferConfig(
                        serverName = item.optString(KEY_SERVER_NAME),
                        baseUrl = item.optString(KEY_BASE_URL),
                        authToken = item.optString(KEY_AUTH_TOKEN),
                        certificateSha256 = item.optString(KEY_CERT_SHA256),
                    ).normalized()
                    if (config.isComplete() && none { it.profileKey() == config.profileKey() }) {
                        add(config)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadLegacy(): TransferConfig? {
        val config = TransferConfig(
            serverName = preferences.getString(KEY_SERVER_NAME, "") ?: "",
            baseUrl = preferences.getString(KEY_BASE_URL, "") ?: "",
            authToken = preferences.getString(KEY_AUTH_TOKEN, "") ?: "",
            certificateSha256 = preferences.getString(KEY_CERT_SHA256, "") ?: "",
        ).normalized()
        return if (config.isComplete()) config else null
    }

    private fun writeProfiles(profiles: List<TransferConfig>, activeKey: String) {
        val array = JSONArray()
        profiles.forEach { config ->
            val normalized = config.normalized()
            array.put(
                JSONObject()
                    .put(KEY_SERVER_NAME, normalized.serverName)
                    .put(KEY_BASE_URL, normalized.baseUrl)
                    .put(KEY_AUTH_TOKEN, normalized.authToken)
                    .put(KEY_CERT_SHA256, normalized.certificateSha256),
            )
        }
        preferences.edit()
            .putString(KEY_PROFILES_JSON, array.toString())
            .putString(KEY_ACTIVE_PROFILE, activeKey)
            .apply()
    }

    private fun writeLegacy(config: TransferConfig) {
        val normalized = config.normalized()
        preferences.edit()
            .putString(KEY_SERVER_NAME, normalized.serverName)
            .putString(KEY_BASE_URL, normalized.baseUrl)
            .putString(KEY_AUTH_TOKEN, normalized.authToken)
            .putString(KEY_CERT_SHA256, normalized.certificateSha256)
            .apply()
    }

    private fun clearLegacy() {
        preferences.edit()
            .remove(KEY_SERVER_NAME)
            .remove(KEY_BASE_URL)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_CERT_SHA256)
            .remove(KEY_ACTIVE_PROFILE)
            .apply()
    }

    private companion object {
        private const val KEY_PROFILES_JSON = "profiles_json"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_SERVER_NAME = "server_name"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CERT_SHA256 = "cert_sha256"
    }
}
