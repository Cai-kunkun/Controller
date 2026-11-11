package dev.arrbrants.controller.agent

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs commands through the device's `su` binary (KernelSU, Magisk, …).
 * Each call is a fresh `su -c` process: simple and fast enough for
 * screencap / input actions.
 */
object RootShell {

    /** KernelSU hides `su` from app namespaces and redirects execve instead; try several paths. */
    private val SU_CANDIDATES = listOf(
        "su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su"
    )

    data class Result(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
    }

    fun isRootAvailable(): Boolean = try {
        // Root is granted manually in the KernelSU/Magisk manager; a denied
        // `su` exits immediately, so a short timeout is enough.
        exec("id", 10000).stdout.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    fun exec(command: String, timeoutMs: Long = 20000): Result {
        val process = startSu(command)
        return runProcess(process, timeoutMs)
    }

    /** Runs a command and returns its raw stdout (used for binary output such as `screencap -p`). */
    fun execBytes(command: String, timeoutMs: Long = 20000): ByteArray {
        val process = startSu(command)
        return readProcessBytes(process, timeoutMs, command)
    }

    /** Shared by the root, Shizuku and ADB backends. */
    internal fun readProcessBytes(process: Process, timeoutMs: Long, command: String): ByteArray {
        val err = StringBuilder()
        val pumpErr = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
            } catch (e: Exception) {
                // Stream closed; nothing to do.
            }
        }
        pumpErr.start()
        val bytes = process.inputStream.readBytes()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IOException("Command timed out: $command")
        }
        pumpErr.join(500)
        if (bytes.isEmpty() && process.exitValue() != 0) {
            throw IOException(err.toString().trim().ifBlank { "Command failed (${process.exitValue()})" })
        }
        return bytes
    }

    /** Shared by the root and Shizuku backends. */
    internal fun runProcess(process: Process, timeoutMs: Long): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val pumpOut = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
            } catch (e: Exception) {
                // Stream closed (e.g. after a timeout kill); nothing to do.
            }
        }
        val pumpErr = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
            } catch (e: Exception) {
                // Same as above.
            }
        }
        pumpOut.start()
        pumpErr.start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IOException("Command timed out")
        }
        pumpOut.join(500)
        pumpErr.join(500)
        return Result(process.exitValue(), out.toString().trim(), err.toString().trim())
    }

    private fun startSu(command: String): Process {
        var lastError: IOException? = null
        for (candidate in SU_CANDIDATES) {
            try {
                return ProcessBuilder(candidate, "-c", command).start()
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("su binary not found")
    }

    /** Single-quotes a string so it survives `sh -c` parsing. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
