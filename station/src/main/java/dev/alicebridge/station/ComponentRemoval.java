package dev.alicebridge.station;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Conservative, data-preserving removal. No recursive deletes and no dependency cascade. */
public final class ComponentRemoval {
    private ComponentRemoval() {}

    public static final Set<String> KINDS = Set.of("yam", "yse", "ssh", "zsh", "apkg");

    public static String warning(String kind) {
        checkKind(kind);
        return switch (kind) {
            case "yse" ->
                    "Полностью удаляются YSE, Entware со всеми пакетами и данными внутри"
                        + " /data/opt/entware, Zsh с его настройками и историей. Общий HOME и чужие"
                        + " файлы сохраняются. Необратимо: сделайте резервную копию. YAP-пакеты,"
                        + " работающие процессы и неизвестные зависимости блокируют удаление.";
            case "yam" ->
                    "YAM будет остановлен. Сначала удалите все плагины через YAM. Настройки,"
                            + " журналы и данные сохраняются; legacy YAM не удаляется.";
            case "ssh" ->
                    "SSH будет остановлен, открытые SSH-сессии могут прерваться. Ключи, пароли и"
                            + " учётные записи сохраняются. Используйте ADB.";
            case "zsh" ->
                    "Закройте Zsh/SSH-сессии. Оболочка root будет заменена на системную sh до"
                            + " удаления пакета Zsh. HOME, история, настройки и зависимости Entware"
                            + " сохраняются.";
            default ->
                    "Удаляется только apkg, не его пакеты и данные. Удаление блокируется, если"
                            + " нельзя подтвердить пустой список установленных пакетов.";
        };
    }

    private static void checkKind(String kind) {
        if (!KINDS.contains(kind == null ? "" : kind))
            throw new IllegalArgumentException("Неизвестный компонент удаления");
    }

    public static String script(String kind, Map<String, String> info) throws IOException {
        checkKind(kind);
        boolean android = !info.getOrDefault("SDK", "").isEmpty();
        boolean one = Station1Plan.matches(info), max = MaxPlan.matches(info);
        if (android && !one && !max)
            throw new IOException("Удаление на этой Android-модели не поддерживается");
        if (max && !Set.of("yam", "apkg").contains(kind))
            throw new IOException("Для Макс поддерживается удаление только YAM и apkg");
        String etc = one ? "/system/etc" : "/etc", target = one ? "/system" : "/";
        String rc = etc + "/rc.local";
        String body =
                switch (kind) {
                    case "yam" -> yam(rc, max);
                    case "ssh" -> ssh(rc, one);
                    case "yse" -> entware(etc, one);
                    case "apkg" -> apkg(android);
                    default -> zsh(etc, one);
                };
        return COMMON
                + "\nkind="
                + kind
                + "\n"
                + "safe /data/quasar/alicebridge-remove-lock\n"
                + "mkdir -p /data/quasar\n"
                + "mkdir /data/quasar/alicebridge-remove-lock 2>/dev/null || fail 'Другая операция"
                + " удаления выполняется'\n"
                + "ro=0\n"
                + "target="
                + target
                + "\n"
                + "trap 'code=$?; trap - EXIT; [ \"$ro\" != 1 ] || mount -o remount,ro \"$target\""
                + " || code=1; rmdir /data/quasar/alicebridge-remove-lock || code=1; exit"
                + " \"$code\"' EXIT\n"
                + "[ ! -d /data/quasar/alicebridge-yse/lock ] || fail 'Дождитесь завершения"
                + " операции YSE'\n"
                + body
                + "\nprintf 'AB_REMOVED=%s\\n' \"$kind\"\n";
    }

