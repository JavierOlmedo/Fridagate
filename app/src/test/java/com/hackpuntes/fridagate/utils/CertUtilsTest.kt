package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CertUtilsTest {

    // Self-signed test CA generated with:
    //   openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
    //     -subj "/C=ES/ST=Madrid/O=Fridagate Test/OU=Fridagate Test CA/CN=Fridagate Test CA"
    // "openssl x509 -subject_hash_old" prints fec4225b for it.
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIICNTCCAdugAwIBAgIUbwzq1XnfVcfbehbhbuGylzU4A24wCgYIKoZIzj0EAwIw
        bzELMAkGA1UEBhMCRVMxDzANBgNVBAgMBk1hZHJpZDEXMBUGA1UECgwORnJpZGFn
        YXRlIFRlc3QxGjAYBgNVBAsMEUZyaWRhZ2F0ZSBUZXN0IENBMRowGAYDVQQDDBFG
        cmlkYWdhdGUgVGVzdCBDQTAgFw0yNjEwMDgxOTQ1MzJaGA8yMTI2MDkxNDE5NDUz
        MlowbzELMAkGA1UEBhMCRVMxDzANBgNVBAgMBk1hZHJpZDEXMBUGA1UECgwORnJp
        ZGFnYXRlIFRlc3QxGjAYBgNVBAsMEUZyaWRhZ2F0ZSBUZXN0IENBMRowGAYDVQQD
        DBFGcmlkYWdhdGUgVGVzdCBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABCdO
        l++1T18RVrKT80OceZTTEI9oYZK2zsvl1kAWioLxxg3rPJxDWJTod1iIj3qsCv29
        R+cpuK8l84rTuJLfeTWjUzBRMB0GA1UdDgQWBBRPaIBQfP1PawC/ppOT7+I/LcM3
        5TAfBgNVHSMEGDAWgBRPaIBQfP1PawC/ppOT7+I/LcM35TAPBgNVHRMBAf8EBTAD
        AQH/MAoGCCqGSM49BAMCA0gAMEUCIQCzv0qbAbxa+lsZDHdoKr3kAWrvQSCIWVJo
        nZSXkPzUKQIgYoQ6Uw99McSK3sfJ4shRBUeSUmHls4fPgiWku5OqNLg=
        -----END CERTIFICATE-----
    """.trimIndent() + "\n"

    @Test
    fun subjectHashOld_matchesOpenssl() {
        assertEquals("fec4225b", CertUtils.subjectHashOld(CertUtils.parse(pem.toByteArray())))
    }

    @Test
    fun toPem_producesTheSameTextAsOpenssl() {
        assertEquals(pem, CertUtils.toPem(CertUtils.parse(pem.toByteArray())))
    }

    @Test
    fun parse_acceptsDer() {
        val fromPem = CertUtils.parse(pem.toByteArray())
        val fromDer = CertUtils.parse(fromPem.encoded)
        assertArrayEquals(fromPem.encoded, fromDer.encoded)
    }
}
