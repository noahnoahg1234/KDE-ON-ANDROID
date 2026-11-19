package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.deleteTree
import java.io.File
import java.nio.file.Files

class FileOpsTest {
    @Test fun deleteTreeNeverFollowsLinks() {
        val base = Files.createTempDirectory("kdx-del").toFile()
        val keep = File(base, "outside/lib").apply { mkdirs() }
        File(keep, "libc.so.6").writeText("precious")
        val tree = File(base, "tree/sub").apply { mkdirs() }
        File(tree, "a.txt").writeText("x")
        Files.createSymbolicLink(File(tree, "lib").toPath(), keep.toPath())           // link to a directory
        Files.createSymbolicLink(File(tree, "file-link").toPath(), File(keep, "libc.so.6").toPath()) // link to a file
        Files.createSymbolicLink(File(tree, "dangling").toPath(), File(base, "nope").toPath())
        File(tree, "ro").apply { mkdirs(); File(this, "f").writeText("y"); setWritable(false) }
        assertTrue(File(base, "tree").deleteTree())
        assertFalse(File(base, "tree").exists())
        assertEquals("precious", File(keep, "libc.so.6").readText())
    }

    @Test fun deleteTreeOnMissingAndSingleFiles() {
        val base = Files.createTempDirectory("kdx-del2").toFile()
        assertTrue(File(base, "missing").deleteTree())
        val f = File(base, "f").apply { writeText("z") }
        assertTrue(f.deleteTree()); assertFalse(f.exists())
    }
}
