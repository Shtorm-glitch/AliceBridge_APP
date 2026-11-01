import dev.alice.bridge.DnsWire;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class DnsTxtTest {
 public static void main(String[] args)throws Exception {
  byte[] query=DnsWire.query("status.0123456789abcdef0123456789abcdef.alicebridge.invalid",16,123);
  if(DnsWire.question(query).type()!=16)throw new AssertionError("TXT question");
  byte[] text="{\"message\":\"Прошивка передана\",\"version\":\"1.372.20261021\"}".getBytes(StandardCharsets.UTF_8);
  ByteArrayOutputStream raw=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(raw);
  byte[] header=query.clone();header[2]=(byte)0x81;header[3]=(byte)0x80;header[7]=1;d.write(header);
  d.writeShort(0xc00c);d.writeShort(16);d.writeShort(1);d.writeInt(0);d.writeShort(text.length+2);
  d.writeByte(17);d.write(text,0,17);d.writeByte(text.length-17);d.write(text,17,text.length-17);
  byte[] response=raw.toByteArray();
  if(!DnsWire.txtAnswers(response).equals(List.of(new String(text,StandardCharsets.UTF_8))))throw new AssertionError("TXT chunks preserve UTF-8");
  try{DnsWire.txtAnswers(Arrays.copyOf(response,response.length-1));throw new AssertionError("Truncation accepted");}catch(IOException expected){}
  if(!DnsWire.status(response).startsWith("NOERROR"))throw new AssertionError("DNS status");
  System.out.println("PASS: 4 DNS feedback protocol checks");
 }
}
