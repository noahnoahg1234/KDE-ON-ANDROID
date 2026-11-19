package org.kdx.shell

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import org.kdx.shell.shell.runnerMatches
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Renders the shell and every built-in app under Robolectric. Fails on any crash during
 * composition, and writes screenshots to app/build/screenshots for manual review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ShellScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val state: KdxState get() = ViewModelProvider(rule.activity)[KdxState::class.java]

    @org.junit.Before fun manualClock() { rule.mainClock.autoAdvance = false }

    private fun shot(name: String) {
        // Clocks and monitors tick forever, so drive the frame clock by hand.
        rule.mainClock.advanceTimeBy(800)
        val dir = File("build/screenshots").apply { mkdirs() }
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun desktopAndPopups() {
        shot("01-desktop")
        rule.onNodeWithContentDescription("KRunner").performClick()
        shot("02-krunner")
        rule.runOnUiThread { state.closeMenus(); state.kickoffOpen = true }
        shot("03-kickoff")
        rule.onNodeWithText("Applications").performClick()
        shot("04-kickoff-apps")
        rule.runOnUiThread { state.closeMenus(); state.calendarOpen = true }
        shot("05-calendar")
    }

    @Test fun everyAppOpens() {
        KApp.entries.forEachIndexed { i, app ->
            rule.runOnUiThread { state.windows.toList().forEach { state.close(it) }; state.open(app) }
            shot("1%02d-%s".format(i, app.name.lowercase()))
        }
    }

    @Test fun floatingWindowsAndOverview() {
        rule.runOnUiThread {
            state.floatingByDefault = true
            state.open(KApp.KCALC); state.open(KApp.KONSOLE); state.open(KApp.DOLPHIN)
        }
        shot("20-floating")
        assertEquals(3, state.windowsOnDesktop().size)
        rule.runOnUiThread { state.switchDesktop(1) }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(0, state.windowsOnDesktop().size)
        rule.runOnUiThread { state.switchDesktop(0); state.overviewOpen = true }
        shot("21-overview")
        rule.runOnUiThread { state.floatingByDefault = false; state.darkThemeForTest() }
        rule.runOnUiThread { state.overviewOpen = false; state.open(KApp.SETTINGS) }
        shot("22-dark-settings")
    }

    @Test fun krunnerMatches() {
        rule.mainClock.advanceTimeBy(100)
        val calc = runnerMatches(state, "2^10")
        assertEquals("= 1024", calc.first().title)
        assertTrue(runnerMatches(state, "konsole").any { it.title == "Konsole" })
        assertTrue(runnerMatches(state, "gg:kde").any { it.title.contains("Google") })
        assertTrue(runnerMatches(state, "5 ft to m").first().title.startsWith("5 ft = 1.524"))
    }

    @Test fun droidSystem() {
        val dir = java.nio.file.Files.createTempDirectory("ds").toFile()
        rule.runOnUiThread { state.installDroid(dir) }
        var tries = 0
        while ((state.droid == null || state.linuxApps.isEmpty()) && tries++ < 100) { rule.mainClock.advanceTimeBy(100); Thread.sleep(50) }
        assertTrue("DroidSystem should install", state.droid != null)
        rule.runOnUiThread { state.open(KApp.SETTINGS) }
        shot("40-settings-droidsystem")
        rule.runOnUiThread {
            state.windows.toList().forEach { state.close(it) }
            state.open(KApp.KONSOLE, "dsfetch")
        }
        shot("41-konsole-dsfetch")
        rule.runOnUiThread { state.windows.toList().forEach { state.close(it) }; state.open(KApp.KONSOLE, "dspkg install droidsay fortune") }
        var waited = 0
        while (!state.droid!!.isPkgInstalled("fortune") && waited++ < 100) {
            rule.mainClock.advanceTimeBy(50); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50)
        }
        shot("42a-konsole-dspkg")
        rule.runOnUiThread { state.windows.toList().forEach { state.close(it) }; state.open(KApp.KONSOLE, "flatpak remote-ls") }
        shot("42-konsole-flatpak")
        assertTrue(state.droid!!.isPkgInstalled("fortune"))
        rule.runOnUiThread { state.windows.toList().forEach { state.close(it) }; state.kickoffOpen = true }
        rule.mainClock.advanceTimeBy(300)
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onLast().performTextInput("droid")
        shot("43-kickoff-linux")
        rule.onNodeWithText("Welcome Tour").assertExists()
        rule.runOnUiThread { state.closeMenus(); state.open(KApp.DOLPHIN, dir.path) }
        shot("44-dolphin-droidsystem")
        // Bazaar is installed by default
        assertTrue(state.linuxApps.any { it.name == "Bazaar" })
        rule.runOnUiThread { state.windows.toList().forEach { state.close(it) }; state.open(KApp.BAZAAR) }
        var t = 0
        while (t++ < 60) { rule.mainClock.advanceTimeBy(50); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30) }
        shot("45-bazaar-explore")
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onLast().performTextInput("tic")
        rule.mainClock.advanceTimeBy(200)
        rule.onAllNodes(androidx.compose.ui.test.hasText("Tic-Tac-Toe")).onFirst().performClick()
        shot("46-bazaar-detail")
        rule.onNodeWithText("Install").performClick()
        t = 0
        while (!state.droid!!.flatpakInstalled().any { it.id == "org.droidsystem.TicTacToe" } && t++ < 100) {
            rule.mainClock.advanceTimeBy(50); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30)
        }
        assertTrue("Bazaar installs apps", state.droid!!.flatpakInstalled().any { it.id == "org.droidsystem.TicTacToe" })
        shot("47-bazaar-installed")
    }

    @Test fun konsoleRunsCommands() {
        rule.runOnUiThread { state.open(KApp.KONSOLE, "help") }
        shot("30-konsole-help")
    }
}

private fun KdxState.darkThemeForTest() { themeMode = org.kdx.shell.core.ThemeMode.DARK }
