package dev.alice.bridge;
import java.io.IOException;
/** Gate readiness on the DNS and HTTP evidence of the actual network engine. */
final class ShizukuProbe {
 static void require(String udp,String tcp,String http,String resolver)throws IOException{
  String expected="["+resolver+"]";
  if(!expected.equals(udp)||!expected.equals(tcp))throw new IOException("DNS вернул другой адрес: UDP="+udp+", TCP="+tcp+"; ожидался "+resolver+". Возможен перехват DNS в сети. Для проверки используйте мобильный интернет телефона вместо домашнего Wi-Fi и отключите другой VPN.");
  if(http==null||!http.matches("HTTP/1\\.[01] 204(?: .*)?"))throw new IOException("Shizuku: проверка интернета не вернула HTTP 204: "+http);
 }
}
