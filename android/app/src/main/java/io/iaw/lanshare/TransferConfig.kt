package io.iaw.lanshare

import java.net.URI

data class TransferConfig(
    val serverName: String,
    val baseUrl: String,
    val authToken: String,
    val certificateSha256: String,
    val id: String = "",
) {
    companion object {
        fun normalizeBaseUrl(value: String): String {
            return value.trim().removeSuffix("/")
        }

        fun normalizeFingerprint(value: String): String {
            return value.lowercase().replace(Regex("[^0-9a-f]"), "")
        }

        fun normalizeAuthToken(value: String): String {
            return value.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")
        }

        private const val MAX_SERVER_NAME_LENGTH = 80
        private const val MIN_TOKEN_LENGTH = 24
        private const val MAX_TOKEN_LENGTH = 512
        private val FINGERPRINT_PATTERN = Regex("^[0-9a-f]{64}$")
    }

    fun isComplete(): Boolean {
        val normalized = normalized()
        return normalized.serverName.isNotBlank() &&
            normalized.serverName.length <= MAX_SERVER_NAME_LENGTH &&
            normalized.authToken.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH &&
            FINGERPRINT_PATTERN.matches(normalized.certificateSha256) &&
            isValidHttpsBaseUrl(normalized.baseUrl)
    }

    fun normalized(): TransferConfig {
        return copy(
            serverName = serverName.trim(),
            baseUrl = normalizeBaseUrl(baseUrl),
            authToken = normalizeAuthToken(authToken),
            certificateSha256 = normalizeFingerprint(certificateSha256),
            id = id.trim(),
        )
    }

    fun profileKey(): String {
        val normalized = normalized()
        return "${normalized.baseUrl}|${normalized.certificateSha256}"
    }

    fun displayLabel(): String {
        val normalized = normalized()
        return if (normalized.serverName.isNotBlank()) {
            "${normalized.serverName} (${normalized.baseUrl})"
        } else {
            normalized.baseUrl
        }
    }

    private fun isValidHttpsBaseUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val path = uri.rawPath.orEmpty()
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (path.isEmpty() || path == "/") &&
            (uri.port == -1 || uri.port in 1..65535)
    }
}
