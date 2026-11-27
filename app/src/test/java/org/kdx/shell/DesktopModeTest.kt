package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.core.AndroidWindows
import org.kdx.shell.core.DesktopResolution

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class DesktopModeTest {
    @Test fun resolutionToDensity() {
        // a 1080 × 2400 phone at density 2.625
        assertEquals(2.625f, DesktopResolution.density(DesktopResolution.PHONE, 1080, 2400, 2.625f), 0.001f)
        assertEquals("1920 across the long side", 1.25f, DesktopResolution.density(DesktopResolution.FULL_HD, 1080, 2400, 2.625f), 0.001f)
        assertEquals(1920 to 864, DesktopResolution.actualSize(DesktopResolution.FULL_HD, 1080, 2400, 2.625f))
        assertEquals("same in landscape", 1.25f, DesktopResolution.density(DesktopResolution.FULL_HD, 2400, 1080, 2.625f), 0.001f)
        assertEquals("never unreadably small", DesktopResolution.MIN_DENSITY, DesktopResolution.density(DesktopResolution.QHD, 720, 1280, 2f), 0.001f)
    }

    @Test fun windowsCascadeOnScreen() {
        val all = (0 until 12).map { AndroidWindows.bounds(it, 2400, 1080) }
        all.forEach { r -> assertTrue("$r", r.left >= 0 && r.top >= 0 && r.right <= 2400 && r.bottom <= 1080 && r.width() > 0 && r.height() > 0) }
        assertNotEquals(all[0], all[1])
        // inside the desktop area: never under KDX's taskbar
        val area = android.graphics.Rect(0, 63, 1080, 2400 - 63 - 126)
        (0 until 12).map { AndroidWindows.bounds(it, 1080, 2400, area) }.forEach { r -> assertTrue("$r", area.contains(r)) }
        val portrait = AndroidWindows.bounds(0, 1080, 2400)
        assertTrue(portrait.right <= 1080 && portrait.bottom <= 2400)
    }
}

class DesktopWidgetsTest {
    @Test fun savedAndLoaded() {
        val list = listOf(
            org.kdx.shell.core.DesktopWidget(1, org.kdx.shell.core.WidgetType.CLOCK),
            org.kdx.shell.core.DesktopWidget(2, org.kdx.shell.core.WidgetType.NOTE),               // shared with the Notes app
            org.kdx.shell.core.DesktopWidget(3, org.kdx.shell.core.WidgetType.NOTE, "Buy milk | eggs\nline 2"), // its own text
            org.kdx.shell.core.DesktopWidget(4, org.kdx.shell.core.WidgetType.CALENDAR),
        )
        val back = org.kdx.shell.core.DesktopWidget.parse(org.kdx.shell.core.DesktopWidget.serialize(list))
        assertEquals(list.map { it.type }, back.map { it.type })
        assertEquals(listOf(null, null, "Buy milk | eggs\nline 2", null), back.map { it.text })
        assertTrue("ids unique", back.map { it.id }.toSet().size == 4)
        assertTrue("unknown entries skipped", org.kdx.shell.core.DesktopWidget.parse("CLOCK|WEATHER|BATTERY").map { it.type } ==
            listOf(org.kdx.shell.core.WidgetType.CLOCK, org.kdx.shell.core.WidgetType.BATTERY))
        assertTrue("empty = no widgets", org.kdx.shell.core.DesktopWidget.parse("").isEmpty())
    }

