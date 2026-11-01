import dev.alice.bridge.PacketMetadata;
public class PacketMetadataTest {
 public static void main(String[] args){
  byte[] p=new byte[80];p[0]=0x45;p[9]=17;p[12]=10;p[15]=3;p[16]=78;p[17]=24;p[18]=(byte)223;p[19]=83;p[20]=1;p[21]=2;p[23]=53;
  byte[] payload="PRIVATE-PAYLOAD".getBytes();System.arraycopy(payload,0,p,28,payload.length);
  String summary=PacketMetadata.describe(p,p.length);if(!summary.contains("258→53")||summary.contains("PRIVATE"))throw new AssertionError(summary);
  if(!PacketMetadata.describe(p,10).contains("truncated"))throw new AssertionError();p[0]=0x4f;if(!PacketMetadata.describe(p,20).contains("invalid IHL"))throw new AssertionError();
  for(int n=0;n<=p.length;n++)PacketMetadata.describe(p,n);
  System.out.println("PASS: IP metadata only; no payload; short/invalid packet handling");
 }
}
