package dev.alicebridge.station;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.*;
import java.util.*;

/** Factory-only file patch; partition identity comes from the running device, never a guessed MTD number. */
public final class RetailCaPlan {
    public static final String MOUNT="/mnt/alicebridge-retail-ca";
    public static final String TARGET=MOUNT+"/system/vendor/quasar/certs/ca-certificates.crt";
    public static final String STAGE="/bkupgrade/data/local/tmp/alicebridge-retail-ca";
    public static final String LITE2_HASH="5b62f4434ecee31e1e0dfbd57a50fce15be54c4fdfc0f4ccc4a140f0c88f68db";
    public static final String AAA_DER_HASH="d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4";
    public static boolean supported(String model){return Set.of("lite2","mini3").contains(model);}
    public static boolean factory(Map<String,String> info){return info.getOrDefault("VERSION","").toLowerCase(Locale.ROOT).contains("factory");}
    public static void requireFactory(String model,Map<String,String> info){
        if(!supported(model)||!info.getOrDefault("SDK","").isEmpty()||!"0".equals(info.get("UID"))||!factory(info))throw new IllegalArgumentException("Retail CA применяется только из factory с root ADB на выбранной Лайт 2 или Мини 3");
        String platform=model.equals("lite2")?"plum":"bergamot";
        boolean matches=List.of("PLATFORM","BUILDNAME","DEVICE","MODEL").stream().map(k->info.getOrDefault(k,"").toLowerCase(Locale.ROOT)).anyMatch(v->v.contains(platform)||model.equals("mini3")&&v.contains("station-mini3"));
        if(!matches)throw new IllegalArgumentException("Выбранная модель не совпала с factory-устройством. CA не изменялся");
        if(model.equals("mini3")){
            String version=info.getOrDefault("VERSION","");
            if(!version.matches("0\\.6\\.1\\.4\\..*factory-6")&&!version.matches("0\\.4\\.1\\.39\\..*factory-4"))throw new IllegalArgumentException("Factory Мини 3 не соответствует описанным factory-4/factory-6. Если recovery обновлён до .50, этот способ не подходит");
            if(!info.getOrDefault("CMDLINE","").contains("rootfstype=ramfs"))throw new IllegalArgumentException("Мини 3 должна быть загружена в factory ramfs, а не в retail");
        }
        if(info.getOrDefault("MAC","").isBlank()||info.getOrDefault("BOOT","").isBlank())throw new IllegalArgumentException("Не получены идентификатор колонки и boot ID");
    }
    public static String sha(byte[] bytes)throws Exception{
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static List<X509Certificate> certificates(byte[] bytes)throws Exception {
        var values=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
        List<X509Certificate> out=new ArrayList<>();for(var c:values)out.add((X509Certificate)c);return out;
    }
    public static byte[] patched(String model,byte[] original,byte[] lite2Bundle,byte[] aaa)throws Exception {
        if(!supported(model))throw new IllegalArgumentException("Неизвестная модель CA");
        if(aaa.length!=1517||certificates(aaa).size()!=1||!sha(certificates(aaa).get(0).getEncoded()).equals(AAA_DER_HASH))throw new IllegalArgumentException("Корень AAA не прошёл проверку отпечатка");
        if(model.equals("lite2")){
            if(!sha(lite2Bundle).equals(LITE2_HASH)||certificates(lite2Bundle).size()!=123)throw new IllegalArgumentException("Повреждён проверенный CA-бандл Лайт 2");
            return lite2Bundle.clone();
        }
        var current=certificates(original);
        boolean hasAaa=false;for(var cert:current)if(sha(cert.getEncoded()).equals(AAA_DER_HASH))hasAaa=true;
        if(hasAaa){if(current.size()!=123)throw new IllegalArgumentException("Неожиданное число сертификатов Mini 3");return original.clone();}
        if(current.size()!=122)throw new IllegalArgumentException("Исходный CA Мини 3 должен содержать 122 сертификата");
        ByteArrayOutputStream result=new ByteArrayOutputStream();result.write(original);
        if(original.length==0||original[original.length-1]!='\n')result.write('\n');result.write(aaa);
        byte[] next=result.toByteArray();if(certificates(next).size()!=123)throw new IllegalArgumentException("Новый CA-бандл не прошёл проверку");return next;
    }
    public static String mountScript(){return """
        set -eu
        [ "$(id -u)" = 0 ]
        case "$(cat /system/vendor/quasar/version_full)" in *factory*) ;; *) echo 'Требуется factory';exit 1;; esac
        mtd=$(awk '$4=="\\"system\\"" {gsub(/^mtd|:$/,"",$1);print $1}' /proc/mtd)
        case "$mtd" in ''|*[!0-9]*) echo 'Неоднозначный раздел system';exit 1;; esac
        env=$(fw_printenv upgrade_step boot_to_recovery)
        printf '%s\\n' "$env" | grep -Eq '^upgrade_step=[12]$'
        printf '%s\\n' "$env" | grep -Eq '^boot_to_recovery=[01]$'
        find_ubi(){
            found=''
            for candidate in /sys/class/ubi/ubi[0-9]*;do
                [ -f "$candidate/mtd_num" ] || continue
                [ "$(cat "$candidate/mtd_num")" = "$mtd" ] || continue
                [ -z "$found" ] || { echo 'Несколько UBI для system' >&2;return 1; }
                found=${candidate##*/}
            done
            printf '%s' "$found"
        }
        ubi=$(find_ubi)
        if [ -z "$ubi" ];then ubiattach /dev/ubi_ctrl -m "$mtd";ubi=$(find_ubi);fi
        case "$ubi" in ubi[0-9]*) ;; *) echo 'UBI system не найден';exit 1;; esac
        volumes=0
        for name in /sys/class/ubi/${ubi}_*/name;do [ -f "$name" ] || continue;[ "$(cat "$name")" != rootfs ] || volumes=$((volumes+1));done
        [ "$volumes" = 1 ] || { echo 'Том rootfs не определён однозначно';exit 1; }
        root_source=$(awk '$2=="/"{print $1;exit}' /proc/mounts)
        [ "$root_source" != "$ubi:rootfs" ] || { echo 'Выбрана активная retail-система вместо factory';exit 1; }
        mountpoint=/mnt/alicebridge-retail-ca
        cleanup_needed=0
        cleanup_failed_mount(){ rc=$?;trap - EXIT;if [ "$rc" != 0 ] && [ "$cleanup_needed" = 1 ];then mount -o remount,ro "$mountpoint" && umount "$mountpoint" || true;fi;exit "$rc"; }
        trap cleanup_failed_mount EXIT
        mkdir -p "$mountpoint"
        existing=$(awk -v p="$mountpoint" '$2==p{print $1;exit}' /proc/mounts)
        if [ -n "$existing" ];then
            [ "$existing" = "$ubi:rootfs" ] || { echo 'Точка монтирования занята другим разделом';exit 1; }
            cleanup_needed=1
            mount -o remount,rw "$mountpoint"
        else mount -t ubifs -o rw "$ubi:rootfs" "$mountpoint";cleanup_needed=1;fi
        target=$mountpoint/system/vendor/quasar/certs/ca-certificates.crt
        [ -f "$target" ] && [ ! -L "$target" ] || { echo 'Retail CA отсутствует или является ссылкой';exit 1; }
        version=$(cat "$mountpoint/system/vendor/quasar/version_full")
        case "$version" in ''|*factory*) echo 'В томе system не retail';exit 1;; esac
        printf 'AB_RETAIL_VERSION=%s\\n' "$version"
        printf 'AB_CA_SHA=';sha256sum "$target" | awk '{print $1}'
        printf 'AB_SYSTEM_MTD=%s\\nAB_SYSTEM_UBI=%s\\n' "$mtd" "$ubi"
        """;}
    public static String writeScript(String sha,boolean mini3){
        if(!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Некорректный SHA256 CA");
        return "set -eu\ntarget="+TARGET+"\nstage="+STAGE+"\n"+
            "[ -f \"$target\" ] && [ ! -L \"$target\" ] || { echo 'Нет обычного файла CA';exit 1; }\n[ ! -L \"$target.orig\" ]\n"+
            "[ \"$(sha256sum \"$stage/ca.new\" | awk '{print $1}')\" = "+sha+" ]\n"+
            "[ -e \"$target.orig\" ] || cp -p \"$target\" \"$target.orig\"\n"+
            "[ ! -L \"$target.alicebridge-new\" ]\ncp \"$stage/ca.new\" \"$target.alicebridge-new\"\nchmod 644 \"$target.alicebridge-new\"\nchown 0:0 \"$target.alicebridge-new\"\n"+
            "[ \"$(sha256sum \"$target.alicebridge-new\" | awk '{print $1}')\" = "+sha+" ]\nmv \"$target.alicebridge-new\" \"$target\"\nsync\n"+
            "[ \"$(sha256sum \"$target\" | awk '{print $1}')\" = "+sha+" ]\n[ \"$(grep -c 'BEGIN CERTIFICATE' \"$target\")\" = 123 ]\n"+
            (mini3?"""
                mkdir -p /bkupgrade/data/quasar
                for marker in /bkupgrade/data/quasar/activate_adb_quasmodrom /bkupgrade/data/quasar/activate_network_adb;do
                    [ ! -L "$marker" ] || { echo 'Маркер ADB является ссылкой';exit 1; }
                    [ -e "$marker" ] || { touch "$marker";chmod 644 "$marker"; }
                    [ -f "$marker" ] && [ ! -L "$marker" ]
                done
                wifi=/bkupgrade/data/wpa_supplicant.conf
                [ ! -L "$wifi" ] && [ ! -L "$wifi.alicebridge-new" ] && [ ! -L "$wifi.before-alicebridge-ca" ] || { echo 'Неожиданная ссылка Wi-Fi';exit 1; }
                if [ -f "$wifi" ] && [ ! -e "$wifi.before-alicebridge-ca" ];then cp -p "$wifi" "$wifi.before-alicebridge-ca";fi
                if [ -s "$wifi" ] && grep -Eq '^[[:space:]]*network[[:space:]]*=' "$wifi";then
                    [ -e "$wifi.before-alicebridge-ca" ] || cp -p "$wifi" "$wifi.before-alicebridge-ca"
                    awk 'BEGIN{done=0} /^[[:space:]]*update_config[[:space:]]*=/{if(!done)print "update_config=0";done=1;next} {print} END{if(!done)print "update_config=0"}' "$wifi" > "$wifi.alicebridge-new"
                else cp /bkupgrade/data/local/tmp/alicebridge-retail-ca/wifi.new "$wifi.alicebridge-new";fi
                chmod 600 "$wifi.alicebridge-new";chown 0:0 "$wifi.alicebridge-new";mv "$wifi.alicebridge-new" "$wifi"
                grep -qx 'update_config=0' "$wifi"
                """:"")+"echo AB_RETAIL_CA_WRITTEN\n";
    }
    public static String returnScript(){return """
        set -eu
        fw_setenv upgrade_step 1
        fw_setenv boot_to_recovery 0
        fw_printenv upgrade_step | grep -qx 'upgrade_step=1'
        fw_printenv boot_to_recovery | grep -qx 'boot_to_recovery=0'
        sync
        if awk '$2=="/mnt/alicebridge-retail-ca"{found=1} END{exit !found}' /proc/mounts;then
            mount -o remount,ro /mnt/alicebridge-retail-ca
            umount /mnt/alicebridge-retail-ca
        fi
        """;}
    public static String cleanupScript(){return "if awk '$2==\"/mnt/alicebridge-retail-ca\"{found=1} END{exit !found}' /proc/mounts;then mount -o remount,ro /mnt/alicebridge-retail-ca && umount /mnt/alicebridge-retail-ca;fi";}
}
