package org.kdx.shell

import android.content.ClipboardManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.kdx.shell.core.Accents
import org.kdx.shell.core.BreezeTheme
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import org.kdx.shell.shell.Shell

class MainActivity : ComponentActivity() {
    private val state: KdxState by viewModels()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClip() }

    override fun onCreate(savedInstanceState: Bundle?) {
        org.kdx.shell.core.StartGuard.installCrashRecorder(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // As a home screen, back never leaves KDX – it closes menus / minimises the active window.
            override fun handleOnBackPressed() {
                state.back()
            }
        })
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener)
        setContent {
            BreezeTheme(state.themeMode, Accents[state.accentIndex.coerceIn(Accents.indices)].second) {
                Shell(state)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard.
        if (hasFocus) readClip()
    }

    private fun readClip() {
        runCatching {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
            if (clip.itemCount > 0) state.recordClip(clip.getItemAt(0).coerceToText(this)?.toString())
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Home button pressed while KDX is in front: behave like "show desktop".
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) {
            if (state.kickoffOpen || state.krunnerOpen || state.overviewOpen) state.closeMenus()
            else state.windowsOnDesktop().forEach { state.minimize(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        state.save()
    }

    override fun onDestroy() {
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    /** Plasma keyboard shortcuts for hardware keyboards / desktop mode. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleShortcut(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private var metaUsedInChord = false

    private fun handleShortcut(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) metaUsedInChord = false
            if (e.action == KeyEvent.ACTION_UP && !metaUsedInChord) {
                state.krunnerOpen = false
                state.kickoffOpen = !state.kickoffOpen
                return true
            }
            return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        if (e.isMetaPressed) metaUsedInChord = true
        val w = state.activeWindow
        when {
            e.isAltPressed && code == KeyEvent.KEYCODE_SPACE -> { state.kickoffOpen = false; state.krunnerOpen = !state.krunnerOpen }
            e.isAltPressed && code == KeyEvent.KEYCODE_F2 -> state.krunnerOpen = true
            e.isAltPressed && code == KeyEvent.KEYCODE_F4 -> w?.let { state.close(it) } ?: return false
            e.isCtrlPressed && e.isAltPressed && code == KeyEvent.KEYCODE_T -> state.open(KApp.KONSOLE)
            e.isMetaPressed && code == KeyEvent.KEYCODE_E -> state.open(KApp.DOLPHIN)
            e.isMetaPressed && code == KeyEvent.KEYCODE_W -> state.overviewOpen = !state.overviewOpen
            e.isMetaPressed && code == KeyEvent.KEYCODE_D -> state.windowsOnDesktop().forEach { state.minimize(it) }
            e.isMetaPressed && code == KeyEvent.KEYCODE_V -> state.open(KApp.KLIPPER)
            e.isMetaPressed && code == KeyEvent.KEYCODE_DPAD_UP -> w?.let { it.maximized = true } ?: return false
            e.isMetaPressed && code == KeyEvent.KEYCODE_DPAD_DOWN -> w?.let { if (it.maximized) it.maximized = false else state.minimize(it) } ?: return false
            e.isMetaPressed && code == KeyEvent.KEYCODE_PAGE_DOWN -> w?.let { state.minimize(it) } ?: return false
            e.isCtrlPressed && e.isMetaPressed && code == KeyEvent.KEYCODE_DPAD_RIGHT -> state.switchDesktop(state.currentDesktop + 1)
            e.isCtrlPressed && e.isMetaPressed && code == KeyEvent.KEYCODE_DPAD_LEFT -> state.switchDesktop(state.currentDesktop - 1)
            e.isCtrlPressed && code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F8 -> {
                val d = code - KeyEvent.KEYCODE_F1
                if (d < state.desktopCount) state.switchDesktop(d) else return false
            }
            e.isAltPressed && code == KeyEvent.KEYCODE_TAB -> {
                // Alt+Tab: cycle through windows on this desktop
                val list = state.windowsOnDesktop()
                if (list.size < 2) return list.isNotEmpty()
                state.focus(list.first())
            }
            else -> return false
        }
        return true
    }
}
