package org.kdx.shell.droid

import java.io.File

/**
 * WindSystem: Windows programs on Android, with Wine inside DroidSystem.
 *
 * Wine isn't Windows: it runs Windows programs by providing the Windows APIs itself, and tells them it is
 * Windows 10 (configurable). It is installed from Debian:
 *  - x86_64 phones/tablets/emulators: Debian's Wine (64-bit + 32-bit programs)
 *  - ARM64 phones: box64 (x86_64 → ARM translator) running Debian's x86_64 Wine (64-bit programs)
 *
 * The Windows C: drive (the Wine prefix) lives in DroidSystem's private storage, because Wine needs symlinks,
 * which Android's shared storage doesn't allow. Your side of WindSystem is the folder Android/WindSystem: drive D:,
 * with Programs (put installers and .exe files here), Documents, Crash reports and Logs. The whole phone storage is E:.
 */
object WindSystem {
    /** The Wine prefix (C: drive) inside DroidSystem. */
    const val PREFIX = "/root/.windsystem"

    /** Where KDX installs WindSystem's own helper scripts inside DroidSystem. */
    const val BIN = "/usr/local/lib/windsystem"

    fun isInstalled(sys: DebianSystem) = sys.hasCommand("usr/local/bin/windsystem-wine")

    fun usesBox64(sys: DebianSystem) = sys.arch == "arm64"

    /** Command line that runs Wine (through box64 on ARM). */
    const val WINE = "windsystem-wine"

    /** Folders inside Android/WindSystem. */
    val FOLDERS = listOf("Programs", "Documents", "Crash reports", "Logs")

    /** The packages / setup script for `windsystem-setup`. */
    fun setupScript(): String = """#!/bin/bash
# Installs WindSystem: Wine (plus box64 on ARM phones) from Debian, and its helper commands.
set -e
arch=${'$'}(dpkg --print-architecture)
echo "Installing WindSystem for ${'$'}arch…"
command -v Xvnc >/dev/null 2>&1 || display-setup
case "${'$'}arch" in
  amd64)
    dpkg --add-architecture i386
    apt-get -q update
    apt-get -q install -y --no-install-recommends -o Dpkg::Progress-Fancy=0 wine wine64 wine32:i386 libwine libwine:i386 fonts-wine \
      winbind xdotool x11-utils openbox fonts-liberation2
    cat > /usr/local/bin/windsystem-wine <<'WRAP'
${wineWrapper("amd64")}WRAP
    cat > /usr/local/bin/windsystem-wineserver <<'WRAP'
${serverWrapper("amd64")}WRAP
    ;;
  arm64)
    # Windows programs are x86: box64 translates x86_64 code to ARM; Wine and its libraries are Debian's amd64 packages
    dpkg --add-architecture amd64
    apt-get -q update
    apt-get -q install -y --no-install-recommends -o Dpkg::Progress-Fancy=0 box64 xdotool x11-utils openbox fonts-liberation2
    apt-get -q install -y --no-install-recommends -o Dpkg::Progress-Fancy=0 wine64:amd64 libwine:amd64 fonts-wine
    # Wine starts its server through Debian's /usr/lib/wine/wineserver *shell script*, which runs wineserver64 directly –
    # an x86_64 program the phone can't run, and box64 can't step in from a script. So wineserver64 itself becomes a
    # small script that runs the real one (diverted, so upgrades keep working) through box64.
    if ! grep -q "$SERVER_MARKER" /usr/lib/wine/wineserver64 2>/dev/null; then
      dpkg-divert --local --rename --divert /usr/lib/wine/wineserver64.x86 --add /usr/lib/wine/wineserver64
      cat > /usr/lib/wine/wineserver64 <<'WRAP'
$SERVER_BOX64_SCRIPT
WRAP
      chmod +x /usr/lib/wine/wineserver64
    fi
    cat > /usr/local/bin/windsystem-wine <<'WRAP'
${wineWrapper("arm64")}WRAP
    cat > /usr/local/bin/windsystem-wineserver <<'WRAP'
${serverWrapper("arm64")}WRAP
    ;;
  *)
    echo "WindSystem needs an x86_64 or ARM64 device (this one is ${'$'}arch)."; exit 1 ;;
esac
chmod +x /usr/local/bin/windsystem-wine /usr/local/bin/windsystem-wineserver
echo "Checking that Wine starts on this device…"
if ! out=${'$'}(timeout 180 /usr/local/bin/windsystem-wine --version 2>&1); then
  echo "${'$'}out" | tail -25
  echo
  echo "Wine doesn't start on this device (the lines above say why). Send them to the KDX developer."
  rm -f /usr/local/bin/windsystem-wine   # not installed until Wine works
  exit 1
fi
echo "${'$'}out"
echo
echo "WindSystem is installed. Open it from Kickoff → WindSystem (the first start prepares drive C:)."
"""

