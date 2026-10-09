package com.hackpuntes.fridagate.utils

import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * CertUtils - Certificate helpers that run inside the app.
 *
 * They replace the "openssl" calls the app used to run on the device:
 * openssl is not shipped with Android, so those calls failed on most phones.
 */
object CertUtils {

    /** Parses a DER or PEM encoded X.509 certificate. Throws if the bytes are not a certificate. */
    fun parse(bytes: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate

    /**
     * Name Android expects for a CA file in the system store, without the ".0" suffix
     * (for Burp's CA this is usually 9a5ba575).
     *
     * Same value as "openssl x509 -subject_hash_old" and as Android's own
     * TrustedCertificateStore: MD5 of the DER-encoded subject name, first 4 bytes
     * read as a little-endian integer, printed as 8 lowercase hex digits.
     */
    fun subjectHashOld(cert: X509Certificate): String {
        val md5 = MessageDigest.getInstance("MD5").digest(cert.subjectX500Principal.encoded)
        val hash = (md5[0].toInt() and 0xff) or
            ((md5[1].toInt() and 0xff) shl 8) or
            ((md5[2].toInt() and 0xff) shl 16) or
            ((md5[3].toInt() and 0xff) shl 24)
        return String.format(Locale.ROOT, "%08x", hash)
    }

    /** PEM text, the format Android's cacerts files use */
    fun toPem(cert: X509Certificate): String = buildString {
        append("-----BEGIN CERTIFICATE-----\n")
        cert.encoded.toByteString().base64().chunked(64).forEach { append(it).append('\n') }
        append("-----END CERTIFICATE-----\n")
    }
}
