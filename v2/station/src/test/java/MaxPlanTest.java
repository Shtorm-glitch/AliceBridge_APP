import dev.alicebridge.station.*;
import java.util.*;
public class MaxPlanTest {
 public static void main(String[] args){
  Map<String,String> valid=Map.of("DEVICE","yandexstation_2","SDK","28","VERSION",MaxPlan.VERSION,"KERNEL","4.9.113","ARCH","aarch64","BOOT","boot-a");MaxPlan.require(valid);
  for(String key:List.of("DEVICE","SDK","VERSION","KERNEL","ARCH","BOOT")){Map<String,String> invalid=new HashMap<>(valid);invalid.put(key,key.equals("BOOT")?"":"wrong");try{MaxPlan.require(invalid);throw new AssertionError(key);}catch(IllegalArgumentException expected){}}
  Map<String,String> numeric=new HashMap<>(valid);numeric.put("VERSION","5000000921");numeric.put("BUILD_ID",MaxPlan.VERSION);MaxPlan.require(numeric);
  Map<String,String> otherBuild=new HashMap<>(valid);otherBuild.put("VERSION","PPR1.180610.011.5000000914");try{MaxPlan.require(otherBuild);throw new AssertionError("0914 cannot use 0921 offsets");}catch(IllegalArgumentException expected){}
  Map<String,String> moved=new HashMap<>(valid);moved.put("IP","10.0.0.2");if(!MaxPlan.attemptKey(valid).equals(MaxPlan.attemptKey(moved)))throw new AssertionError("DHCP must not permit another exploit");
  moved.put("BOOT","boot-b");if(MaxPlan.attemptKey(valid).equals(MaxPlan.attemptKey(moved)))throw new AssertionError("New boot permits new attempt");
  if(!MaxPlan.restoreKey(valid).isEmpty())throw new AssertionError("Do not remember unidentified speakers");
  moved.put("MAC","AA:BB:CC:DD:EE:FF");if(!MaxPlan.restoreKey(moved).equals("max_restore_aa:bb:cc:dd:ee:ff"))throw new AssertionError("Per-speaker restore setting");
  if(MaxPlan.readerRetry("build-gate reader-bringup mismatch")||!MaxPlan.readerRetry("reader-bringup failed"))throw new AssertionError("retry gate");
  MaxPlan.requireIdentity("0\nCapEff:\t0000001fffffffff\nu:r:init:s0\n");
  for(String invalid:List.of("2000\nCapEff: 0000001fffffffff\nu:r:init:s0","0\nCapEff: 0000000000000000\nu:r:init:s0","0\nCapEff: 0000001fffffffff\nu:r:shell:s0")){try{MaxPlan.requireIdentity(invalid);throw new AssertionError("invalid root identity");}catch(IllegalArgumentException expected){}}
  System.out.println("PASS: Max exact build, SDK, kernel, architecture and model gate; permissive retry exclusions");
 }
}
