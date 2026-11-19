package org.kdx.shell.droid

import android.content.Context
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DroidSystem: KDX's own Linux-style system. Everything here is bundled inside the APK
 * (app/src/main/assets/droidsystem) and installed into a normal folder on the phone –
 * nothing is downloaded.
 *
 * Layout (FHS-like):
 *   etc/            os-release, hostname, passwd, motd, profile, flatpak remotes
 *   home/user/      the user's home
 *   usr/bin/        commands installed by dspkg (shell scripts)
 *   usr/share/applications/  .desktop launchers shown in Kickoff
 *   var/lib/dspkg/  package database
 *   var/lib/flatpak/app/<id>/current/active/{metadata,files,export}
 */
class DroidSystem(private val ctx: Context, val root: File) {
    val home get() = File(root, "home/user")
    val binDirs get() = listOf(File(root, "usr/bin"), File(root, "bin"), File(root, "usr/local/bin"), File(home, ".local/bin"))
    private val db get() = File(root, "var/lib/dspkg/installed.json")
    val flatpakApps get() = File(root, "var/lib/flatpak/app")
    val isInstalled get() = File(root, "etc/os-release").isFile

    val version: String
        get() = runCatching {
            File(root, "etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')?.trim('"')
        }.getOrNull() ?: "DroidSystem"

    // ------------------------------------------------------------------ install
    fun install(progress: (Float, String) -> Unit) {
        val dirs = listOf(
            "bin", "boot", "dev", "etc/flatpak/remotes.d", "home/user/.local/bin", "home/user/.local/share/applications",
            "home/user/Desktop", "home/user/Downloads", "lib", "media", "mnt/android", "opt", "proc", "root", "run", "srv",
            "sys", "tmp", "usr/bin", "usr/lib", "usr/local/bin", "usr/share/applications", "usr/share/icons", "usr/share/doc",
            "var/cache/dspkg", "var/lib/dspkg", "var/lib/flatpak/app", "var/lib/flatpak/repo", "var/log",
        )
        progress(0.05f, "Creating file system hierarchy")
        dirs.forEach { File(root, it).mkdirs() }
        progress(0.25f, "Writing base system")
        copyAsset("droidsystem/rootfs", root)
        File(root, "etc/flatpak/remotes.d/droidhub.flatpakrepo").writeText(
            "[Flatpak Repo]\nTitle=DroidHub\nComment=Offline Flatpak remote bundled with DroidSystem\nUrl=file:///android_asset/droidsystem/flatpak\n"
        )
        File(root, "mnt/android/README").writeText("Your Android storage is the folder that contains DroidSystem.\n")
        progress(0.55f, "Registering base package")
        val installed = loadDb()
        installed.put("ds-base", JSONObject().put("version", "1.0").put("source", "main").put("files", JSONArray()))
        saveDb(installed)
        progress(0.75f, "Installing Welcome Tour flatpak")
        flatpakInstallBundled("org.droidsystem.Welcome") {}
        progress(0.95f, "Writing install log")
        File(root, "var/log/install.log").appendText("DroidSystem installed at ${java.util.Date()} into ${root.path}\n")
        progress(1f, "Done")
    }

    fun uninstall(): Boolean = root.deleteRecursively()

    /** Every file in the bundled image, relative to assets/droidsystem (generated at build time). */
    private val index: List<String> by lazy {
        ctx.assets.open("droidsystem/index.txt").bufferedReader().readLines().filter { it.isNotBlank() }
    }

    /** Full asset paths of all files below [path] (an "assets/" relative directory). */
    private fun assetFiles(path: String): List<String> {
        val rel = path.removePrefix("droidsystem/")
        return index.filter { it.startsWith("$rel/") }.map { "droidsystem/$it" }
    }

    /** Immediate sub-directory/file names of [path]. */
    private fun assetChildren(path: String): List<String> =
        assetFiles(path).map { it.removePrefix("$path/").substringBefore('/') }.distinct()

    private fun copyAsset(path: String, dest: File) {
        assetFiles(path).forEach { full ->
            val out = File(dest, full.removePrefix("$path/"))
            out.parentFile?.mkdirs()
            ctx.assets.open(full).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }

    fun sizeOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ------------------------------------------------------------------ commands
    /** Finds an installed command (script) on DroidSystem's PATH. */
    fun which(cmd: String): File? = binDirs.map { File(it, cmd) }.firstOrNull { it.isFile }

    fun commands(): List<String> = binDirs.flatMap { it.list()?.toList() ?: emptyList() }.distinct().sorted()

    // ------------------------------------------------------------------ dspkg
    data class Pkg(val name: String, val version: String, val arch: String, val description: String, val source: String)

    private fun loadDb(): JSONObject = runCatching { JSONObject(db.readText()) }.getOrDefault(JSONObject())
    private fun saveDb(o: JSONObject) { db.parentFile?.mkdirs(); db.writeText(o.toString(2)) }

    fun installed(): List<Pkg> {
        val o = loadDb()
        return o.keys().asSequence().map { k ->
            val p = o.getJSONObject(k)
            Pkg(k, p.optString("version"), p.optString("arch", "all"), p.optString("description"), p.optString("source"))
        }.sortedBy { it.name }.toList()
    }

    fun isPkgInstalled(name: String) = loadDb().has(name)

    /** Packages in the bundled, offline "main" repository. */
    fun available(): List<Pkg> = assetChildren("droidsystem/packages").mapNotNull { name ->
        runCatching {
            val c = parseControl(ctx.assets.open("droidsystem/packages/$name/DSPKG").bufferedReader().readText())
            Pkg(c["Package"] ?: name, c["Version"] ?: "?", c["Architecture"] ?: "all", c["Description"] ?: "", "main")
        }.getOrNull()
    }.sortedBy { it.name }

    fun installFromRepo(name: String, log: (String) -> Unit) {
        val pkg = available().firstOrNull { it.name == name } ?: throw IOException("Unable to locate package $name")
        log("Unpacking ${pkg.name} (${pkg.version}) from main…")
        val base = "droidsystem/packages/$name/root"
        val files = assetFiles(base).map { it.removePrefix("$base/") }
        files.forEach { rel ->
            val out = File(root, rel)
            out.parentFile?.mkdirs()
            ctx.assets.open("$base/$rel").use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        record(pkg.name, pkg.version, pkg.arch, pkg.description, "main", files)
        log("Setting up ${pkg.name} (${pkg.version}) …")
    }

    private fun record(name: String, version: String, arch: String, desc: String, source: String, files: List<String>) {
        val o = loadDb()
        o.put(name, JSONObject().put("version", version).put("arch", arch).put("description", desc).put("source", source)
            .put("files", JSONArray(files)))
        saveDb(o)
    }

    fun remove(name: String, log: (String) -> Unit) {
        val o = loadDb()
        if (!o.has(name)) throw IOException("Package $name is not installed")
        if (name == "ds-base") throw IOException("ds-base is essential and cannot be removed")
        val files = o.getJSONObject(name).optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) File(root, files.getString(i)).delete()
        o.remove(name)
        saveDb(o)
        log("Removed $name (${files.length()} files)")
    }

    /**
     * .dsp – DroidSystem's own package format: a zip holding a DSPKG control file and a root/ tree.
     */
    fun installDsp(file: File, log: (String) -> Unit) {
        var control: Map<String, String>? = null
        val files = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "DSPKG" -> control = parseControl(zip.bufferedReader().readText())
                    e.name.startsWith("root/") && !e.isDirectory -> {
                        val rel = e.name.removePrefix("root/")
                        writeSafe(rel, zip); files += rel
                    }
                }
            }
        }
        val c = control ?: throw IOException("Not a DroidSystem package: missing DSPKG")
        val name = c["Package"] ?: file.nameWithoutExtension
        record(name, c["Version"] ?: "?", c["Architecture"] ?: "all", c["Description"] ?: "", "local", files)
        log("Installed $name ${c["Version"] ?: ""} (${files.size} files)")
    }

    /** Builds a .dsp from a folder containing DSPKG and root/. */
    fun buildDsp(dir: File, log: (String) -> Unit): File {
        val control = File(dir, "DSPKG")
        if (!control.isFile) throw IOException("${dir.path} has no DSPKG control file")
        val c = parseControl(control.readText())
        val out = File(dir.parentFile, "${c["Package"] ?: dir.name}_${c["Version"] ?: "1.0"}.dsp")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("DSPKG")); control.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            val r = File(dir, "root")
            r.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry("root/" + f.relativeTo(r).path)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        log("Built ${out.path}")
        return out
    }

    /** Debian packages: parsed and unpacked natively (ar + tar.{gz,xz,bz2}). */
    fun inspectDeb(file: File): Map<String, String> {
        ArArchiveInputStream(file.inputStream().buffered()).use { ar ->
            while (true) {
                val e = ar.nextEntry ?: break
                if (e.name.startsWith("control.tar")) {
                    TarArchiveInputStream(decompress(e.name, ar)).use { tar ->
                        while (true) {
                            val t = tar.nextEntry ?: break
                            if (t.name.trimStart('.', '/') == "control") return parseControl(tar.bufferedReader().readText())
                        }
                    }
                }
            }
        }
        throw IOException("No control file found – is this a .deb?")
    }

    fun installDeb(file: File, log: (String) -> Unit) {
        var control: Map<String, String> = emptyMap()
        val files = mutableListOf<String>()
        var elfCount = 0
        ArArchiveInputStream(file.inputStream().buffered()).use { ar ->
            while (true) {
                val e = ar.nextEntry ?: break
                when {
                    e.name.startsWith("control.tar") -> TarArchiveInputStream(decompress(e.name, ar)).let { tar ->
                        while (true) {
                            val t = tar.nextEntry ?: break
                            if (t.name.trimStart('.', '/') == "control") control = parseControl(tar.bufferedReader().readText())
                        }
                    }
                    e.name.startsWith("data.tar") -> {
                        val name = control["Package"] ?: file.nameWithoutExtension
                        log("Unpacking $name (${control["Version"] ?: "?"}) …")
                        TarArchiveInputStream(decompress(e.name, ar)).let { tar ->
                            while (true) {
                                val t = tar.nextEntry ?: break
                                if (t.isDirectory || t.isSymbolicLink || t.isLink) continue
                                val rel = t.name.trimStart('.', '/')
                                if (rel.isEmpty()) continue
                                val out = writeSafe(rel, tar)
                                if (isElf(out)) elfCount++
                                files += rel
                            }
                        }
                    }
                }
            }
        }
        val name = control["Package"] ?: throw IOException("Not a valid .deb: no control data")
        record(name, control["Version"] ?: "?", control["Architecture"] ?: "?", control["Description"]?.lines()?.first() ?: "", "deb", files)
        log("Setting up $name (${control["Version"]}) – ${files.size} files")
        if (elfCount > 0) log(
            "Note: $elfCount file(s) are native Linux (${control["Architecture"]}) programs. Android does not allow apps to run " +
                "glibc Linux binaries, so those files are installed but cannot be executed. Scripts and data work normally."
        )
        if (control["Depends"] != null) log("Dependencies (not checked offline): ${control["Depends"]}")
    }

    private fun decompress(name: String, input: InputStream): InputStream = when {
        name.endsWith(".gz") -> GzipCompressorInputStream(input)
        name.endsWith(".xz") -> XZCompressorInputStream(input)
        name.endsWith(".bz2") -> BZip2CompressorInputStream(input)
        name.endsWith(".zst") -> throw IOException("zstd-compressed packages are not supported")
        else -> input
    }

    /** Writes an archive entry under [root], refusing paths that escape it. */
    private fun writeSafe(rel: String, input: InputStream, base: File = root): File {
        val out = File(base, rel)
        if (!out.canonicalPath.startsWith(base.canonicalPath + File.separator)) throw IOException("Blocked unsafe path $rel")
        out.parentFile?.mkdirs()
        out.outputStream().use { input.copyTo(it) }
        return out
    }

    /** Extracts zip / tar / tar.gz / tar.xz / tar.bz2 into [dest]. Returns the number of files written. */
    fun extract(file: File, dest: File): Int {
        val n = file.name.lowercase()
        var count = 0
        if (n.endsWith(".zip") || n.endsWith(".dsp")) {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) File(dest, e.name).mkdirs() else { writeSafe(e.name, zip, dest); count++ }
                }
            }
            return count
        }
        val raw = file.inputStream().buffered()
        val stream = when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> GzipCompressorInputStream(raw)
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> XZCompressorInputStream(raw)
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> BZip2CompressorInputStream(raw)
            n.endsWith(".tar") -> raw
            else -> throw IOException("Unsupported archive type")
        }
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val t = tar.nextEntry ?: break
                if (t.isSymbolicLink || t.isLink) continue
                if (t.isDirectory) File(dest, t.name).mkdirs() else { writeSafe(t.name, tar, dest); count++ }
            }
        }
        return count
    }

    // ------------------------------------------------------------------ flatpak
    data class Flatpak(val id: String, val name: String, val comment: String, val command: String, val runtime: String)

    private fun parseFlatpakMeta(id: String, metadata: String, desktop: String?): Flatpak {
        val m = parseIni(metadata)["Application"] ?: emptyMap()
        val d = desktop?.let { parseIni(it)["Desktop Entry"] } ?: emptyMap()
        return Flatpak(m["name"] ?: id, d["Name"] ?: id.substringAfterLast('.'), d["Comment"] ?: "", m["command"] ?: "", m["runtime"] ?: "")
    }

    /** Apps in the offline DroidHub remote bundled with KDX. */
    fun flatpakRemote(): List<Flatpak> = assetChildren("droidsystem/flatpak").map { id ->
        val base = "droidsystem/flatpak/$id"
        parseFlatpakMeta(
            id, ctx.assets.open("$base/metadata").bufferedReader().readText(),
            runCatching { ctx.assets.open("$base/export/share/applications/$id.desktop").bufferedReader().readText() }.getOrNull(),
        )
    }.sortedBy { it.id }

    fun flatpakDeploy(id: String) = File(flatpakApps, "$id/current/active")

    fun flatpakInstalled(): List<Flatpak> = (flatpakApps.listFiles() ?: emptyArray()).mapNotNull { dir ->
        val active = File(dir, "current/active")
        val meta = File(active, "metadata").takeIf { it.isFile } ?: return@mapNotNull null
        val desktop = File(active, "export/share/applications/${dir.name}.desktop")
        parseFlatpakMeta(dir.name, meta.readText(), desktop.takeIf { it.isFile }?.readText())
    }.sortedBy { it.id }

    fun flatpakInstallBundled(id: String, log: (String) -> Unit) {
        val base = "droidsystem/flatpak/$id"
        if (assetFiles(base).isEmpty()) throw IOException("$id not found in remote droidhub")
        log("Installing $id from droidhub…")
        copyAsset(base, flatpakDeploy(id))
        log("Installation complete: $id")
    }

    /**
     * Installs a Flatpak build directory (metadata + files/ [+ export/]), as produced by
     * `flatpak build-export`/`flatpak-builder`, either as a folder or packed in zip/tar.
     */
    fun flatpakInstallBuild(src: File, log: (String) -> Unit) {
        val dir = if (src.isDirectory) src else {
            val tmp = File(root, "tmp/flatpak-${System.currentTimeMillis()}")
            tmp.mkdirs()
            extract(src, tmp)
            // allow a single top-level folder inside the archive
            tmp.listFiles()?.singleOrNull()?.takeIf { it.isDirectory && !File(tmp, "metadata").exists() } ?: tmp
        }
        val meta = File(dir, "metadata")
        if (!meta.isFile) throw IOException("No 'metadata' file – this is not a Flatpak build directory")
        val app = parseIni(meta.readText())["Application"] ?: throw IOException("metadata has no [Application] group")
        val id = app["name"] ?: throw IOException("metadata has no application name")
        val dest = flatpakDeploy(id)
        dest.deleteRecursively()
        dir.copyRecursively(dest, overwrite = true)
        val desktop = File(dest, "export/share/applications/$id.desktop")
        if (!desktop.exists()) {
            desktop.parentFile?.mkdirs()
            desktop.writeText("[Desktop Entry]\nType=Application\nName=${id.substringAfterLast('.')}\nExec=flatpak run $id\nTerminal=true\nX-Flatpak=$id\n")
        }
        if (!src.isDirectory) File(root, "tmp").listFiles()?.filter { it.name.startsWith("flatpak-") }?.forEach { it.deleteRecursively() }
        log("Installed $id (runtime ${app["runtime"] ?: "none"})")
    }

    fun flatpakUninstall(id: String): Boolean = File(flatpakApps, id).deleteRecursively()

    /** The file a `flatpak run` would execute. */
    fun flatpakEntry(id: String): File? {
        val active = flatpakDeploy(id)
        val cmd = (parseIni(File(active, "metadata").takeIf { it.isFile }?.readText() ?: return null)["Application"] ?: return null)["command"]
            ?: return null
        return listOf(File(active, "files/bin/$cmd"), File(active, "files/$cmd")).firstOrNull { it.isFile }
    }

    // ------------------------------------------------------------------ desktop entries
    data class DesktopEntry(val file: File, val name: String, val comment: String, val exec: String, val flatpakId: String?) {
        val key get() = "linux:${file.path}"
    }

    fun desktopEntries(includeFlatpaks: Boolean): List<DesktopEntry> {
        val dirs = mutableListOf(File(root, "usr/share/applications"), File(home, ".local/share/applications"))
        if (includeFlatpaks) flatpakApps.listFiles()?.forEach { dirs += File(it, "current/active/export/share/applications") }
        return dirs.flatMap { d -> d.listFiles { f -> f.name.endsWith(".desktop") }?.toList() ?: emptyList() }
            .mapNotNull { parseDesktop(it) }
            .sortedBy { it.name.lowercase() }
    }

    companion object {
        fun parseControl(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var last: String? = null
            text.lines().forEach { l ->
                if ((l.startsWith(" ") || l.startsWith("\t")) && last != null) out[last!!] = out[last!!] + "\n" + l.trim()
                else if (l.contains(':')) { last = l.substringBefore(':').trim(); out[last!!] = l.substringAfter(':').trim() }
            }
            return out
        }

        fun parseIni(text: String): Map<String, Map<String, String>> {
            val out = LinkedHashMap<String, MutableMap<String, String>>()
            var group = ""
            text.lines().map { it.trim() }.forEach { l ->
                when {
                    l.isEmpty() || l.startsWith("#") || l.startsWith(";") -> {}
                    l.startsWith("[") && l.endsWith("]") -> group = l.substring(1, l.length - 1)
                    l.contains('=') -> out.getOrPut(group) { LinkedHashMap() }[l.substringBefore('=').trim()] = l.substringAfter('=').trim()
                }
            }
            return out
        }

        fun parseDesktop(f: File): DesktopEntry? = runCatching {
            val e = parseIni(f.readText())["Desktop Entry"] ?: return null
            if (e["NoDisplay"] == "true" || e["Hidden"] == "true" || (e["Type"] ?: "Application") != "Application") return null
            val exec = (e["Exec"] ?: return null).replace(Regex("%[fFuUdDnNickvm]"), "").trim()
            DesktopEntry(f, e["Name"] ?: f.nameWithoutExtension, e["Comment"] ?: e["GenericName"] ?: "", exec, e["X-Flatpak"])
        }.getOrNull()

        fun isElf(f: File): Boolean = runCatching {
            f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte() }
        }.getOrDefault(false)

        /** Describes Linux executables, AppImages and packages (used by `linuxinfo` and Dolphin). */
        fun describe(f: File): List<String> {
            val out = mutableListOf("File: ${f.path}", "Size: ${f.length()} bytes")
            if (isElf(f)) {
                val h = ByteArray(20)
                f.inputStream().use { it.read(h) }
                val bits = if (h[4].toInt() == 2) 64 else 32
                val machine = (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)
                val arch = when (machine) { 0x3E -> "x86_64"; 0xB7 -> "aarch64"; 0x28 -> "arm"; 0x03 -> "i386"; 0xF3 -> "riscv"; else -> "machine $machine" }
                val appImage = h[8] == 'A'.code.toByte() && h[9] == 'I'.code.toByte()
                out += "Type: ${if (appImage) "AppImage (type ${h[10]})" else "ELF executable"}, $bits-bit $arch"
                out += "Android blocks apps from executing downloaded native Linux programs (W^X policy and no glibc)," +
                    " so DroidSystem can store and inspect this file but cannot run it."
                if (appImage) out += "Tip: if the app ships a Flatpak build or a script version, install that instead."
                return out
            }
            when (f.extension.lowercase()) {
                "rpm" -> out += "Type: RPM package (Fedora/openSUSE). DroidSystem uses dspkg; convert it or use a .deb/.dsp."
                "flatpak" -> out += "Type: Flatpak single-file bundle (OSTree). Install the app's build directory (metadata + files/) instead."
                "snap" -> out += "Type: Snap package (squashfs). Not supported by DroidSystem."
                else -> out += "Type: ${if (f.isFile && f.length() < 2_000_000 && runCatching { f.readText().startsWith("#!") }.getOrDefault(false)) "script" else "data"}"
            }
            return out
        }
    }
}
