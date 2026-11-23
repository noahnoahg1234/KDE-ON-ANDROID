package org.kdx.shell

import android.app.Application
import org.kdx.shell.core.StartGuard
import java.io.File

/** Runs before anything else in KDX's process: the restart-loop check has to come before KDX's own screen and state. */
class KdxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val name = runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault(packageName)
        if (name == packageName) StartGuard.onProcessStart(this) else StartGuard.installCrashRecorder(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android asking for memory back (it ends apps that don't give enough): worth knowing when the screen blinks
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) StartGuard.log(this, "Android is low on memory (trim level $level)")
    }
}
