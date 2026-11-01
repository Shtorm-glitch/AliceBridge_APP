#!/bin/sh
# Run only after the explicit Zsh installation confirmation in AliceBridge.
set -eu
base=$1
arch=$2
case "$arch" in aarch64-k3.10) pkgarch=aarch64-3.10;; armv7sf-k3.2) pkgarch=armv7-3.2;; *) exit 1;; esac
[ "$(id -u)" = 0 ] || { echo 'Для настройки Zsh нужен root ADB'; exit 1; }
[ -x /opt/bin/opkg ] && [ -x /opt/bin/curl ] || { echo 'Сначала установите YSE и Entware отдельной кнопкой'; exit 1; }
cat > "$base/offline.conf" <<EOF
dest root /
dest ram /opt/tmp
lists_dir ext /opt/var/opkg-lists
option tmp_dir /opt/tmp
arch all 100
arch $pkgarch 160
EOF
if [ -L /opt/etc/shells ]; then
    cp /opt/etc/shells "$base/shells.copy"
    rm /opt/etc/shells
    mv "$base/shells.copy" /opt/etc/shells
fi
chmod 755 "$base/opkg"
echo '[AliceBridge] Устанавливаем Zsh и зависимости из APK'
"$base/opkg" -f "$base/offline.conf" install "$base"/packages/*.ipk
/opt/bin/zsh --version
home=/data/opt/home
config="$home/.config/alicebridge-zsh"
mkdir -p "$config" /data/opt/bin
if [ ! -f "$config/.zshrc" ]; then
cat > "$config/.zshrc" <<'ZSH'
# AliceBridge default Zsh configuration. Edit this file to customize your shell.
export PATH=/data/local/tmp/alicebridge-tools:/data/local/bin:/usr/local/bin:/data/opt/bin:/opt/bin:/opt/sbin:/system/bin:/system/xbin:/usr/bin:/bin:/usr/sbin:/sbin
export SHELL=/opt/bin/zsh
HISTFILE="$HOME/.zsh_history"
HISTSIZE=5000
SAVEHIST=5000
setopt APPEND_HISTORY SHARE_HISTORY HIST_IGNORE_DUPS HIST_IGNORE_SPACE
setopt AUTO_CD INTERACTIVE_COMMENTS
bindkey -e
bindkey '^[[A' history-beginning-search-backward
bindkey '^[[B' history-beginning-search-forward
bindkey '^[[H' beginning-of-line
bindkey '^[[F' end-of-line
bindkey '^[[3~' delete-char
autoload -Uz compinit
compinit -d "$ZDOTDIR/.zcompdump"
zstyle ':completion:*' menu select
zstyle ':completion:*' list-colors ''
autoload -Uz promptinit
promptinit
prompt adam1
ZSH
fi
chmod 700 "$config"
chmod 600 "$config/.zshrc"
/opt/bin/zsh -n "$config/.zshrc"
cat > /data/opt/bin/alicebridge-login <<'LOGIN'
#!/bin/sh
export HOME=/data/opt/home
export ZDOTDIR="$HOME/.config/alicebridge-zsh"
export PATH=/data/local/tmp/alicebridge-tools:/data/local/bin:/usr/local/bin:/data/opt/bin:/opt/bin:/opt/sbin:/system/bin:/system/xbin:/usr/bin:/bin:/usr/sbin:/sbin
export TERM="${TERM:-xterm-256color}"
factory=/bin/sh
[ -x "$factory" ] || factory=/system/bin/sh
if [ "${1:-}" = -c ]; then exec "$factory" "$@"; fi
if [ -x /opt/bin/zsh ]; then
    export SHELL=/opt/bin/zsh
    if [ "$#" = 0 ]; then exec /opt/bin/zsh -l; fi
    exec /opt/bin/zsh "$@"
fi
export SHELL="$factory"
if [ ! -d "$HOME" ]; then HOME=/data;export HOME;fi
if [ "$#" = 0 ]; then exec "$factory" -i; fi
exec "$factory" "$@"
LOGIN
if [ ! -x /bin/sh ] && [ -x /system/bin/sh ]; then
    sed '1s|#!/bin/sh|#!/system/bin/sh|' /data/opt/bin/alicebridge-login > /data/opt/bin/alicebridge-login.new
    mv /data/opt/bin/alicebridge-login.new /data/opt/bin/alicebridge-login
fi
chmod 755 /data/opt/bin/alicebridge-login
if [ ! -e /data/opt/bin/zsh ] && [ ! -L /data/opt/bin/zsh ]; then ln -s /opt/bin/zsh /data/opt/bin/zsh; fi
/data/opt/bin/alicebridge-login -ic '[[ -n "$ZSH_VERSION" && -n "$PROMPT" ]] && (( $+functions[compdef] )) && [[ -n "$(whence -p zsh)" ]] && print -r -- "Zsh initialized: $ZSH_VERSION, PATH, prompt and completion"'
# Preserve every account and root's existing password. Restore the original mount mode.
[ -f /etc/passwd ] && [ ! -L /etc/passwd ] || { echo 'Неизвестный формат /etc/passwd'; exit 1; }
[ "$(awk -F: '$1=="root"{n++}END{print n+0}' /etc/passwd)" = 1 ] || exit 1
[ ! -L /etc/shells ] || { echo '/etc/shells — ссылка; настройка остановлена'; exit 1; }
backup=/data/quasar/alicebridge-backup
mkdir -p "$backup"
[ -e "$backup/zsh-passwd" ] || cp -p /etc/passwd "$backup/zsh-passwd"
if [ -f /etc/shells ] && [ ! -e "$backup/zsh-shells" ]; then cp -p /etc/shells "$backup/zsh-shells"; fi
mount_target=/
mount_bin=mount
if command -v getprop >/dev/null 2>&1 && [ "$(getprop ro.board.platform)" = tulip ]; then mount_target=/system; mount_bin=/system/bin/mount; fi
options=$(awk -v target="$mount_target" '$2==target{print $4;exit}' /proc/mounts)
restore_ro=0
case ",$options," in *,ro,*) restore_ro=1;; esac
cleanup(){ rc=$?;trap - EXIT;rm -f /etc/.alicebridge-zsh-passwd.$$;if [ "$restore_ro" = 1 ]; then sync;"$mount_bin" -o remount,ro "$mount_target" || rc=1;fi;exit "$rc"; }
trap cleanup EXIT
if [ "$restore_ro" = 1 ]; then "$mount_bin" -o remount,rw "$mount_target"; fi
mkdir -p /etc/profile.d
if [ ! -f /etc/profile.d/alicebridge-zsh.sh ]; then
cat > /etc/profile.d/alicebridge-zsh.sh <<'PROFILE'
# AliceBridge: interactive tools in PATH, system scripts keep their own shell.
export PATH=/data/local/bin:/usr/local/bin:/data/opt/bin:/opt/bin:/opt/sbin:$PATH
PROFILE
chmod 644 /etc/profile.d/alicebridge-zsh.sh
fi
login=/usr/bin/alicebridge-login
if [ "$mount_target" = /system ]; then login=/system/bin/alicebridge-login; fi
if [ -f "$login" ] && [ ! -e "$backup/zsh-login" ]; then cp -p "$login" "$backup/zsh-login"; fi
cp /data/opt/bin/alicebridge-login "$login"
chmod 755 "$login"
for shell in /bin/sh /opt/bin/zsh /data/opt/bin/alicebridge-login "$login"; do
    grep -qxF "$shell" /etc/shells 2>/dev/null || printf '%s\n' "$shell" >> /etc/shells
done
awk -F: -v ab_shell="$login" 'BEGIN{OFS=":"} $1=="root"{$6="/data/opt/home";$7=ab_shell}{print}' /etc/passwd > /etc/.alicebridge-zsh-passwd.$$
chmod 644 /etc/.alicebridge-zsh-passwd.$$
mv /etc/.alicebridge-zsh-passwd.$$ /etc/passwd
if [ -f /opt/etc/shells ] && [ ! -L /opt/etc/shells ]; then
    grep -qxF /opt/bin/zsh /opt/etc/shells || printf '%s\n' /opt/bin/zsh >> /opt/etc/shells
    grep -qxF /data/opt/bin/alicebridge-login /opt/etc/shells || printf '%s\n' /data/opt/bin/alicebridge-login >> /opt/etc/shells
fi
mkdir -p /data/quasar/alicebridge-zsh
printf 'adam1\n' > /data/quasar/alicebridge-zsh/configured
rm -f "$base"/packages/*.ipk "$base/locale-archive"
rmdir "$base/packages"
echo '[AliceBridge] Zsh готов: тема adam1, история и автодополнение. Новые сессии терминала и SSH используют Zsh; настройка сохраняется после перезагрузки.'
