package org.kdx.shell.droid

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Lets programs inside DroidSystem start Flatpak apps the way KDX does.
 *
 * `flatpak run` normally builds a bubblewrap sandbox, which can't work under proot (Android gives apps no kernel
 * namespaces). KDX runs Flatpak apps with its own [FlatpakRunner] instead, but programs inside DroidSystem – real
 * KDE Plasma's Kickoff and panel, the real Bazaar's "Open" button, a `flatpak run` typed in a terminal – call
 * /usr/bin/flatpak. So that becomes [WRAPPER]: for `run` it hands the request to this server (a localhost socket,
 * protected by a random token in a file only DroidSystem can read), and KDX starts the app on the display the request
 * came from. Every other command goes to the real flatpak (/usr/bin/flatpak.real).
 */
class LinuxLauncher(val sys: DebianSystem, private val onRun: (Request) -> String?) {
    /** A `flatpak run` request: app [id], optional --command, the app's arguments, and the caller's X display number. */
    data class Request(val id: String, val command: String?, val args: List<String>, val display: Int?)

    @Volatile private var server: ServerSocket? = null
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    val port get() = server?.localPort ?: 0

    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        File(sys.root, INFO).apply { parentFile?.mkdirs() }.writeText("${s.localPort} $token\n")
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ serve(c) }, "kdx-launcher-client").apply { isDaemon = true; start() }
            }
        }, "kdx-launcher").apply { isDaemon = true; start() }
    }

    fun stop() { runCatching { server?.close() }; server = null; File(sys.root, INFO).delete() }

    private fun serve(c: Socket) = c.use { sock ->
        sock.soTimeout = 10_000
        val lines = mutableListOf<String>()
        val r = sock.getInputStream().bufferedReader()
        while (lines.size < 1000) { val l = r.readLine() ?: break; lines += l; if (l.startsWith("RUN ")) break }
        val reply = when (val req = parse(lines, token)) {
            null -> "ERR bad request"
            else -> runCatching { onRun(req) }.fold({ it?.let { e -> "ERR $e" } ?: "OK" }, { "ERR ${it.message}" })
        }
        sock.getOutputStream().apply { write("$reply\n".toByteArray()); flush() }
    }

    companion object {
        /** Port and token, inside DroidSystem (KDX's private storage: other Android apps can't read it). */
        const val INFO = "run/kdx/launcher"

        /** Parses a request; null if the token is wrong or it's malformed. */
        fun parse(lines: List<String>, token: String): Request? {
            if (lines.firstOrNull() != "KDX1 $token") return null
            var display: Int? = null; var command: String? = null; val args = mutableListOf<String>()
            for (l in lines.drop(1)) {
                val key = l.substringBefore(' '); val v = l.substringAfter(' ', "")
                when (key) {
                    "DISPLAY" -> display = v.substringAfter(':').substringBefore('.').toIntOrNull()
                    "COMMAND" -> command = v.ifBlank { null }
                    "ARG" -> args += runCatching { String(java.util.Base64.getDecoder().decode(v)) }.getOrElse { return null }
                    "RUN" -> return v.trim().takeIf { ID.matches(it) }?.let { Request(it, command, args, display) }
                }
            }
            return null
        }

        private val ID = Regex("[A-Za-z0-9_.-]+")

        /** /usr/bin/flatpak inside DroidSystem. */
        val WRAPPER = """#!/bin/bash
# KDX-DroidSystem flatpak wrapper
# Flatpak's sandbox (bubblewrap) can't run under proot, so "flatpak run" asks KDX to start the app its own way.
# Everything else goes to the real flatpak.
REAL=/usr/bin/flatpak.real
[ "${'$'}1" = run ] && [ -r /$INFO ] || exec "${'$'}REAL" "${'$'}@"
orig=("${'$'}@"); shift
cmd=; id=
while [ ${'$'}# -gt 0 ]; do
  case "${'$'}1" in
    --command=*) cmd=${'$'}{1#--command=} ;;
    --command) shift; cmd=${'$'}1 ;;
    --branch|--arch|--runtime|--runtime-version|--cwd|--env|--filesystem|--socket|--device|--share|--talk-name|--own-name) shift ;;
    -*) ;;
    *) id=${'$'}1; shift; break ;;
  esac
  shift
done
[ -n "${'$'}id" ] || exec "${'$'}REAL" "${'$'}{orig[@]}"
read -r port token < /$INFO
exec 3<>"/dev/tcp/127.0.0.1/${'$'}port" 2>/dev/null || exec "${'$'}REAL" "${'$'}{orig[@]}"
{
  echo "KDX1 ${'$'}token"
  echo "DISPLAY ${'$'}{DISPLAY:-}"
  [ -n "${'$'}cmd" ] && echo "COMMAND ${'$'}cmd"
  for a in "${'$'}@"; do
    case "${'$'}a" in @@|@@u) continue ;; esac   # file-forwarding markers
    printf 'ARG %s\n' "${'$'}(printf %s "${'$'}a" | base64 -w0)"
  done
  echo "RUN ${'$'}id"
} >&3
read -r reply <&3
case "${'$'}reply" in
  OK*) exit 0 ;;
  *) echo "flatpak run ${'$'}id: ${'$'}{reply#ERR }" >&2; exit 1 ;;
esac
"""

        private const val MARKER = "# KDX-DroidSystem flatpak wrapper"

        /**
         * DroidSystem pre-creates /var/lib/flatpak/repo for its own layout; an OSTree repo folder without a config
         * makes the real flatpak fail ("opening repo: openat(config)") – and with it `flatpak list` and the real
         * Bazaar. Turn such a folder into a valid, empty repo.
         */
        fun ensureSystemRepo(sys: DebianSystem) {
            val repo = File(sys.root, "var/lib/flatpak/repo")
            if (!repo.isDirectory || File(repo, "config").isFile) return
            listOf("objects", "tmp", "refs/heads", "refs/remotes", "refs/mirrors", "state", "extensions").forEach { File(repo, it).mkdirs() }
            File(repo, "config").writeText("[core]\nrepo_version=1\nmode=bare-user-only\n")
        }

        /** Puts [WRAPPER] in place of /usr/bin/flatpak (the real one is diverted, so apt upgrades keep working). */
        fun installWrapper(sys: DebianSystem) {
            ensureSystemRepo(sys)
            val bin = File(sys.root, "usr/bin/flatpak")
            if (!sys.hasCommand("usr/bin/flatpak")) return
            val current = runCatching { bin.readText().take(200) }.getOrDefault("")
            if (current.contains(MARKER)) { if (bin.readText() != WRAPPER) bin.writeText(WRAPPER); return }
            if (!sys.hasCommand("usr/bin/flatpak.real")) {
                sys.run("dpkg-divert --local --rename --divert /usr/bin/flatpak.real --add /usr/bin/flatpak")
            }
            if (sys.hasCommand("usr/bin/flatpak.real")) {
                java.nio.file.Files.deleteIfExists(bin.toPath())
                bin.writeText(WRAPPER); bin.setExecutable(true, false)
            }
        }
    }
}
