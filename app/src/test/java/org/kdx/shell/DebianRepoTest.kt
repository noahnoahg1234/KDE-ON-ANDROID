package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.DebianRepo

class DebianRepoTest {
    private val index = """
Package: base-files
Version: 13
Priority: required
Essential: yes
Filename: pool/b/base-files.deb
SHA256: aa
Size: 10

Package: apt
Version: 3.0
Priority: important
Depends: libapt (>= 3.0), gpgv | gpgv2, debian-archive-keyring
Filename: pool/a/apt.deb
Size: 100

Package: libapt
Version: 3.0
Priority: optional
Depends: libc6:any (>= 2.38)
Filename: pool/l/libapt.deb
Size: 50

Package: libc6
Version: 2.41
Priority: optional
Filename: pool/l/libc6.deb
Size: 500

Package: gpgv-alt
Version: 1
Priority: optional
Provides: gpgv2
Filename: pool/g/gpgv-alt.deb

Package: gpgv
Version: 2
Priority: optional
Pre-Depends: libc6
Filename: pool/g/gpgv.deb

Package: debian-archive-keyring
Version: 2025
Priority: important
Filename: pool/d/keyring.deb

Package: unrelated
Version: 1
Priority: optional
Filename: pool/u/unrelated.deb
""".trimIndent()

    private fun repo() = DebianRepo(arch = "arm64").apply { parse(index.lineSequence()) }

    @Test fun parsesDependencies() {
        assertEquals(listOf(listOf("libapt"), listOf("gpgv", "gpgv2"), listOf("debian-archive-keyring")), repo().packages["apt"]!!.depends)
        assertEquals(listOf(listOf("libc6")), DebianRepo.parseDeps("libc6:any (>= 2.38)"))
    }

    @Test fun minbaseClosure() {
        val names = repo().minbase(extra = emptyList()).map { it.name }.toSet()
        assertEquals(setOf("base-files", "apt", "libapt", "libc6", "gpgv", "debian-archive-keyring"), names)
        assertFalse("unrelated" in names); assertFalse("gpgv-alt" in names)
    }

    @Test fun virtualPackages() {
        assertEquals("gpgv-alt", repo().resolve("gpgv2")!!.name)
    }

    @Test fun abiMapping() {
        assertEquals("arm64", DebianRepo.archForAbi("arm64-v8a"))
        assertEquals("armhf", DebianRepo.archForAbi("armeabi-v7a"))
        assertEquals("amd64", DebianRepo.archForAbi("x86_64"))
    }
}
