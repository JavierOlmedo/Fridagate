package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FridaUtilsTest {

    @Test
    fun mapsAndroidAbisToFridaArchitectures() {
        assertEquals("arm64", FridaUtils.fridaArchForAbi("arm64-v8a"))
        assertEquals("arm", FridaUtils.fridaArchForAbi("armeabi-v7a"))
        assertEquals("arm", FridaUtils.fridaArchForAbi("armeabi"))
        assertEquals("x86_64", FridaUtils.fridaArchForAbi("x86_64"))
        assertEquals("x86", FridaUtils.fridaArchForAbi("x86"))
        assertNull(FridaUtils.fridaArchForAbi("riscv64"))
    }

    @Test
    fun versionFormat() {
        assertTrue(FridaUtils.isValidVersionFormat("16.7.19"))
        assertTrue(FridaUtils.isValidVersionFormat("17.0.0-rc1"))
        assertFalse(FridaUtils.isValidVersionFormat("16.7"))
        assertFalse(FridaUtils.isValidVersionFormat("16.7.19'; id; '"))
    }
}
