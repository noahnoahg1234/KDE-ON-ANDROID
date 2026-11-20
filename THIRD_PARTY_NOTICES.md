# Third-party software in KDX

KDX bundles these programs, unmodified except for the library-name and RUNPATH fix-ups noted below, from the
[Termux](https://termux.dev) package repository (`packages.termux.dev`, package versions listed):

| File in APK (`lib/<abi>/`) | Origin | License | Source |
|---|---|---|---|
| `libproot.so`, `libproot-loader.so`, `libproot-loader32.so` | `proot` 5.1.107.102 (`bin/proot`, `libexec/proot/loader*`) | GPL-2.0-or-later | https://github.com/termux/proot |
| `libtalloc.so` | `libtalloc` 2.5.0 | LGPL-3.0-or-later | https://talloc.samba.org |
| `libandroid-shmem.so` | `libandroid-shmem` 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem |

Fix-ups (made with `patchelf`): `libproot.so` links `libtalloc.so` instead of `libtalloc.so.2`; Termux's RUNPATH was
removed from all three; `libtalloc.so`'s soname was set to `libtalloc.so`. Android only installs native files named
`lib*.so`, which is why the programs carry these names.

DroidSystem 2 downloads Debian packages from `deb.debian.org` on the user's device when the Linux system is turned
on; each package keeps its own license under `/usr/share/doc` inside DroidSystem.

The ad blocker bundles a domain list derived from [StevenBlack/hosts](https://github.com/StevenBlack/hosts)
(unified hosts: adware + malware, MIT License; `app/src/main/assets/adblock/blocklist.bin`, gzip). "Update list" in the
app downloads the current version from the same project.

Java libraries: Apache Commons Compress (Apache-2.0), XZ for Java (0BSD), AndroidX / Jetpack Compose (Apache-2.0).
