package io.iaw.lanshare

data class TransferConfig(
    val serverName: String,
    val baseUrl: String,
    val authToken: String,
    val certificateSha256: String,
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
    }

    fun isComplete(): Boolean {
        return serverName.isNotBlank() &&
            baseUrl.isNotBlank() &&
            authToken.isNotBlank() &&
            certificateSha256.length == 64
    }
}
