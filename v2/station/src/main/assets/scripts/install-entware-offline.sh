#!/bin/sh
# Official Entware packages are supplied by AliceBridge, without network downloads.
set -eu
base=$1
arch=$2
case "$arch" in aarch64-k3.10) pkgarch=aarch64-3.10;; armv7sf-k3.2) pkgarch=armv7-3.2;; *) echo 'Unsupported Entware architecture'; exit 1;; esac
root=/data/opt/entware
mkdir -p "$root"
if [ ! -L /opt ]; then
    if [ -e /opt ] && { [ ! -d /opt ] || [ -n "$(ls -A /opt)" ]; }; then
        echo '/opt занят; существующие файлы сохранены'; exit 1
    fi
    was_ro=0
    awk '$2=="/" && $4 ~ /(^|,)ro(,|$)/ {found=1} END {exit !found}' /proc/mounts && was_ro=1
    if [ "$was_ro" = 1 ]; then mount -o remount,rw /; fi
    restore(){ if [ "$was_ro" = 1 ]; then sync; mount -o remount,ro /; fi; }
    trap restore EXIT
    [ ! -d /opt ] || rmdir /opt
    ln -s "$root" /opt
    restore
    trap - EXIT
fi
mkdir -p /opt/bin /opt/etc /opt/lib/opkg /opt/tmp /opt/var/lock /opt/var/opkg-lists
chmod 1777 /opt/tmp
cat > "$base/offline.conf" <<EOF
dest root /
dest ram /opt/tmp
lists_dir ext /opt/var/opkg-lists
option tmp_dir /opt/tmp
arch all 100
arch $pkgarch 160
EOF
mkdir -p /opt/usr/lib/locale
if [ ! -s /opt/usr/lib/locale/locale-archive ]; then cp "$base/locale-archive" /opt/usr/lib/locale/locale-archive; fi
chmod 755 "$base/opkg"
echo '[AliceBridge] Устанавливаем Entware, curl и сертификаты из APK'
"$base/opkg" -f "$base/offline.conf" install "$base"/packages/*.ipk
if [ ! -s /opt/etc/opkg.conf ]; then
cat > /opt/etc/opkg.conf <<EOF
src/gz entware http://bin.entware.net/$arch
dest root /
dest ram /opt/tmp
lists_dir ext /opt/var/opkg-lists
option tmp_dir /opt/tmp
arch all 100
arch $pkgarch 160
EOF
fi
for name in passwd group shells shadow gshadow; do
    if [ ! -e "/opt/etc/$name" ] && [ ! -L "/opt/etc/$name" ] && [ -f "/etc/$name" ]; then ln -s "/etc/$name" "/opt/etc/$name"; fi
done
[ ! -f /etc/localtime ] || [ -e /opt/etc/localtime ] || ln -s /etc/localtime /opt/etc/localtime
if [ -L /opt/etc/shells ]; then
    cp /opt/etc/shells "$base/shells.copy"
    rm /opt/etc/shells
    mv "$base/shells.copy" /opt/etc/shells
fi
/opt/bin/opkg list-installed | grep -q '^libpthread '
[ -x /opt/bin/curl ]
/opt/bin/curl --version
rm -f "$base"/packages/*.ipk "$base/locale-archive"
rmdir "$base/packages"
echo '[AliceBridge] Entware установлен из APK'
