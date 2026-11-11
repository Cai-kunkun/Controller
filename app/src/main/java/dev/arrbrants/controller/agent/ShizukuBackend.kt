package dev.arrbrants.controller.agent

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream

/** Executes shell commands through a running Shizuku service. */
object ShizukuBackend {

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            // Shizuku is not running.
        }
    }

    fun exec(command: String, timeoutMs: Long): RootShell.Result {
        if (!isAvailable()) return RootShell.Result(1, "", "Shizuku is not running")
        if (!hasPermission()) {
            return RootShell.Result(1, "", "Shizuku permission not granted")
        }
        val process = try {
            val binder = Shizuku.getBinder() ?: return RootShell.Result(1, "", "Shizuku binder unavailable")
            val service = IShizukuService.Stub.asInterface(binder)
            val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
            RemoteProcess(remote)
        } catch (e: Throwable) {
            return RootShell.Result(1, "", "Shizuku exec failed: ${e.message}")
        }
        return try {
            RootShell.runProcess(process, timeoutMs)
        } catch (e: Exception) {
            RootShell.Result(1, "", e.message ?: "Shizuku command failed")
        }
    }

    fun execBytes(command: String, timeoutMs: Long): ByteArray {
        check(hasPermission()) { "Shizuku permission not granted" }
        val binder = Shizuku.getBinder() ?: throw IllegalStateException("Shizuku binder unavailable")
        val service = IShizukuService.Stub.asInterface(binder)
        val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
        return RootShell.readProcessBytes(RemoteProcess(remote), timeoutMs, command)
    }

    /** Adapts Shizuku's ParcelFileDescriptor-based process to java.lang.Process. */
    private class RemoteProcess(private val remote: IRemoteProcess) : Process() {
        private val stdin: OutputStream by lazy {
            ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
        }
        private val stdout: InputStream by lazy {
            ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
        }
        private val stderr: InputStream by lazy {
            ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = remote.waitFor()
        override fun exitValue(): Int = remote.exitValue()
        override fun destroy() {
            try {
                remote.destroy()
            } catch (e: Throwable) {
                // Already gone.
            }
        }
    }
}
