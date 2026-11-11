package dev.arrbrants.controller

import android.app.Application
import dev.arrbrants.controller.agent.AdbBackend
import dev.arrbrants.controller.agent.Shell
import dev.arrbrants.controller.data.AppPrefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.init(this)
        if (AppPrefs.shellMode(this) == "adb") {
            Thread { AdbBackend.connect(this) }.start()
        }
    }
}
