package com.hackpuntes.fridagate.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * RootUtils - Runs shell commands as root through one persistent "su" shell.
 *
 * How a command runs:
 *  1. It is wrapped as  sh -c '<command>' </dev/null  and written to the shell's stdin,
 *     followed by  echo "<marker> $?"  and  echo <marker> >&2
 *  2. Two reader threads drain the shell's stdout and stderr all the time, line by line.
 *  3. We collect stdout up to the marker (which also carries the exit code) and stderr up
 *     to its marker. The marker is a random UUID per command, so it can't clash with output.
 *
 * Why it is built this way:
 *  - No sleeps: we know exactly when a command ends and what its exit code is.
 *  - stderr is captured, so real error messages can be shown in the UI.
 *  - The readers never stop, so a command that writes a lot can't block on a full pipe.
 *  - sh -c isolates each command: a syntax error or an "exit" can't kill the shared shell,
 *    and </dev/null stops a command from swallowing the next one from our stdin.
 *  - Calls are serialized with a lock, so concurrent coroutines never mix their output.
 *  - Every command has a timeout. On timeout the shell is killed and the next call opens a new one.
 *
 * The shell is opened with "su --mount-master" when the root manager supports it (Magisk,
 * KernelSU, SuperSU), which puts it in the global mount namespace: mounts made from it
 * (the CA store overlay) are seen by every app, not only inside Fridagate. Plain "su" is
 * the fallback.
 *
 * Background jobs started from here (nohup ... &) must redirect their stdout and stderr,
 * otherwise their output could end up mixed with the output of later commands.
 */
object RootUtils {

    /**
     * Result of a root command.
     *
     * @param exitCode exit status of the command, or [TIMEOUT] / [NO_ROOT]
     * @param stdout   everything the command printed on stdout
     * @param stderr   everything the command printed on stderr
     */
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0

        /** Best text to show when the command failed */
        val errorMessage: String
            get() = stderr.trim().ifEmpty { stdout.trim() }.ifEmpty { "exit code $exitCode" }

        companion object {
            /** The command didn't finish in time, or the shell died while it ran */
            const val TIMEOUT = -1

            /** No root shell could be opened (no su binary, or access denied) */
            const val NO_ROOT = -2
        }
    }

    /** Default per-command timeout */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /**
     * How long we wait for "id -u" when opening the shell. Long on purpose: it includes
     * the time the user needs to tap "Grant" in the root manager prompt.
     */
    private const val OPEN_TIMEOUT_MS = 45_000L

    private val shell = PersistentShell(
        candidates = listOf(listOf("su", "--mount-master"), listOf("su")),
        openTimeoutMs = OPEN_TIMEOUT_MS,
        isAcceptable = { idResult -> idResult.isSuccess && idResult.stdout.trim() == "0" }
    )

    /**
     * Runs [command] as root and returns its exit code, stdout and stderr. Never throws.
     * Blocking: call it from Dispatchers.IO.
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result =
        shell.exec(command, timeoutMs)

    /**
     * True if a root shell can be opened and it really runs as uid 0.
     * The first call may show the root manager's permission prompt.
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) { shell.canOpen() }

    /** True if the open root shell runs in the global mount namespace (su --mount-master) */
    val isGlobalMountNamespace: Boolean
        get() = shell.openedWith?.contains("--mount-master") == true

    /**
     * Closes the root shell; the next command opens a new one.
     * Never blocks: if a command is running, the shell is left open.
     */
    fun closeSuProcess() = shell.close()
}

/**
 * Keeps one long-lived shell and runs commands on it, one at a time.
 * RootUtils uses it with su; unit tests use it with a plain sh.
 *
 * @param candidates   command lines tried in order to open the shell
 * @param openTimeoutMs how long to wait for the first "id -u" on a new shell
 * @param isAcceptable decides from the "id -u" result whether the new shell is usable
 */
