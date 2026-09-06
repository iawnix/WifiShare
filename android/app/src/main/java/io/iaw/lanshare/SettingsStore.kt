package io.iaw.lanshare

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secureCipher = SecureSettingsCipher()

    fun load(): TransferConfig? = loadActive()

    fun loadActive(): TransferConfig? = withStoreLock {
        val profiles = loadAll()
        ServerProfilePolicy.resolveActive(
            profiles = profiles,
            activeProfileId = preferences.getString(KEY_ACTIVE_PROFILE_ID, null),
            legacyActiveKey = preferences.getString(KEY_ACTIVE_PROFILE, null),
        )
    }

    fun loadAll(): List<TransferConfig> = withStoreLock {
        val encryptedProfiles = preferences.getString(KEY_PROFILES_ENCRYPTED, null)
        val legacyProfiles = preferences.getString(KEY_PROFILES_JSON, null)
        val storedProfiles = if (encryptedProfiles != null) {
            runCatching { secureCipher.decrypt(encryptedProfiles) }.getOrNull()
        } else {
            legacyProfiles
        }
        val decoded = ServerProfileJson.decode(storedProfiles)
        if (decoded.profiles.isNotEmpty()) {
            val active = ServerProfilePolicy.resolveActive(
                profiles = decoded.profiles,
                activeProfileId = preferences.getString(KEY_ACTIVE_PROFILE_ID, null),
                legacyActiveKey = preferences.getString(KEY_ACTIVE_PROFILE, null),
            )
            val activeMetadataNeedsMigration = ServerProfilePolicy.activeMetadataNeedsMigration(
                active = active,
                activeProfileId = preferences.getString(KEY_ACTIVE_PROFILE_ID, null),
                hasLegacyActiveKey = preferences.contains(KEY_ACTIVE_PROFILE),
            )
            if (decoded.needsMigration || activeMetadataNeedsMigration || encryptedProfiles == null) {
                writeProfiles(decoded.profiles, active)
            }
            return@withStoreLock decoded.profiles
        }

        val legacyConfig = loadLegacy() ?: return@withStoreLock emptyList()
        val legacy = legacyConfig.copy(
            id = ServerProfilePolicy.migratedId(legacyConfig.profileKey()),
        )
        writeProfiles(listOf(legacy), legacy)
        listOf(legacy)
    }

    fun findById(profileId: String?): TransferConfig? {
        if (profileId.isNullOrBlank()) {
            return null
        }
        return loadAll().firstOrNull { it.id == profileId }
    }

    fun save(config: TransferConfig) {
        saveAndActivate(config)
    }

    fun saveProfile(config: TransferConfig, replaceProfileId: String? = null): Boolean = withStoreLock {
        val currentProfiles = loadAll()
        val previousActive = resolveActiveWithoutLoading(currentProfiles)
        val update = ServerProfilePolicy.upsert(currentProfiles, config, replaceProfileId)
            ?: return@withStoreLock false
        val replacedActive = previousActive?.id == replaceProfileId ||
            previousActive?.profileKey() == update.saved.profileKey()
        val nextActive = when {
            replacedActive -> update.saved
            previousActive != null && update.profiles.any { it.id == previousActive.id } -> previousActive
            update.profiles.size == 1 -> update.saved
            else -> update.profiles.firstOrNull()
        }
        writeProfiles(update.profiles, nextActive)
        true
    }

    fun saveAndActivate(config: TransferConfig, replaceProfileId: String? = null): Boolean = withStoreLock {
        val update = ServerProfilePolicy.upsert(loadAll(), config, replaceProfileId)
            ?: return@withStoreLock false
        writeProfiles(update.profiles, update.saved)
        true
    }

    fun setActive(config: TransferConfig): Boolean = setActive(config.id.ifBlank { config.profileKey() })

    fun setActive(profileIdentifier: String): Boolean = withStoreLock {
        val target = loadAll().firstOrNull {
            it.id == profileIdentifier || it.profileKey() == profileIdentifier
        } ?: return@withStoreLock false
        writeActiveMetadata(target)
        true
    }

    fun delete(profileIdentifier: String): Boolean = withStoreLock {
        val currentProfiles = loadAll()
        val target = currentProfiles.firstOrNull {
            it.id == profileIdentifier || it.profileKey() == profileIdentifier
        } ?: return@withStoreLock false
        val previousActive = resolveActiveWithoutLoading(currentProfiles)
        val remaining = currentProfiles.filter { it.id != target.id }
        val nextActive = previousActive
            ?.takeIf { active -> active.id != target.id && remaining.any { it.id == active.id } }
            ?: remaining.firstOrNull()
        writeProfiles(remaining, nextActive)
        if (nextActive == null) {
            clearLegacy()
        }
        true
    }

    fun loadThemeMode(): ThemeModeSetting {
        val raw = preferences.getString(KEY_THEME_MODE, ThemeModeSetting.SYSTEM.name)
            ?: ThemeModeSetting.SYSTEM.name
        return runCatching { ThemeModeSetting.valueOf(raw) }.getOrDefault(ThemeModeSetting.SYSTEM)
    }

    fun saveThemeMode(mode: ThemeModeSetting) {
        preferences.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    private fun resolveActiveWithoutLoading(profiles: List<TransferConfig>): TransferConfig? {
        return ServerProfilePolicy.resolveActive(
            profiles = profiles,
            activeProfileId = preferences.getString(KEY_ACTIVE_PROFILE_ID, null),
            legacyActiveKey = preferences.getString(KEY_ACTIVE_PROFILE, null),
        )
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

    private fun writeProfiles(profiles: List<TransferConfig>, active: TransferConfig?) {
        val normalizedProfiles = ServerProfilePolicy.ensureStableIds(profiles)
        val normalizedActive = active?.let { target ->
            normalizedProfiles.firstOrNull { it.id == target.id }
        }
        val encryptedProfiles = secureCipher.encrypt(ServerProfileJson.encode(normalizedProfiles))
        preferences.edit()
            .putString(KEY_PROFILES_ENCRYPTED, encryptedProfiles)
            .remove(KEY_PROFILES_JSON)
            .putString(KEY_ACTIVE_PROFILE_ID, normalizedActive?.id.orEmpty())
            .remove(KEY_ACTIVE_PROFILE)
            .remove(KEY_SERVER_NAME)
            .remove(KEY_BASE_URL)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_CERT_SHA256)
            .apply()
    }

    private fun writeActiveMetadata(config: TransferConfig) {
        preferences.edit()
            .putString(KEY_ACTIVE_PROFILE_ID, config.id)
            .remove(KEY_ACTIVE_PROFILE)
            .apply()
    }

    private fun clearLegacy() {
        preferences.edit()
            .remove(KEY_SERVER_NAME)
            .remove(KEY_BASE_URL)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_CERT_SHA256)
            .remove(KEY_ACTIVE_PROFILE_ID)
            .remove(KEY_ACTIVE_PROFILE)
            .apply()
    }

    private fun <T> withStoreLock(block: () -> T): T = synchronized(LOCK, block)

    private companion object {
        private const val PREFERENCES_NAME = "lan_secure_share"
        private const val KEY_PROFILES_ENCRYPTED = "profiles_encrypted_v1"
        private const val KEY_PROFILES_JSON = "profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_SERVER_NAME = "server_name"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CERT_SHA256 = "cert_sha256"
        private const val KEY_THEME_MODE = "theme_mode"
        private val LOCK = Any()
    }
}
