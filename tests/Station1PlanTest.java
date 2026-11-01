import dev.alice.bridge.*;
import java.util.*;
import java.nio.file.*;
public class Station1PlanTest {
 public static void main(String[] args)throws Exception{
  if(!Station1Plan.matches(Map.of("PLATFORM","yandexstation","BOARD","tulip")))throw new AssertionError();
  if(!Station1Plan.matches(Map.of("DEVICE","tulip-d1","BOARD","tulip","SDK","23")))throw new AssertionError();
  for(String device:List.of("yandexmicro","yandexmini","yandexstation_2"))if(Station1Plan.matches(Map.of("DEVICE",device)))throw new AssertionError(device);
  if(Station1Plan.matches(Map.of("PLATFORM","yandexstation","DEVICE","yandexstation_2")))throw new AssertionError("Max conflict");
  if(!DeviceNames.name(Map.of("BUILDNAME","yandexmini_2")).equals("Станция Мини 2"))throw new AssertionError();
  if(!DeviceNames.name(Map.of("DEVICE","yandexstation_2")).equals("Станция Макс"))throw new AssertionError();
  if(!DeviceNames.name(Map.of("BUILDNAME","yandexmicro")).equals("Станция Лайт 1"))throw new AssertionError();
  Files.createDirectories(Path.of("build/station1-tests"));Files.writeString(Path.of("build/station1-tests/flash.sh"),Station1Plan.writeScript());
  Files.writeString(Path.of("build/station1-tests/backup.sh"),Station1Plan.backupScript("/data/local/tmp/alicebridge-station1/boot-before.img"));
  Files.writeString(Path.of("build/station1-tests/cleanup.sh"),Station1Plan.cleanupScript());
  System.out.println("PASS: Station 1 applicability, Max/Lite exclusion, model names, flash script exported");
 }
}
