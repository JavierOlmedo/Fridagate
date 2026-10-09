package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidatorTest {

    @Test
    fun ipv4() {
        listOf("192.168.1.10", "10.0.0.1", "0.0.0.0", "255.255.255.255").forEach {
            assertTrue(it, InputValidator.isValidIpv4(it))
        }
        listOf("", "256.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4", "a.b.c.d", "1.2.3.4 ", "1.2.3.4;id", "::1").forEach {
            assertFalse(it, InputValidator.isValidIpv4(it))
        }
    }

    @Test
    fun port() {
        assertTrue(InputValidator.isValidPort(1))
        assertTrue(InputValidator.isValidPort(8080))
        assertTrue(InputValidator.isValidPort(65535))
        assertFalse(InputValidator.isValidPort(0))
        assertFalse(InputValidator.isValidPort(65536))
        assertFalse(InputValidator.isValidPort(-1))
    }

    @Test
    fun packageName() {
        listOf("com.target.app", "a.b", "com.example.my_app2").forEach {
            assertTrue(it, InputValidator.isValidPackageName(it))
        }
        listOf("", "app", "com..app", ".com.app", "com.app.", "1com.app", "com.1app", "com.app;id", "com.app name").forEach {
            assertFalse(it, InputValidator.isValidPackageName(it))
        }
    }
}
