#!/bin/sh
# AliceBridge fastfetch appearance v1; explicit CLI options remain untouched.
BASE=/data/quasar/alicebridge-fastfetch
REAL=__BIN__/fastfetch.real
if [ "$#" -gt 0 ]; then exec "$REAL" "$@"; fi
width=$(stty size 2>/dev/null | awk '{print $2}')
case "$width" in ''|*[!0-9]*) width=${COLUMNS:-80};; esac
case "$width" in ''|*[!0-9]*) width=80;; esac
profile=wide
if [ "$width" -lt 85 ]; then
    profile=compact
    # In portrait put the 28-column logo above the details instead of beside them.
    if [ "$width" -ge 30 ]; then
        printf '\n\033[1;38;2;122;85;255m'
        padding=$(((width - 28) / 2))
        awk -v pad="$padding" '{sub(/^\$1/, "");printf "%*s%s\n",pad,"",$0}' "$BASE/yandexstation.txt"
        printf '\033[0m\n'
    fi
fi
"$REAL" --config "$BASE/$profile.jsonc"
result=$?
# Optional OTG helpers are not installed on every station.
if [ -x /data/otg/ff-usb.sh ]; then
    /data/otg/ff-usb.sh 1
    /data/otg/ff-usb.sh 2
    /data/otg/ff-usb.sh 3
    /data/otg/ff-usb.sh 4
fi
exit "$result"
