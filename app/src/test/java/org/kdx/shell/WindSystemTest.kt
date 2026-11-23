package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.LnkFile
import org.kdx.shell.droid.WindSystem

class WindSystemTest {
    @Test fun crashesAreRecognisedWithBacktrace() {
        val out = listOf("about to crash",
            "wine: Unhandled page fault on write access to 0000000000000000 at address 000000014000150B (thread 014c), starting debugger...",
            "Unhandled exception: page fault on write access to 0x0000000000000000 in 64-bit code (0x000000014000150b).",
            "Register dump:", " rip:000000014000150b", "Backtrace:", "=>0 0x0000014000150b in crashme (+0x150b)", "  1 0x00000140001307 in crashme (+0x1307)")
        val c = WindSystem.parseCrash("crashme", 5, out)!!
        assertTrue(c.summary, c.summary.startsWith("page fault on write access"))
        assertEquals("Backtrace:", c.details.first())
        assertNull("normal exit is not a crash", WindSystem.parseCrash("notepad", 0, listOf("fixme:something")))
        assertEquals("Android ended it – usually low memory.", WindSystem.parseCrash("x", 137, emptyList())!!.summary)
        val report = WindSystem.report(c, WindSystem.Config(), "arm64")
        assertTrue(report.contains("Program: crashme") && report.contains("box64") && report.contains("Windows 10"))
    }

    @Test fun registryAppliesTheSettings() {
        val r = WindSystem.registry(WindSystem.Config(windowsVersion = "win11", dpi = 144, renderer = "gdi", sound = false, wallpaper = 0xFF0F4C81))
        assertTrue(r.startsWith("Windows Registry Editor Version 5.00"))
        assertTrue(r.contains("\"Version\"=\"win11\""))
        assertTrue(r.contains("\"LogPixels\"=dword:00000090"))
        assertTrue(r.contains("\"renderer\"=\"gdi\""))
        assertTrue(r.contains("\"Audio\"=\"\""))
        assertTrue(r.contains("\"ShowCrashDialog\"=dword:00000000"))
        assertTrue(r.contains("\"Background\"=\"15 76 129\""))
        assertTrue(r.contains("light.msstyles"))
    }

    @Test fun environmentAndPaths() {
        val fast = WindSystem.env(WindSystem.Config(performance = "fast", dllOverrides = "d3d9=native,builtin", debugLog = "off"), "/storage/emulated/0/Android/WindSystem")
        assertEquals(WindSystem.PREFIX, fast["WINEPREFIX"])
        assertEquals("3", fast["BOX64_DYNAREC_BIGBLOCK"])
        assertEquals("-all", fast["WINEDEBUG"])
        assertTrue(fast["WINEDLLOVERRIDES"]!!.contains("d3d9=native"))
        assertEquals("/root/.windsystem/dosdevices/c:/Program Files/App/app.exe", WindSystem.winToUnix("C:\\Program Files\\App\\app.exe"))
    }

    @Test fun shortcutTargets() {
        // a minimal .lnk: header (HasLinkInfo), LinkInfo with a local base path
        val path = "C:\\Program Files\\Game\\game.exe"
        val header = ByteArray(0x4c).apply { this[0] = 0x4c; this[0x14] = 0x02 }
        val infoHeader = 0x1c
        val p = path.toByteArray(Charsets.ISO_8859_1) + 0
        val info = ByteArray(infoHeader + p.size).apply {
            val size = this.size
            this[0] = size.toByte(); this[4] = infoHeader.toByte(); this[0x10] = infoHeader.toByte()
            p.copyInto(this, infoHeader)
        }
        val f = java.io.File.createTempFile("game", ".lnk").apply { writeBytes(header + info) }
        assertEquals(path, LnkFile.target(f))
        f.delete()
    }

    @Test fun scriptsAreValidShell() {
        for (arch in listOf("amd64", "arm64")) {
            val f = java.io.File.createTempFile("wsys", ".sh").apply { writeText(WindSystem.wineWrapper(arch)) }
            assertEquals(0, ProcessBuilder("sh", "-n", f.path).start().waitFor())
            assertTrue("TMPDIR is unset (Wine 10 aborts with it)", f.readText().contains("unset TMPDIR"))
            f.delete()
        }
        val setup = java.io.File.createTempFile("windsystem-setup", ".sh").apply { writeText(WindSystem.setupScript()) }
        val p = ProcessBuilder("bash", "-n", setup.path).redirectErrorStream(true).start()
        assertEquals(p.inputStream.bufferedReader().readText(), 0, p.waitFor())
        setup.delete()
    }

