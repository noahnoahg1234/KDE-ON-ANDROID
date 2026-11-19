package org.kdx.shell.droid

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** An app listed on Flathub. */
data class FlathubApp(
    val id: String,
    val name: String,
    val summary: String,
    val icon: String?,
    val developer: String,
    val category: String,
    val installsLastMonth: Int,
    val verified: Boolean,
    val arches: List<String>,
    val license: String,
)

/** Full Flathub app page data (appstream + summary). */
data class FlathubDetails(
    val app: FlathubApp,
    val description: String,
    val screenshots: List<String>,
    val version: String?,
    val homepage: String?,
    val downloadSize: Long?,
    val installedSize: Long?,
    val permissions: List<String>,
)

/**
 * Client for Flathub's public API (https://flathub.org/api/v2). Responses are cached on disk so Bazaar still shows
 * the last catalog it saw when the phone is offline.
 */
class Flathub(ctx: Context) {
    private val base = "https://flathub.org/api/v2"
    private val cacheDir = File(ctx.cacheDir, "flathub").apply { mkdirs() }
    private val imgDir = File(ctx.cacheDir, "flathub-img").apply { mkdirs() }

    /** True when the last response came from the offline cache. */
    @Volatile var offline = false
        private set

    val categories = listOf(
        "audiovideo" to "Audio & Video", "development" to "Developer Tools", "education" to "Education",
        "game" to "Games", "graphics" to "Graphics & Photography", "network" to "Networking", "office" to "Productivity",
        "science" to "Science", "system" to "System", "utility" to "Utilities",
    )

    fun categoryLabel(c: String) = categories.firstOrNull { it.first == c.lowercase() }?.second ?: c.replaceFirstChar { it.uppercase() }

    // ------------------------------------------------------------------ HTTP + cache
    private fun key(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun http(url: String, body: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "KDX-Bazaar/1.0 (Android)")
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) throw IOException("Flathub returned HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** GET/POST JSON with a disk cache: fresh responses are cached, failures fall back to the cache. */
    private fun json(path: String, body: String? = null, maxAgeMs: Long = 6 * 3600_000L): String {
        val f = File(cacheDir, key(path + (body ?: "")) + ".json")
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < maxAgeMs) { offline = false; return f.readText() }
        return try {
            String(http(base + path, body)).also { f.writeText(it); offline = false }
        } catch (e: Exception) {
            if (f.isFile) { offline = true; f.readText() } else throw IOException("Can't reach Flathub (${e.message})", e)
        }
    }

