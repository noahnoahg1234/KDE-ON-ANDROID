package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.AutoScaler

class AutoScalerTest {
    @Test fun stepsDownWhenSlowAndBackUpWithHeadroom() {
        val a = AutoScaler()
        assertFalse(a.update(30)); assertTrue(a.update(30)); assertEquals(0.85f, a.scale, 0.001f)
        a.update(35); a.update(35); assertEquals(0.70f, a.scale, 0.001f)
        repeat(10) { a.update(10_0) } // capped server rate is 60, but anything >= 58 counts as headroom
        assertTrue(a.scale > 0.70f)
    }

    @Test fun idleAppsAreLeftAlone() {
        val a = AutoScaler()
        repeat(20) { a.update(2) }
        assertEquals(1f, a.scale, 0.001f)
    }

    @Test fun neverBelowMinimum() {
        val a = AutoScaler()
        repeat(40) { a.update(20) }
        assertEquals(0.45f, a.scale, 0.001f)
    }

    @Test fun oneSlowReadingIsNotEnough() {
        val a = AutoScaler()
        a.update(30); a.update(60); a.update(30); a.update(60)
        assertEquals(1f, a.scale, 0.001f)
    }
}
