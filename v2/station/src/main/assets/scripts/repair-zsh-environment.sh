#!/bin/sh
# Repair only AliceBridge-managed launchers and PATH; preserve custom Zsh settings and packages.
set -eu
[ "$(id -u)" = 0 ]
config=/data/opt/home/.config/alicebridge-zsh
[ -x /opt/bin/zsh ] && [ -f /data/quasar/alicebridge-zsh/configured ]
mkdir -p "$config"
cat > "$config/paths.zsh" <<'PATHS'
# AliceBridge: keep native, manually installed and Entware tools visible.
typeset -gU path
path=(/data/local/tmp/alicebridge-tools /data/local/bin /usr/local/bin /data/opt/bin /opt/bin /opt/sbin /system/bin /system/xbin /usr/bin /bin /usr/sbin /sbin $path)
export PATH
PATHS
for file in .zshenv .zshrc; do
    touch "$config/$file"
    if ! grep -qF '# AliceBridge tools PATH v2' "$config/$file"; then
        printf '\n%s\n%s\n' '# AliceBridge tools PATH v2' '[ ! -r "$ZDOTDIR/paths.zsh" ] || source "$ZDOTDIR/paths.zsh"' >> "$config/$file"
    fi
    chmod 600 "$config/$file"
done
chmod 600 "$config/paths.zsh"
cat > /data/opt/bin/alicebridge-login <<'LOGIN'
#!/bin/sh
export HOME=/data/opt/home
export ZDOTDIR="$HOME/.config/alicebridge-zsh"
export PATH=/data/local/tmp/alicebridge-tools:/data/local/bin:/usr/local/bin:/data/opt/bin:/opt/bin:/opt/sbin:/system/bin:/system/xbin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}
export TERM="${TERM:-xterm-256color}"
# SSH automation and service scripts keep POSIX parsing; interactive logins use Zsh.
factory=/bin/sh
[ -x "$factory" ] || factory=/system/bin/sh
if [ "${1:-}" = -c ]; then exec "$factory" "$@"; fi
if [ -x /opt/bin/zsh ]; then
    export SHELL=/opt/bin/zsh
    if [ "$#" = 0 ]; then exec /opt/bin/zsh -l; fi
    exec /opt/bin/zsh "$@"
fi
export SHELL="$factory"
if [ "$#" = 0 ]; then exec "$factory" -i; fi
exec "$factory" "$@"
LOGIN
if [ ! -x /bin/sh ] && [ -x /system/bin/sh ]; then
    sed '1s|#!/bin/sh|#!/system/bin/sh|' /data/opt/bin/alicebridge-login > /data/opt/bin/alicebridge-login.new
    mv /data/opt/bin/alicebridge-login.new /data/opt/bin/alicebridge-login
fi
chmod 755 /data/opt/bin/alicebridge-login
mount_target=/
mount_bin=mount
login=/usr/bin/alicebridge-login
if command -v getprop >/dev/null 2>&1 && [ "$(getprop ro.board.platform)" = tulip ]; then mount_target=/system; mount_bin=/system/bin/mount;login=/system/bin/alicebridge-login;fi
options=$(awk -v target="$mount_target" '$2==target{print $4;exit}' /proc/mounts)
[ -n "$options" ]
restore_ro=0
case ",$options," in *,ro,*) restore_ro=1;; esac
cleanup(){ rc=$?;trap - EXIT;if [ "$restore_ro" = 1 ];then sync;"$mount_bin" -o remount,ro "$mount_target" || rc=1;fi;exit "$rc"; }
trap cleanup EXIT
if [ "$restore_ro" = 1 ];then "$mount_bin" -o remount,rw "$mount_target";fi
# Replace only the app-owned login wrapper, never /bin/sh or the root account itself.
[ ! -L "$login" ]
cp /data/opt/bin/alicebridge-login "$login"
chmod 755 "$login"
cat > /etc/profile.d/alicebridge-zsh.sh <<'PROFILE'
# AliceBridge tool directories, without overriding the system shell.
export PATH=/data/local/bin:/usr/local/bin:/data/opt/bin:/opt/bin:/opt/sbin:$PATH
PROFILE
chmod 644 /etc/profile.d/alicebridge-zsh.sh
/data/opt/bin/alicebridge-login -ic 'command -v zsh >/dev/null; [[ ":$PATH:" == *:/usr/local/bin:* && ":$PATH:" == *:/data/local/bin:* ]]'
printf '2\n' > /data/quasar/alicebridge-zsh/environment-version
echo 'AB_ZSH_ENVIRONMENT_OK'
