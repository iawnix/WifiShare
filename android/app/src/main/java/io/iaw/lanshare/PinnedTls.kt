package io.iaw.lanshare

import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object PinnedTls {
    fun open(config: TransferConfig, path: String): HttpsURLConnection {
        return (URL(config.baseUrl + path).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer ${config.authToken}")
            sslSocketFactory = buildPinnedSslContext(config.certificateSha256).socketFactory
            hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        }
    }

    private fun buildPinnedSslContext(expectedFingerprint: String): SSLContext {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not supported")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull() ?: throw CertificateException("No server certificate")
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString(separator = "") { "%02x".format(it) }
                val expected = TransferConfig.normalizeFingerprint(expectedFingerprint)
                if (actual != expected) {
                    throw CertificateException("Pinned certificate fingerprint mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
    }
}
