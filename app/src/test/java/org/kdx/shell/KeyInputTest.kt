package org.kdx.shell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.apps.ImeBridge
import org.kdx.shell.apps.KeyMap
import org.kdx.shell.droid.RfbClient

class KeyInputTest {
    /** What X receives, as text: ⌫ for backspace, ← → for arrows. */
    private val sent = StringBuilder()
    private val b = ImeBridge(object : ImeBridge.Out {
        override fun backspace(count: Int) { repeat(count) { sent.append('⌫') } }
        override fun type(text: String) { sent.append(text) }
        override fun arrow(right: Boolean, count: Int) { repeat(count) { sent.append(if (right) '→' else '←') } }
    })
    private val S = ImeBridge.SENTINEL
    private fun ime(text: String, cursor: Int = text.length, composing: TextRange? = null) =
        b.onChange(TextFieldValue(text, TextRange(cursor), composing))

    @Test fun plainTypingAndComposingWords() {
        ime("${S}h", composing = TextRange(2, 3)); ime("${S}he", composing = TextRange(2, 4)); ime("${S}hey", composing = TextRange(2, 5))
        ime("${S}hey ")
        assertEquals("hey ", sent.toString())
    }

    @Test fun autocorrectReplacesOnlyWhatChanged() {
        ime("${S}teh"); ime("${S}the ") // keyboard fixes the word when space is pressed
        assertEquals("teh⌫⌫he ", sent.toString())
    }

    @Test fun swipeAndVoiceInsertWholeWords() {
        ime("${S}hello "); ime("${S}hello world ")
        assertEquals("hello world ", sent.toString())
    }

    @Test fun backspaceWorksWithNothingTyped() {
        ime(" ") // the keyboard deleted one sentinel space
        assertEquals("⌫", sent.toString())
        assertEquals(S, b.value.text) // ready for the next one
        ime(" "); assertEquals("⌫⌫", sent.toString())
    }

    @Test fun emojiAreOneCharacterEachWay() {
        ime("${S}😀"); ime(S)
        assertEquals("😀⌫", sent.toString())
    }

    @Test fun spaceBarCursorDragBecomesArrowKeys() {
        ime("${S}abc")
        b.onChange(TextFieldValue("${S}abc", TextRange(S.length + 1))) // cursor dragged 2 left
        assertEquals("abc←←", sent.toString())
        assertEquals(TextRange(S.length + 3), b.value.selection) // back at the end for the keyboard
    }

    @Test fun enterSendsReturnAndStartsFresh() {
        ime("${S}ls\n")
        assertEquals("ls\n", sent.toString())
        assertEquals(S, b.value.text)
    }

    @Test fun keyMapAndKeysyms() {
        assertEquals(RfbClient.KEY_F1 + 4, KeyMap.special(android.view.KeyEvent.KEYCODE_F5))
        assertEquals(RfbClient.KEY_RETURN, KeyMap.special(android.view.KeyEvent.KEYCODE_ENTER))
        assertNull("right Alt is AltGr: its characters come as text", KeyMap.special(android.view.KeyEvent.KEYCODE_ALT_RIGHT))
        assertNull("letters are text", KeyMap.special(android.view.KeyEvent.KEYCODE_A))
        assertEquals('c'.code, KeyMap.shortcutKeysym('C'.code)) // Ctrl+Shift+C → c (+ the Shift X already has)
        assertEquals(0x0101F600, RfbClient.keysymFor(0x1F600)) // 😀 as X Unicode keysym
        assertEquals(0xe9, RfbClient.keysymFor('é'.code))
    }
}