internal class PersistentShell(
    private val candidates: List<List<String>>,
    private val openTimeoutMs: Long,
    private val isAcceptable: (RootUtils.Result) -> Boolean
) {
    private val lock = ReentrantLock()
    private var session: ShellSession? = null

    /** Command line of the open shell, e.g. [su, --mount-master]. Null when no shell is open. */
    @Volatile
    var openedWith: List<String>? = null
        private set

    fun exec(command: String, timeoutMs: Long): RootUtils.Result {
        lock.withLock {
            val current = openSession()
                ?: return RootUtils.Result(RootUtils.Result.NO_ROOT, "", "root shell not available")
            val result = current.run(command, timeoutMs)
            if (result == null) {
                // Timed out or the shell died: drop it, the next call opens a fresh one
                closeLocked()
                return RootUtils.Result(
                    RootUtils.Result.TIMEOUT, "",
                    "no answer after $timeoutMs ms (timed out or the shell died)"
                )
            }
            return result
        }
    }

    fun canOpen(): Boolean = lock.withLock { openSession() != null }

    /**
     * Closes the shell without waiting: it can be called from the main thread
     * (ViewModel.onCleared). If a command is running, the shell is left open.
     */
    fun close() {
        if (!lock.tryLock()) return
        try {
            closeLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun closeLocked() {
        session?.destroy()
        session = null
        openedWith = null
    }

    private fun openSession(): ShellSession? {
        session?.let { if (it.isAlive()) return it }
        closeLocked()
        for (candidate in candidates) {
            val newSession = ShellSession.start(candidate) ?: continue
            val idResult = newSession.run("id -u", openTimeoutMs)
            if (idResult != null && isAcceptable(idResult)) {
                session = newSession
                openedWith = candidate
                return newSession
            }
            // Still running but silent: the user never answered the root prompt.
            // Trying the next candidate would only show the prompt again.
            val ignoredPrompt = idResult == null && newSession.isAlive()
            newSession.destroy()
            if (ignoredPrompt) break
        }
        return null
    }
}

/**
 * One running shell process plus the two threads that read its output.
 * Not thread-safe on its own: PersistentShell serializes access.
 */
internal class ShellSession private constructor(private val process: Process) {

    private val stdin: Writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
    private val stdoutLines = LinkedBlockingQueue<Any>()
    private val stderrLines = LinkedBlockingQueue<Any>()

    @Volatile
    private var dead = false

    init {
        startReader(process.inputStream, stdoutLines, "fridagate-shell-stdout")
        startReader(process.errorStream, stderrLines, "fridagate-shell-stderr")
    }

    fun isAlive(): Boolean {
        if (dead) return false
        return try {
            process.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    /**
     * Runs one command and waits for it.
     * Returns null if it didn't finish within [timeoutMs] or the shell died;
     * the caller must then destroy this session.
     */
    fun run(command: String, timeoutMs: Long): RootUtils.Result? {
        if (!discardStaleOutput()) return null
        val marker = "__FRIDAGATE_" + UUID.randomUUID().toString().replace("-", "")
        try {
            stdin.write("sh -c ${ShellUtils.quote(command)} </dev/null\n")
            stdin.write("echo \"$marker \$?\"\n")
            stdin.write("echo $marker >&2\n")
            stdin.flush()
        } catch (e: IOException) {
            dead = true
            return null
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val stdout = StringBuilder()
        val exitText = readUntilMarker(stdoutLines, marker, stdout, deadline) ?: return null
        val stderr = StringBuilder()
        readUntilMarker(stderrLines, marker, stderr, deadline) ?: return null

        val exitCode = exitText.trim().toIntOrNull() ?: 255
        return RootUtils.Result(exitCode, stdout.toString(), stderr.toString())
    }

    /**
     * Moves lines from [queue] into [into] until one contains [marker].
     * Text before the marker on that line is output that had no trailing newline.
     *
     * @return the text after the marker (the exit code, on stdout), or null on timeout / EOF
     */
    private fun readUntilMarker(
        queue: LinkedBlockingQueue<Any>,
        marker: String,
        into: StringBuilder,
        deadline: Long
    ): String? {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            val item = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
            if (item === EOF) {
                dead = true
                return null
            }
            val line = item as String
            val index = line.indexOf(marker)
            if (index >= 0) {
                into.append(line, 0, index)
                return line.substring(index + marker.length)
            }
            into.append(line).append('\n')
        }
    }

    /**
     * Drops output that arrived between commands (e.g. from a background job).
     * Returns false if the shell has exited.
     */
    private fun discardStaleOutput(): Boolean {
        for (queue in listOf(stdoutLines, stderrLines)) {
            while (true) {
                val item = queue.poll() ?: break
                if (item === EOF) dead = true
            }
        }
        return !dead
    }

    fun destroy() {
        dead = true
        try {
            stdin.write("exit\n")
            stdin.flush()
        } catch (_: IOException) {
        }
        try {
            stdin.close()
        } catch (_: IOException) {
        }
        process.destroy()
    }

    companion object {
        /** Put in a queue when its stream reaches end of file */
        private val EOF = Any()

        /** Starts [command] (e.g. su). Returns null if the binary can't be executed. */
        fun start(command: List<String>): ShellSession? = try {
            ShellSession(ProcessBuilder(command).start())
        } catch (e: IOException) {
            null
        }

        private fun startReader(stream: InputStream, queue: LinkedBlockingQueue<Any>, name: String) {
            val reader = Thread({
                try {
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { lines ->
                        while (true) {
                            val line = lines.readLine() ?: break
                            queue.put(line)
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    queue.put(EOF)
                }
            }, name)
            reader.isDaemon = true
            reader.start()
        }
    }
}
