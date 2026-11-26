package org.kdx.shell.droid

/**
 * The Linux distribution DroidSystem 2 is built from. Both use the same package format and tools (dpkg/apt), so
 * DroidSystem builds either one the same way, straight from the official package servers.
 */
enum class Distro(
    val label: String,
    val suite: String,
    val version: String,
    val keyringPackage: String,
    val keyring: String,
) {
    /** Ubuntu 26.04 LTS – the default for new installs. */
    UBUNTU("Ubuntu", "resolute", "26.04 LTS", "ubuntu-keyring", "/usr/share/keyrings/ubuntu-archive-keyring.gpg"),
    DEBIAN("Debian", "trixie", "13", "debian-archive-keyring", "/usr/share/keyrings/debian-archive-keyring.gpg");

    val fullName get() = "$label $version"

    /** Ubuntu serves ARM (and RISC-V) from its "ports" server, PCs from the main archive. */
    private fun isPorts(arch: String) = arch !in setOf("amd64", "i386")

    fun mirror(arch: String): String = when (this) {
        DEBIAN -> "https://deb.debian.org/debian"
        UBUNTU -> if (isPorts(arch)) "https://ports.ubuntu.com/ubuntu-ports" else "https://archive.ubuntu.com/ubuntu"
    }

    /** The packages DroidSystem's base system adds to the minimal set (the distribution's keyring for apt). */
    val baseExtra get() = listOf("ca-certificates", keyringPackage, "less", "nano", "procps", "sensible-utils")

    /**
     * apt sources (deb822 format). On an ARM phone, Ubuntu's x86 packages – which WindSystem runs with box64 – come
     * from a different server than the ARM ones, so each server is limited to its architectures.
     */
    fun aptSources(arch: String): String = when (this) {
        DEBIAN ->
            "Types: deb\nURIs: https://deb.debian.org/debian\nSuites: trixie trixie-updates\nComponents: main contrib non-free non-free-firmware\n" +
                "Signed-By: $keyring\n\n" +
                "Types: deb\nURIs: https://security.debian.org/debian-security\nSuites: trixie-security\nComponents: main contrib non-free non-free-firmware\n" +
                "Signed-By: $keyring\n"
        UBUNTU -> {
            val comps = "main restricted universe multiverse"
            val suites = "$suite $suite-updates $suite-backports"
            if (isPorts(arch))
                "Types: deb\nURIs: https://ports.ubuntu.com/ubuntu-ports\nSuites: $suites $suite-security\nComponents: $comps\n" +
                    "Architectures: arm64 armhf riscv64\nSigned-By: $keyring\n\n" +
                    "Types: deb\nURIs: https://archive.ubuntu.com/ubuntu\nSuites: $suites\nComponents: $comps\n" +
                    "Architectures: amd64 i386\nSigned-By: $keyring\n\n" +
                    "Types: deb\nURIs: https://security.ubuntu.com/ubuntu\nSuites: $suite-security\nComponents: $comps\n" +
                    "Architectures: amd64 i386\nSigned-By: $keyring\n"
            else
                "Types: deb\nURIs: https://archive.ubuntu.com/ubuntu\nSuites: $suites\nComponents: $comps\nSigned-By: $keyring\n\n" +
                    "Types: deb\nURIs: https://security.ubuntu.com/ubuntu\nSuites: $suite-security\nComponents: $comps\nSigned-By: $keyring\n"
        }
    }

    /** /etc/os-release: DroidSystem, based on this distribution (programs that check for Ubuntu/Debian see it). */
    fun osRelease(): String = when (this) {
        DEBIAN -> "PRETTY_NAME=\"DroidSystem 2.0 (Debian trixie)\"\nNAME=\"DroidSystem\"\nVERSION_ID=\"2.0\"\nVERSION=\"2.0 (Breeze)\"\n" +
            "VERSION_CODENAME=trixie\nID=droidsystem\nID_LIKE=debian\nHOME_URL=\"https://github.com/noahnoahg1234/kde-on-android\"\n"
        UBUNTU -> "PRETTY_NAME=\"DroidSystem 2.0 (Ubuntu $version)\"\nNAME=\"DroidSystem\"\nVERSION_ID=\"2.0\"\nVERSION=\"2.0 (Breeze)\"\n" +
            "VERSION_CODENAME=$suite\nUBUNTU_CODENAME=$suite\nID=droidsystem\nID_LIKE=\"ubuntu debian\"\n" +
            "HOME_URL=\"https://github.com/noahnoahg1234/kde-on-android\"\n"
    }

    /** "based on Ubuntu 26.04 LTS" / "based on Debian trixie", for the welcome text. */
    val basedOn get() = if (this == DEBIAN) "Debian trixie" else "Ubuntu $version"

    companion object {
        /** Reads the distribution from DroidSystem's install marker ("<suite> <arch> <date>"); old installs are Debian. */
        fun fromMarker(marker: String?): Distro? {
            val suite = marker?.trim()?.substringBefore(' ') ?: return null
            return entries.firstOrNull { it.suite == suite }
        }
    }
}