    private static final String COMMON =
            """
set -eu
fail(){ echo "$*"; exit 1; }
[ "$(id -u)" = 0 ] || fail 'Нужен root UID 0'
# Reject symlinked ancestors as well as leaf files before touching anything.
safe(){ p=$1; while [ "$p" != / ]; do [ ! -L "$p" ] || fail "Неизвестная ссылка: $p"; p=${p%/*}; [ -n "$p" ] || p=/; done; }
absent(){ [ ! -e "$1" ] && [ ! -L "$1" ] || fail "Не удалён: $1"; }
purge_check(){
    safe "$1"
    awk -v p="$1" '$2==p || index($2,p"/")==1{bad=1}END{exit bad}' /proc/mounts || fail "Внутри компонента есть mount: $1"
}
purge(){
    case "$1" in /data/opt/entware|/data/opt/etc/yse|/data/opt/var/yse|/data/quazar/yam|/data/quasar/alicebridge-ssh|/data/dropbear|/data/opt/home/.config/alicebridge-zsh|/data/quasar/alicebridge-zsh) :;; *) fail 'Каталог вне allowlist';; esac
    purge_check "$1"
    rm -rf "$1"
    absent "$1"
}
empty_dir(){ for p in "$1"/* "$1"/.[!.]* "$1"/..?*; do [ ! -e "$p" ] && [ ! -L "$p" ] || fail "Сначала удалите зависимые пакеты: $1"; done; }
writable(){
    options=$(awk -v t="$target" '$2==t{print $4;exit}' /proc/mounts)
    [ -n "$options" ] || fail 'Не определён режим раздела'
    case ",$options," in *,ro,*) ro=1; mount -o remount,rw "$target";; esac
}
# Remove only exact owned lines, never restore a stale rc.local backup.
hook(){
    file=$1; marker=$2; line=$3
    [ -f "$file" ] || return 0
    safe "$file"; safe "$file.alicebridge-remove"
    [ ! -e "$file.alicebridge-remove" ] || fail 'Остался временный файл автозапуска'
    cp -p "$file" "$file.alicebridge-remove"
    awk -v m="$marker" -v l="$line" '$0!=m && $0!=l{print}' "$file" > "$file.alicebridge-remove"
    mv "$file.alicebridge-remove" "$file"
    ! grep -qxF "$line" "$file" || fail 'Автозапуск не удалён'
}
stop_owned(){
    bin=$1
    for proc in /proc/[0-9]*; do
        exe=$(readlink "$proc/exe" 2>/dev/null || true)
        owned=0
        case "$exe" in "$bin"|"$bin (deleted)") owned=1;; esac
        if [ "$owned" = 0 ] && [ -r "$proc/cmdline" ]; then
            # Dropbear launched via the bundled musl loader.
            case "$exe" in */ld-musl-aarch64.so.1)
                tr '\\000' '\\n' < "$proc/cmdline" | grep -qxF "$bin" && owned=1;;
            esac
        fi
        [ "$owned" = 1 ] || continue
        pid=${proc##*/}; [ "$pid" -gt 1 ] || fail 'Некорректный PID'
        kill "$pid" || fail 'Не удалось остановить процесс'
        n=0
        while kill -0 "$pid" 2>/dev/null; do n=$((n+1)); [ "$n" -lt 6 ] || fail 'Процесс ещё работает; повторите позже'; sleep 1; done
    done
}
""";

