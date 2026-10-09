package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ScriptUtilsTest {

    @Test
    fun sanitizesImportedFileNames() {
        assertEquals("bypass.js", ScriptUtils.sanitizeScriptName("bypass.js"))
        assertEquals("Bypass.js", ScriptUtils.sanitizeScriptName("Bypass.JS"))
        assertEquals("my_hook.js", ScriptUtils.sanitizeScriptName("my hook"))
        assertEquals("evil.js", ScriptUtils.sanitizeScriptName("../../evil.js"))
        assertEquals("a_b_c.js", ScriptUtils.sanitizeScriptName("a;b`c.js"))
        assertEquals("script.js", ScriptUtils.sanitizeScriptName(null))
        assertEquals("script.js", ScriptUtils.sanitizeScriptName("..."))
        assertTrue(ScriptUtils.sanitizeScriptName("x".repeat(200)).length <= 63)
    }

    @Test
    fun picksAFreeNameWhenTheFileExists() {
        val dir = Files.createTempDirectory("scripts").toFile()
        try {
            assertEquals("hook.js", ScriptUtils.uniqueFile(dir, "hook.js").name)
            dir.resolve("hook.js").writeText("//")
            dir.resolve("hook-2.js").writeText("//")
            assertEquals("hook-3.js", ScriptUtils.uniqueFile(dir, "hook.js").name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun builtInScriptsAreNotCustom() {
        assertTrue(ScriptUtils.SCRIPTS.isNotEmpty())
        assertFalse(ScriptUtils.SCRIPTS.any { it.isCustom })
    }
}