    /**
     * `windsystem-wine`: starts Wine (through box64 on ARM). TMPDIR must not be set: Debian's Wine 10 aborts at start-up
     * with "free(): invalid pointer" when it is (DroidSystem sets TMPDIR=/tmp for every program).
     */
    fun wineWrapper(arch: String) = """#!/bin/sh
# WindSystem: runs Wine${if (arch == "arm64") " through box64" else ""}
unset TMPDIR
${if (arch == "arm64") "$BOX64_ENV\nexec box64 /usr/lib/wine/wine64 \"${'$'}@\"" else "[ -x /usr/lib/wine/wine64 ] && exec /usr/lib/wine/wine64 \"${'$'}@\"\nexec wine \"${'$'}@\""}
"""

    fun serverWrapper(arch: String) = """#!/bin/sh
unset TMPDIR
${if (arch == "arm64") "$BOX64_ENV\nexec /usr/lib/wine/wineserver64 \"${'$'}@\"" else "[ -x /usr/lib/wine/wineserver64 ] && exec /usr/lib/wine/wineserver64 \"${'$'}@\"\nexec wineserver \"${'$'}@\""}
"""

    private const val SERVER_MARKER = "WindSystem: the x86_64 Wine server, through box64"

    /** /usr/lib/wine/wineserver64 on ARM: the real (diverted) x86_64 server, through box64. */
    val SERVER_BOX64_SCRIPT = """#!/bin/sh
# $SERVER_MARKER
export BOX64_LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu
exec box64 /usr/lib/wine/wineserver64.x86 "${'$'}@"
"""

    /** Where box64 finds Debian's x86_64 libraries (multiarch folders) and Wine's own. */
    private const val BOX64_ENV = "export BOX64_LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix\nexport BOX64_PATH=/usr/lib/wine:/usr/bin"

    /**
     * A quick self-check when WindSystem can't start: device, box64, Wine files, and whether Wine runs at all
     * (its own error output). Shown in WindSystem's window, ready to copy.
     */
    fun diagnose(sys: DebianSystem): String = runCatching {
        sys.query("""
            echo "Device: ${'$'}(uname -m), DroidSystem packages: ${'$'}(dpkg --print-architecture), memory page size: ${'$'}(getconf PAGESIZE 2>/dev/null)"
            if command -v box64 >/dev/null 2>&1; then echo "box64: ${'$'}(box64 --version 2>&1 | head -1)"; else echo "box64: not installed"; fi
            grep -q "$SERVER_MARKER" /usr/lib/wine/wineserver64 2>/dev/null && echo "wineserver64: runs through box64" || echo "wineserver64: original"
            for f in /usr/lib/wine/wine64 /usr/lib/wine/wineserver64 /usr/local/bin/windsystem-wine; do [ -e "${'$'}f" ] && echo "found ${'$'}f" || echo "MISSING ${'$'}f"; done
            dpkg -l 'wine64*' 'libwine*' box64 2>/dev/null | awk '/^[a-z][a-z] /{print ${'$'}1, ${'$'}2, ${'$'}3}'
            echo "--- windsystem-wine --version:"
            timeout 90 windsystem-wine --version 2>&1 | tail -15; echo "(exit ${'$'}?)"
        """.trimIndent()).second.trim()
    }.getOrElse { "Self-check failed: ${it.message}" }

