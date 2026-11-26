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