    @Test fun resolutions() {
        assertEquals(1280 to 720, WindSystem.fixedResolution("1280x720"))
        assertEquals(720 to 1280, WindSystem.fixedResolution("720x1280"))
        assertEquals(1360 to 768, WindSystem.fixedResolution("1366x768")) // X screens are multiples of 8 wide
        assertNull(WindSystem.fixedResolution("phone"))
        assertNull(WindSystem.fixedResolution("12x"))
        assertTrue(WindSystem.RESOLUTIONS.all { WindSystem.fixedResolution(it) != null })
        assertFalse("Openbox mustn't strip every frame", WindSystem.OPENBOX_XML.contains("<decor>no</decor>"))
    }

    @Test fun dosboxSetup() {
        assertTrue(WindSystem.setupScript().contains("dosbox-x"))
        val conf = WindSystem.dosboxConfig("/storage/emulated/0/Android/WindSystem/DOS")
        assertTrue(conf.contains("mount c \"/storage/emulated/0/Android/WindSystem/DOS\""))
        assertTrue(conf.contains("output=surface") && conf.contains("autolock=false"))
        assertTrue(WindSystem.BUILTIN.any { it.name == "DOSBox-X" && it.native })
        assertTrue("DOS" in WindSystem.FOLDERS)
    }

    @Test fun storagePackerTool() {
        val f = java.io.File.createTempFile("droidsystem-pack", ".sh").apply { writeText(org.kdx.shell.droid.StoragePacker.toolScript()) }
        val p = ProcessBuilder("bash", "-n", f.path).redirectErrorStream(true).start()
        assertEquals(p.inputStream.bufferedReader().readText(), 0, p.waitFor())
        f.delete()
        val ids = org.kdx.shell.droid.StoragePacker.PACKS.map { it.id }
        assertEquals(listOf("plasma", "windsystem-c", "flatpak"), ids)
        assertTrue(org.kdx.shell.droid.LinuxLauncher.WRAPPER.contains("droidsystem-pack unpack flatpak"))
    }

    @Test fun autoSavePlan() {
        val P = org.kdx.shell.droid.StoragePacker
        val GB = 1024L * 1024 * 1024; val now = 100 * P.DAY
        fun c(id: String, gb: Double, daysAgo: Int, inUse: Boolean = false, packed: Boolean = false) =
            org.kdx.shell.droid.StoragePacker.Candidate(id, (gb * GB).toLong(), now - daysAgo * P.DAY, inUse, packed)
        val all = listOf(c("plasma", 0.8, 10), c("windsystem-c", 1.4, 5), c("flatpak", 4.0, 1))
        assertEquals("enough space: nothing", emptyList<String>(), P.planAutoSave(3 * GB, 2 * GB, all, now))
        assertEquals("idle ones, biggest first; recently used Flatpak kept", listOf("windsystem-c", "plasma"), P.planAutoSave(GB, 2 * GB, all, now))
        assertEquals("stops once enough is freed", listOf("windsystem-c"), P.planAutoSave((1.5 * GB).toLong(), 2 * GB, all, now))
        assertEquals("nearly full (450 MB): even a just-used part is packed", listOf("flatpak"),
            P.planAutoSave(450L * 1024 * 1024, 2 * GB, listOf(c("flatpak", 0.5, 0)), now))
        assertEquals("never what's open", listOf("plasma"), P.planAutoSave(GB, 2 * GB, listOf(c("windsystem-c", 1.4, 5, inUse = true), c("plasma", 0.8, 10)), now))
        assertEquals("already packed: skipped", emptyList<String>(), P.planAutoSave(GB, 2 * GB, listOf(c("plasma", 0.8, 10, packed = true)), now))
        assertEquals("no room to write the archive: skipped", emptyList<String>(), P.planAutoSave(100L * 1024 * 1024, 2 * GB, listOf(c("flatpak", 4.0, 10)), now))
    }
}