    /** Rewrites the wrappers (so fixes reach existing installs); call before starting WindSystem. */
    fun ensureWrappers(sys: DebianSystem) {
        if (!isInstalled(sys)) return
        // ARM installs from before this fix: put the box64 wineserver64 in place (see setupScript)
        if (sys.arch == "arm64" && runCatching { File(sys.root, "usr/lib/wine/wineserver64").readText().contains(SERVER_MARKER) }.getOrDefault(false).not()) {
            sys.run("[ -e /usr/lib/wine/wineserver64.x86 ] || dpkg-divert --local --rename --divert /usr/lib/wine/wineserver64.x86 --add /usr/lib/wine/wineserver64")
            if (File(sys.root, "usr/lib/wine/wineserver64.x86").exists()) {
                File(sys.root, "usr/lib/wine/wineserver64").apply { java.nio.file.Files.deleteIfExists(toPath()); writeText(SERVER_BOX64_SCRIPT); setExecutable(true, false) }
            }
        }
        listOf("windsystem-wine" to wineWrapper(sys.arch), "windsystem-wineserver" to serverWrapper(sys.arch)).forEach { (n, body) ->
            val f = File(sys.root, "usr/local/bin/$n")
            if (runCatching { f.readText() }.getOrNull() != body) { f.writeText(body); f.setExecutable(true, false) }
        }
    }

    /** Fixed screen sizes for WindSystem (besides phone/balanced/fast). */
    val RESOLUTIONS = listOf("800x600", "1024x768", "1280x720", "1280x800", "1366x768", "1440x900", "1600x900", "1920x1080",
        "720x1280", "1080x1920")

    /** A fixed resolution "WxH" as (w, h), or null for phone/balanced/fast. */
    fun fixedResolution(r: String): Pair<Int, Int>? = r.split('x').takeIf { it.size == 2 }?.let { (a, b) ->
        val w = a.toIntOrNull() ?: return null; val h = b.toIntOrNull() ?: return null
        (w / 8 * 8).coerceIn(640, 3840) to h.coerceIn(400, 2160)
    }

    /** Windows versions Wine can report to programs. */
    val WINDOWS_VERSIONS = listOf("win10" to "Windows 10", "win11" to "Windows 11", "win81" to "Windows 8.1", "win7" to "Windows 7", "winxp64" to "Windows XP")

    /** Settings, applied to the prefix (registry) and to Wine's environment each time WindSystem starts. */
    data class Config(
        val windowsVersion: String = "win10",
        val dpi: Int = 120,
        val renderer: String = "gl",            // gl = Direct3D through OpenGL (software), gdi = no 3D (fastest for 2D programs)
        val sound: Boolean = true,
        val phoneDriveE: Boolean = true,
        val darkTaskbar: Boolean = true,
        val clock24h: Boolean = true,
        val clockSeconds: Boolean = false,
        val showSearch: Boolean = true,
        val wallpaper: Long = 0xFF0F4C81,       // desktop colour (ARGB)
        val performance: String = "balanced",   // box64 presets: stable / balanced / fast
        val crashDialog: Boolean = true,        // show WindSystem's crash dialog when a program crashes
        val autoRestart: Boolean = false,       // restart a program once after it crashes
        val debugLog: String = "errors",        // off / errors / warnings / verbose
        val dllOverrides: String = "",          // e.g. "d3d9=native,builtin;xinput1_3=native"
        val resolution: String = "phone",       // phone / balanced / fast (like KDE Plasma's)
        val modernTheme: Boolean = true,        // Wine's flat "Light" window theme instead of the classic grey one
        val touchFrames: Boolean = true,        // title bars, window buttons, borders, menus and scrollbars sized for fingers
    )

