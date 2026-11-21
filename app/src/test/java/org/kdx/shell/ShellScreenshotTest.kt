package org.kdx.shell

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.test.performTextClearance
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
        rule.runOnUiThread { state.autoInstallDebian = false; state.installDroid(dir) }
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

        // Real Flathub catalog (skipped when flathub.org isn't reachable)
        val online = runCatching { java.net.URL("https://flathub.org/api/v2/collection/category").openStream().close() }.isSuccess
        if (online) {
            rule.runOnUiThread { state.windows.toList().forEach { state.close(it) }; state.open(KApp.BAZAAR) }
            fun pump(until: () -> Boolean) {
                var n = 0
                while (!until() && n++ < 200) {
                    rule.mainClock.advanceTimeBy(50)
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                    Thread.sleep(50)
                }
            }
            pump { rule.onAllNodes(androidx.compose.ui.test.hasText("Popular on Flathub")).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(androidx.compose.ui.test.hasText("Flathub")).fetchSemanticsNodes().size > 3 }
            repeat(2) { pump { false } } // let icons arrive
            shot("48-bazaar-flathub")
            rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onLast().performTextClearance()
            rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onLast().performTextInput("krita")
            pump { rule.onAllNodes(androidx.compose.ui.test.hasText("Krita")).fetchSemanticsNodes().isNotEmpty() }
            shot("49-bazaar-search")
            rule.onAllNodes(androidx.compose.ui.test.hasText("Krita")).onFirst().performClick()
            pump { rule.onAllNodes(androidx.compose.ui.test.hasText("Installed size")).fetchSemanticsNodes().isNotEmpty() }
            pump { false }
            shot("50-bazaar-krita")
            rule.onNodeWithText("Install").performClick()
            rule.mainClock.advanceTimeBy(300)
            shot("51-bazaar-install-dialog")
            rule.onNodeWithText("Install Krita?").assertExists()
        }
    }

    @Test fun linuxAppWindowUsesAppTitleAndIcon() {
        // a stand-in app icon (orange circle)
        val icon = java.io.File(rule.activity.cacheDir, "org.example.Paint.png")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawCircle(32f, 32f, 30f, android.graphics.Paint().apply { color = 0xFFF67400.toInt(); isAntiAlias = true })
        icon.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.runOnUiThread {
            state.open(KApp.KCALC)
            val w = org.kdx.shell.core.KWindow(900, KApp.LINUXDISPLAY, "app:62", 0, maximized = true)
            w.title = "Untitled — Paint"; w.iconFile = icon
            state.windows.add(w); state.activeId = w.id
        }
        rule.mainClock.advanceTimeBy(500)
        Thread.sleep(300)
        rule.mainClock.advanceTimeBy(500)
        shot("60-linux-app-window")
        rule.onAllNodes(androidx.compose.ui.test.hasText("Untitled — Paint")).onFirst().assertExists()
    }

    @Test fun adBlockerPage() {
        rule.runOnUiThread { state.open(KApp.ADBLOCK) }
        rule.mainClock.advanceTimeBy(2000); Thread.sleep(500); rule.mainClock.advanceTimeBy(500)
        shot("70-adblocker")
        rule.onNodeWithText("Ad blocker is off").assertExists()
        // the bundled list is packaged and loads
        val list = org.kdx.shell.droid.AdBlockService.loadList(rule.activity)
        assertTrue("bundled blocklist in the app (${list.size})", list.size > 50_000)
    }

    @Test fun realKdeSetting() {
        rule.runOnUiThread { state.open(KApp.SETTINGS) }
        rule.mainClock.advanceTimeBy(1500)
        shot("72-real-kde-setting")
        rule.onNodeWithText("Use real KDE Plasma").assertExists()
        rule.onNodeWithText("Phone's resolution").assertExists()
        // 1080x2340 phone: native pixels, or as few as Plasma needs (720 on the short side)
        state.changePlasmaResolution(org.kdx.shell.core.PlasmaResolution.PHONE)
        assertEquals(1080 to 2340, state.plasmaGeometry(1080, 2340))
        state.changePlasmaResolution(org.kdx.shell.core.PlasmaResolution.FAST)
        assertEquals(720 to 1560, state.plasmaGeometry(1080, 2340))
        state.changePlasmaResolution(org.kdx.shell.core.PlasmaResolution.BALANCED)
        assertEquals(864 to 1872, state.plasmaGeometry(1080, 2340))
        state.changePlasmaResolution(org.kdx.shell.core.PlasmaResolution.PHONE)
    }

    @Test fun windSystemTaskbarAndMenu() {
        state.windWindows = listOf("1" to "Untitled - Notepad", "2" to "Setup - My Program", "3" to "File Explorer")
        state.windActive = "1"
        rule.runOnUiThread { rule.activity.setContent {
            org.kdx.shell.core.BreezeTheme(org.kdx.shell.core.ThemeMode.DARK, androidx.compose.ui.graphics.Color(0xFF3DAEE9)) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0F4C81))) {
                    val wc = org.kdx.shell.apps.windColors(true)
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(bottom = 52.dp, start = 6.dp)) {
                        org.kdx.shell.apps.StartMenuPreview(wc, org.kdx.shell.droid.WindSystem.BUILTIN.take(7) +
                            org.kdx.shell.droid.WindSystem.Program("setup", listOf("/x/setup.exe"), category = "Programs folder"))
                    }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) {
                        org.kdx.shell.apps.Taskbar(state, wc, true, {}, {}, androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }, "", {})
                    }
                }
            }
        } }
        rule.mainClock.advanceTimeBy(1000)
        shot("73-windsystem-taskbar")
        rule.onNodeWithContentDescription("Search").assertExists() // a search button on a phone-width screen
        rule.onNodeWithText("Untitled - Notepad").assertExists()
    }

    @Test fun privacyVpnPage() {
        rule.runOnUiThread { state.open(KApp.PRIVACY) }
        rule.mainClock.advanceTimeBy(2000); Thread.sleep(500); rule.mainClock.advanceTimeBy(500)
        shot("71-privacy-vpn")
        rule.onNodeWithText("Not protected").assertExists()
        rule.onNodeWithText("Tor (free, no account)").assertExists()
        rule.onNodeWithText("Fake location (GPS)").assertExists()
    }

    @Test fun konsoleRunsCommands() {
        rule.runOnUiThread { state.open(KApp.KONSOLE, "help") }
        shot("30-konsole-help")
    }
}

private fun KdxState.darkThemeForTest() { themeMode = org.kdx.shell.core.ThemeMode.DARK }
