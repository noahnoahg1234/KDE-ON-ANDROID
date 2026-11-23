package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.core.StartGuard

class StartGuardTest {
    @Test fun restartLoopDetection() {
        val now = 10 * 60_000L
        assertFalse("first start", StartGuard.isLoop(listOf(now), now))
        assertFalse("two quick starts", StartGuard.isLoop(listOf(now - 30_000, now), now))
        assertTrue("three within 3 minutes = loop", StartGuard.isLoop(listOf(now - 100_000, now - 40_000, now), now))
        assertFalse("spread over 10 minutes = fine", StartGuard.isLoop(listOf(now - 500_000, now - 300_000, now), now))
    }
}