    /** Environment for every Windows program. */
    fun env(c: Config, shared: String, box64: Boolean = false): Map<String, String> = buildMap {
        put("WINEPREFIX", PREFIX)
        // Wine starts its server and new Wine processes itself; through these wrappers they always go through box64
        // on ARM (instead of relying on box64 catching Wine's exec of an x86_64 program)
        if (box64) {
            put("WINESERVER", "/usr/local/bin/windsystem-wineserver")
            put("WINELOADER", "/usr/local/bin/windsystem-wine")
        }
        put("WINEARCH", "win64")
        put("WINEDEBUG", when (c.debugLog) { "off" -> "-all"; "warnings" -> "warn+all,fixme-all"; "verbose" -> "warn+all,+seh,+loaddll"; else -> "err+all,fixme-all,warn-all" })
        // no Windows-style menu entries in Linux menus / no browser integration prompts
        val overrides = mutableListOf("winemenubuilder.exe=", "mscoree=", "mshtml=")
        if (c.dllOverrides.isNotBlank()) overrides += c.dllOverrides.split(';', ',').filter { it.contains('=') }.map { it.trim() }
        put("WINEDLLOVERRIDES", overrides.joinToString(";"))
        put("WINDSYSTEM_SHARED", shared)
        // box64 (ARM): speed/compatibility presets
        when (c.performance) {
            "stable" -> { put("BOX64_DYNAREC_BIGBLOCK", "0"); put("BOX64_DYNAREC_STRONGMEM", "2"); put("BOX64_DYNAREC_SAFEFLAGS", "2") }
            "fast" -> { put("BOX64_DYNAREC_BIGBLOCK", "3"); put("BOX64_DYNAREC_STRONGMEM", "0"); put("BOX64_DYNAREC_FASTNAN", "1"); put("BOX64_DYNAREC_FASTROUND", "1"); put("BOX64_DYNAREC_CALLRET", "1") }
            else -> { put("BOX64_DYNAREC_BIGBLOCK", "1"); put("BOX64_DYNAREC_STRONGMEM", "1") }
        }
        put("BOX64_NOBANNER", "1"); put("BOX64_LOG", "0")
        if (!c.sound) put("PULSE_SERVER", "none")
    }

    /** Registry settings for [c] (imported with `regedit /S` – one process for all of them). */
    /**
     * Sizes of window parts (title bar, its buttons, borders, menus, scrollbars). Wine draws them at classic pixel
     * sizes (an 18 px title bar), which is tiny on a phone screen; for touch they grow with the DPI. Values are in
     * Windows' twips: -15 × pixels.
     */
    fun windowMetrics(c: Config): String {
        val scale = (c.dpi.coerceAtLeast(96) / 96f) * (if (c.touchFrames) 1.35f else 1f)
        fun px(base: Int) = "\"" + (-15 * Math.round(base * scale)) + "\""
        return "[HKEY_CURRENT_USER\\Control Panel\\Desktop\\WindowMetrics]\r\n" +
            "\"CaptionHeight\"=${px(22)}\r\n\"CaptionWidth\"=${px(22)}\r\n" +
            "\"SmCaptionHeight\"=${px(18)}\r\n\"SmCaptionWidth\"=${px(18)}\r\n" +
            "\"BorderWidth\"=${px(if (c.touchFrames) 2 else 1)}\r\n\"PaddedBorderWidth\"=\"0\"\r\n" +
            "\"MenuHeight\"=${px(22)}\r\n\"MenuWidth\"=${px(22)}\r\n" +
            "\"ScrollHeight\"=${px(if (c.touchFrames) 20 else 16)}\r\n\"ScrollWidth\"=${px(if (c.touchFrames) 20 else 16)}\r\n\r\n"
    }

    /** Height of a window's title-bar strip (frame + caption) in X pixels, matching [windowMetrics]. */
    fun titleBarPx(c: Config): Int {
        val scale = (c.dpi.coerceAtLeast(96) / 96f) * (if (c.touchFrames) 1.35f else 1f)
        return Math.round(22 * scale) + Math.round((if (c.touchFrames) 2 else 1) * scale) + 8
    }

