package org.kdx.shell

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.kdx.shell.core.StartGuard
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class StartGuardTest {
    @After fun reset() { StartGuard.mode = StartGuard.Mode.NORMAL }

    @Test fun restartLoopDetection() {
        val now = 10 * 60_000L
        assertFalse("first start", StartGuard.isLoop(listOf(now), now))
        assertFalse("two quick starts", StartGuard.isLoop(listOf(now - 30_000, now), now))
        assertTrue("three within 3 minutes = loop", StartGuard.isLoop(listOf(now - 100_000, now - 40_000, now), now))
        assertFalse("spread over 10 minutes = fine", StartGuard.isLoop(listOf(now - 500_000, now - 300_000, now), now))
        assertEquals(StartGuard.Mode.NORMAL, StartGuard.modeFor(listOf(now - 30_000, now), now))
        assertEquals(StartGuard.Mode.SAFE, StartGuard.modeFor(listOf(now - 100_000, now - 40_000, now), now))
        assertEquals("still restarting in safe mode → recovery screen", StartGuard.Mode.RECOVERY,
            StartGuard.modeFor(listOf(now - 200_000, now - 150_000, now - 100_000, now - 40_000, now), now))
    }

    @Test fun startsAreCountedAcrossProcessStarts() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        repeat(2) { StartGuard.onProcessStart(ctx) }
        assertEquals(StartGuard.Mode.NORMAL, StartGuard.mode)
        StartGuard.onProcessStart(ctx)
        assertEquals(StartGuard.Mode.SAFE, StartGuard.mode)
        repeat(2) { StartGuard.onProcessStart(ctx) }
        assertEquals(StartGuard.Mode.RECOVERY, StartGuard.mode)
        assertTrue(StartGuard.recentLog(ctx).contains("mode recovery"))
        StartGuard.markStable(ctx); StartGuard.onProcessStart(ctx)
        assertEquals(StartGuard.Mode.NORMAL, StartGuard.mode)
    }

    @Test fun flickerDetector() {
        val f = StartGuard.Flicker()
        assertFalse((0 until 4).map { f.lost(it * 1000L) }.any { it })
        assertTrue("5 losses within 20 s", f.lost(4000))
        val g = StartGuard.Flicker()
        assertFalse("spread out = normal use", (0 until 10).map { g.lost(it * 10_000L) }.any { it })
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun find(v: View, label: String): Button? = when (v) {
        is Button -> v.takeIf { it.text == label }
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it), label) }
        else -> null
    }

    /** Still restarting in safe mode: KDX shows the plain recovery screen and none of its desktop, then starts from it. */
    @Test fun recoveryScreen() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        StartGuard.crashFile(ctx).writeText("KDX crashed – test\njava.lang.IllegalStateException: boom\n\tat org.kdx.Example")
        StartGuard.mode = StartGuard.Mode.RECOVERY
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = ctl.get()
        val root = a.findViewById<ViewGroup>(android.R.id.content)
        val all = texts(root).joinToString("\n")
        assertTrue(all, all.contains("KDX recovery"))
        assertTrue("crash details shown", all.contains("IllegalStateException: boom"))
        val v = root.getChildAt(0)
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/75-recovery.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // pausing must not touch KDX's (not created) state
        ctl.pause().resume()
        find(root, "Start KDX in safe mode")!!.performClick()
        assertEquals(StartGuard.Mode.SAFE, StartGuard.mode)
        assertFalse("desktop replaced the recovery screen", texts(root).any { it == "KDX recovery" })
        ctl.pause().stop().destroy()
    }
}
