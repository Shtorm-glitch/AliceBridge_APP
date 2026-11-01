package dev.alicebridge.bridge;
import java.io.IOException;
/** Gate readiness on the DNS and HTTP evidence of the actual network engine. */
public final class ShizukuProbe {
 public static void require(String udp,String tcp,String http,String resolver)throws IOException{
  String expected="["+resolver+"]";
  if(!expected.equals(udp)||!expected.equals(tcp))throw new IOException("DNS вернул другой адрес: UDP="+udp+", TCP="+tcp+"; ожидался "+resolver+". Возможен перехват DNS в сети. Проверьте адрес и доступность сервера. С публичным сервером попробуйте мобильный интернет без другого VPN; локальный сервер должен быть доступен телефону по локальной сети.");
  if(http==null||!http.matches("HTTP/1\\.[01] 204(?: .*)?"))throw new IOException("Shizuku: проверка интернета не вернула HTTP 204: "+http);
 }
}
