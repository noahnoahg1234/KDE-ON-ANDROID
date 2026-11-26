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
        // Flatpak apps (installed with KDX's Bazaar or the real one) appear in Plasma's menus too
        "XDG_DATA_DIRS" to "/usr/local/share:/usr/share:/root/.local/share/flatpak/exports/share:/var/lib/flatpak/exports/share",
        // no GPU: Qt Quick's software renderer is much faster than OpenGL on llvmpipe, and no compositing
        "QT_QUICK_BACKEND" to "software", "KWIN_COMPOSE" to "N", "KWIN_X11_NO_SYNC_TO_VBLANK" to "1",
        "QT_QPA_PLATFORMTHEME" to "kde", "QT_AUTO_SCREEN_SCALE_FACTOR" to "0",
        "GTK_USE_PORTAL" to "0",
        // KDE's crash dialog can't do anything useful here
        "KDE_DEBUG" to "1",
    )

    /** KDE background services (kded6 modules) that do nothing useful in DroidSystem; not loading them saves start-up time and memory. */
    val KDED_OFF = listOf(
        "baloosearchmodule", "colorcorrectlocationupdater", "device_automounter", "devicenotifications", "donationmessage",
        "freespacenotifier", "geotimezoned", "kded_touchpad", "keyboard", "ktimezoned", "oom-notifier", "remotenotifier",
        "soliduiserver", "audioshortcutsservice", "smbwatcher", "bluedevil", "networkmanagement", "printmanager",
        "browserintegrationreminder", "plasma-welcome", "kded_bolt", "wacomtablet", "kscreen",
    )

    /**
     * Programs Plasma normally starts at login that aren't needed here: the file indexer, accessibility helper,
     * polkit agent (no polkit daemon runs), a second PulseAudio (KDX already runs one), GTK menu proxy, crash
     * session restore and Discover's update checker. Hidden=true in KDX's config dir (searched before /etc/xdg).
     */
    val AUTOSTART_OFF = listOf(
        "baloo_file", "kaccess", "polkit-kde-authentication-agent-1", "pulseaudio", "gmenudbusmenuproxy",
        "org.kde.plasma-fallback-session-restore", "org.kde.discover.notifier", "geoclue-demo-agent", "org.kde.kgpg",
        "kdeconnectd", "org.kde.kdeconnect.daemon", "at-spi-dbus-bus", "xdg-user-dirs", "spice-vdagent",
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
        // background services that only make sense on a PC (disks, Wi-Fi, location, touchpads, time zones…)
        "kded6rc" to KDED_OFF.joinToString("") { "[Module-$it]\nautoload=false\n\n" },
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
        val autostart = File(dir, "autostart").apply { mkdirs() }
        AUTOSTART_OFF.forEach { File(autostart, "$it.desktop").writeText("[Desktop Entry]\nType=Application\nName=$it\nHidden=true\n") }
        File(sys.root, "root/.config").mkdirs()
    }

    /** The real Bazaar app store on Flathub. */
    const val BAZAAR_ID = "io.github.kolunmi.Bazaar"

    fun bazaarInstalled(sys: DebianSystem) = File(sys.root, "root/.local/share/flatpak/app/$BAZAAR_ID").isDirectory

    /**
     * Installs the real Bazaar (a GTK app store for Flathub; needs the GNOME runtime, ~300 MB the first time) into
     * DroidSystem's per-user Flatpak installation, and puts it on Plasma's desktop. Inside DroidSystem, its "Open"
     * buttons and Plasma's menu start apps through KDX (see LinuxLauncher).
     */
    fun bazaarSetupScript(): String = """#!/bin/sh
# Installs the real Bazaar app store (Flathub) for KDE Plasma and DroidSystem.
set -e
flatpak-setup >/dev/null
echo "Installing Bazaar from Flathub (about 13 MB, plus the GNOME runtime the first time)…"
flatpak install -y --noninteractive --user flathub $BAZAAR_ID
# on Plasma's desktop too
mkdir -p /root/Desktop
f=/root/.local/share/flatpak/exports/share/applications/$BAZAAR_ID.desktop
if [ -f "${'$'}f" ]; then cp "${'$'}f" /root/Desktop/; chmod +x /root/Desktop/$BAZAAR_ID.desktop; fi
echo "Bazaar is installed. Open it from KDE Plasma's menu or desktop."
"""

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
# skip manual-page indexing (no manuals are installed, see /etc/dpkg/dpkg.cfg.d/droidsystem)
echo "man-db man-db/auto-update boolean false" | debconf-set-selections
# -q: no constantly redrawn progress bar (each redraw is a new line for Konsole to draw)
apt-get -q update
# Ubuntu 26.04+ ships Plasma's X11 session (startplasma-x11) as its own package; Debian keeps it in plasma-workspace.
# DroidSystem needs X11 (Wayland can't run under proot).
X11_SESSION=${'$'}(apt-cache show plasma-session-x11 >/dev/null 2>&1 && echo plasma-session-x11)
apt-get -q install -y --no-install-recommends -o Dpkg::Progress-Fancy=0 ${PACKAGES.joinToString(" ")} ${'$'}X11_SESSION || { dpkg --configure -a; apt-get -q install -y -f --no-install-recommends; }
# build KDE's application database now, so the first start doesn't have to
echo "Preparing KDE's app database…"
XDG_DATA_DIRS=/usr/local/share:/usr/share:/root/.local/share/flatpak/exports/share:/var/lib/flatpak/exports/share XDG_CONFIG_DIRS=/$DEFAULTS_DIR:/etc/xdg kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
fc-cache >/dev/null 2>&1 || true
# the real Bazaar app store (Flathub), for Plasma's menu and desktop; Plasma works without it if this fails
bazaar-setup || echo "(The Bazaar app store couldn't be installed now. Run bazaar-setup later to try again.)"
echo
echo "KDE Plasma is installed. Open it from Kickoff → KDE Plasma."
"""
}
