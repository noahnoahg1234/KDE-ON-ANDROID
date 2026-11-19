package org.kdx.shell.droid

import android.os.Build
import org.kdx.shell.apps.KonsoleSession
import org.kdx.shell.core.KdxState
import java.io.File

/** DroidSystem's userland commands, available in Konsole when the Linux system is enabled. */
object DroidShell {
    val help = listOf(
        "DroidSystem commands:",
        "  bazaar                        open the Bazaar app store",
        "  dsfetch                       system information",
        "  dspkg update|upgrade|list|search|show|install|remove|build",
        "        install accepts names, .deb, .rpm, .pkg.tar.{gz,xz} and .dsp files",
        "  apt / apt-get                 aliases for dspkg",
        "  flatpak list|remote-ls|search|info|install|uninstall|run|update",
        "          remotes|remote-add FILE.flatpakrepo|remote-delete NAME",
        "  run-desktop <file.desktop>    launch a .desktop entry",
        "  extract <archive> [dir]       zip jar 7z tar tar.gz/xz/bz2 gz xz bz2 cpio ar",
        "  linuxinfo <file>              identify Linux binaries, AppImages, packages",
        "  droidsystem                   version and install location",
        "Installed commands run by name and work in pipes: fortune | droidsay",
        "Built-ins can be piped too: dspkg list | grep game",
    )

