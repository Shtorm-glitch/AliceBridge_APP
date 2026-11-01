package dev.alicebridge.station;
import dev.alicebridge.bridge.RootShell;
import java.util.*;

public final class MaxHybridPlan {
 public static final String FINGERPRINT="Yandex/yandexstation_2/yandexstation_2:9/PPR1.180610.011/5000000921:user/release-keys";
 public static final String ROOTD_SHA="30ee1e1f92623322ccdc77a3ff1d548ebab4f3ee003d5fc3258b473b490a927f";
 public static final String PERM_SHA="f580ff0283a181936309d2d1fcf2dae0e7da6e21a7888bdc10e6675947d3746b";
 public static final String RC_SHA="0940cfb282167e54193a10126de0c9cc1e62a2390201884220a42788d0b1bbf4";
 public static void require(Map<String,String> info){MaxPlan.require(info);if(!FINGERPRINT.equals(info.get("FINGERPRINT")))throw new IllegalArgumentException("Гибрид rootd поддерживает только точный fingerprint Макс 0921");}
 public static void addresses(String router,String dns,String ipv6){
  RootShell.validateIpv4(router);RootShell.validateIpv4(dns);
  String[] r=router.split("\\.");int a=Integer.parseInt(r[0]),b=Integer.parseInt(r[1]);
  if(!(a==10||a==192&&b==168||a==172&&b>=16&&b<=31))throw new IllegalArgumentException("Укажите адрес домашнего роутера в локальной сети");
  int d=Integer.parseInt(dns.substring(0,dns.indexOf('.')));if(d==0||d==127||d>=224)throw new IllegalArgumentException("Некорректный DNS для перенаправления");
  if(!Set.of("none","drop").contains(ipv6))throw new IllegalArgumentException("IPv6: none или drop");
 }
 public static String config(String router,String dns,String ipv6){addresses(router,dns,ipv6);return "ROUTER_IP="+router+"\nDNS_IP="+dns+"\nIPV6="+ipv6+"\n";}
 public static String launcher(String base,String operation,String id,String backup,boolean replace,boolean unmarked){
  if(!base.matches("/data/local/tmp/alicebridge-hybrid-[a-f0-9]{32}")||!id.matches("[a-f0-9]{32}")||!Set.of("install","rollback").contains(operation))throw new IllegalArgumentException("Некорректная операция rootd");
  if(operation.equals("rollback")&&!backup.matches("/data/local/tmp/rootd-backup-[A-Za-z0-9._-]+"))throw new IllegalArgumentException("Нужен путь проверенной резервной копии rootd");
  String command=operation.equals("install")?"/system/bin/sh \"$BASE/hybrid/install-device.sh\"":"/system/bin/sh \"$BASE/hybrid/rollback-device.sh\" "+(replace?"--replace-config ":"")+(unmarked?"--allow-unmarked ":"")+RootShell.quote(backup);
  return "#!/system/bin/sh\nset -eu\numask 077\nBASE="+RootShell.quote(base)+"\nexport BASE\nOPERATION_ID="+id+"\nSTAGING_NONCE="+id+"\nexport OPERATION_ID STAGING_NONCE\n"+
   ". \"$BASE/hybrid/common.sh\"\nroot_gate\nbuild_gate\n"+
   "n=0\nwhile :; do\n names=$(process_names) || exit 71\n second=$(count_second_channels \"$names\")\n [ \"$second\" = 0 ] && break\n n=$((n+1));[ \"$n\" -lt 20 ] || exit 72\n sleep 1\ndone\n"+
   "set +e\n"+command+"\nrc=$?\nset -e\n"+
   "printf '%s\\n' \"$rc\" > /data/local/tmp/ab-hybrid-"+id+".result.new\nchown 2000:2000 /data/local/tmp/ab-hybrid-"+id+".result.new\nchmod 0600 /data/local/tmp/ab-hybrid-"+id+".result.new\nmv /data/local/tmp/ab-hybrid-"+id+".result.new /data/local/tmp/ab-hybrid-"+id+".result\nexit \"$rc\"\n";
 }
}
