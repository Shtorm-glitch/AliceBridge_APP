#!/system/bin/sh
set -eu
BB=/system/bin/busybox-smp
[ "$(getprop ro.board.platform)" = tulip ]
[ "$($BB id -u)" = 0 ]
$BB mkdir -p /data/opt/bin /data/opt/etc
cat > /data/opt/bin/alicebridge-dns.sh <<'DNS'
#!/system/bin/sh
set -eu
BB=/system/bin/busybox-smp
target=/data/opt/etc/alicebridge-resolv.conf
tmp=$target.$$
trap '$BB rm -f "$tmp"' EXIT
{ getprop net.dns1; getprop net.dns2; getprop dhcp.wlan0.dns1; getprop dhcp.wlan0.dns2; } |
    $BB awk -F. 'NF==4 {valid=1;for(i=1;i<=4;i++)if($i!~/^[0-9]+$/||$i>255)valid=0;if(valid&&$0!="0.0.0.0"&&!seen[$0]++)print "nameserver "$0}' > "$tmp"
if [ ! -s "$tmp" ]; then [ -s "$target" ] && exit 0; echo 'Android ещё не сообщил DNS сети'; exit 1; fi
printf 'options timeout:3 attempts:2\n' >> "$tmp"
$BB chmod 644 "$tmp"
$BB mv "$tmp" "$target"
DNS
$BB chmod 755 /data/opt/bin/alicebridge-dns.sh
/system/bin/sh /data/opt/bin/alicebridge-dns.sh
target=/data/opt/etc/alicebridge-resolv.conf
# Respect an existing independently configured resolver.
if [ "$($BB readlink /etc/resolv.conf 2>/dev/null || true)" != "$target" ]; then
    if [ -s /etc/resolv.conf ] && $BB grep -q '^nameserver ' /etc/resolv.conf; then
        echo 'Существующий DNS Entware сохранён'; exit 0
    fi
    options=$($BB awk '$2=="/system"{print $4;exit}' /proc/mounts)
    [ -n "$options" ]
    ro=0;case ",$options," in *,ro,*)ro=1;;esac
    cleanup(){ rc=$?;trap - EXIT;$BB rm -f /system/etc/.alicebridge-resolv.$$;if [ "$ro" = 1 ];then /system/bin/mount -o ro,remount /system || rc=1;fi;exit "$rc"; }
    trap cleanup EXIT
    [ "$ro" = 0 ] || /system/bin/mount -o rw,remount /system
    if [ -e /etc/resolv.conf ] || [ -L /etc/resolv.conf ]; then
        [ -e /data/opt/etc/resolv-before-alicebridge ] || $BB cp -P /etc/resolv.conf /data/opt/etc/resolv-before-alicebridge
    fi
    $BB ln -s "$target" /system/etc/.alicebridge-resolv.$$
    $BB mv -f /system/etc/.alicebridge-resolv.$$ /system/etc/resolv.conf
    [ "$ro" = 0 ] || /system/bin/mount -o ro,remount /system
    trap - EXIT
fi
if [ -d /opt/etc ] && [ ! -e /opt/etc/resolv.conf ] && [ ! -L /opt/etc/resolv.conf ]; then $BB ln -s "$target" /opt/etc/resolv.conf;fi
env=/data/opt/bin/alicebridge-env.sh
if [ -f "$env" ] && ! $BB grep -qF '/data/opt/bin/alicebridge-dns.sh' "$env"; then
    printf '\n[ ! -x /data/opt/bin/alicebridge-dns.sh ] || /system/bin/sh /data/opt/bin/alicebridge-dns.sh || true\n' >> "$env"
fi
echo 'DNS Entware синхронизирован с DNS Android'