    @Test fun oldSettingsCarryOver() {
        val w = org.kdx.shell.core.DesktopWidget.fromOldSwitches(clock = true, system = false, note = true)
        assertEquals(listOf(org.kdx.shell.core.WidgetType.CLOCK, org.kdx.shell.core.WidgetType.NOTE), w.map { it.type })
        assertNull("the old note stays linked to the Notes app", w[1].text)
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class AndroidWindowsTaskbarTest {
    @Test fun openAppsFollowUsageEvents() {
        val t = org.kdx.shell.core.OpenAppTracker()
        val apps = setOf("com.a", "com.b")
        val R = org.kdx.shell.core.OpenAppTracker.RESUMED; val S = org.kdx.shell.core.OpenAppTracker.STOPPED
        t.apply(listOf(Triple("com.a", R, 1000L), Triple("com.b", R, 2000L), Triple("org.kdx.shell", R, 2100L), Triple("com.android.systemui", R, 2200L))) { it in apps }
        assertEquals(listOf("com.a", "com.b"), t.packages)
        // the user taps KDX's desktop: KDX comes in front and both windows are hidden behind it (stopped) – still open
        t.kdxCameToFront(10_000)
        t.apply(listOf(Triple("com.a", S, 10_300L), Triple("com.b", S, 10_400L))) { it in apps }
        assertEquals("hidden behind KDX, not closed", listOf("com.a", "com.b"), t.packages)
        // switching to b brings the windows up again (KDX leaves the front)
        t.kdxLeftFront(20_000)
        t.apply(listOf(Triple("com.a", R, 20_100L), Triple("com.b", R, 20_200L))) { it in apps }
        // b's window is closed with its ✕ while KDX isn't in front: Android reports it as stopped – closed
        t.apply(listOf(Triple("com.b", 2, 30_000L), Triple("com.b", S, 30_200L))) { it in apps }
        assertEquals("closed window leaves the taskbar", listOf("com.a"), t.packages)
        // a stop that arrives a moment after KDX came in front (events race a little) still counts as hidden
        t.kdxCameToFront(40_000)
        t.apply(listOf(Triple("com.a", S, 39_200L))) { it in apps }
        assertEquals(listOf("com.a"), t.packages)
        assertTrue(t.kdxInFrontAt(45_000))
        assertFalse(t.kdxInFrontAt(25_000))
    }

    @Test fun maximizeLeavesTheTaskbarVisible() {
        val d = 2.625f; val status = 63; val nav = 63
        val bottom = org.kdx.shell.core.DesktopMargins.of(org.kdx.shell.core.PanelPosition.BOTTOM, d, status, nav).bounds(1080, 2400)
        assertEquals(android.graphics.Rect(0, 63, 1080, 2400 - 63 - 126), bottom)
        val dock = org.kdx.shell.core.DesktopMargins.of(org.kdx.shell.core.PanelPosition.LEFT_DOCK, d, status, nav).bounds(2400, 1080)
        assertEquals(android.graphics.Rect(157, 63 + 89, 2400, 1080 - 63), dock)
        val right = org.kdx.shell.core.DesktopMargins.of(org.kdx.shell.core.PanelPosition.RIGHT_DOCK, d, status, nav).bounds(2400, 1080)
        assertEquals(2400 - 157, right.right)
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MaximizedAndroidAppTest {
    /** After Maximize, the app opens at the desktop's size (Android only sizes a window when it opens). */
    @Test fun maximizedAppsOpenMaximized() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        android.provider.Settings.Global.putInt(app.contentResolver, "enable_freeform_support", 1)
        val state = org.kdx.shell.core.KdxState(app)
        state.androidAppWindows = true
        val a = org.kdx.shell.core.AndroidApp("Chrome", "com.android.chrome", "Main", null)
        val shadow = org.robolectric.Shadows.shadowOf(app)
        fun launchBounds(): android.graphics.Rect? {
            state.launch(a)
            val started = shadow.nextStartedActivityForResult ?: return null
            @Suppress("DEPRECATION") return started.options?.getParcelable<android.graphics.Rect>("android:activity.launchBounds")
        }
        val dm = app.resources.displayMetrics
        val desktop = state.desktopMargins().bounds(dm.widthPixels, dm.heightPixels)
        // apps open maximised above the taskbar, like a PC
        assertTrue(state.isMaximized(a))
        assertEquals(desktop, launchBounds())
        assertTrue("taskbar stays visible", desktop.bottom < dm.heightPixels)
        // Restore size: a normal floating window from then on (Window Control off: applied at the next start)
        state.restoreAndroidWindow(a)
        assertFalse(state.isMaximized(a))
        val normal = launchBounds()!!
        assertTrue("inside the desktop", desktop.contains(normal))
        assertTrue("smaller than maximised", normal.width() * normal.height() < desktop.width() * desktop.height())
        state.maximizeAndroidWindow(a)
        assertEquals(desktop, launchBounds())
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ManyAndroidWindowsTest {
    private val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun allowUsageAccess() {
        val ops = app.getSystemService(android.app.AppOpsManager::class.java)
        org.robolectric.Shadows.shadowOf(ops).setMode(43 /* OP_GET_USAGE_STATS */, android.os.Process.myUid(), app.packageName, android.app.AppOpsManager.MODE_ALLOWED)
    }

    /** Switching to one app brings all open windows back above KDX – the chosen one last, so it's on top. */
    @Test fun switchingBringsAllWindowsUp() {
        android.provider.Settings.Global.putInt(app.contentResolver, "enable_freeform_support", 1)
        allowUsageAccess()
        assertTrue(org.kdx.shell.core.AndroidWindows.hasUsageAccess(app))
        val state = org.kdx.shell.core.KdxState(app)
        state.androidAppWindows = true
        val apps = (1..10).map { org.kdx.shell.core.AndroidApp("App $it", "com.app$it", "Main", null) }
        apps.forEach { state.androidWindows.add(it) }
        val shadow = org.robolectric.Shadows.shadowOf(app)
        while (shadow.nextStartedActivity != null) {}
        // how this test double reports order: start "first", then "second"
        app.startActivity(android.content.Intent().setClassName("first", "A").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        app.startActivity(android.content.Intent().setClassName("second", "A").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        assertEquals("newest start is returned first", "second", shadow.nextStartedActivity.component!!.packageName)
        while (shadow.nextStartedActivity != null) {}
        state.focusAndroidWindow(apps[3])
        org.robolectric.shadows.ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS)
        // Robolectric returns the newest start first: reverse to get the order they were started in
        val started = generateSequence { shadow.nextStartedActivity }.map { it.component!!.packageName }.toList().reversed()
        assertEquals("all 10 windows brought up", 10, started.size)
        assertEquals("the chosen app ends on top", "com.app4", started.last())
        assertEquals(apps.map { it.pkg }.toSet(), started.toSet())
    }

    /** The taskbar floats over the apps while they're in front of KDX, and goes away when KDX is in front. */
    @Test fun floatingTaskbarOnlyOverApps() {
        android.provider.Settings.Global.putInt(app.contentResolver, "enable_freeform_support", 1)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val state = org.kdx.shell.core.KdxState(app)
        state.androidAppWindows = false // always on: doesn't depend on that switch
        val wm = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(app.getSystemService(android.view.WindowManager::class.java))
        org.kdx.shell.shell.OverlayTaskbar.sync(app, state, kdxInFront = false)
        assertEquals("shown over the apps", 1, wm.views.size)
        val lp = wm.views[0].layoutParams as android.view.WindowManager.LayoutParams
        assertTrue("goes below the on-screen keyboard (never covers it)",
            lp.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0 &&
                lp.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        org.kdx.shell.shell.OverlayTaskbar.sync(app, state, kdxInFront = true)
        assertEquals("KDX in front: its own taskbar", 0, wm.views.size)
        // collapsed: the window is only the handle (a full-width invisible window caught taps along the apps' bottom)
        org.kdx.shell.shell.OverlayTaskbar.sync(app, state, kdxInFront = false)
        assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, org.kdx.shell.shell.OverlayTaskbar.layoutParams!!.width)
        org.kdx.shell.shell.OverlayTaskbar.setCompact(true)
        val small = org.kdx.shell.shell.OverlayTaskbar.layoutParams!!
        assertEquals(android.view.WindowManager.LayoutParams.WRAP_CONTENT, small.width)
        assertEquals(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL, small.gravity)
        org.kdx.shell.shell.OverlayTaskbar.setCompact(false)
        assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, org.kdx.shell.shell.OverlayTaskbar.layoutParams!!.width)
        org.kdx.shell.shell.OverlayTaskbar.hide(app)
        // an app went full screen anyway: the taskbar shrinks to its tab
        org.kdx.shell.shell.OverlayTaskbar.coveredByFullscreen = true
        org.kdx.shell.shell.OverlayTaskbar.sync(app, state, kdxInFront = false)
        assertEquals("still there (as a tab)", 1, wm.views.size)
        org.kdx.shell.shell.OverlayTaskbar.coveredByFullscreen = false
        org.kdx.shell.shell.OverlayTaskbar.hide(app)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        org.kdx.shell.shell.OverlayTaskbar.sync(app, state, kdxInFront = false)
        assertEquals("not without the permission", 0, wm.views.size)
    }
}