    fun registry(c: Config): String = buildString {
        append("Windows Registry Editor Version 5.00\r\n\r\n")
        append("[HKEY_CURRENT_USER\\Software\\Wine]\r\n\"Version\"=\"${c.windowsVersion}\"\r\n\r\n")
        append("[HKEY_CURRENT_USER\\Control Panel\\Desktop]\r\n\"LogPixels\"=dword:${"%08x".format(c.dpi)}\r\n\r\n")
        append("[HKEY_CURRENT_USER\\Software\\Wine\\Fonts]\r\n\"LogPixels\"=dword:${"%08x".format(c.dpi)}\r\n\r\n")
        append(windowMetrics(c))
        append("[HKEY_CURRENT_USER\\Software\\Wine\\Direct3D]\r\n\"renderer\"=\"${if (c.renderer == "gdi") "gdi" else "gl"}\"\r\n\r\n")
        append("[HKEY_CURRENT_USER\\Software\\Wine\\Drivers]\r\n\"Audio\"=\"${if (c.sound) "pulse" else ""}\"\r\n\r\n")
        // crashes: no interactive Wine debugger dialog; winedbg prints the backtrace into the program's log instead
        append("[HKEY_CURRENT_USER\\Software\\Wine\\WineDbg]\r\n\"ShowCrashDialog\"=dword:00000000\r\n\r\n")
        // Wine draws window frames itself (Windows-style title bars); the X window manager only places and focuses
        append("[HKEY_CURRENT_USER\\Software\\Wine\\X11 Driver]\r\n\"Decorated\"=\"N\"\r\n\"Managed\"=\"Y\"\r\n\"UseTakeFocus\"=\"N\"\r\n\r\n")
        // window theme: Wine's flat "Light" visual style (modern look) or classic
        append("[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\ThemeManager]\r\n")
        if (c.modernTheme) append("\"ThemeActive\"=\"1\"\r\n\"DllName\"=\"C:\\\\windows\\\\resources\\\\themes\\\\light\\\\light.msstyles\"\r\n\"ColorName\"=\"NormalColor\"\r\n\"SizeName\"=\"NormalSize\"\r\n\r\n")
        else append("\"ThemeActive\"=\"0\"\r\n\r\n")
        append("[HKEY_CURRENT_USER\\Control Panel\\Colors]\r\n\"Background\"=\"${(c.wallpaper shr 16) and 0xFF} ${(c.wallpaper shr 8) and 0xFF} ${c.wallpaper and 0xFF}\"\r\n")
    }

    /**
     * Drive letters: D: = Android/WindSystem, E: = the phone's storage (Wine's dosdevices are symlinks in the
     * private prefix pointing at shared storage, which is allowed).
     */
    fun driveScript(c: Config, shared: String): String = """
        mkdir -p "$PREFIX/dosdevices"
        ln -sfn "$shared" "$PREFIX/dosdevices/d:"
        ${if (c.phoneDriveE) "ln -sfn /storage/emulated/0 \"$PREFIX/dosdevices/e:\"" else "rm -f \"$PREFIX/dosdevices/e:\""}
    """.trimIndent()

    /** A program in the start menu. */
    data class Program(val name: String, val command: List<String>, val icon: File? = null, val builtin: Boolean = false, val category: String = "")

    /** Windows tools that come with Wine. */
    val BUILTIN = listOf(
        Program("File Explorer", listOf("explorer"), builtin = true, category = "Windows tools"),
        Program("Command Prompt", listOf("wineconsole", "cmd"), builtin = true, category = "Windows tools"),
        Program("Notepad", listOf("notepad"), builtin = true, category = "Windows accessories"),
        Program("WordPad", listOf("wordpad"), builtin = true, category = "Windows accessories"),
        Program("Paint", listOf("mspaint"), builtin = true, category = "Windows accessories"),
        Program("Task Manager", listOf("taskmgr"), builtin = true, category = "Windows tools"),
        Program("Control Panel", listOf("control"), builtin = true, category = "Windows tools"),
        Program("Registry Editor", listOf("regedit"), builtin = true, category = "Windows tools"),
        Program("Wine Configuration", listOf("winecfg"), builtin = true, category = "Windows tools"),
        Program("Add or Remove Programs", listOf("uninstaller"), builtin = true, category = "Windows tools"),
        Program("Internet Explorer", listOf("iexplore"), builtin = true, category = "Windows accessories"),
        Program("Calculator", listOf("winecalc"), builtin = true, category = "Windows accessories"),
    )

