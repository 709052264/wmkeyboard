package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import kotlin.io.encoding.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * The OkHttp client the sinks added after OneDrive share: the internet gate,
 * the network activity log, and the timeouts every other sink also uses. The
 * older sinks build the same thing each for itself.
 */
internal object BackupHttp {

    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 60L
    private const val WRITE_TIMEOUT_S = 120L

    fun client(pin: CertPin? = null, followRedirects: Boolean = true): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { BackupTraffic.unattended })
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
        if (pin != null) {
            val trust = PinningTrustManager(pin)
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            // The pin is the whole certificate, so the name on it adds
            // nothing: a NAS's own certificate names whatever the NAS was
            // called the day it made it, rarely the address typed here.
            builder.sslSocketFactory(tls.socketFactory, trust).hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    /**
     * Whether [failure] is a TLS handshake the certificate failed: one no
     * authority vouches for, or one that is not the pinned one. Not
     * transient, and not a password problem either.
     */
    fun isCertificateRefusal(failure: Throwable): Boolean {
        var at: Throwable? = failure
        while (at != null) {
            if (at is SSLHandshakeException || at is CertificateException) return true
            at = at.cause
        }
        return false
    }
}

/**
 * Trust on first use for a server's own certificate. [expected] empty means
 * none seen yet: the first handshake is accepted and [onFirstSeen] records
 * it. From then on only that certificate is.
 */
class CertPin(val expected: String, val onFirstSeen: (String) -> Unit) {
    companion object {
        /** SHA-256 of the DER encoding, base64. Public for the tests. */
        fun of(certificate: X509Certificate): String =
            Base64.encode(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
    }
}

private class PinningTrustManager(private val pin: CertPin) : X509TrustManager {

    @Volatile
    private var seen: String? = null

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val digest = CertPin.of(leaf)
        val expected = pin.expected.ifEmpty { seen.orEmpty() }
        if (expected.isEmpty()) {
            seen = digest
            pin.onFirstSeen(digest)
            return
        }
        if (digest != expected) throw CertificateException("certificate changed")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
