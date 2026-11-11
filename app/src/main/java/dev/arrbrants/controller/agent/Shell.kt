package dev.arrbrants.controller.agent

import android.content.Context
import dev.arrbrants.controller.data.AppPrefs

/** Chooses the shell backend: root (su), Shizuku or ADB wireless debugging. */
object Shell {

    @Volatile private var appContext: Context? = null
    @Volatile private var mode: String = "root"

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh() {
        val ctx = appContext ?: return
        mode = AppPrefs.shellMode(ctx)
    }

    fun currentMode(): String = mode

    fun isAvailable(): Boolean = when (mode) {
        "shizuku" -> ShizukuBackend.isAvailable()
        "adb" -> AdbBackend.isAvailable()
        else -> RootShell.isRootAvailable()
    }

    fun exec(command: String, timeoutMs: Long = 20000): RootShell.Result = when (mode) {
        "shizuku" -> ShizukuBackend.exec(command, timeoutMs)
        "adb" -> AdbBackend.exec(command, timeoutMs)
        else -> RootShell.exec(command, timeoutMs)
    }

    /** Raw stdout, for binary commands such as `screencap -p`. */
    fun execBytes(command: String, timeoutMs: Long = 20000): ByteArray = when (mode) {
        "shizuku" -> ShizukuBackend.execBytes(command, timeoutMs)
        "adb" -> AdbBackend.execBytes(command, timeoutMs)
        else -> RootShell.execBytes(command, timeoutMs)
    }
}
