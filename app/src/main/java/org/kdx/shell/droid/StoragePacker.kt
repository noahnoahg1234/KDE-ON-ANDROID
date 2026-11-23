package org.kdx.shell.droid

/**
 * Storage packing for DroidSystem. Android gives apps no compressed file systems, so space is saved two ways:
 *  - clean-up of things that are safe to delete (downloaded packages, caches, docs left by older installs…)
 *  - packs: big parts that aren't used all the time (KDE Plasma, WindSystem's drive C:, Flatpak apps) are compressed
 *    into one archive and their files removed; they're unpacked automatically the next time they're opened.
 * The work is done by the `droidsystem-pack` tool inside DroidSystem (also usable in Konsole).
 */
object StoragePacker {
    /** Something that can be cleaned up: [paths] are measured; [command] deletes it. */
    data class Cleanup(val id: String, val title: String, val description: String, val paths: List<String>, val command: String)

    val CLEANUPS = listOf(
        Cleanup("apt-cache", "Downloaded package files", "Installers apt keeps after installing. Safe to delete.",
            listOf("/var/cache/apt/archives"), "apt-get clean"),
        Cleanup("apt-lists", "Package lists", "Debian's catalogue; downloaded again before the next install.",
            listOf("/var/lib/apt/lists"), "find /var/lib/apt/lists -maxdepth 1 -type f ! -name lock -delete"),
        Cleanup("docs", "Translations, manuals and docs", "Left by installs made before DroidSystem skipped them (it runs in English).",
            listOf("/usr/share/doc", "/usr/share/man", "/usr/share/locale", "/usr/share/help", "/usr/share/info"),
            "find /usr/share/doc -type f ! -name copyright -delete; find /usr/share/doc -type d -empty -delete; " +
                "find /usr/share/man /usr/share/info /usr/share/help -mindepth 1 -delete 2>/dev/null; " +
                "find /usr/share/locale -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} + 2>/dev/null; true"),
        Cleanup("caches", "Caches", "Temporary files programs keep to start faster; they rebuild what they need.",
            listOf("/root/.cache", "/var/cache/man", "/var/cache/debconf"),
            "find /root/.cache -mindepth 1 -maxdepth 1 ! -name 'ksycoca6*' -exec rm -rf {} + 2>/dev/null; " +
                "rm -rf /var/cache/man/* /var/cache/debconf/*-old 2>/dev/null; true"),
        Cleanup("logs", "System logs", "Old log files of Debian's package tools.",
            listOf("/var/log"), "find /var/log -type f \\( -name '*.gz' -o -name '*.[0-9]' -o -name '*.old' \\) -delete; " +
                "find /var/log -type f -name '*.log' -exec truncate -s 0 {} +; true"),
        Cleanup("flatpak-unused", "Unused Flatpak runtimes", "Runtimes and extensions no installed app needs any more.",
            listOf(), "command -v flatpak >/dev/null && flatpak uninstall --user --unused -y --noninteractive; true"),
    )

    /** A part of DroidSystem that can be packed into an archive and unpacked on demand. */
    data class Pack(val id: String, val title: String, val description: String, val paths: List<String>)

    val PACKS = listOf(
        Pack("plasma", "KDE Plasma", "The real KDE Plasma desktop and KDE apps. Unpacked automatically when you open KDE Plasma.",
            listOf()),
        Pack("windsystem-c", "WindSystem's drive C:", "Installed Windows programs and their settings. Unpacked automatically when you open WindSystem.",
            listOf(WindSystem.PREFIX)),
        Pack("flatpak", "Flatpak apps", "Apps from Bazaar (Flathub) and their runtimes. Unpacked when you open a Flatpak app or Bazaar installs one.",
            listOf("/root/.local/share/flatpak")),
    )

    const val PACK_DIR = "var/lib/droidsystem/packs"

    /** Makes sure the tool is there (DroidSystems from before it existed get it here as well). */
    fun ensureTool(sys: DebianSystem) {
        val f = java.io.File(sys.root, "usr/local/bin/droidsystem-pack")
        if (runCatching { f.readText() }.getOrNull() != toolScript()) { f.parentFile?.mkdirs(); f.writeText(toolScript()); f.setExecutable(true, false) }
    }

    fun isPacked(sys: DebianSystem, id: String) =
        java.io.File(sys.root, PACK_DIR).listFiles()?.any { it.name.startsWith("$id.tar.") && !it.name.endsWith(".part") } == true

    /** Size of the archive for a packed [id], in bytes (0 if not packed). */
    fun packedSize(sys: DebianSystem, id: String): Long =
        java.io.File(sys.root, PACK_DIR).listFiles()?.firstOrNull { it.name.startsWith("$id.tar.") && !it.name.endsWith(".part") }?.length() ?: 0

    /** Sizes (bytes) of each clean-up item and each pack's current files, measured inside DroidSystem in one go. */
    fun scan(sys: DebianSystem): Map<String, Long> {
        val script = buildString {
            CLEANUPS.filter { it.paths.isNotEmpty() }.forEach { c -> append("echo \"${c.id} \$(du -scb ${c.paths.joinToString(" ")} 2>/dev/null | tail -1 | cut -f1)\"\n") }
            PACKS.filter { it.paths.isNotEmpty() }.forEach { p -> append("echo \"pack:${p.id} \$(du -scb ${p.paths.joinToString(" ")} 2>/dev/null | tail -1 | cut -f1)\"\n") }
            append("echo \"pack:plasma \$(droidsystem-pack size plasma 2>/dev/null || echo 0)\"\n")
            // DroidSystem's own folders (not "/": that would follow binds into the phone's shared storage)
            append("echo \"total \$(du -scb /usr /var /opt /etc /root /srv 2>/dev/null | tail -1 | cut -f1)\"\n")
        }
        return sys.query(script).second.lines().mapNotNull { l ->
            val (k, v) = l.trim().split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            v.trim().toLongOrNull()?.let { k to it }
        }.toMap()
    }

