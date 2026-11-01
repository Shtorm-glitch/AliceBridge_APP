import dev.alicebridge.station.RemoteShell;
import dev.alicebridge.station.HomeWifi;
import java.nio.file.*;
public class PostSetupSafetyTest {
 public static void main(String[]args)throws Exception{
  String marker="AB_TEST_EXIT=";
  for(int code:new int[]{0,7,42}){
   Process p=new ProcessBuilder("sh","-c",RemoteShell.wrap("echo output; exit "+code,marker)).redirectErrorStream(true).start();
   String output=new String(p.getInputStream().readAllBytes());p.waitFor();
   var result=RemoteShell.parse(output,marker);
   if(result.code()!=code||!result.output().equals("output"))throw new AssertionError("remote exit "+code);
  }
  if(RemoteShell.parse("disconnected",marker).code()==0)throw new AssertionError("missing marker");
  String noisy="root@station:/ # echoed startup command\r\n"+marker+"BEGIN\r\ne1db1234\r\n"+marker+"0\r\n";
  if(!RemoteShell.parse(noisy,marker).output().equals("e1db1234"))throw new AssertionError("Root PTY startup leaked into command output");
  String wifi=HomeWifi.script("IEEE","password");
  if(!wifi.contains("f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e")||wifi.contains("password"))throw new AssertionError("WPA2 derivation");
  String injection="a'$(touch /tmp/bad)";
  if(HomeWifi.script(injection,"password").contains(injection))throw new AssertionError("SSID injection");
  try{HomeWifi.script("ssid","short");throw new AssertionError("short PSK");}catch(IllegalArgumentException expected){}
  Utf8.write(Path.of(args[0],"home-wifi.sh"),wifi);
  System.out.println("PASS: legacy ADB exit codes, disconnect detection, WPA2 vector, credential quoting and validation");
 }
}
