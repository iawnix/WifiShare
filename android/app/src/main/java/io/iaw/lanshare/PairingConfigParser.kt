package io.iaw.lanshare

import android.content.Intent
import android.net.Uri

object PairingConfigParser {
    fun fromIntent(intent: Intent?): TransferConfig? {
        val data = intent?.data ?: return null
        return fromUri(data)
    }

    private fun fromUri(uri: Uri): TransferConfig? {
        if (uri.scheme != "lss" || uri.host != "pair") {
            return null
        }

        val certificateSha256 = uri.getQueryParameter("certificate_sha256")
            ?: uri.getQueryParameter("cert_sha256")
            ?: uri.getQueryParameter("fingerprint")
            ?: ""

        val config = TransferConfig(
            serverName = uri.getQueryParameter("server_name").orEmpty().trim(),
            baseUrl = TransferConfig.normalizeBaseUrl(uri.getQueryParameter("base_url").orEmpty()),
            authToken = TransferConfig.normalizeAuthToken(uri.getQueryParameter("auth_token").orEmpty()),
            certificateSha256 = TransferConfig.normalizeFingerprint(certificateSha256),
        )
        return if (config.isComplete()) config else null
    }
}
