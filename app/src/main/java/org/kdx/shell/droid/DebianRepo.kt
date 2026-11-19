package org.kdx.shell.droid

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One binary package from a Debian `Packages` index. */
data class DebPackage(
    val name: String,
    val version: String,
    val priority: String,
    val essential: Boolean,
    val depends: List<List<String>>, // Pre-Depends + Depends; inner list = alternatives
    val provides: List<String>,
    val filename: String,
    val sha256: String,
    val size: Long,
)

/**
 * Reads Debian's package index and resolves what a minimal system needs – the same selection debootstrap's
 * "minbase" variant makes (all Priority: required packages, plus apt) – with dependencies closed over.
 */
class DebianRepo(
    val mirror: String = "https://deb.debian.org/debian",
    val suite: String = "trixie",
    val arch: String,
) {
    val packages = HashMap<String, DebPackage>()
    private val providers = HashMap<String, MutableList<String>>()

    fun load(cacheDir: File) {
        cacheDir.mkdirs()
        val f = File(cacheDir, "Packages-$suite-$arch")
        if (!f.isFile || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) {
            val tmp = File(cacheDir, f.name + ".part")
            open("$mirror/dists/$suite/main/binary-$arch/Packages.xz").use { input ->
                XZCompressorInputStream(input.buffered()).use { xz -> tmp.outputStream().use { xz.copyTo(it) } }
            }
            tmp.renameTo(f)
        }
        f.bufferedReader().use { parse(it.lineSequence()) }
    }

    fun parse(lines: Sequence<String>) {
        val cur = HashMap<String, String>()
        var last: String? = null
        fun flush() {
            val name = cur["Package"]
            if (name != null && cur["Filename"] != null) {
                val p = DebPackage(
                    name, cur["Version"] ?: "", cur["Priority"] ?: "optional", cur["Essential"] == "yes",
                    parseDeps((cur["Pre-Depends"]?.let { "$it, " } ?: "") + (cur["Depends"] ?: "")),
                    cur["Provides"]?.split(',')?.map { it.trim().substringBefore(' ') }?.filter { it.isNotEmpty() } ?: emptyList(),
                    cur["Filename"]!!, cur["SHA256"] ?: "", cur["Size"]?.toLongOrNull() ?: 0,
                )
                // keep the first entry for a name (Packages lists one version per suite)
                if (packages.putIfAbsent(name, p) == null) p.provides.forEach { providers.getOrPut(it) { mutableListOf() } += name }
            }
            cur.clear(); last = null
        }
        for (l in lines) {
            when {
                l.isEmpty() -> flush()
                l[0] == ' ' || l[0] == '\t' -> {} // continuation (descriptions) – not needed
                else -> {
                    val i = l.indexOf(':')
                    if (i > 0) {
                        val k = l.substring(0, i)
                        if (k in WANTED) { cur[k] = l.substring(i + 1).trim(); last = k }
                    }
                }
            }
        }
        flush()
    }

    /** Resolves a package name (or virtual package) to a real package. */
    fun resolve(name: String, chosen: Set<String> = emptySet()): DebPackage? {
        packages[name]?.let { return it }
        val prov = providers[name] ?: return null
        return (prov.firstOrNull { it in chosen } ?: prov.first()).let { packages[it] }
    }

    /** All packages needed for [roots], following Pre-Depends/Depends (first satisfiable alternative wins). */
    fun closure(roots: Collection<String>): List<DebPackage> {
        val chosen = LinkedHashMap<String, DebPackage>()
        val queue = ArrayDeque<String>()
        roots.forEach { queue += it }
        while (queue.isNotEmpty()) {
            val p = resolve(queue.removeFirst(), chosen.keys) ?: continue
            if (p.name in chosen) continue
            chosen[p.name] = p
            for (alts in p.depends) {
                if (alts.any { a -> a in chosen || chosen.values.any { a in it.provides } }) continue
                val pick = alts.firstOrNull { resolve(it, chosen.keys) != null } ?: continue
                queue += pick
            }
        }
        return chosen.values.toList()
    }

    /** debootstrap --variant=minbase selection, plus a few comforts. */
    fun minbase(extra: List<String> = DEFAULT_EXTRA): List<DebPackage> =
        closure(packages.values.filter { it.priority == "required" || it.essential }.map { it.name } + "apt" + extra)

    fun url(p: DebPackage) = "$mirror/${p.filename}"

    companion object {
        private val WANTED = setOf("Package", "Version", "Priority", "Essential", "Pre-Depends", "Depends", "Provides", "Filename", "SHA256", "Size")
        val DEFAULT_EXTRA = listOf("ca-certificates", "debian-archive-keyring", "less", "nano", "procps", "sensible-utils")

        fun parseDeps(s: String): List<List<String>> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { group ->
            group.split('|').map { alt -> alt.trim().substringBefore(' ').substringBefore('(').substringBefore(':').trim() }.filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }

        fun open(url: String): InputStream {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "DroidSystem-bootstrap/2.0")
            if (c.responseCode !in 200..299) { c.disconnect(); throw IOException("HTTP ${c.responseCode} for $url") }
            return c.inputStream
        }

        /** Downloads [url] to [dest], verifying SHA-256. Skips the download if a verified copy exists. */
        fun download(url: String, dest: File, sha256: String, onBytes: (Long) -> Unit = {}) {
            if (dest.isFile && sha256.isNotEmpty() && sha(dest) == sha256) { onBytes(dest.length()); return }
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            var lastErr: Exception? = null
            repeat(3) {
                try {
                    open(url).use { i ->
                        tmp.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); onBytes(n.toLong()) }
                        }
                    }
                    if (sha256.isNotEmpty() && sha(tmp) != sha256) throw IOException("Checksum mismatch for ${dest.name}")
                    tmp.renameTo(dest); return
                } catch (e: Exception) { lastErr = e }
            }
            throw IOException("Download failed: ${dest.name} (${lastErr?.message})", lastErr)
        }

        fun sha(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Debian architecture name for an Android ABI. */
        fun archForAbi(abi: String) = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armhf"
            "x86_64" -> "amd64"
            "x86" -> "i386"
            else -> throw IOException("Unsupported CPU: $abi")
        }
    }
}
