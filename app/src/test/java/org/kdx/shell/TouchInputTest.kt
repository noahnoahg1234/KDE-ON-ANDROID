package org.kdx.shell

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.apps.TouchTranslator

class TouchInputTest {
    private val events = mutableListOf<Triple<Int, Int, Int>>()
    private val marks = mutableListOf<TouchTranslator.Kind>()
    private var haptics = 0
    private var pinch = 1f
    // 1 screen px = 0.5 X px
    private val t = TouchTranslator(slopPx = 24f, toFb = { (it.x / 2).toInt() to (it.y / 2).toInt() }, out = { x, y, m -> events += Triple(x, y, m) },
        scrollStepPx = { 100f }, onPinch = { f, _ -> pinch *= f }, haptic = { haptics++ }, mark = { _, k -> marks += k })

    private fun masks() = events.map { it.third }.filter { it != 0 }

    @Test fun smallWobbleIsStillATap() {
        t.down(1, Offset(400f, 400f), 0); t.move(1, Offset(412f, 409f), 40); t.up(1, Offset(412f, 409f), 90)
        assertEquals(listOf(1), masks())
        assertEquals(Triple(200, 200, 1), events.first { it.third == 1 }) // clicks where the finger went down
        assertEquals(listOf(TouchTranslator.Kind.TAP), marks)
    }

    @Test fun secondTapLandsOnTheFirstForDoubleClick() {
        t.down(1, Offset(400f, 400f), 0); t.up(1, Offset(400f, 400f), 60)
        t.down(1, Offset(420f, 390f), 200); t.up(1, Offset(420f, 390f), 260)
        val clicks = events.filter { it.third == 1 }
        assertEquals(2, clicks.size); assertEquals(clicks[0], clicks[1])
        // much later: a normal tap where it is
        t.down(1, Offset(420f, 390f), 2000); t.up(1, Offset(420f, 390f), 2060)
        assertEquals(Triple(210, 195, 1), events.last { it.third == 1 })
    }

    @Test fun longPressIsRightClickWithHaptic() {
        t.down(1, Offset(300f, 300f), 0); t.tick(200); assertEquals(0, haptics)
        t.tick(460); assertEquals(1, haptics)
        t.up(1, Offset(300f, 300f), 700)
        assertEquals(listOf(4), masks())
    }

    @Test fun longPressThenDragDragsWithLeftButton() {
        t.down(1, Offset(300f, 300f), 0); t.tick(500)
        t.move(1, Offset(400f, 300f), 600); t.move(1, Offset(500f, 300f), 650); t.up(1, Offset(500f, 300f), 700)
        assertTrue(masks().all { it == 1 }); assertEquals(Triple(250, 150, 0), events.last())
    }

    @Test fun dragScrollsFollowingTheFingerWithoutLosingSteps() {
        t.down(1, Offset(300f, 800f), 0)
        // 330 px up in small moves: the first 30 px are the tap slop, then 300 px = 3 wheel-down steps (100 px each)
        var y = 800f; var time = 0L
        repeat(33) { y -= 10f; time += 16; t.move(1, Offset(300f, y), time) }
        assertEquals(listOf(16, 16, 16), masks())
        t.move(1, Offset(300f, y - 60f), time + 16); assertEquals(3, masks().size) // part of a step is kept…
        t.move(1, Offset(300f, y - 110f), time + 32); assertEquals(4, masks().size) // …and counts with the next movement
        assertTrue("scrolls where the finger started", events.filter { it.third == 16 }.all { it.first == 150 && it.second == 400 })
    }

    @Test fun flickKeepsScrolling() {
        t.down(1, Offset(300f, 1000f), 0)
        var y = 1000f
        for (i in 1..6) { y -= 60f; t.move(1, Offset(300f, y), i * 16L) } // fast flick up
        t.up(1, Offset(300f, y), 100)
        val during = masks().size
        assertTrue(t.flinging)
        var now = 100L; while (t.flinging && now < 5000) { now += 16; t.tick(now) }
        assertTrue("fling added steps (${masks().size} vs $during)", masks().size > during + 3)
        assertTrue(masks().all { it == 16 })
        assertFalse(t.flinging)
        // touching again stops a fling at once
        t.down(1, Offset(300f, y), 200); t.up(1, Offset(300f, y), 230)
    }

    @Test fun twoFingerTapIsRightClickThreeFingerTapIsMiddle() {
        t.down(1, Offset(300f, 300f), 0); t.down(2, Offset(400f, 300f), 10); t.up(1, Offset(300f, 300f), 80); t.up(2, Offset(400f, 300f), 90)
        assertEquals(listOf(4), masks())
        assertEquals(Triple(175, 150, 4), events.first { it.third == 4 }) // between the fingers
        events.clear()
        t.down(1, Offset(300f, 300f), 1000); t.down(2, Offset(400f, 300f), 1005); t.down(3, Offset(350f, 400f), 1010)
        t.up(1, Offset(300f, 300f), 1080); t.up(2, Offset(400f, 300f), 1085); t.up(3, Offset(350f, 400f), 1090)
        assertEquals(listOf(2), masks())
    }

    @Test fun pinchZoomsAndTwoFingerMoveScrolls() {
        t.down(1, Offset(300f, 500f), 0); t.down(2, Offset(500f, 500f), 5)
        t.move(1, Offset(200f, 500f), 30); t.move(2, Offset(600f, 500f), 30); t.move(1, Offset(100f, 500f), 60)
        assertTrue("zoomed in: $pinch", pinch > 1.2f)
        t.up(1, Offset(100f, 500f), 90); t.up(2, Offset(600f, 500f), 95)
        assertTrue("a pinch is not a click", masks().isEmpty())
        // two fingers moving together: scroll
        t.down(1, Offset(300f, 800f), 1000); t.down(2, Offset(400f, 800f), 1005)
        for (i in 1..10) { t.move(1, Offset(300f, 800f - i * 30), 1000L + i * 16); t.move(2, Offset(400f, 800f - i * 30), 1000L + i * 16) }
        assertTrue(masks().isNotEmpty() && masks().all { it == 16 })
    }

    @Test fun mouseModeDragsWithLeftButton() {
        t.touchMode = { false }
        t.down(1, Offset(100f, 100f), 0); t.move(1, Offset(300f, 100f), 30); t.up(1, Offset(300f, 100f), 60)
        assertEquals(listOf(1, 1), masks()); assertEquals(Triple(150, 50, 0), events.last())
    }
}