    /** `droidsystem-pack`: packs, unpacks and measures packs (see [PACKS]). */
    fun toolScript(): String = """#!/bin/bash
# droidsystem-pack: packs parts of DroidSystem that aren't used all the time into compressed archives (saving
# space – Android has no compressed file systems for apps), and unpacks them again when they're needed.
#   droidsystem-pack pack <name>      droidsystem-pack unpack <name>      droidsystem-pack status|size <name>
# names: plasma (KDE Plasma's own packages), windsystem-c (WindSystem's drive C:), flatpak (Flatpak apps)
set -e
P=/$PACK_DIR
mkdir -p "${'$'}P"
cmd=${'$'}1; name=${'$'}2
existing() { ls "${'$'}P/${'$'}name".tar.zst "${'$'}P/${'$'}name".tar.xz 2>/dev/null | head -1; }
dir_of() { case "${'$'}1" in windsystem-c) echo ${WindSystem.PREFIX} ;; flatpak) echo /root/.local/share/flatpak ;; esac; }
# KDE Plasma: the packages only Plasma needs (what removing Plasma would remove), and their files
plasma_pkgs() { apt-get -s purge --autoremove ${KdePlasma.PACKAGES.joinToString(" ")} 2>/dev/null | awk '/^Purg /{print ${'$'}2}' | sort -u; }
plasma_files() { plasma_pkgs | xargs -r dpkg -L 2>/dev/null | while IFS= read -r f; do [ -f "${'$'}f" ] || [ -L "${'$'}f" ] && echo "${'$'}{f#/}"; done | sort -u; }
case "${'$'}cmd" in
  status)
    if [ -n "${'$'}(existing)" ]; then echo packed; else echo unpacked; fi ;;
  size)
    if [ "${'$'}name" = plasma ]; then plasma_files | tr '\n' '\0' | (cd / && du -cb --files0-from=- 2>/dev/null) | tail -1 | cut -f1
    else d=${'$'}(dir_of "${'$'}name"); du -sb "${'$'}d" 2>/dev/null | cut -f1; fi ;;
  pack)
    [ -z "${'$'}(existing)" ] || { echo "${'$'}name is already packed."; exit 0; }
    command -v zstd >/dev/null 2>&1 || apt-get -q install -y --no-install-recommends zstd </dev/null >/dev/null 2>&1 || true
    if command -v zstd >/dev/null 2>&1; then ext=zst; comp="zstd -T0 -12 -q"; else ext=xz; comp="xz -T0 -6"; fi
    list=${'$'}P/${'$'}name.list
    if [ "${'$'}name" = plasma ]; then
      plasma_files > "${'$'}list"; plasma_pkgs > "${'$'}P/plasma.pkgs"
      [ -s "${'$'}list" ] || { echo "KDE Plasma isn't installed."; rm -f "${'$'}list"; exit 1; }
    else
      d=${'$'}(dir_of "${'$'}name"); [ -n "${'$'}d" ] && [ -e "${'$'}d" ] || { echo "Nothing to pack for ${'$'}name."; exit 1; }
      echo "${'$'}{d#/}" > "${'$'}list"
    fi
    echo "Packing ${'$'}name…"
    tar -C / -cf - --files-from="${'$'}list" | ${'$'}comp > "${'$'}P/${'$'}name.tar.${'$'}ext.part"
    # check the archive before deleting anything
    tar -I "${'$'}{comp%% *}" -tf "${'$'}P/${'$'}name.tar.${'$'}ext.part" >/dev/null
    mv "${'$'}P/${'$'}name.tar.${'$'}ext.part" "${'$'}P/${'$'}name.tar.${'$'}ext"
    if [ "${'$'}name" = plasma ]; then
      (cd / && xargs -r -d '\n' rm -f < "${'$'}list")
      xargs -r apt-mark hold < "${'$'}P/plasma.pkgs" >/dev/null 2>&1 || true   # no upgrades while the files are packed
    else
      rm -rf "/${'$'}(cat "${'$'}list")"
    fi
    echo "Packed ${'$'}name: ${'$'}(du -h "${'$'}P/${'$'}name.tar.${'$'}ext" | cut -f1)." ;;
  unpack)
    f=${'$'}(existing); [ -n "${'$'}f" ] || { echo "${'$'}name isn't packed."; exit 0; }
    case "${'$'}f" in *.zst) dec="zstd -d -q -c" ;; *) dec="xz -d -c" ;; esac
    echo "Unpacking ${'$'}name…"
    ${'$'}dec "${'$'}f" | tar -C / -xf -
    [ "${'$'}name" = plasma ] && [ -f "${'$'}P/plasma.pkgs" ] && xargs -r apt-mark unhold < "${'$'}P/plasma.pkgs" >/dev/null 2>&1 || true
    rm -f "${'$'}f" "${'$'}P/${'$'}name.list" "${'$'}P/${'$'}name.pkgs"
    echo "Unpacked ${'$'}name." ;;
  *) echo "usage: droidsystem-pack pack|unpack|status|size plasma|windsystem-c|flatpak"; exit 2 ;;
esac
"""
}
