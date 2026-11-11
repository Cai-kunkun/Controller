package dev.arrbrants.controller.agent

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.android.AndroidUtils
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Executes shell commands through ADB wireless debugging (LibADB). */
object AdbBackend {

    @Volatile private var connected = false
    @Volatile private var manager: AdbManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun isAvailable(): Boolean = connected

    @Synchronized
    fun pair(context: Context, port: Int, code: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val mgr = AdbManager.get(context)
            manager = mgr
            val host = AndroidUtils.getHostIpAddress(context)
            val ok = mgr.pair(host, port, code)
            if (ok) connect(context) else false
        } catch (e: Throwable) {
            false
        }
    }

    @Synchronized
    fun connect(context: Context): Boolean {
        return try {
            val mgr = AdbManager.get(context)
            manager = mgr
            connected = mgr.autoConnect(context, 5000)
            connected
        } catch (e: Throwable) {
            try {
                val mgr = AdbManager.get(context)
                manager = mgr
                connected = mgr.connect(5555)
            } catch (e2: Throwable) {
                connected = false
            }
            connected
        }
    }

    @Synchronized
    fun disconnect() {
        try {
            manager?.close()
        } catch (e: Throwable) {
            // Already disconnected.
        }
        connected = false
    }

    fun exec(command: String, timeoutMs: Long): RootShell.Result {
        if (!connected) return RootShell.Result(1, "", "ADB is not connected")
        val mgr = manager ?: return RootShell.Result(1, "", "ADB is not connected")
        var stream: io.github.muntashirakon.adb.AdbStream? = null
        return try {
            stream = mgr.openStream("shell:$command")
            val streamRef = stream
            val future = executor.submit<String> {
                streamRef.openInputStream().bufferedReader().use { it.readText() }
            }
            val output = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            RootShell.Result(0, output.trim(), "")
        } catch (e: TimeoutException) {
            try {
                stream?.close()
            } catch (ignored: Exception) {
            }
            RootShell.Result(1, "", "ADB command timed out")
        } catch (e: Throwable) {
            RootShell.Result(1, "", e.message ?: "ADB command failed")
        }
    }

    fun execBytes(command: String, timeoutMs: Long): ByteArray {
        check(connected) { "ADB is not connected" }
        val mgr = manager ?: throw IllegalStateException("ADB is not connected")
        var stream: io.github.muntashirakon.adb.AdbStream? = null
        try {
            stream = mgr.openStream("shell:$command")
            val streamRef = stream
            val future = executor.submit<ByteArray> {
                streamRef.openInputStream().readBytes()
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw java.io.IOException("ADB command timed out")
        } finally {
            try {
                stream?.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
