package org.kdx.shell.droid

import java.io.File

/**
 * Finds and kills DroidSystem processes. Linux apps run as children of proot; when a window closes or KDX
 * restarts, those children can outlive the process KDX started (a killed tracer leaves its tracees running), and a
 * leftover app makes the next launch hand over to it and exit. Processes are recognised by their environment.
 */
object Procs {
    private val self = runCatching { File("/proc/self").canonicalFile.name.toInt() }.getOrDefault(-1)

    private fun read(f: File) = runCatching { String(f.readBytes()) }.getOrDefault("")

    /** PIDs of our processes whose environment contains [entry] exactly (e.g. "KDX_TAG=d3-123"). */
    fun withEnv(entry: String): List<Int> = all().filter { pid ->
        read(File("/proc/$pid/environ")).split('\u0000').any { it == entry }
    }

    /** PIDs whose command line contains [marker]. */
    fun withCmdline(marker: String): List<Int> = all().filter { pid -> read(File("/proc/$pid/cmdline")).contains(marker) }

    private fun all(): List<Int> = (File("/proc").listFiles() ?: emptyArray())
        .mapNotNull { it.name.toIntOrNull() }.filter { it != self && it > 1 }

    fun kill(pids: Collection<Int>, signal: Int = 9) {
        if (pids.isEmpty()) return
        runCatching { Runtime.getRuntime().exec(arrayOf("kill", "-$signal") + pids.map { it.toString() }).waitFor() }
    }

    /**
     * Asks every process carrying [entry] to quit (SIGTERM – apps like Firefox save their session and exit cleanly),
     * waits up to [graceMs], then force-kills whatever is left.
     */
    fun stopEnv(entry: String, graceMs: Long = 2000) {
        kill(withEnv(entry), 15)
        val end = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < end && withEnv(entry).isNotEmpty()) Thread.sleep(100)
        killEnv(entry)
    }

    /** Kills every process carrying [entry] in its environment; repeats once to catch children forked meanwhile. */
    fun killEnv(entry: String) {
        repeat(2) { kill(withEnv(entry)); Thread.sleep(150) }
    }
}
