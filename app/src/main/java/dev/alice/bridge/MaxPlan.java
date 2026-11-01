package dev.alice.bridge;
import java.util.*;
/** The supplied Max runtime tools are reviewed only for the original YNDX-0008 on 0921. */
public final class MaxPlan {
 public static final String VERSION="PPR1.180610.011.5000000921";
 public static final String BASE="/data/local/tmp/alicebridge-max";
 public static final Map<String,String> HASHES=Map.of("root-bridge","f53c3e127a95b8f78b0934ccea73382964d35fd7b3dd21b8b0ce258f50704474","permissive","53d9aa327156066c994ae18e842619adbf9072e14b7ab12b8deb2c9e3b7baa74");
 public static boolean matches(Map<String,String> info){return List.of("PLATFORM","BUILDNAME","DEVICE").stream().anyMatch(k->"yandexstation_2".equals(info.get(k)));}
 public static String restoreKey(Map<String,String> info){String mac=info.getOrDefault("MAC","").toLowerCase(Locale.ROOT);return mac.matches("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")&&!mac.equals("00:00:00:00:00:00")?"max_restore_"+mac:"";}
 public static String attemptKey(Map<String,String> info){String boot=info.getOrDefault("BOOT","");if(boot.isEmpty())throw new IllegalArgumentException("Нет идентификатора загрузки Макс");return "max_attempt_"+boot;}
 public static void require(Map<String,String> info){
  if(!matches(info)||!"28".equals(info.get("SDK"))||!VERSION.equals(info.getOrDefault("BUILD_ID",info.get("VERSION")))||!info.getOrDefault("KERNEL","").matches("4\\.9\\.113(?:[-+].*)?")||!"aarch64".equals(info.get("ARCH")))throw new IllegalArgumentException("Этот root предназначен только для Макс YNDX-0008: Android 9, ядро 4.9.113, сборка "+VERSION+". Бинарники не запускались.");
  if(info.getOrDefault("BOOT","").isEmpty())throw new IllegalArgumentException("Не получен идентификатор загрузки Макс");
 }
 public static void requireIdentity(String output){
  if(!output.trim().matches("(?s)0\\r?\\n.*")||!output.contains("u:r:init:s0"))throw new IllegalArgumentException("Не подтверждены UID 0 и init-SID Макс: "+output);
  java.util.regex.Matcher caps=java.util.regex.Pattern.compile("CapEff:\\s*([0-9a-fA-F]+)").matcher(output);
  if(!caps.find()||(Long.parseUnsignedLong(caps.group(1),16)&((1L<<1)|(1L<<21)))!=((1L<<1)|(1L<<21)))throw new IllegalArgumentException("Не подтверждены root capabilities Макс: "+output);
 }
 public static List<StationSetup.Step> pending(){return List.of(new StationSetup.Step("maxroot","Root Станции Макс","Ожидание",""),new StationSetup.Step("maxguard","Защита cache recovery","Ожидание",""),new StationSetup.Step("maxpermissive","SELinux Permissive","Ожидание",""));}
 public static boolean readerRetry(String output){return output.contains("reader-bringup")&&!output.contains("mismatch")&&!output.contains("build-gate");}
}
