import dev.alicebridge.station.Station1Plan;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class Station1PlanRegressionTest {
 @Test public void profileAndWriteGuards()throws Exception{
  assertTrue(Station1Plan.matches(Map.of("BOARD","tulip","PLATFORM","yandexstation")));
  assertFalse(Station1Plan.matches(Map.of("BOARD","tulip","DEVICE","yandexstation_2","PLATFORM","yandexstation")));
  assertFalse(Station1Plan.matches(Map.of("BOARD","cucumber","PLATFORM","yandexmicro")));
  String script=Station1Plan.writeScript();
  assertTrue(script.contains("/dev/block/mmcblk0p6"));assertTrue(script.contains("= 32768"));
  assertTrue(script.indexOf("sha256sum /data/local/tmp/boot-root.img")<script.indexOf("dd if=/data/local/tmp/boot-root.img"));
  assertTrue(script.contains("dd if=/dev/block/mmcblk0p6"));
  assertFalse(script.contains(Station1Plan.STOCK_HASH));
  Path output=Path.of("build/station1-tests");Files.createDirectories(output);
  Utf8.write(output.resolve("flash.sh"),script);Utf8.write(output.resolve("cleanup.sh"),Station1Plan.cleanupScript());
 }
}