    /** Splits a command line, honouring single and double quotes. */
    fun split(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var q: Char? = null
        var any = false
        for (ch in s) {
            when {
                q != null && ch == q -> q = null
                q != null -> cur.append(ch)
                ch == '"' || ch == '\'' -> { q = ch; any = true }
                ch.isWhitespace() -> { if (cur.isNotEmpty() || any) out += cur.toString(); cur.clear(); any = false }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun file(s: KonsoleSession, p: String) = when {
        p.startsWith("/") -> File(p)
        p == "~" -> s.home
        p.startsWith("~/") -> File(s.home, p.drop(2))
        else -> File(s.cwd, p)
    }

    private val builtins = setOf(
        "dsfetch", "neofetch", "droidsystem", "dspkg", "apt", "apt-get", "flatpak", "run-desktop", "extract",
        "linuxinfo", "file", "bazaar", "kdx-app",
    )

    /** Index of the first unquoted '|' that is not part of '||', or -1. */
    private fun pipeIndex(line: String): Int {
        var q: Char? = null
        line.forEachIndexed { i, ch ->
            when {
                q != null -> if (ch == q) q = null
                ch == '"' || ch == '\'' -> q = ch
                ch == '|' && line.getOrNull(i + 1) != '|' && line.getOrNull(i - 1) != '|' -> return i
            }
        }
        return -1
    }

    /** Returns true if the command was a DroidSystem command (handled here). */
    fun handle(s: KonsoleSession, state: KdxState, ds: DroidSystem, line: String): Boolean {
        val argv = split(line)
        if (argv.isEmpty()) return false
        val args = argv.drop(1)
        // `builtin … | shell pipeline`: capture the builtin's output and feed it to the rest.
        val pipe = pipeIndex(line)
        if (argv[0] in builtins && pipe > 0) {
            val captured = mutableListOf<String>()
            s.capture = captured
            try { handle(s, state, ds, line.substring(0, pipe)) } finally { s.capture = null }
            s.exec(line.substring(pipe + 1), captured.joinToString("\n", postfix = "\n"))
            return true
        }
        when (argv[0]) {
            "bazaar" -> state.open(org.kdx.shell.core.KApp.BAZAAR)
            "kdx-app" -> runCatching { state.open(org.kdx.shell.core.KApp.valueOf(args.first().uppercase())) }
                .onFailure { s.println("kdx-app: unknown app ${args.firstOrNull() ?: ""}", 2) }
            "dsfetch", "neofetch" -> dsfetch(s, state, ds)
            "droidsystem" -> {
                s.println(ds.version); s.println("Root: ${ds.root.path}")
                s.println("Packages: ${ds.installed().size} · Flatpaks: ${ds.flatpakInstalled().size}")
            }
            "dspkg", "apt", "apt-get" -> dspkg(s, state, ds, argv[0], args)
            "flatpak" -> if (!state.flatpakEnabled) s.println("flatpak: Flatpak support is turned off in System Settings → Linux System", 2)
                else flatpak(s, state, ds, args)
            "run-desktop" -> {
                val f = args.firstOrNull()?.let { file(s, it) } ?: return usage(s, "run-desktop <file.desktop>")
                val e = DroidSystem.parseDesktop(f) ?: return err(s, "run-desktop: not a launchable .desktop file")
                s.println("Launching ${e.name}…", 2)
                s.submitLine(e.exec)
            }
            "extract" -> {
                val f = args.firstOrNull()?.let { file(s, it) } ?: return usage(s, "extract <archive> [dir]")
                val dest = args.getOrNull(1)?.let { file(s, it) }
                    ?: File(f.parentFile, f.name.replace(Regex("\\.(tar\\.(gz|xz|bz2)|tgz|txz|tbz2|tar|zip|dsp)$", RegexOption.IGNORE_CASE), ""))
                s.background { log ->
                    val n = ds.extract(f, dest.apply { mkdirs() })
                    log("Extracted $n files to ${dest.path}")
                }
            }
            "linuxinfo", "file" -> {
                if (args.isEmpty()) return usage(s, "linuxinfo <file>")
                DroidSystem.describe(file(s, args[0])).forEach { s.println(it) }
                if (file(s, args[0]).extension.lowercase() == "deb") runCatching {
                    ds.inspectDeb(file(s, args[0])).forEach { (k, v) -> s.println("$k: $v") }
                }
            }
            else -> {
                val cmd = argv[0]
                // ./program or path to a file
                if (cmd.contains('/')) {
                    val f = file(s, cmd)
                    if (!f.isFile) return false
                    if (DroidSystem.isElf(f)) { DroidSystem.describe(f).drop(2).forEach { s.println(it, 2) }; return true }
                    s.exec("sh ${quote(f.path)} ${args.joinToString(" ") { quote(it) }}")
                    return true
                }
                val bin = ds.which(cmd)
                if (bin != null && DroidSystem.isElf(bin)) { DroidSystem.describe(bin).drop(2).forEach { s.println(it, 2) }; return true }
                // Installed commands are shell functions in the preamble, so pipes, ;, && etc. all work.
                s.exec(line)
            }
        }
        return true
    }

    private fun usage(s: KonsoleSession, u: String): Boolean { s.println("usage: $u", 2); return true }
    private fun err(s: KonsoleSession, m: String): Boolean { s.println(m, 2); return true }

    private fun dsfetch(s: KonsoleSession, state: KdxState, ds: DroidSystem) {
        val art = listOf(
            "    .-------.    ",
            "   /  o   o  \\   ",
            "  |    ___    |  ",
            "  |   \\___/   |  ",
            "   \\_________/   ",
            "    |  | |  |    ",
            "   [__]   [__]   ",
        )
        val mem = runCatching {
            val m = File("/proc/meminfo").readLines()
            fun kb(k: String) = m.first { it.startsWith(k) }.split(Regex("\\s+"))[1].toLong()
            "${(kb("MemTotal") - kb("MemAvailable")) / 1024} MiB / ${kb("MemTotal") / 1024} MiB"
        }.getOrDefault("unknown")
        val info = listOf(
            "user@droidsystem",
            "----------------",
            "OS: ${ds.version}",
            "Host: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Kernel: ${System.getProperty("os.version")}",
            "Base: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Packages: ${ds.installed().size} (dspkg), ${ds.flatpakInstalled().size} (flatpak)",
            "Shell: konsole + sh",
            "DE: KDX (Plasma-style)",
            "Theme: ${state.themeMode.label}",
            "CPU: ${Runtime.getRuntime().availableProcessors()} × ${Build.SUPPORTED_ABIS.firstOrNull()}",
            "Memory: $mem",
        )
        for (i in 0 until maxOf(art.size, info.size)) s.println((art.getOrNull(i) ?: " ".repeat(17)) + "  " + (info.getOrNull(i) ?: ""), if (i < 2) 1 else 0)
    }

    private fun dspkg(s: KonsoleSession, state: KdxState, ds: DroidSystem, name: String, args: List<String>) {
        val sub = args.firstOrNull() ?: run {
            s.println("usage: $name update | list [--installed|--available] | search TERM | show PKG | install PKG|FILE… | remove PKG… | build DIR", 2); return
        }
        val rest = args.drop(1).filterNot { it == "-y" || it == "--yes" }
        when (sub) {
            "update" -> {
                s.println("Hit:1 main (bundled with KDX, offline) – ${ds.available().size} packages")
                s.println("Reading package lists… Done")
            }
            "upgrade", "dist-upgrade", "full-upgrade" -> s.background { log ->
                val up = ds.upgrade(log)
                log(if (up.isEmpty()) "All packages are up to date." else "Upgraded: ${up.joinToString()}")
                state.refreshLinuxApps()
            }
            "list" -> {
                val inst = ds.installed().associateBy { it.name }
                val list = when {
                    "--installed" in rest -> inst.values.toList()
                    else -> (ds.available() + inst.values.filter { p -> ds.available().none { it.name == p.name } }).distinctBy { it.name }
                }
                list.forEach { p -> s.println("%-14s %-8s %-6s %s%s".format(p.name, p.version, p.arch, p.description, if (p.name in inst) "  [installed]" else "")) }
            }
            "search" -> {
                val q = rest.joinToString(" ")
                ds.available().filter { it.name.contains(q, true) || it.description.contains(q, true) }
                    .forEach { s.println("${it.name}/${it.source} ${it.version}\n  ${it.description}") }
            }
            "show", "info" -> {
                val n = rest.firstOrNull() ?: return
                val p = ds.installed().firstOrNull { it.name == n } ?: ds.available().firstOrNull { it.name == n }
                if (p == null) s.println("E: Unable to locate package $n", 2)
                else listOf("Package: ${p.name}", "Version: ${p.version}", "Architecture: ${p.arch}", "Source: ${p.source}",
                    "Status: ${if (ds.isPkgInstalled(p.name)) "installed" else "not installed"}", "Description: ${p.description}").forEach { s.println(it) }
            }
            "install" -> {
                if (rest.isEmpty()) { s.println("E: nothing to install", 2); return }
                s.background { log ->
                    rest.forEach { target ->
                        val f = file(s, target)
                        runCatching {
                            when {
                                f.isFile && f.extension.equals("deb", true) -> ds.installDeb(f, log)
                                f.isFile && f.extension.equals("dsp", true) -> ds.installDsp(f, log)
                                f.isFile && f.extension.equals("rpm", true) -> ds.installRpm(f, log)
                                f.isFile && f.name.contains(".pkg.tar") -> ds.installArch(f, log)
                                f.isFile -> throw java.io.IOException("${f.name}: unsupported package type (use .deb, .rpm, .pkg.tar.* or .dsp)")
                                ds.isPkgInstalled(target) -> log("$target is already the newest version.")
                                else -> ds.installFromRepo(target, log)
                            }
                        }.onFailure { log("E: ${it.message}") }
                    }
                    state.refreshLinuxApps()
                }
            }
            "remove", "purge", "uninstall" -> s.background { log ->
                rest.forEach { n -> runCatching { ds.remove(n, log) }.onFailure { log("E: ${it.message}") } }
                state.refreshLinuxApps()
            }
            "build" -> {
                val d = rest.firstOrNull()?.let { file(s, it) } ?: return
                s.background { log -> ds.buildDsp(d, log) }
            }
            else -> s.println("$name: unknown command '$sub'", 2)
        }
    }

    private fun flatpak(s: KonsoleSession, state: KdxState, ds: DroidSystem, args: List<String>) {
        val sub = args.firstOrNull() ?: "--help"
        val rest = args.drop(1).filterNot { it.startsWith("-") }
        when (sub) {
            "--help", "help" -> listOf(
                "Usage: flatpak COMMAND",
                "  list                      installed applications",
                "  remotes                   configured remotes",
                "  remote-ls [droidhub]      apps available in DroidHub (offline)",
                "  search TERM               search DroidHub",
                "  install [droidhub] ID     install from DroidHub",
                "  install PATH              install a build dir / .zip / .tar.* (metadata + files/), or a .flatpakref",
                "  info ID | run ID [ARGS] | uninstall ID",
            ).forEach { s.println(it) }
            "--version" -> s.println("Flatpak (DroidSystem implementation) 1.0")
            "remotes" -> {
                s.println("%-12s %s".format("Name", "Options / URL"))
                s.println("%-12s %s".format("droidhub", "system,offline (bundled with KDX)"))
                ds.flatpakRemotes().filter { it.first != "droidhub" }.forEach { (n, u) -> s.println("%-12s %s".format(n, "$u (offline – not fetched)")) }
            }
            "remote-add" -> {
                val f = rest.lastOrNull()?.let { file(s, it) } ?: return run { s.println("usage: flatpak remote-add FILE.flatpakrepo", 2) }
                runCatching { ds.flatpakRemoteAdd(f, rest.takeIf { it.size > 1 }?.first()) }
                    .onSuccess { s.println("Added remote '$it'. DroidSystem never downloads, so only build directories can be installed from it.") }
                    .onFailure { s.println("error: ${it.message}", 2) }
            }
            "remote-delete" -> {
                val n = rest.firstOrNull() ?: return
                if (n == "droidhub") s.println("error: droidhub is built in", 2)
                else s.println(if (File(ds.root, "etc/flatpak/remotes.d/$n.flatpakrepo").delete()) "Removed remote $n" else "error: no remote $n", 0)
            }
            "update" -> s.background { log ->
                val up = ds.upgrade(log).filter { it.contains('.') }
                log(if (up.isEmpty()) "Nothing to do." else "Updated: ${up.joinToString()}")
            }
            "remote-ls", "search" -> {
                val q = if (sub == "search") rest.joinToString(" ") else ""
                val installed = ds.flatpakInstalled().map { it.id }.toSet()
                ds.flatpakRemote().filter { q.isEmpty() || it.id.contains(q, true) || it.name.contains(q, true) || it.comment.contains(q, true) }
                    .forEach { s.println("%-16s %-28s %s%s".format(it.name, it.id, it.comment, if (it.id in installed) "  [installed]" else "")) }
            }
            "list" -> {
                val l = ds.flatpakInstalled()
                if (l.isEmpty()) s.println("No Flatpak apps installed.")
                l.forEach { s.println("%-16s %-28s %s".format(it.name, it.id, it.runtime)) }
            }
            "info" -> {
                val id = rest.firstOrNull() ?: return
                val a = ds.flatpakInstalled().firstOrNull { it.id == id } ?: return run { s.println("error: $id not installed", 2) }
                listOf("ID: ${a.id}", "Name: ${a.name}", "Description: ${a.comment}", "Command: ${a.command}", "Runtime: ${a.runtime}",
                    "Location: ${ds.flatpakDeploy(id).path}").forEach { s.println(it) }
            }
            "install" -> {
                val target = rest.lastOrNull() ?: return run { s.println("error: specify an app ID or a path", 2) }
                val f = file(s, target)
                s.background { log ->
                    runCatching {
                        when {
                            f.exists() && f.name.endsWith(".flatpakref") -> {
                                val ref = DroidSystem.parseIni(f.readText())["Flatpak Ref"] ?: emptyMap()
                                val id = ref["Name"] ?: throw java.io.IOException("Invalid .flatpakref")
                                if (ds.flatpakRemote().any { it.id == id }) ds.flatpakInstallBundled(id, log)
                                else throw java.io.IOException(
                                    "$id comes from ${ref["Url"] ?: "a remote server"}. DroidSystem is offline-only and never downloads apps; " +
                                        "install the app's build directory instead."
                                )
                            }
                            f.exists() && f.name.endsWith(".flatpakrepo") -> log("Added remote '${ds.flatpakRemoteAdd(f)}'.")
                            f.exists() && f.name.endsWith(".flatpak") -> throw java.io.IOException(
                                "Single-file .flatpak bundles use OSTree, which DroidSystem doesn't support. Install a build directory (metadata + files/) instead."
                            )
                            f.exists() -> ds.flatpakInstallBuild(f, log)
                            else -> ds.flatpakInstallBundled(target, log)
                        }
                    }.onFailure { log("error: ${it.message}") }
                    state.refreshLinuxApps()
                }
            }
            "uninstall", "remove" -> {
                val id = rest.firstOrNull() ?: return
                if (ds.flatpakInstalled().none { it.id == id }) s.println("error: $id is not installed", 2)
                else { ds.flatpakUninstall(id); s.println("Uninstalled $id") }
                state.refreshLinuxApps()
            }
            "run" -> {
                val id = args.drop(1).firstOrNull { !it.startsWith("-") } ?: return
                val appArgs = args.drop(1).dropWhile { it != id }.drop(1)
                val entry = ds.flatpakEntry(id) ?: return run { s.println("error: app/$id not installed", 2) }
                if (DroidSystem.isElf(entry)) { DroidSystem.describe(entry).drop(2).forEach { s.println(it, 2) }; return }
                s.exec("FLATPAK_ID=$id sh ${quote(entry.path)} ${appArgs.joinToString(" ") { quote(it) }}")
            }
            else -> s.println("error: unknown command '$sub' (see flatpak --help)", 2)
        }
    }
}