    private fun hits(text: String): List<FlathubApp> {
        val arr = JSONObject(text).optJSONArray("hits") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> parseHit(arr.getJSONObject(i)) }
    }

    private fun parseHit(o: JSONObject): FlathubApp? {
        val id = o.optString("app_id").ifEmpty { o.optString("id") }.ifEmpty { return null }
        val cats = o.opt("main_categories")
        val category = when (cats) { is JSONArray -> cats.optString(0); is String -> cats; else -> "" }
        val arches = o.optJSONArray("arches")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return FlathubApp(
            id, o.optString("name", id), o.optString("summary"), o.optString("icon").takeIf { it.startsWith("http") },
            o.optString("developer_name").takeIf { it.isNotEmpty() && it != "null" } ?: "Unknown developer",
            category, o.optInt("installs_last_month"), o.optBoolean("verification_verified"), arches,
            o.optString("project_license").takeIf { it != "null" } ?: "",
        )
    }

    // ------------------------------------------------------------------ API
    suspend fun collection(kind: String, page: Int = 1, perPage: Int = 24): List<FlathubApp> = withContext(Dispatchers.IO) {
        hits(json("/collection/$kind?page=$page&per_page=$perPage"))
    }

    suspend fun category(cat: String, page: Int = 1, perPage: Int = 30): List<FlathubApp> = withContext(Dispatchers.IO) {
        hits(json("/collection/category/$cat?page=$page&per_page=$perPage"))
    }

    suspend fun search(query: String): List<FlathubApp> = withContext(Dispatchers.IO) {
        hits(json("/search", JSONObject().put("query", query).toString(), maxAgeMs = 30 * 60_000L))
    }

    suspend fun details(app: FlathubApp): FlathubDetails = withContext(Dispatchers.IO) {
        val a = JSONObject(json("/appstream/${app.id}"))
        val shots = a.optJSONArray("screenshots")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val sizes = arr.getJSONObject(i).optJSONArray("sizes") ?: return@mapNotNull null
                val all = (0 until sizes.length()).map { sizes.getJSONObject(it) }
                // pick a phone-friendly size, around 750px wide
                (all.filter { (it.optString("width").toIntOrNull() ?: 0) in 600..1300 }.minByOrNull { it.optString("width").toInt() }
                    ?: all.lastOrNull())?.optString("src")
            }
        } ?: emptyList()
        val release = a.optJSONArray("releases")?.optJSONObject(0)?.optString("version")
        val summary = runCatching { JSONObject(json("/summary/${app.id}")) }.getOrNull()
        val perms = summary?.optJSONObject("metadata")?.optJSONObject("permissions")?.let { permissionList(it) } ?: emptyList()
        val merged = app.copy(
            name = a.optString("name", app.name), summary = a.optString("summary", app.summary),
            icon = a.optString("icon").takeIf { it.startsWith("http") } ?: app.icon,
            developer = a.optString("developer_name").takeIf { it.isNotEmpty() && it != "null" } ?: app.developer,
            license = a.optString("project_license").takeIf { it.isNotEmpty() && it != "null" } ?: app.license,
            arches = summary?.optJSONArray("arches")?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: app.arches,
        )
        FlathubDetails(
            merged, htmlToText(a.optString("description")), shots, release,
            a.optJSONObject("urls")?.optString("homepage")?.takeIf { it.isNotEmpty() },
            summary?.optLong("download_size")?.takeIf { it > 0 }, summary?.optLong("installed_size")?.takeIf { it > 0 }, perms,
        )
    }

    /** Downloads the app's .flatpakref (for installing on a Linux PC) into [dir]. */
    suspend fun saveFlatpakref(id: String, dir: File): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        File(dir, "$id.flatpakref").apply { writeBytes(http("https://dl.flathub.org/repo/appstream/$id.flatpakref")) }
    }

    // ------------------------------------------------------------------ images
    private val memory = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val downloads = Semaphore(4) // a few parallel downloads, polite to the CDN

    fun cachedImage(url: String): ImageBitmap? = memory.get(url)

    /** Loads an icon/screenshot from Flathub's CDN, via memory and disk caches. */
    suspend fun image(url: String, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        val f = File(imgDir, key(url))
        if (!f.isFile) runCatching {
            val bytes = downloads.withPermit { http(url) }
            f.writeBytes(bytes)
        }
        if (!f.isFile) return@withContext null
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= maxPx) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }.getOrNull()?.also { memory.put(url, it) }
    }

    companion object {
        /** Readable lines for Flatpak permissions, like Bazaar/GNOME Software show them. */
        fun permissionList(p: JSONObject): List<String> {
            fun list(k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val out = mutableListOf<String>()
            val shared = list("shared"); val sockets = list("sockets"); val devices = list("devices"); val fs = list("filesystems")
            if ("network" in shared) out += "Network access"
            if ("x11" in sockets || "wayland" in sockets || "fallback-x11" in sockets) out += "Shows windows on the desktop (${sockets.filter { it in setOf("x11", "wayland", "fallback-x11") }.joinToString()})"
            if ("pulseaudio" in sockets) out += "Plays and records audio"
            if ("all" in devices) out += "Access to all devices (webcam, USB…)" else if ("dri" in devices) out += "GPU acceleration"
            fs.forEach { f ->
                out += when {
                    f.startsWith("host") -> "Full access to your files"
                    f.startsWith("home") -> "Access to your home folder"
                    f.startsWith("xdg-download") -> "Access to Downloads"
                    f.startsWith("xdg-documents") -> "Access to Documents"
                    f.startsWith("xdg-pictures") -> "Access to Pictures"
                    f.startsWith("xdg-music") -> "Access to Music"
                    f.startsWith("xdg-videos") -> "Access to Videos"
                    else -> "File access: $f"
                }
            }
            if (p.optJSONObject("system-bus") != null) out += "Talks to system services"
            if (out.isEmpty()) out += "No special permissions (sandboxed)"
            return out.distinct()
        }

        /** Flathub descriptions are AppStream HTML (<p>, <ul><li>, <em>, <code>). */
        fun htmlToText(html: String): String = html
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)<li[^>]*>"), "\n• ")
            .replace(Regex("(?i)</p>|<br\\s*/?>|</ul>|</ol>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        fun formatInstalls(n: Int) = when {
            n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
            n >= 1_000 -> "%.1fk".format(n / 1_000.0)
            else -> n.toString()
        }
    }
}
