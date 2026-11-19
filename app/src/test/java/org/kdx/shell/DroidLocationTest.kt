package org.kdx.shell

import android.Manifest
import android.os.Environment
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.kdx.shell.core.KdxState
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidLocationTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun pump(state: KdxState, until: () -> Boolean) {
        var n = 0
        while (!until() && n++ < 100) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(30) }
    }

    @Test fun newInstallsGoInTheAndroidFolder() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        val state = KdxState(app)
        assertEquals(File(Environment.getExternalStorageDirectory(), "Android/DroidSystem").path, state.droidTargetDir().path)
    }

    @Test fun withoutFileAccessUsesKdxsAndroidDataFolder() {
        val state = KdxState(app)
        val dir = state.droidTargetDir().path
        assertTrue(dir, dir.contains("Android/data/") || dir.endsWith("files/DroidSystem"))
        assertTrue(dir.endsWith("DroidSystem"))
    }

    @Test fun oldInstallIsMovedIntoTheAndroidFolder() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        val legacy = File(Environment.getExternalStorageDirectory(), "DroidSystem")
        File(legacy, "etc").mkdirs()
        File(legacy, "etc/os-release").writeText("PRETTY_NAME=\"DroidSystem 1.1\"\n")
        File(legacy, "home/user/notes.txt").apply { parentFile!!.mkdirs(); writeText("keep me") }
        app.getSharedPreferences("kdx", 0).edit().putBoolean("droid", true).putString("droidRoot", legacy.path).commit()
        val state = KdxState(app)
        val target = File(Environment.getExternalStorageDirectory(), "Android/DroidSystem")
        pump(state) { state.droidRoot == target.path && state.droid != null }
        assertEquals(target.path, state.droidRoot)
        assertEquals("keep me", File(target, "home/user/notes.txt").readText())
        assertFalse("old folder removed", legacy.exists())
        assertEquals(target.path, state.droid!!.root.path)
    }
}
