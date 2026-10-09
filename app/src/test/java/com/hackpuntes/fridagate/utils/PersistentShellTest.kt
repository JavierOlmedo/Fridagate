package com.hackpuntes.fridagate.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/**
 * Exercises the shell engine behind RootUtils with a plain /bin/sh instead of su.
 * Skipped on systems without /bin/sh (Windows).
 */
class PersistentShellTest {

    private lateinit var shell: PersistentShell

    @Before
    fun setUp() {
        assumeTrue(File("/bin/sh").canExecute())
        shell = PersistentShell(listOf(listOf("/bin/sh")), 5_000) { true }
    }

    @After
    fun tearDown() {
        if (::shell.isInitialized) shell.close()
    }

    @Test
    fun separatesStdoutStderrAndExitCode() {
        val result = shell.exec("echo out; echo err >&2; exit 3", 5_000)
        assertEquals(3, result.exitCode)
        assertEquals("out\n", result.stdout)
        assertEquals("err\n", result.stderr)
    }

    @Test
    fun keepsOutputWithoutTrailingNewline() {
        val result = shell.exec("printf abc; printf def >&2", 5_000)
        assertTrue(result.isSuccess)
        assertEquals("abc", result.stdout)
        assertEquals("def", result.stderr)
    }

    @Test
    fun passesQuotesAndSpecialCharactersIntact() {
        val result = shell.exec("printf '%s|' 'a b' \"c'd\" '\$HOME'", 5_000)
        assertEquals("a b|c'd|\$HOME|", result.stdout)
    }

    @Test
    fun syntaxErrorDoesNotKillTheShell() {
        val broken = shell.exec("if then fi", 5_000)
        assertNotEquals(0, broken.exitCode)
        assertTrue(broken.stderr.isNotBlank())
        assertEquals("ok\n", shell.exec("echo ok", 5_000).stdout)
    }

    @Test
    fun exitDoesNotKillTheShell() {
        assertEquals(7, shell.exec("exit 7", 5_000).exitCode)
        assertEquals("ok\n", shell.exec("echo ok", 5_000).stdout)
    }

    @Test
    fun commandsCannotReadTheProtocolFromStdin() {
        val result = shell.exec("cat", 3_000)
        assertTrue(result.isSuccess)
        assertEquals("", result.stdout)
        assertEquals("ok\n", shell.exec("echo ok", 5_000).stdout)
    }

    @Test
    fun largeStderrDoesNotBlock() {
        val result = shell.exec(
            "i=0; while [ \$i -lt 20000 ]; do echo 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' >&2; i=\$((i+1)); done; echo done",
            30_000
        )
        assertEquals("done\n", result.stdout)
        assertEquals(20000, result.stderr.lines().count { it.isNotEmpty() })
    }

    @Test
    fun timeoutIsReportedAndTheShellRecovers() {
        val result = shell.exec("sleep 5", 300)
        assertEquals(RootUtils.Result.TIMEOUT, result.exitCode)
        assertEquals("ok\n", shell.exec("echo ok", 5_000).stdout)
    }

    @Test
    fun backgroundJobReturnsImmediately() {
        val start = System.nanoTime()
        val result = shell.exec("nohup sleep 3 </dev/null >/dev/null 2>&1 &", 2_000)
        assertTrue(result.isSuccess)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500)
    }

    @Test
    fun concurrentCallersGetTheirOwnOutput() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..40).map { i ->
                pool.submit<RootUtils.Result> { shell.exec("echo start-$i; sleep 0.01; echo end-$i", 10_000) }
            }
            futures.forEachIndexed { index, future ->
                val i = index + 1
                assertEquals("start-$i\nend-$i\n", future.get().stdout)
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun closeDoesNotWaitForARunningCommand() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<RootUtils.Result> { shell.exec("sleep 1; echo done", 5_000) }
            Thread.sleep(200) // let the command start and take the lock
            val start = System.nanoTime()
            shell.close()
            assertTrue((System.nanoTime() - start) / 1_000_000 < 100)
            assertEquals("done\n", running.get().stdout)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun missingBinaryMeansNoRoot() {
        val missing = PersistentShell(listOf(listOf("/nonexistent/su")), 1_000) { true }
        assertEquals(RootUtils.Result.NO_ROOT, missing.exec("id", 1_000).exitCode)
    }

    @Test
    fun shellThatExitsFallsBackToTheNextCandidate() {
        // Like "su --mount-master" on a root manager that doesn't know the option
        val fallback = PersistentShell(listOf(listOf("/bin/sh", "-c", "exit 1"), listOf("/bin/sh")), 2_000) { it.isSuccess }
        try {
            assertEquals("hi\n", fallback.exec("echo hi", 2_000).stdout)
            assertEquals(listOf("/bin/sh"), fallback.openedWith)
        } finally {
            fallback.close()
        }
    }

    @Test
    fun silentShellDoesNotTryTheNextCandidate() {
        // Like a root prompt nobody answers: alive, but never runs anything
        val ignored = PersistentShell(listOf(listOf("/bin/sh", "-c", "sleep 5"), listOf("/bin/sh")), 500) { true }
        assertEquals(RootUtils.Result.NO_ROOT, ignored.exec("echo hi", 1_000).exitCode)
        assertNull(ignored.openedWith)
    }
}
