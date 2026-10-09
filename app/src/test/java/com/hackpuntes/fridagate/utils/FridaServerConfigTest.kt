package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FridaServerConfigTest {

    @Test
    fun defaultsMatchFrida() {
        val config = FridaServerConfig()
        assertEquals("/data/local/tmp/frida-server", config.binaryPath)
        assertTrue(config.isDefaultPort)
        assertEquals(emptyList<String>(), config.listenArgs(emptyList()))
    }

    @Test
    fun validatesBinaryNames() {
        listOf("frida-server", "k3v9qz7a", "adbd2", "a.b_c-d").forEach { assertTrue(it, FridaServerConfig.isValidName(it)) }
        listOf("", "-rf", ".hidden", "x".repeat(16), "a b", "a;id", "a/b", "frida-inject", "fridagate")
            .forEach { assertFalse(it, FridaServerConfig.isValidName(it)) }
    }

    @Test
    fun randomNamesAreValidAndDontSayFrida() {
        val random = Random(42)
        repeat(200) {
            val name = FridaServerConfig.randomName(random)
            assertTrue(name, FridaServerConfig.isValidName(name))
            assertFalse(name, name.contains("frida"))
            assertEquals(8, name.length)
        }
    }

    @Test
    fun customPortAddsAListenAddressUnlessTheUserSetsOne() {
        val config = FridaServerConfig(name = "k3v9qz7a", port = 31337)
        assertEquals(listOf("-l", "127.0.0.1:31337"), config.listenArgs(emptyList()))
        assertEquals(listOf("-l", "127.0.0.1:31337"), config.listenArgs(listOf("--token=secret")))
        assertEquals(emptyList<String>(), config.listenArgs(listOf("-l", "0.0.0.0:31337")))
        assertEquals(emptyList<String>(), config.listenArgs(listOf("--listen=0.0.0.0:1234")))
        assertEquals(emptyList<String>(), config.listenArgs(listOf("-l0.0.0.0:1234")))
    }
}
