#!/bin/bash
# Unpacks Samba into ~/smbsync-test/root without installing anything system-wide.
set -u
T=~/smbsync-test
mkdir -p "$T/debs" "$T/root"
cd "$T/debs"
ls samba_*.deb >/dev/null 2>&1 || apt-get download samba samba-common-bin samba-libs
extract() { for d in *.deb; do dpkg -x "$d" "$T/root"; done; }
extract
libpath() { find "$T/root" -type d \( -name 'x86_64-linux-gnu' -o -name samba -o -name private \) | tr '\n' ':'; }
SMBD=$(find "$T/root" -type f -name smbd -path '*sbin*' | head -1)
echo "smbd: $SMBD"
for round in 1 2 3 4 5 6; do
  missing=$(LD_LIBRARY_PATH="$(libpath)" ldd "$SMBD" 2>/dev/null | grep 'not found' | awk '{print $1}' | sort -u)
  [ -z "$missing" ] && break
  echo "round $round missing: $missing"
  pkgs=""
  for lib in $missing; do
    p=$(apt-file search -l "$lib" 2>/dev/null | head -1)
    if [ -z "$p" ]; then
      case "$lib" in
        libldb.so*) p=libldb2 ;;
        libtalloc.so*) p=libtalloc2 ;;
        libtdb.so*) p=libtdb1 ;;
        libtevent.so*) p=libtevent0t64 ;;
        libwbclient.so*) p=libwbclient0 ;;
        libcups.so*) p=libcups2t64 ;;
        libtirpc.so*) p=libtirpc3t64 ;;
        liburing.so*) p=liburing2 ;;
        libndr*.so*) p=libndr6 ;;
        libbsd.so*) p=libbsd0 ;;
        libpopt.so*) p=libpopt0 ;;
        libldap*.so*|liblber*.so*) p=libldap2 ;;
        libjansson.so*) p=libjansson4 ;;
        libavahi-client.so*) p=libavahi-client3 ;;
        libavahi-common.so*) p=libavahi-common3 ;;
        libdbus-1.so*) p=libdbus-1-3 ;;
        libsasl2.so*) p=libsasl2-2 ;;
        libicu*.so*) p=$(apt-cache search --names-only '^libicu[0-9]+$' | awk '{print $1}' | tail -1) ;;
        liblmdb.so*) p=liblmdb0 ;;
        libgpgme.so*) p=libgpgme11t64 ;;
        libarchive.so*) p=libarchive13t64 ;;
        libsmbconf.so*|libsamba*.so*|libdcerpc*.so*|libnetapi*.so*) p=samba-libs ;;
        *) p="" ;;
      esac
    fi
    [ -n "$p" ] && pkgs="$pkgs $p" || echo "  no package known for $lib"
  done
  pkgs=$(echo $pkgs | tr ' ' '\n' | sort -u | tr '\n' ' ')
  echo "  downloading: $pkgs"
  [ -z "$pkgs" ] && break
  apt-get download $pkgs 2>&1 | grep -E '^E:|Err'
  extract
done
echo "--- still missing:"
LD_LIBRARY_PATH="$(libpath)" ldd "$SMBD" | grep 'not found' || echo none
libpath > "$T/libpath.txt"
echo "$SMBD" > "$T/smbd.txt"
LD_LIBRARY_PATH="$(libpath)" "$SMBD" --version
