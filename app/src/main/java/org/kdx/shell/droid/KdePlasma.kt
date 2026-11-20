package org.kdx.shell.droid

import java.io.File

/**
 * Real KDE Plasma 6 (Debian's packages) running inside DroidSystem on its own X display: KWin, plasmashell, the
 * Plasma panel and Kickoff, Dolphin, Konsole, Kate and System Settings – the actual KDE software, not KDX's look-alike.
 *
 * Plasma normally runs on a PC with systemd, a GPU and a login manager. Here it gets: no systemd boot, no compositing
 * and Qt Quick's software renderer (fast without a GPU), touch ("tablet") mode for the phone's screen, and no screen
 * locker, file indexer or splash screen. These defaults live in their own config directory, so anything the user
 * changes in Plasma's System Settings still wins.
 */
object KdePlasma {
    /** The packages for a working Plasma desktop with KDE's core apps (no recommends: ~320 MB download, 1.2 GB installed). */
    val PACKAGES = listOf(
        "plasma-desktop", "plasma-workspace", "kwin-x11", "systemsettings", "dolphin", "konsole", "kate", "ark",
        "gwenview", "okular", "kcalc", "kde-spectacle", "plasma-systemmonitor", "kinfocenter", "breeze", "breeze-gtk-theme",
        "breeze-icon-theme", "kde-config-gtk-style", "plasma-pa",
        "fonts-noto-core", "fonts-noto-color-emoji", "dbus-x11", "xdg-utils",
    )

    fun isInstalled(sys: DebianSystem) = sys.hasCommand("usr/bin/startplasma-x11")

    /** Where KDX keeps its Plasma defaults inside DroidSystem (searched before /etc/xdg). */
    const val DEFAULTS_DIR = "usr/share/kdx/plasma-xdg"

    val env = mapOf(
        "XDG_SESSION_TYPE" to "x11", "XDG_CURRENT_DESKTOP" to "KDE", "XDG_SESSION_DESKTOP" to "KDE",
        "DESKTOP_SESSION" to "plasma", "KDE_FULL_SESSION" to "true", "KDE_SESSION_VERSION" to "6",
        "XDG_CONFIG_DIRS" to "/$DEFAULTS_DIR:/etc/xdg",
        "XDG_DATA_DIRS" to "/usr/local/share:/usr/share",
        // no GPU: Qt Quick's software renderer is much faster than OpenGL on llvmpipe, and no compositing
        "QT_QUICK_BACKEND" to "software", "KWIN_COMPOSE" to "N", "KWIN_X11_NO_SYNC_TO_VBLANK" to "1",
        "QT_QPA_PLATFORMTHEME" to "kde", "QT_AUTO_SCREEN_SCALE_FACTOR" to "0",
        "GTK_USE_PORTAL" to "0",
        // KDE's crash dialog can't do anything useful here
        "KDE_DEBUG" to "1",
    )

    /** Plasma's default settings for running on a phone inside DroidSystem. */
    val DEFAULTS = mapOf(
        // started by startplasma-x11 directly, not as systemd user units (there is no systemd)
        "startkderc" to "[General]\nsystemdBoot=false\n",
        // no compositing (no GPU); touch-friendly tablet mode (bigger controls, on-screen keyboard behaviour)
        "kwinrc" to "[Compositing]\nEnabled=false\n\n[Input]\nTabletMode=on\n\n[Plugins]\nblurEnabled=false\ncontrastEnabled=false\n",
        "ksplashrc" to "[KSplash]\nEngine=none\nTheme=None\n",
        "kscreenlockerrc" to "[Daemon]\nAutolock=false\nLockOnResume=false\nTimeout=0\n",
        "baloofilerc" to "[Basic Settings]\nIndexing-Enabled=false\n",
        "ksmserverrc" to "[General]\nloginMode=emptySession\nconfirmLogout=false\n",
        // snappier without a GPU
        "kdeglobals" to "[KDE]\nAnimationDurationFactor=0.5\nSingleClick=true\n",
        "powerdevilrc" to "[AC][Display]\nTurnOffDisplayWhenIdle=false\nDimDisplayWhenIdle=false\n",
        "kded6rc" to "[Module-baloosearchmodule]\nautoload=false\n",
    )

    /**
     * D-Bus services that can't work inside DroidSystem and would make Plasma wait for timeouts at every step: the
     * splash screen (turned off), and the desktop portal, whose document store needs FUSE (Android apps can't mount).
     * Replacing them with services that fail at once lets Plasma move on immediately. /usr/local/share comes before
     * /usr/share in D-Bus's search path.
     */
    val DISABLED_SERVICES = listOf("org.kde.KSplash", "org.freedesktop.portal.Desktop", "org.freedesktop.portal.Documents")

    /** Writes [DEFAULTS] and the D-Bus overrides into DroidSystem (cheap; done before every start so KDX updates apply). */
    fun writeDefaults(sys: DebianSystem) {
        val dir = File(sys.root, DEFAULTS_DIR).apply { mkdirs() }
        DEFAULTS.forEach { (name, text) -> File(dir, name).writeText(text) }
        val services = File(sys.root, "usr/local/share/dbus-1/services").apply { mkdirs() }
        DISABLED_SERVICES.forEach { File(services, "$it.service").writeText("[D-BUS Service]\nName=$it\nExec=/bin/false\n") }
        File(sys.root, "root/.config").mkdirs()
    }

    /** The command that runs a whole Plasma session on the current display. */
    const val COMMAND = "startplasma-x11"

    /** Shell script for `plasma-setup` (Konsole): installs Plasma, working around what doesn't apply inside proot. */
    fun setupScript(): String = """#!/bin/sh
# Installs the real KDE Plasma 6 desktop (Debian's packages) into DroidSystem.
set -e
command -v Xvnc >/dev/null 2>&1 || display-setup
echo "Installing KDE Plasma – about 320 MB to download, 1.2 GB on disk. This takes a while."
# systemd-tmpfiles is only useful when booting with systemd; under proot it can crash package setup
for b in /usr/bin/systemd-tmpfiles; do
  if [ ! -e "${'$'}b.real" ]; then dpkg-divert --local --rename --divert "${'$'}b.real" --add "${'$'}b" >/dev/null 2>&1 || true; fi
  ln -sf /bin/true "${'$'}b"
done
# no systemd user manager here: don't let D-Bus pretend one can be started (Plasma would try to use it)
f=/usr/share/dbus-1/services/org.freedesktop.systemd1.service
dpkg-divert --local --rename --divert "${'$'}f.disabled" --add "${'$'}f" >/dev/null 2>&1 || true
apt-get update
apt-get install -y --no-install-recommends ${PACKAGES.joinToString(" ")} || { dpkg --configure -a; apt-get install -y -f --no-install-recommends; }
echo
echo "KDE Plasma is installed. Open it from Kickoff → KDE Plasma."
"""
}