    /**
     * Programs installed into the prefix: Wine's own Start Menu shortcuts are .lnk files, which installers create;
     * the .exe files they point at are found by scanning Program Files for the shortcut's name. Plus every .exe/.msi
     * in Android/WindSystem/Programs (tap to run or install).
     */
    fun installedPrograms(sys: DebianSystem, sharedHost: File): List<Program> {
        val out = mutableListOf<Program>()
        val drive = File(sys.root, "${PREFIX.removePrefix("/")}/drive_c")
        val lnkRoots = listOf(File(drive, "ProgramData/Microsoft/Windows/Start Menu/Programs"),
            File(drive, "users/root/AppData/Roaming/Microsoft/Windows/Start Menu/Programs"))
        lnkRoots.forEach { r ->
            r.walkTopDown().maxDepth(4).filter { it.isFile && it.name.endsWith(".lnk", true) }.forEach { lnk ->
                val name = lnk.name.removeSuffix(".lnk").removeSuffix(".LNK")
                if (name.contains("uninstall", true)) return@forEach
                val target = LnkFile.target(lnk)
                // run the program itself (not through `start`), so WindSystem sees its output, exit and crashes
                out += Program(name, if (target != null) listOf(winToUnix(target)) else listOf("start", "/unix", "$PREFIX/drive_c/${lnk.relativeTo(drive).path}"),
                    category = "Installed")
            }
        }
        (sharedHost.resolve("Programs").listFiles() ?: emptyArray()).filter { it.isFile && it.extension.lowercase() in setOf("exe", "msi", "bat") }
            .sortedBy { it.name.lowercase() }.forEach { f ->
                val unix = "${sharedHostToGuest(sharedHost)}/Programs/${f.name}"
                out += Program(f.nameWithoutExtension, when (f.extension.lowercase()) {
                    "msi" -> listOf("msiexec", "/i", unix); "bat" -> listOf("start", "/unix", unix); else -> listOf(unix) },
                    category = "Programs folder")
            }
        return out.distinctBy { it.name.lowercase() }
    }

    /** "C:\\Program Files\\App\\app.exe" → path inside DroidSystem. */
    fun winToUnix(win: String): String {
        val p = win.replace('\\', '/')
        return if (p.length > 2 && p[1] == ':') "$PREFIX/dosdevices/${p[0].lowercaseChar()}:${p.substring(2)}" else p
    }

    /** Android/WindSystem as seen inside DroidSystem (shared storage is bound at the same path). */
    fun sharedHostToGuest(host: File): String = host.absolutePath

    /** What a crash looked like, from Wine's output. */
    data class Crash(val program: String, val code: Int, val summary: String, val details: List<String>)

    /**
     * Detects a crash in a program's output: Wine prints "Unhandled exception: …" (and with the crash dialog off,
     * winedbg adds registers and a backtrace). Returns null for a normal exit.
     */
    fun parseCrash(program: String, exitCode: Int, lines: List<String>): Crash? {
        // prefer winedbg's "Unhandled exception: …" line (clearest), else Wine's own "wine: Unhandled …"
        val i = lines.indexOfFirst { it.contains("Unhandled exception:") }.takeIf { it >= 0 }
            ?: lines.indexOfFirst { it.contains("Unhandled page fault") || it.contains("wine: Unhandled") }
        if (i < 0 && (exitCode == 0 || exitCode == 1)) return null
        if (i < 0) {
            val why = when (exitCode) {
                137 -> "Android ended it – usually low memory."
                139 -> "Segmentation fault (in Wine or box64)."
                134 -> "It aborted."
                else -> "It exited with code $exitCode."
            }
            return Crash(program, exitCode, why, lines.takeLast(40))
        }
        val summary = lines[i].substringAfter("Unhandled exception:").substringAfter("wine: ").trim().ifEmpty { lines[i].trim() }
        val bt = lines.indexOfFirst { it.trim().startsWith("Backtrace:") }
        val details = if (bt >= 0) lines.subList(bt, minOf(lines.size, bt + 30)) else lines.subList(i, minOf(lines.size, i + 30))
        return Crash(program, exitCode, summary, details)
    }

