package dev.alice.bridge;
import java.util.*;
public final class DiagnosticTest {
 static void absent(String text,String... values){for(String value:values)if(text.contains(value))throw new AssertionError("Secret retained");}
 public static void main(String[] args){
  DiagnosticRedactor r=new DiagnosticRedactor(List.of("known-wifi-password"));
  String sample="password=abc12345 psk=anotherpass token=secretval&x=1\nAuthorization: OAuth topsecret\nCookie: Session_id=session-secret\n{\"activation_code\":\"123456789\"}\nknown-wifi-password\nstatus.123456abcdef.alicebridge.invalid\n-----BEGIN RSA PRIVATE KEY-----\nkeydata\n-----END RSA PRIVATE KEY-----";
  absent(r.clean(sample),"abc12345","anotherpass","secretval","topsecret","session-secret","123456789","known-wifi-password","123456abcdef","keydata");
  String important="192.168.3.33:5555 unauthorized timeout VERSION=0.369.1 MAC=aa:bb:cc:dd:ee:ff";
  if(!r.clean(important).equals(important))throw new AssertionError("Diagnostic information damaged");
  var events=new ArrayList<JournalView.Entry>();for(int i=0;i<50;i++)events.add(new JournalView.Entry(i,"ADB","192.168.3.33","raw adb connect stdout","Ждём ADB"));events.add(new JournalView.Entry(55,"ERROR","Прошивка","failure reason","Ошибка"));
  String compact=JournalView.format(events,false),full=JournalView.format(events,true);
  if(compact.contains("raw adb connect")||!compact.contains("×50")||!compact.contains("failure reason"))throw new AssertionError("Compact journal");
  if(!full.contains("raw adb connect stdout")||full.contains("×50"))throw new AssertionError("Detailed journal");
  System.out.println("Diagnostic redaction and journal tests passed");
 }
}
