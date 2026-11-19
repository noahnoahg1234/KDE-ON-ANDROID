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
        progress(0.7f, "Installing default apps: Bazaar, Welcome Tour")
        installDefaults()
        progress(0.95f, "Writing install log")
        File(root, "var/log/install.log").appendText("DroidSystem installed at ${java.util.Date()} into ${root.path}\n")
        marker.writeText(SYSTEM_VERSION)
        progress(1f, "Done")
    }

    fun uninstall(): Boolean = root.deleteTree()

    private val marker get() = File(root, "etc/droidsystem-version")

    private fun installDefaults() {
        record("bazaar", SYSTEM_VERSION, "all", "Graphical app store for Flatpaks and packages", "main",
            listOf("usr/share/applications/org.droidsystem.Bazaar.desktop"), "System")
        if (!flatpakDeploy("org.droidsystem.Welcome").exists()) flatpakInstallBundled("org.droidsystem.Welcome") {}
        marker.writeText(SYSTEM_VERSION)
    }

    /** Brings an older DroidSystem up to the bundled version without touching the user's home or apps. */
    fun migrate(): Boolean {
        if (!isInstalled || marker.takeIf { it.isFile }?.readText()?.trim() == SYSTEM_VERSION) return false
        listOf("etc/os-release", "etc/motd", "etc/profile", "etc/dspkg.conf").forEach { rel ->
            ctx.assets.open("droidsystem/rootfs/$rel").use { i -> File(root, rel).outputStream().use { i.copyTo(it) } }
        }
        copyAsset("droidsystem/rootfs/usr/share/applications", File(root, "usr/share/applications"))
        installDefaults()
        File(root, "var/log/install.log").appendText("Upgraded to DroidSystem $SYSTEM_VERSION at ${java.util.Date()}\n")
        return true
    }

    /**
     * Shell code run before every command: the profile plus one function per installed command, so DroidSystem
     * commands work inside pipes and scripts even though Android mounts storage noexec.
     */
    fun shellPreamble(): String = buildString {
        File(root, "etc/profile").takeIf { it.isFile }?.let { append(". ").append(q(it.path)).append('\n') }
        commandFiles().forEach { (name, f) -> append(name).append("() { sh ").append(q(f.path)).append(" \"$@\"; }\n") }
        flatpakInstalled().forEach { a ->
            val entry = flatpakEntry(a.id) ?: return@forEach
            if (!isElf(entry) && a.command.matches(ident) && which(a.command) == null) {
                append(a.command).append("() { FLATPAK_ID=").append(a.id).append(" sh ").append(q(entry.path)).append(" \"$@\"; }\n")
            }
        }
    }

    private val ident = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun commandFiles(): List<Pair<String, File>> =
        binDirs.flatMap { d -> d.listFiles()?.filter { it.isFile && it.name.matches(ident) && !isElf(it) }?.map { it.name to it } ?: emptyList() }
            .distinctBy { it.first }

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
    data class Pkg(
        val name: String, val version: String, val arch: String, val description: String, val source: String,
        val category: String = "Other", val size: Long = 0,
    )

    private fun loadDb(): JSONObject = runCatching { JSONObject(db.readText()) }.getOrDefault(JSONObject())
    private fun saveDb(o: JSONObject) { db.parentFile?.mkdirs(); db.writeText(o.toString(2)) }

    fun installed(): List<Pkg> {
        val o = loadDb()
        return o.keys().asSequence().map { k ->
            val p = o.getJSONObject(k)
            Pkg(k, p.optString("version"), p.optString("arch", "all"), p.optString("description"), p.optString("source"), p.optString("category", "Other"))
        }.sortedBy { it.name }.toList()
    }

    fun isPkgInstalled(name: String) = loadDb().has(name)

    /** Packages in the bundled, offline "main" repository. */
    fun available(): List<Pkg> = assetChildren("droidsystem/packages").mapNotNull { name ->
        runCatching {
            val c = parseControl(ctx.assets.open("droidsystem/packages/$name/DSPKG").bufferedReader().readText())
            Pkg(c["Package"] ?: name, c["Version"] ?: "?", c["Architecture"] ?: "all", c["Description"] ?: "", "main",
                c["Category"] ?: "Other", assetFiles("droidsystem/packages/$name/root").size.toLong())
        }.getOrNull()
    }.sortedBy { it.name }

    fun installFromRepo(name: String, log: (String) -> Unit) {
        if (name == "bazaar") {
            copyAsset("droidsystem/rootfs/usr/share/applications", File(root, "usr/share/applications"))
            installDefaults(); log("Setting up bazaar ($SYSTEM_VERSION) …"); return
        }
        val pkg = available().firstOrNull { it.name == name } ?: throw IOException("Unable to locate package $name")
        log("Unpacking ${pkg.name} (${pkg.version}) from main…")
        val base = "droidsystem/packages/$name/root"
        val files = assetFiles(base).map { it.removePrefix("$base/") }
        files.forEach { rel ->
            val out = File(root, rel)
            out.parentFile?.mkdirs()
            ctx.assets.open("$base/$rel").use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        record(pkg.name, pkg.version, pkg.arch, pkg.description, "main", files, pkg.category)
        log("Setting up ${pkg.name} (${pkg.version}) …")
    }

    private fun record(name: String, version: String, arch: String, desc: String, source: String, files: List<String>, category: String = "Other") {
        val o = loadDb()
        o.put(name, JSONObject().put("version", version).put("arch", arch).put("description", desc).put("source", source)
            .put("category", category).put("files", JSONArray(files)))
        saveDb(o)
    }

    /** Reinstalls packages from the bundled repo whose version changed. Returns the upgraded names. */
    fun upgrade(log: (String) -> Unit): List<String> {
        val repo = available().associateBy { it.name }
        val done = installed().filter { it.source == "main" && repo[it.name] != null && repo[it.name]!!.version != it.version }
            .map { installFromRepo(it.name, log); it.name }
        val fps = flatpakInstalled().mapNotNull { a ->
            val r = flatpakRemote().firstOrNull { it.id == a.id } ?: return@mapNotNull null
            if (r.version != a.version) { flatpakInstallBundled(a.id, log); a.id } else null
        }
        return done + fps
    }

    fun remove(name: String, log: (String) -> Unit) {
        val o = loadDb()
        if (!o.has(name)) throw IOException("Package $name is not installed")
        if (name == "ds-base") throw IOException("ds-base is essential and cannot be removed")
        val files = o.getJSONObject(name).optJSONArray("files") ?: JSONArray()
        if (name == "bazaar") log("Bazaar removed – reinstall it any time with: dspkg install bazaar")
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

    /** RPM: lead + signature header + header, then a cpio payload (gzip/xz/bzip2). */
    fun installRpm(file: File, log: (String) -> Unit) {
        val input = java.io.DataInputStream(file.inputStream().buffered())
        val lead = ByteArray(96); input.readFully(lead)
        if (lead[0] != 0xED.toByte() || lead[1] != 0xAB.toByte()) throw IOException("Not an RPM file")
        fun readHeader(align: Boolean): Map<Int, Any> {
            val magic = ByteArray(8); input.readFully(magic)
            if (magic[0] != 0x8E.toByte() || magic[1] != 0xAD.toByte()) throw IOException("Corrupt RPM header")
            val n = input.readInt(); val size = input.readInt()
            val idx = (0 until n).map { IntArray(4) { input.readInt() } }
            val store = ByteArray(size); input.readFully(store)
            if (align) { val pad = (8 - (16 + 16 * n + size) % 8) % 8; input.skipBytes(pad) }
            val out = HashMap<Int, Any>()
            idx.forEach { (tag, type, off, _) ->
                if (type == 6 || type == 9) { // string / i18n string
                    var e = off; while (e < store.size && store[e] != 0.toByte()) e++
                    out[tag] = String(store, off, e - off)
                }
            }
            return out
        }
        readHeader(align = true)
        val h = readHeader(align = false)
        val name = h[1000] as? String ?: file.nameWithoutExtension
        val version = "${h[1001] ?: "?"}-${h[1002] ?: "0"}"
        val arch = h[1022] as? String ?: "?"
        val compressor = h[1125] as? String ?: "gzip"
        log("Unpacking $name ($version, $arch) from RPM…")
        val payload: InputStream = when (compressor) {
            "gzip" -> GzipCompressorInputStream(input)
            "xz", "lzma" -> XZCompressorInputStream(input)
            "bzip2" -> BZip2CompressorInputStream(input)
            else -> throw IOException("RPM payload compression '$compressor' is not supported")
        }
        val files = mutableListOf<String>()
        var elf = 0
        org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream(payload).use { cpio ->
            while (true) {
                val e = cpio.nextEntry ?: break
                if (e.isDirectory || e.isSymbolicLink || !e.isRegularFile) continue
                val rel = e.name.trimStart('.', '/'); if (rel.isEmpty()) continue
                if (isElf(writeSafe(rel, cpio))) elf++
                files += rel
            }
        }
        record(name, version, arch, h[1004] as? String ?: "", "rpm", files, "Other")
        log("Setting up $name – ${files.size} files")
        if (elf > 0) log("Note: $elf native Linux program(s) installed; Android cannot execute them.")
    }

    /** Arch Linux packages (.pkg.tar.gz / .pkg.tar.xz) – a tarball with .PKGINFO. */
    fun installArch(file: File, log: (String) -> Unit) {
        val n = file.name.lowercase()
        if (n.endsWith(".zst")) throw IOException("zstd-compressed Arch packages are not supported; use a .pkg.tar.xz")
        val files = mutableListOf<String>()
        var info = emptyMap<String, String>()
        TarArchiveInputStream(decompress(n, file.inputStream().buffered())).use { tar ->
            while (true) {
                val t = tar.nextEntry ?: break
                if (t.isDirectory || t.isSymbolicLink || t.isLink) continue
                val rel = t.name.trimStart('.', '/')
                if (rel == "PKGINFO") {
                    // readBytes() leaves the archive stream open (readLines() would close it)
                    info = String(tar.readBytes()).lines().filter { " = " in it }.associate { it.substringBefore(" = ") to it.substringAfter(" = ") }
                    continue
                }
                if (t.name.removePrefix("./").startsWith(".")) continue
                writeSafe(rel, tar); files += rel
            }
        }
        val name = info["pkgname"] ?: file.name.substringBefore(".pkg")
        record(name, info["pkgver"] ?: "?", info["arch"] ?: "?", info["pkgdesc"] ?: "", "arch", files, "Other")
        log("Installed $name ${info["pkgver"] ?: ""} (${files.size} files)")
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
        if (n.endsWith(".7z")) {
            org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(file).get().use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) File(dest, e.name).mkdirs() else { writeSafe(e.name, z.getInputStream(e), dest); count++ }
                }
            }
            return count
        }
        if (n.endsWith(".cpio") || n.endsWith(".ar") || n.endsWith(".a")) {
            val s = file.inputStream().buffered()
            val ai: org.apache.commons.compress.archivers.ArchiveInputStream<*> =
                if (n.endsWith(".cpio")) org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream(s) else ArArchiveInputStream(s)
            ai.use {
                while (true) {
                    val e = it.nextEntry ?: break
                    if (!e.isDirectory) { writeSafe(e.name.trimStart('/'), it, dest); count++ }
                }
            }
            return count
        }
        // single compressed files: foo.txt.gz -> foo.txt
        val single = listOf(".gz", ".xz", ".bz2").firstOrNull { n.endsWith(it) && !n.endsWith(".tar$it") }
        if (single != null) {
            dest.mkdirs()
            decompress(n, file.inputStream().buffered()).use { i -> writeSafe(file.name.dropLast(single.length), i, dest) }
            return 1
        }
        if (n.endsWith(".zst") || n.endsWith(".lz4") || n.endsWith(".rar")) throw IOException("${file.extension} archives are not supported")
        if (n.endsWith(".zip") || n.endsWith(".dsp") || n.endsWith(".jar") || n.endsWith(".war") || n.endsWith(".epub")) {
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
    data class Flatpak(
        val id: String, val name: String, val comment: String, val command: String, val runtime: String,
        val version: String = "", val category: String = "Other", val developer: String = "", val description: String = "",
        val permissions: List<String> = emptyList(),
    )

    private fun parseFlatpakMeta(id: String, metadata: String, desktop: String?): Flatpak {
        val ini = parseIni(metadata)
        val m = ini["Application"] ?: emptyMap()
        val x = ini["X-DroidHub"] ?: emptyMap()
        val ctxPerms = ini["Context"] ?: emptyMap()
        val d = desktop?.let { parseIni(it)["Desktop Entry"] } ?: emptyMap()
        val perms = ctxPerms.flatMap { (k, v) -> v.split(';').filter { it.isNotBlank() }.map { "$k: $it" } }
        return Flatpak(
            m["name"] ?: id, d["Name"] ?: id.substringAfterLast('.'), d["Comment"] ?: "", m["command"] ?: "", m["runtime"] ?: "",
            x["version"] ?: "1.0", x["category"] ?: d["Categories"]?.substringBefore(';') ?: "Other",
            x["developer"] ?: "Unknown developer", x["description"] ?: d["Comment"] ?: "", perms,
        )
    }

    /** Remotes from etc/flatpak/remotes.d (droidhub is always present). */
    fun flatpakRemotes(): List<Pair<String, String>> =
        (File(root, "etc/flatpak/remotes.d").listFiles { f -> f.name.endsWith(".flatpakrepo") }?.toList() ?: emptyList()).map { f ->
            val r = parseIni(f.readText())["Flatpak Repo"] ?: emptyMap()
            f.nameWithoutExtension to (r["Url"] ?: "")
        }.sortedBy { it.first }

    fun flatpakRemoteAdd(file: File, name: String? = null): String {
        val r = parseIni(file.readText())["Flatpak Repo"] ?: throw IOException("Not a .flatpakrepo file")
        val n = (name ?: r["Title"] ?: file.nameWithoutExtension).lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        File(root, "etc/flatpak/remotes.d/$n.flatpakrepo").writeText(file.readText())
        return n
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
        dest.deleteTree()
        dir.copyRecursively(dest, overwrite = true)
        val desktop = File(dest, "export/share/applications/$id.desktop")
        if (!desktop.exists()) {
            desktop.parentFile?.mkdirs()
            desktop.writeText("[Desktop Entry]\nType=Application\nName=${id.substringAfterLast('.')}\nExec=flatpak run $id\nTerminal=true\nX-Flatpak=$id\n")
        }
        if (!src.isDirectory) File(root, "tmp").listFiles()?.filter { it.name.startsWith("flatpak-") }?.forEach { it.deleteTree() }
        log("Installed $id (runtime ${app["runtime"] ?: "none"})")
    }

    fun flatpakUninstall(id: String): Boolean = File(flatpakApps, id).deleteTree()

    /** The file a `flatpak run` would execute. */
    fun flatpakEntry(id: String): File? {
        val active = flatpakDeploy(id)
        val cmd = (parseIni(File(active, "metadata").takeIf { it.isFile }?.readText() ?: return null)["Application"] ?: return null)["command"]
            ?: return null
        return listOf(File(active, "files/bin/$cmd"), File(active, "files/$cmd")).firstOrNull { it.isFile }
    }

    // ------------------------------------------------------------------ desktop entries
    /** Where a launcher comes from: DroidSystem's own apps, Debian (apt) or Flathub (real Flatpak). */
    enum class EntrySource { CLASSIC, DEBIAN, FLATHUB }

    data class DesktopEntry(
        val file: File, val name: String, val comment: String, val exec: String, val flatpakId: String?,
        val source: EntrySource = EntrySource.CLASSIC, val terminal: Boolean = true, val icon: File? = null,
    ) {
        val key get() = "linux:${file.path}"

        /** Entries with `Exec=kdx-app NAME` open a native KDX window (e.g. Bazaar) instead of a terminal. */
        val kdxApp: org.kdx.shell.core.KApp?
            get() = exec.takeIf { it.startsWith("kdx-app ") }?.let {
                runCatching { org.kdx.shell.core.KApp.valueOf(it.removePrefix("kdx-app ").trim().uppercase()) }.getOrNull()
            }
    }

    fun desktopEntries(includeFlatpaks: Boolean): List<DesktopEntry> {
        val dirs = mutableListOf(File(root, "usr/share/applications"), File(home, ".local/share/applications"))
        if (includeFlatpaks) flatpakApps.listFiles()?.forEach { dirs += File(it, "current/active/export/share/applications") }
        return dirs.flatMap { d -> d.listFiles { f -> f.name.endsWith(".desktop") }?.toList() ?: emptyList() }
            .mapNotNull { parseDesktop(it) }
            .sortedBy { it.name.lowercase() }
    }

    companion object {
        const val SYSTEM_VERSION = "1.1"

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
                val elfType = when (h[16].toInt()) { 1 -> "relocatable object"; 2 -> "executable"; 3 -> "shared object / PIE"; 4 -> "core dump"; else -> "ELF" }
                val machine = (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)
                val arch = when (machine) { 0x3E -> "x86_64"; 0xB7 -> "aarch64"; 0x28 -> "arm"; 0x03 -> "i386"; 0xF3 -> "riscv"; else -> "machine $machine" }
                val appImage = h[8] == 'A'.code.toByte() && h[9] == 'I'.code.toByte()
                val kind = when {
                    appImage -> "AppImage (type ${h[10]})"
                    f.extension == "ko" -> "Linux kernel module"
                    f.name.contains(".so") -> "shared library"
                    else -> "ELF $elfType"
                }
                out += "Type: $kind, $bits-bit $arch"
                out += "Android blocks apps from executing downloaded native Linux programs (W^X policy and no glibc)," +
                    " so DroidSystem can store and inspect this file but cannot run it."
                if (appImage) out += "Tip: if the app ships a Flatpak build or a script version, install that instead."
                return out
            }
            val head = ByteArray(16)
            val iso = ByteArray(5)
            runCatching { java.io.RandomAccessFile(f, "r").use { r -> r.read(head); if (r.length() > 0x8006) { r.seek(0x8001); r.read(iso) } } }
            fun m(vararg b: Int) = b.indices.all { head[it] == b[it].toByte() }
            val type = when {
                m(0xED, 0xAB, 0xEE, 0xDB) -> "RPM package – install with: dspkg install ${f.name}"
                String(head, 0, 8) == "!<arch>\n" && f.extension == "deb" -> "Debian package – install with: dspkg install ${f.name}"
                String(head, 0, 8) == "!<arch>\n" -> "ar archive (static library or package)"
                m(0x1F, 0x8B) -> "gzip-compressed data" + if (f.name.contains(".pkg.tar")) " (Arch package)" else ""
                m(0xFD, '7'.code, 'z'.code, 'X'.code, 'Z'.code) -> "xz-compressed data" + if (f.name.contains(".pkg.tar")) " (Arch package)" else ""
                m('B'.code, 'Z'.code, 'h'.code) -> "bzip2-compressed data"
                m(0x28, 0xB5, 0x2F, 0xFD) -> "zstd-compressed data (not supported by DroidSystem)"
                m('7'.code, 'z'.code, 0xBC, 0xAF) -> "7-Zip archive – extract with: extract ${f.name}"
                m('P'.code, 'K'.code, 3, 4) -> "Zip archive" + when (f.extension.lowercase()) { "jar" -> " (Java archive)"; "apk" -> " (Android app – open it from Dolphin)"; "epub" -> " (e-book)"; else -> "" }
                m('h'.code, 's'.code, 'q'.code, 's'.code) -> "SquashFS file system image (used by Snap/AppImage); not mountable on Android"
                String(iso) == "CD001" -> "ISO 9660 disc image (e.g. a Linux installer); flash it from a PC, it can't boot inside Android"
                m('0'.code, '7'.code, '0'.code, '7'.code, '0'.code) -> "cpio archive – extract with: extract ${f.name}"
                f.extension.lowercase() == "flatpak" -> "Flatpak single-file bundle (OSTree); install the app's build directory instead"
                f.extension.lowercase() == "flatpakrepo" -> "Flatpak remote definition – add with: flatpak remote-add ${f.name}"
                f.extension.lowercase() == "snap" -> "Snap package (squashfs); not supported by DroidSystem"
                f.isFile && f.length() < 2_000_000 && runCatching { f.readText().startsWith("#!") }.getOrDefault(false) ->
                    "script (${f.readLines().first().removePrefix("#!").trim()})"
                else -> "data"
            }
            out += "Type: $type"
            return out
        }
    }
}
