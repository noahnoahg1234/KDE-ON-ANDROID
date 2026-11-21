package org.kdx.shell.apps

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.kdx.shell.droid.RfbClient

/**
 * Bridges Android's on-screen keyboard to X key presses.
 *
 * The keyboard types into a hidden text field. Android keyboards don't just add letters: they compose words, replace
 * them (autocorrect, suggestions, swipe typing, voice), and move the cursor (Gboard's space-bar drag). So the field
 * keeps what was typed, and every change is turned into the minimal edit: backspaces for what disappeared after the
 * common prefix, then the new text. Cursor moves become arrow keys. The field always starts with [SENTINEL], so
 * backspace still works when nothing has been typed yet (an empty field gives the keyboard nothing to delete).
 */
class ImeBridge(private val out: Out) {
    interface Out {
        fun backspace(count: Int)
        fun type(text: String)
        fun arrow(right: Boolean, count: Int)
    }

    var value = fresh(); private set

    fun onChange(v: TextFieldValue): TextFieldValue {
        val old = value.text; val new = v.text
        if (old == new) {
            // only the cursor moved (e.g. dragging on the space bar): arrow keys, then back to the end
            val d = v.selection.end - value.selection.end
            if (v.selection.collapsed && d != 0 && value.selection.collapsed) {
                out.arrow(d > 0, codePoints(if (d > 0) old.substring(value.selection.end, v.selection.end) else old.substring(v.selection.end, value.selection.end)))
                value = v.copy(selection = TextRange(new.length)); return value
            }
            value = v; return value
        }
        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        if (p > 0 && Character.isHighSurrogate(old[p - 1])) p-- // don't split an emoji
        val removed = codePoints(old.substring(p))
        val added = new.substring(p)
        if (removed > 0) out.backspace(removed)
        if (added.isNotEmpty()) out.type(added)
        // start over after a new line, when the field gets long, or when the sentinel was deleted
        value = if (!new.startsWith(SENTINEL) || new.contains('\n') || new.length > 80) fresh()
            else v.copy(selection = if (v.selection.collapsed) TextRange(new.length) else v.selection)
        return value
    }

    fun reset() { value = fresh() }

    private fun fresh() = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

    companion object {
        const val SENTINEL = "  "
        fun codePoints(s: String) = s.codePointCount(0, s.length)
    }
}

/** Android hardware-keyboard key codes → X keysyms for keys that aren't text. */
object KeyMap {
    private val table: Map<Int, Int> = buildMap {
        put(android.view.KeyEvent.KEYCODE_ENTER, RfbClient.KEY_RETURN); put(android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, RfbClient.KEY_RETURN)
        put(android.view.KeyEvent.KEYCODE_DEL, RfbClient.KEY_BACKSPACE); put(android.view.KeyEvent.KEYCODE_FORWARD_DEL, RfbClient.KEY_DELETE)
        put(android.view.KeyEvent.KEYCODE_TAB, RfbClient.KEY_TAB); put(android.view.KeyEvent.KEYCODE_ESCAPE, RfbClient.KEY_ESCAPE)
        put(android.view.KeyEvent.KEYCODE_DPAD_LEFT, RfbClient.KEY_LEFT); put(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, RfbClient.KEY_RIGHT)
        put(android.view.KeyEvent.KEYCODE_DPAD_UP, RfbClient.KEY_UP); put(android.view.KeyEvent.KEYCODE_DPAD_DOWN, RfbClient.KEY_DOWN)
        put(android.view.KeyEvent.KEYCODE_MOVE_HOME, RfbClient.KEY_HOME); put(android.view.KeyEvent.KEYCODE_MOVE_END, RfbClient.KEY_END)
        put(android.view.KeyEvent.KEYCODE_PAGE_UP, RfbClient.KEY_PAGE_UP); put(android.view.KeyEvent.KEYCODE_PAGE_DOWN, RfbClient.KEY_PAGE_DOWN)
        put(android.view.KeyEvent.KEYCODE_INSERT, RfbClient.KEY_INSERT); put(android.view.KeyEvent.KEYCODE_MENU, RfbClient.KEY_MENU)
        put(android.view.KeyEvent.KEYCODE_SYSRQ, RfbClient.KEY_PRINT); put(android.view.KeyEvent.KEYCODE_CAPS_LOCK, RfbClient.KEY_CAPS_LOCK)
        put(android.view.KeyEvent.KEYCODE_CTRL_LEFT, RfbClient.KEY_CTRL); put(android.view.KeyEvent.KEYCODE_CTRL_RIGHT, 0xffe4)
        put(android.view.KeyEvent.KEYCODE_SHIFT_LEFT, RfbClient.KEY_SHIFT); put(android.view.KeyEvent.KEYCODE_SHIFT_RIGHT, 0xffe2)
        put(android.view.KeyEvent.KEYCODE_ALT_LEFT, RfbClient.KEY_ALT) // right Alt = AltGr: its characters (@, €…) arrive as text
        put(android.view.KeyEvent.KEYCODE_META_LEFT, RfbClient.KEY_SUPER); put(android.view.KeyEvent.KEYCODE_META_RIGHT, 0xffec)
        for (i in 0..11) put(android.view.KeyEvent.KEYCODE_F1 + i, RfbClient.KEY_F1 + i)
    }

    /** The keysym for a non-text key, or null. */
    fun special(keyCode: Int): Int? = table[keyCode]

    /**
     * For shortcuts (Ctrl/Alt/Super held): the key's plain character, because Android reports no character while
     * Ctrl is down. [plainChar] is the key's character without modifiers (KeyEvent.getUnicodeChar(0)).
     */
    fun shortcutKeysym(plainChar: Int): Int? = plainChar.takeIf { it > 0 }?.let { RfbClient.keysymFor(Character.toLowerCase(it)) }
}
