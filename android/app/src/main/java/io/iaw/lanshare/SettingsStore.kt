package io.iaw.lanshare

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("lan_secure_share", Context.MODE_PRIVATE)

    fun load(): TransferConfig? {
        val config = TransferConfig(
            serverName = preferences.getString(KEY_SERVER_NAME, "") ?: "",
            baseUrl = preferences.getString(KEY_BASE_URL, "") ?: "",
            authToken = preferences.getString(KEY_AUTH_TOKEN, "") ?: "",
            certificateSha256 = preferences.getString(KEY_CERT_SHA256, "") ?: "",
        )
        return if (config.isComplete()) config else null
    }

    fun save(config: TransferConfig) {
        preferences.edit()
            .putString(KEY_SERVER_NAME, config.serverName)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_AUTH_TOKEN, config.authToken)
            .putString(KEY_CERT_SHA256, config.certificateSha256)
            .apply()
    }

    private companion object {
        private const val KEY_SERVER_NAME = "server_name"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CERT_SHA256 = "cert_sha256"
    }
}
