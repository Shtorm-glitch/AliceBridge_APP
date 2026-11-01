import dev.alice.bridge.AdbForward;
public final class AdbForwardTest {
 public static void main(String[] args){
  String listing="192.168.3.33:5555 tcp:44361 tcp:8099\r\n";
  if(!AdbForward.matches(listing,"192.168.3.33",44361))throw new AssertionError("reuse live tunnel");
  if(AdbForward.matches(listing,"192.168.3.33",34369))throw new AssertionError("stale local port after wifi switch");
  if(AdbForward.matches(listing,"10.188.231.191",44361))throw new AssertionError("old transport after wifi switch");
  if(AdbForward.matches("192.168.3.33:5555 tcp:44361 tcp:9000","192.168.3.33",44361))throw new AssertionError("wrong remote service");
  System.out.println("PASS: reuse live ADB forward; reject stale port, old IP, wrong service");
 }
}
