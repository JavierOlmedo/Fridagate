package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ShellUtilsTest {

    @Test
    fun quote_wrapsValueInSingleQuotes() {
        assertEquals("'abc'", ShellUtils.quote("abc"))
        assertEquals("''", ShellUtils.quote(""))
    }

    @Test
    fun quote_escapesSingleQuotes() {
        assertEquals("'it'\\''s'", ShellUtils.quote("it's"))
    }

    @Test
    fun quote_reachesRealShellUnchanged() {
        assumeTrue(File("/bin/sh").canExecute())
        val values = listOf(
            "a b", "it's", "\$(id)", "`id`", "a;b|c&d", "\"double\"", "back\\slash",
            "new\nline", "", "*", "--token=p\$\$w'rd", "'; rm -rf / #"
        )
        for (value in values) {
            val process = ProcessBuilder("/bin/sh", "-c", "printf '%s' ${ShellUtils.quote(value)}").start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            process.waitFor()
            assertEquals(value, output)
        }
    }

    @Test
    fun splitArgs_splitsOnWhitespace() {
        assertEquals(listOf("-l", "0.0.0.0:27042"), ShellUtils.splitArgs("  -l \t 0.0.0.0:27042  "))
        assertEquals(emptyList<String>(), ShellUtils.splitArgs("   "))
    }

    @Test
    fun splitArgs_groupsQuotedText() {
        assertEquals(
            listOf("--token=my secret", "x y", ""),
            ShellUtils.splitArgs("--token='my secret' \"x y\" ''")
        )
    }

    @Test
    fun splitArgs_handlesBackslashes() {
        assertEquals(listOf("a b"), ShellUtils.splitArgs("a\\ b"))
        assertEquals(listOf("a\"b"), ShellUtils.splitArgs("\"a\\\"b\""))
        assertEquals(listOf("a\\b"), ShellUtils.splitArgs("'a\\b'"))
    }

    @Test
    fun splitArgs_keepsShellMetacharactersAsText() {
        assertEquals(listOf("a;b", "\$(id)", "|", "`x`"), ShellUtils.splitArgs("a;b \$(id) | `x`"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitArgs_rejectsUnclosedQuote() {
        ShellUtils.splitArgs("--token='abc")
    }
}
