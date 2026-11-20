#!/bin/bash
# Test helper: accepts the proot command line KDX builds (-r ROOT -b HOST:GUEST -w CWD ...) and runs it with bind
# mounts + chroot instead. For root-capable Linux test machines whose proot is too old for Qt 6 (Plasma); phones use
# Termux's proot.  Use with: -Pkdx.proot=$PWD/tools/chroot-proot.sh
root=; cwd=/; binds=()
while [ $# -gt 0 ]; do
  case "$1" in
    -0|--kill-on-exit|--link2symlink|--sysvipc) shift ;;
    -r) root=$2; shift 2 ;;
    -b) binds+=("$2"); shift 2 ;;
    -w) cwd=$2; shift 2 ;;
    *) break ;;
  esac
done
for b in "${binds[@]}"; do
  host=${b%%:*}; guest=${b#*:}; [ "$b" = "$host" ] && guest=$host
  t="$root$guest"
  if [ -d "$host" ]; then mkdir -p "$t"; else mkdir -p "$(dirname "$t")"; [ -e "$t" ] || touch "$t"; fi
  mountpoint -q "$t" 2>/dev/null || mount --bind "$host" "$t" 2>/dev/null
done
exec chroot "$root" /bin/sh -c 'cd "$0" 2>/dev/null; exec "$@"' "$cwd" "$@"
