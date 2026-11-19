package org.kdx.shell

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kdx.shell.droid.Flathub
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlathubTest {
    @Test fun appstreamHtmlToText() {
        val t = Flathub.htmlToText("<p>Krita is a <em>painting</em> app.</p>\n  <ul><li>Brushes</li><li>Layers &amp; masks</li></ul><p>Free.</p>")
        assertEquals("Krita is a painting app.\n\n• Brushes\n• Layers & masks\n\nFree.", t)
    }

    @Test fun permissionsAreReadable() {
        val p = Flathub.permissionList(JSONObject("""{"shared":["ipc","network"],"sockets":["x11","pulseaudio"],"devices":["dri"],"filesystems":["host"]}"""))
        assertEquals(listOf("Network access", "Shows windows on the desktop (x11)", "Plays and records audio", "GPU acceleration", "Full access to your files"), p)
        assertEquals(listOf("No special permissions (sandboxed)"), Flathub.permissionList(JSONObject("{}")))
    }

    /** Talks to the real flathub.org; skipped when there is no network. */
    @Test fun liveCatalog() = runBlocking {
        val fh = Flathub(ApplicationProvider.getApplicationContext())
        val popular = runCatching { fh.collection("popular", perPage = 10) }.getOrNull()
        assumeTrue("flathub.org not reachable", popular != null)
        assertTrue(popular!!.size >= 5)
        assertTrue(popular.all { it.id.contains('.') && it.name.isNotBlank() })
        val krita = fh.search("krita").first { it.id == "org.kde.krita" }
        val d = fh.details(krita)
        assertEquals("Krita", d.app.name)
        assertTrue(d.screenshots.isNotEmpty())
        assertTrue(d.description.contains("Krita"))
        assertTrue(d.downloadSize!! > 1_000_000)
        assertTrue(fh.category("game", perPage = 5).isNotEmpty())
        assertNotNull(fh.image(krita.icon!!, 128))
        // second call is served from the disk cache
        assertEquals(popular.map { it.id }, fh.collection("popular", perPage = 10).map { it.id })
    }
}