    private static String entware(String etc, boolean one) {
        String script0 =
                """
safe /data/opt/bin/yse
safe /data/opt/yap
empty_dir /data/opt/yap
for owned in /data/opt/entware /data/opt/etc/yse /data/opt/var/yse; do purge_check "$owned"; done
# A second explicit removal after successful cleanup is a no-op.
if [ ! -e /data/opt/bin/yse ] && [ ! -e /data/opt/entware ] &&
   [ ! -e /data/opt/etc/yse ] && [ ! -e /data/opt/var/yse ] &&
   [ ! -L /opt ] && [ -d /opt ] && [ -z "$(ls -A /opt)" ]; then
    echo 'YSE и Entware уже удалены'; echo AB_REMOVED=yse; exit 0
fi
[ "$(readlink /opt)" = /data/opt/entware ] || fail 'Не подтверждено владение /opt: ожидается ссылка на /data/opt/entware'
[ -f /data/opt/entware/lib/opkg/status ] || fail 'Не найдена база Entware; автоматическое удаление небезопасно'
# Standalone managers may have installed binaries linked to Entware libraries.
[ ! -x /usr/local/bin/apkg ] && [ ! -x /data/local/bin/apkg ] || fail 'Сначала удалите apkg: зависимости его пакетов от Entware неизвестны'
empty_dir /data/quazar/yam/data/plugins
for proc in /proc/[0-9]*/exe; do
    exe=$(readlink "$proc" 2>/dev/null || true)
    case "$exe" in /opt/*|/data/opt/entware/*) fail 'Закройте процессы Entware/Zsh перед удалением';; esac
done
# Unknown external startup entries must be reviewed, not silently destroyed.
for file in /etc/rc.local /system/etc/rc.local /etc/init.d/*; do
    [ -f "$file" ] || continue
    if grep -qE '/opt/|/data/opt/entware' "$file"; then fail "Внешний автозапуск зависит от Entware: $file"; fi
done
""";
        String script1 =
                """
                purge /data/opt/entware
                rm /opt
                mkdir /opt
                purge /data/opt/etc/yse
                purge /data/opt/var/yse
                rm -f /data/opt/bin/yse
                absent /data/opt/bin/yse
                absent /data/opt/entware
                """;
        return script0
                + "safe "
                + etc
                + "/profile.d/yse.sh\n"
                + "if [ -x /opt/bin/zsh ] || [ -e /data/quasar/alicebridge-zsh/configured ]; then\n"
                + zsh(etc, one)
                + "fi\n"
                + "writable\n"
                + script1
                + "rm -f "
                + etc
                + "/profile.d/yse.sh\n";
    }

    private static String yam(String rc, boolean max) {
        String script0 =
                """
purge_check /data/quazar/yam
[ ! -d /data/quazar/yam ] || [ -f /data/quazar/yam/alicebridge-release ] || fail 'Не подтверждено владение YAM'
safe /data/quazar/yam/bin/yamd
safe /data/quazar/yam/start.sh
safe /data/quazar/yam/bin/yamd.previous
safe /data/quazar/yam/bin/yamd.new
safe /data/quazar/yam/data/plugins
[ ! -e /data/yam/yamd ] || fail 'Legacy YAM требует ручного удаления'
empty_dir /data/quazar/yam/data/plugins
""";
        String script1 =
                """
stop_owned /data/quazar/yam/bin/yamd
rm -f /data/quazar/yam/bin/yamd /data/quazar/yam/bin/yamd.previous /data/quazar/yam/bin/yamd.new /data/quazar/yam/start.sh
absent /data/quazar/yam/bin/yamd
absent /data/quazar/yam/start.sh
""";
        return script0
                + (max
                        ? ""
                        : "safe "
                                + rc
                                + "\nwritable\nhook "
                                + rc
                                + " '# AliceBridge YAM' '[ ! -x /data/quazar/yam/start.sh ] || sh"
                                + " /data/quazar/yam/start.sh'\n")
                + script1;
    }

    private static String ssh(String rc, boolean one) {
        String base = one ? "/data/dropbear" : "/data/quasar/alicebridge-ssh";
        String start = one ? base + "/start.sh" : "/etc/init.d/S99zalicebridge-ssh";
        String line =
                one
                        ? "[ ! -x /data/dropbear/start.sh ] || /system/bin/sh"
                                + " /data/dropbear/start.sh"
                        : "[ -x /etc/init.d/S99zalicebridge-ssh ] &&"
                                + " /etc/init.d/S99zalicebridge-ssh start";
        return "safe "
                + base
                + "/dropbear\nsafe "
                + base
                + "/dropbearkey\nsafe "
                + start
                + "\nsafe "
                + rc
                + "\nwritable\nhook "
                + rc
                + " '# AliceBridge SSH' '"
                + line
                + "'\nstop_owned "
                + base
                + "/dropbear\nrm -f "
                + base
                + "/dropbear "
                + base
                + "/dropbearkey "
                + start
                + "\nabsent "
                + base
                + "/dropbear\nabsent "
                + start
                + "\n";
    }