    /** A crash report as text (saved to Android/WindSystem/Crash reports). */
    fun report(c: Crash, config: Config, arch: String) = buildString {
        appendLine("WindSystem crash report – ${java.util.Date()}")
        appendLine("Program: ${c.program}")
        appendLine("Exit code: ${c.code}")
        appendLine("What happened: ${c.summary}")
        appendLine("Device: $arch${if (arch == "arm64") " (box64, ${config.performance})" else ""}; reports ${WINDOWS_VERSIONS.firstOrNull { it.first == config.windowsVersion }?.second}; renderer ${config.renderer}")
        appendLine("----")
        c.details.forEach { appendLine(it) }
    }

    /**
     * Prints the visible windows on the display every 1.5 s as "id<TAB>title" lines followed by "--" (one long-running
     * process instead of one per check), for WindSystem's taskbar.
     */
    const val WINDOW_LIST_SCRIPT = """while :; do
  for w in ${'$'}(xdotool search --onlyvisible --name '.' 2>/dev/null); do
    t=${'$'}(xdotool getwindowname "${'$'}w" 2>/dev/null) || continue
    [ -n "${'$'}t" ] || continue
    eval ${'$'}(xdotool getwindowgeometry --shell "${'$'}w" 2>/dev/null)
    printf '%s\t%s %s %s %s\t%s\n' "${'$'}w" "${'$'}X" "${'$'}Y" "${'$'}WIDTH" "${'$'}HEIGHT" "${'$'}t"
  done
  echo --
  sleep 1.5
done"""

    /** Openbox config for WindSystem: Wine draws Windows-style frames (and asks Openbox for none); click to focus. */
    const val OPENBOX_RC = "etc/xdg/openbox/windsystem.xml"
    val OPENBOX_XML = """<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc">
  <focus><focusNew>yes</focusNew><followMouse>no</followMouse></focus>
  <placement><policy>Smart</policy><center>yes</center></placement>
  <theme><name>Clearlooks</name><titleLayout>NLIMC</titleLayout>
    <!-- a big title font makes Openbox's (fallback) title bars tall enough for fingers -->
    <font place="ActiveWindow"><name>sans</name><size>16</size><weight>bold</weight></font>
    <font place="InactiveWindow"><name>sans</name><size>16</size></font>
  </theme>
  <desktops><number>1</number></desktops>
  <!-- no rule stripping every frame: Wine-drawn windows ask for no frame themselves (Motif hints), and any window
       that doesn't draw its own title bar gets Openbox's – so there is always a title bar -->
  <mouse><context name="Root"><mousebind button="Right" action="Press"/></context></mouse>
</openbox_config>
"""
}

/** Reads the target path out of a Windows shortcut (.lnk): enough of the format for installers' shortcuts. */
object LnkFile {
    fun target(f: File): String? = runCatching {
        val b = f.readBytes()
        if (b.size < 0x4c || b[0].toInt() != 0x4c) return null
        val flags = le32(b, 0x14)
        var p = 0x4c
        if (flags and 0x01 != 0) p += 2 + le16(b, p) // LinkTargetIDList
        if (flags and 0x02 == 0) return null          // no LinkInfo
        val infoStart = p
        val localBaseOff = le32(b, infoStart + 0x10)
        val headerSize = le32(b, infoStart + 0x04)
        if (headerSize >= 0x24) {
            val uOff = le32(b, infoStart + 0x1c)
            if (uOff != 0) return readUtf16z(b, infoStart + uOff)
        }
        if (localBaseOff == 0) return null
        readAnsiz(b, infoStart + localBaseOff)
    }.getOrNull()

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
    private fun readAnsiz(b: ByteArray, i: Int): String { var e = i; while (e < b.size && b[e].toInt() != 0) e++; return String(b, i, e - i, Charsets.ISO_8859_1) }
    private fun readUtf16z(b: ByteArray, i: Int): String { var e = i; while (e + 1 < b.size && (b[e].toInt() != 0 || b[e + 1].toInt() != 0)) e += 2; return String(b, i, e - i, Charsets.UTF_16LE) }
}
