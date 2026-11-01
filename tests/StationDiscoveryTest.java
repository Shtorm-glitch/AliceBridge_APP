import dev.alice.bridge.StationDiscovery;
import java.util.*;
public final class StationDiscoveryTest {
 public static void main(String[] args){
  if(!StationDiscovery.soleSpeaker(Map.of()).isEmpty())throw new AssertionError("empty discovery");
  if(!StationDiscovery.soleSpeaker(Map.of("192.168.1.3","Lite")).equals("192.168.1.3"))throw new AssertionError("one device");
  if(!StationDiscovery.soleSpeaker(Map.of("192.168.1.3","Lite","192.168.1.4","Mini")).isEmpty())throw new AssertionError("must choose when multiple speakers");
  System.out.println("PASS: no device, single device, multiple speakers require selection");
 }
}