    private static String apkg(boolean android) {
        String bin = (android ? "/data/local/bin" : "/usr/local/bin") + "/apkg";
        return "safe "
                + bin
                + "\nif [ -e "
                + bin
                + " ]; then\nexport ANDROID_DATA=/data ANDROID_ROOT=/system\npackages=$("
                + bin
                + " list -i) || fail 'Не удалось проверить пакеты apkg'\n"
                + "[ -z \"$packages\" ] || fail 'apkg: список не пуст или формат не подтверждён;"
                + " сначала удалите пакеты'\n"
                + "fi\n"
                + (android ? "" : "writable\n")
                + "rm -f "
                + bin
                + "\nabsent "
                + bin
                + "\n";
    }

    private static String zsh(String etc, boolean one) {
        String script0 =
                """
[ -x /opt/bin/opkg ] || fail 'Нет opkg для безопасного удаления Zsh'
for proc in /proc/[0-9]*/exe; do
    exe=$(readlink "$proc" 2>/dev/null || true)
    case "$exe" in */bin/zsh|*'/bin/zsh (deleted)') fail 'Закройте все сессии Zsh';; esac
done
# opkg without force/autoremove refuses reverse dependencies.
/opt/bin/opkg --noaction remove zsh || fail 'Zsh имеет зависимости или opkg недоступен'
""";
        String script1 =
                """
                /opt/bin/opkg remove zsh
                [ ! -x /opt/bin/zsh ] || fail 'Zsh всё ещё установлен'
                """;
        String login = one ? "/system/bin/alicebridge-login" : "/usr/bin/alicebridge-login";
        String factory = one ? "/system/bin/sh" : "/bin/sh";
        return "safe "
                + etc
                + "/passwd\nsafe "
                + etc
                + "/passwd.alicebridge-remove\nsafe "
                + login
                + "\n"
                + "safe /data/opt/bin/alicebridge-login\n"
                + "safe /data/quasar/alicebridge-zsh/configured\n"
                + script0
                + "[ -x "
                + factory
                + " ] || fail 'Нет системной sh'\n[ -f "
                + etc
                + "/passwd ] || fail 'Нет passwd'\n[ \"$(awk -F: '$1==\"root\"{n++}END{print n+0}' "
                + etc
                + "/passwd)\" = 1 ] || fail 'Неизвестный passwd'\n"
                + "awk -F: '$1!=\"root\" && $7 ~ /zsh|alicebridge-login/{bad=1}END{exit bad}' "
                + etc
                + "/passwd || fail 'Другие учётные записи используют Zsh'\n"
                + "[ ! -e "
                + etc
                + "/passwd.alicebridge-remove ] || fail 'Остался временный passwd'\n"
                + "writable\n"
                + "cp -p "
                + etc
                + "/passwd "
                + etc
                + "/passwd.alicebridge-remove\n"
                + "awk -F: -v sh="
                + factory
                + " 'BEGIN{OFS=\":\"} $1==\"root\" && ($7==\"/usr/bin/alicebridge-login\" ||"
                + " $7==\"/system/bin/alicebridge-login\" ||"
                + " $7==\"/data/opt/bin/alicebridge-login\" || $7==\"/opt/bin/zsh\"){$7=sh}{print}'"
                + " "
                + etc
                + "/passwd > "
                + etc
                + "/passwd.alicebridge-remove\nmv "
                + etc
                + "/passwd.alicebridge-remove "
                + etc
                + "/passwd\n"
                + "awk -F: '$7 ~ /zsh|alicebridge-login/{bad=1}END{exit bad}' "
                + etc
                + "/passwd || fail 'Другие учётные записи используют Zsh; переключите их вручную'\n"
                + script1
                + "rm -f "
                + login
                + " /data/opt/bin/alicebridge-login /data/quasar/alicebridge-zsh/configured\n"
                + "absent "
                + login
                + "\nabsent /data/opt/bin/alicebridge-login\n";
    }
}
