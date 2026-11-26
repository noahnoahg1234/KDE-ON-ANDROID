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
        t.apply(listOf("com.a" to 1, "com.b" to 1, "org.kdx.shell" to 1, "com.android.systemui" to 1)) { it in apps }
        assertEquals(listOf("com.a", "com.b"), t.packages)
        t.apply(listOf("com.a" to 2, "com.a" to 23)) { it in apps } // paused / stopped: window still open
        assertEquals(listOf("com.a", "com.b"), t.packages)
        t.apply(listOf("com.b" to 24)) { it in apps }                 // destroyed: window closed
        assertEquals(listOf("com.a"), t.packages)
        t.apply(listOf("com.b" to 1)) { it in apps }                  // opened again
        assertEquals(listOf("com.a", "com.b"), t.packages)
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
        val normal = launchBounds()!!
        state.maximizeAndroidWindow(a) // Window Control off: remembered for the next start
        assertTrue(state.isMaximized(a))
        val max = launchBounds()!!
        val dm = app.resources.displayMetrics
        assertEquals(state.desktopMargins().bounds(dm.widthPixels, dm.heightPixels), max)
        assertTrue("bigger than a normal window", max.width() * max.height() > normal.width() * normal.height())
        assertTrue("taskbar stays visible", max.bottom < dm.heightPixels)
        state.restoreAndroidWindow(a)
        assertFalse(state.isMaximized(a))
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
        state.androidAppWindows = true
        val wm = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(app.getSystemService(android.view.WindowManager::class.java))
        org.kdx.shell.shell.OverlayTaskbar.sync(app, state, kdxInFront = false)
        assertEquals("shown over the apps", 1, wm.views.size)
        org.kdx.shell.shell.OverlayTaskbar.sync(app, state, kdxInFront = true)
        assertEquals("KDX in front: its own taskbar", 0, wm.views.size)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        org.kdx.shell.shell.OverlayTaskbar.sync(app, state, kdxInFront = false)
        assertEquals("not without the permission", 0, wm.views.size)
    }
}
