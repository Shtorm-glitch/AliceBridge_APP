import dev.alicebridge.station.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
public class ConfiguredAndCleanupTest {
 @Test public void configuredRequiresMandatoryProofsOnly(){
  Map<String,String> good=Map.of("OTA","1","ADB","1","ANALYTICS","1");
  assertTrue(StationSetup.alreadyConfigured(good));
  for(String key:List.of("OTA","ADB")){
   Map<String,String> missing=new HashMap<>(good);missing.remove(key);assertFalse(StationSetup.alreadyConfigured(missing));
   missing.put(key,"0");assertFalse(StationSetup.alreadyConfigured(missing));
  }
 }
 @Test public void manualCleanupDeletesOnlyOtaArtifactsWithoutBackup()throws Exception{
  Path root=Files.createTempDirectory("ota-cleanup-fixture-");Path downloads=Files.createDirectory(root.resolve("downloads"));Path cache=Files.createDirectory(root.resolve("cache"));
  Path installed=root.resolve("installed-firmware");Files.write(installed,new byte[]{1,2,3});
  Path old=downloads.resolve("update_0.369.20261021.img");Files.write(old,new byte[]{4,5});
  Path zip=cache.resolve("update_2.369.20261013.zip");Files.write(zip,new byte[]{6});
  Path unrelated=downloads.resolve("update_personal.img");Files.write(unrelated,new byte[]{7});
  Path link=downloads.resolve("update_999.img");Files.createSymbolicLink(link,installed);
  String script=OtaCacheCleanup.script().replace("/bkupgrade/",downloads+"/").replace("/cache/",cache+"/").replace("\"$(id -u)\"","\"0\"").replace("\n        sync\n","\n        :\n").replace("\nsync\n","\n:\n").replace("df -Pk /data","df -Pk "+root);
  Process p=new ProcessBuilder("/bin/sh","-c",script).redirectErrorStream(true).start();String output=new String(p.getInputStream().readAllBytes());assertEquals(output,0,p.waitFor());
  assertTrue(output,output.contains("AB_REMOVED=2"));assertFalse(Files.exists(old));assertFalse(Files.exists(zip));
  assertTrue(Files.exists(unrelated));assertTrue(Files.isSymbolicLink(link));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(installed));
  assertFalse(OtaCacheCleanup.script().contains("backup"));assertFalse(OtaCacheCleanup.script().contains("sha256"));
 }
}
