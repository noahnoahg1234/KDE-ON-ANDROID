package org.kdx.shell.droid

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes a file or a whole directory tree WITHOUT following symbolic links: a link is removed, never what it
 * points to. (Kotlin's File.deleteRecursively() follows links into other directories – fatal for a Linux root
 * file system full of links such as lib -> usr/lib.)
 */
fun File.deleteTree(): Boolean {
    val root = toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
    return try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() { // walkFileTree never follows links by default
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                dir.toFile().apply { setReadable(true); setWritable(true); setExecutable(true) } // read-only dirs from packages
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
            }
        })
        true
    } catch (_: IOException) {
        false
    }
}
