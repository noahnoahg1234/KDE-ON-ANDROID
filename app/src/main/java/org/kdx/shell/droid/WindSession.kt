package org.kdx.shell.droid

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One running WindSystem: its X display (Windows programs' windows, placed by Openbox, framed by Wine itself),
 * the programs started from its start menu, their logs and crashes, and the window list for the taskbar.
 */
class WindSession(
    val sys: DebianSystem,
    val display: LinuxDisplay,
    /** Android/WindSystem (also D: inside Windows). */
    val shared: File,
    @Volatile var config: WindSystem.Config,
    /** DPI when the config says "auto" (0): follows the screen's scale. */
    private val autoDpi: Int,
    private val onCrash: (WindSystem.Crash, File?) -> Unit,
    private val onWindows: (List<Pair<String, String>>, String?) -> Unit,
    private val status: (String?) -> Unit,
) {
    private val running = ConcurrentHashMap<Process, String>()
    @Volatile private var watcher: Process? = null
    val programCount get() = running.size

    private val env get() = display.env + WindSystem.env(config, shared.absolutePath, WindSystem.usesBox64(sys))

    /** Sets up drive C: on first start (a few minutes on a phone), applies drives and settings, starts the window list. */
    fun start() {
        WindSystem.FOLDERS.forEach { File(shared, it).mkdirs() }
        WindSystem.ensureWrappers(sys)
        sys.run("xsetroot -solid '#%06x' 2>/dev/null; true".format(config.wallpaper and 0xFFFFFF), env = display.env)
        display.launch("openbox --config-file /${WindSystem.OPENBOX_RC}")
        val prefix = File(sys.root, WindSystem.PREFIX.removePrefix("/"))
        // a complete drive C: has its registry and the Windows folder
        val firstRun = !(File(prefix, "system.reg").isFile && File(prefix, "drive_c/windows/system32").isDirectory)
        if (firstRun) {
            // anything less is an interrupted first start: start that over
            File(sys.root, WindSystem.PREFIX.removePrefix("/")).takeIf { it.exists() }?.deleteTree()
            status("Preparing drive C: (first start – this can take a few minutes)…")
        }
        else status("Starting WindSystem…")
        val startLog = File(shared, "Logs/WindSystem.log").apply { parentFile?.mkdirs(); writeText("WindSystem start – ${java.util.Date()}\n") }
        val toLog: (String) -> Unit = { l -> runCatching { startLog.appendText(l + "\n") } }
        // the Wine server first (persistent while WindSystem runs), then drive C:
        // (detached from our output pipe: the server keeps running, and a held pipe would make this wait forever)
        sys.run("mkdir -p ${WindSystem.PREFIX}; windsystem-wineserver -p </dev/null >/dev/null 2>&1; true", toLog, env = env)
        // Output goes to a file, not our pipe: Wine's background services (services.exe, plugplay…) keep running
        // after wineboot and would hold the pipe open – waiting for it to close would wait forever.
        var rc = sys.run(BOOT, toLog, env = env)
        if (rc != 0) {
            // once more with detailed logging, so the log says why
            toLog("---- failed with code $rc; trying again with detailed logging")
            sys.run("windsystem-wineserver -k 2>&1; true", toLog, env = env)
            val verbose = env + mapOf("WINEDEBUG" to "err+all,warn+server,+process,+loaddll", "BOX64_LOG" to "1", "BOX64_SHOWSEGV" to "1")
            sys.run("windsystem-wineserver -p </dev/null >/dev/null 2>&1; true", toLog, env = verbose)
            rc = sys.run(BOOT.replace("cat ", "tail -n 150 "), toLog, env = verbose)
        }
        if (rc != 0) throw java.io.IOException("Wine couldn't prepare drive C: (code $rc) – see ${startLog.path}")
        applySettings()
        status(null)
        startWatcher()
    }

    private val BOOT = "${WindSystem.WINE} wineboot --init </dev/null >/tmp/windsystem-boot.log 2>&1; rc=${'$'}?; cat /tmp/windsystem-boot.log; exit ${'$'}rc"

    /** Writes [config] into the registry and drive letters (one regedit run). */
    fun applySettings() {
        val c = config.copy(dpi = if (config.dpi == 0) autoDpi else config.dpi)
        val reg = File(sys.root, "tmp/windsystem-settings.reg").apply { writeText(WindSystem.registry(c)) }
        sys.run(WindSystem.driveScript(c, shared.absolutePath), env = env)
        sys.run("${WindSystem.WINE} regedit /S 'Z:\\tmp\\windsystem-settings.reg' >/dev/null 2>&1; true", env = env)
        reg.delete()
        sys.run("xsetroot -solid '#%06x' 2>/dev/null; true".format(c.wallpaper and 0xFFFFFF), env = display.env)
    }

    /** Runs a start-menu program; watches it and reports a crash (with its log) if it ends badly. */
    fun run(p: WindSystem.Program, attempt: Int = 1) {
        val args = p.command.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val exe = p.command.firstOrNull { it.startsWith("/") }
        val cd = exe?.let { "cd '" + File(it).parent.replace("'", "'\\''") + "' 2>/dev/null; " } ?: ""
        val proc = sys.command(listOf("/bin/bash", "-lc", "${cd}exec ${WindSystem.WINE} $args"), "/root", env).start()
        proc.outputStream.close()
        running[proc] = p.name
        val log = File(shared, "Logs").apply { mkdirs() }.resolve(p.name.replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".log")
        val lines = java.util.concurrent.ConcurrentLinkedDeque<String>()
        // output → log file (helper processes the program started may keep this going after it exits)
        Thread({
            runCatching {
                log.printWriter().use { w ->
                    w.println("WindSystem – ${p.name} – ${java.util.Date()}")
                    proc.inputStream.bufferedReader().forEachLine { l ->
                        w.println(l); w.flush()
                        lines.addLast(l); while (lines.size > 400) lines.pollFirst()
                    }
                }
            }
        }, "windsystem-log-${p.name}").apply { isDaemon = true; start() }
        // the program's own exit: report a crash right away
        Thread({
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            Thread.sleep(800) // let the last lines (winedbg's backtrace) arrive
            running.remove(proc)
            val crash = WindSystem.parseCrash(p.name, code, lines.toList()) ?: return@Thread
            val report = runCatching {
                val dir = File(shared, "Crash reports").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                File(dir, "${p.name.replace(Regex("[^A-Za-z0-9._-]+"), "_")}-$stamp.txt").apply { writeText(WindSystem.report(crash, config, sys.arch)) }
            }.getOrNull()
            if (config.autoRestart && attempt == 1 && code != 137) run(p, attempt = 2)
            if (config.crashDialog) onCrash(crash, report)
        }, "windsystem-${p.name}").apply { isDaemon = true; start() }
    }

    /** "Run…" box: a Windows command line, e.g. `notepad C:\test.txt` or `D:\Programs\setup.exe`. */
    fun runLine(line: String) {
        val parts = Regex("\"([^\"]*)\"|(\\S+)").findAll(line.trim()).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        if (parts.isEmpty()) return
        run(WindSystem.Program(parts.first().substringAfterLast('\\').substringAfterLast('/'), parts))
    }

    private fun xdo(args: String) = runCatching { sys.run("xdotool $args >/dev/null 2>&1; true", env = display.env) }

    fun focus(id: String) = xdo("windowactivate $id")
    fun minimize(id: String) = xdo("windowminimize $id")
    /** Asks the window to close (Alt+F4, like Windows): programs can still ask to save. */
    fun close(id: String) = xdo("windowactivate --sync $id key --clearmodifiers alt+F4")
    fun showDesktop(ids: List<String>) = ids.forEach { minimize(it) }

    private fun startWatcher() {
        watcher?.destroy()
        val script = WindSystem.WINDOW_LIST_SCRIPT.replace("  echo --", "  echo \"-- \$(xdotool getactivewindow 2>/dev/null)\"")
        val p = sys.command(listOf("/bin/bash", "-c", script), "/root", display.env).start()
        p.outputStream.close()
        watcher = p
        Thread({
            val cur = mutableListOf<Pair<String, String>>()
            runCatching {
                p.inputStream.bufferedReader().forEachLine { l ->
                    if (l.startsWith("--")) {
                        val active = l.removePrefix("--").trim().ifEmpty { null }
                        onWindows(cur.filter { (_, t) -> t != "Default IME" && t != "Wine System Tray" && !t.startsWith("OleMainThreadWndName") }, active)
                        cur.clear()
                    } else l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { cur += it[0] to it[1] }
                }
            }
        }, "windsystem-windows").apply { isDaemon = true; start() }
    }

    /** Ends every Windows program (like shutting down). */
    fun shutdown() {
        runCatching { sys.run("windsystem-wineserver -k >/dev/null 2>&1; true", env = env) }
        running.keys.forEach { it.destroy() }
        watcher?.destroy()
    }

    /** Restart: end all programs, then start again (prefix, settings, window list). */
    fun restart() { shutdown(); start() }
}
