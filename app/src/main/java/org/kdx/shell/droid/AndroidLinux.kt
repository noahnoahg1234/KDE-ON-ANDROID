package org.kdx.shell.droid

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/** Wires DroidSystem's Debian to Android: the bundled proot, storage and /proc workarounds. */
object AndroidLinux {
    fun arch(): String = DebianRepo.archForAbi(Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")

    /** proot ships inside the APK as native libraries (see app/src/main/jniLibs). */
    fun prootConfig(ctx: Context): ProotConfig {
        val lib = ctx.applicationInfo.nativeLibraryDir
        val tmp = File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }
        val env = mutableMapOf(
            "PROOT_TMP_DIR" to tmp.path,
            "PROOT_LOADER" to "$lib/libproot-loader.so",
            "LD_LIBRARY_PATH" to lib,
        )
        if (File(lib, "libproot-loader32.so").isFile) env["PROOT_LOADER_32"] = "$lib/libproot-loader32.so"
        val binds = mutableListOf<Pair<String, String>>()
        // Shared storage at /sdcard and /storage, as on Android
        runCatching {
            val sd = Environment.getExternalStorageDirectory()
            if (sd.canRead()) { binds += "/sdcard" to sd.path; binds += "/storage" to "/storage" }
        }
        binds += fakeProc(ctx)
        return ProotConfig("$lib/libproot.so", env, listOf("--kill-on-exit", "--link2symlink", "--sysvipc"), binds)
    }

    /**
     * Android hides some /proc files from apps (SELinux). Tools like top, ps and free need them, so – like
     * Termux's proot-distro – give programs harmless stand-ins for the ones we can't read.
     */
    private fun fakeProc(ctx: Context): List<Pair<String, String>> {
        val dir = File(ctx.filesDir, "proc-stubs").apply { mkdirs() }
        val cores = Runtime.getRuntime().availableProcessors()
        val stubs = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to buildString {
                append("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")
                repeat(cores) { append("cpu$it 500 0 700 23000 60 80 60 20 0 0\n") }
                append("intr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\nsoftirq 0\n")
            },
            "uptime" to "7000.00 6000.00\n",
            "version" to "Linux version ${System.getProperty("os.version")} (droidsystem@kdx) #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 100000\nnr_inactive_anon 1000\nnr_active_anon 1000\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
        )
        return stubs.mapNotNull { (name, content) ->
            val real = File("/proc/$name")
            if (runCatching { real.readText().isNotEmpty() }.getOrDefault(false)) null
            else { val f = File(dir, name); f.writeText(content); "/proc/$name" to f.path }
        }
    }

    fun debian(ctx: Context, home: File?): DebianSystem = DebianSystem(File(ctx.filesDir, "debian"), prootConfig(ctx), home, arch())

    // ------------------------------------------------------------------ Linux apps for Kickoff
    private val iconSizes = listOf("64x64", "48x48", "96x96", "128x128", "256x256", "32x32")

    /** Finds a PNG for an icon name in the hicolor theme or pixmaps (SVG icons can't be drawn natively). */
    fun findIcon(roots: List<File>, icon: String?): File? {
        if (icon.isNullOrBlank()) return null
        if (icon.startsWith("/")) return null // absolute paths are rare; themes cover nearly all apps
        for (r in roots) {
            for (s in iconSizes) File(r, "share/icons/hicolor/$s/apps/$icon.png").takeIf { it.isFile }?.let { return it }
            File(r, "share/pixmaps/$icon.png").takeIf { it.isFile }?.let { return it }
            for (theme in listOf("Adwaita", "breeze")) for (s in iconSizes) File(r, "share/icons/$theme/$s/apps/$icon.png").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /** Desktop apps installed in Debian (apt) and Flatpak apps, as Kickoff entries. */
    fun desktopEntries(sys: DebianSystem, includeFlatpaks: Boolean): List<DroidSystem.DesktopEntry> {
        val out = mutableListOf<DroidSystem.DesktopEntry>()
        val usr = File(sys.root, "usr")
        (File(usr, "share/applications").listFiles { f -> f.name.endsWith(".desktop") } ?: emptyArray()).forEach { f ->
            val e = DroidSystem.parseDesktop(f) ?: return@forEach
            if (e.exec.isBlank()) return@forEach
            val ini = runCatching { DroidSystem.parseIni(f.readText())["Desktop Entry"] }.getOrNull() ?: return@forEach
            if (ini["OnlyShowIn"] != null && ini["OnlyShowIn"]?.contains("KDE") != true) return@forEach
            out += e.copy(source = DroidSystem.EntrySource.DEBIAN, terminal = ini["Terminal"] == "true",
                icon = findIcon(listOf(usr), ini["Icon"]))
        }
        if (includeFlatpaks) {
            listOf(File(sys.root, "var/lib/flatpak/exports"), File(sys.flatpakHome, "exports")).forEach { exp ->
                (File(exp, "share/applications").listFiles { f -> f.name.endsWith(".desktop") } ?: emptyArray()).forEach { f ->
                    val ini = runCatching { DroidSystem.parseIni(f.readText())["Desktop Entry"] }.getOrNull() ?: return@forEach
                    val id = ini["X-Flatpak"] ?: f.nameWithoutExtension
                    val e = DroidSystem.parseDesktop(f) ?: return@forEach
                    out += e.copy(flatpakId = id, source = DroidSystem.EntrySource.FLATHUB, terminal = ini["Terminal"] == "true",
                        icon = findIcon(listOf(exp), ini["Icon"]))
                }
            }
        }
        return out.distinctBy { it.key }.sortedBy { it.name.lowercase() }
    }
}
