import dev.alice.bridge.HotspotSocks;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
public class HotspotSocksTest {
 static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
 static Socket auth(int port,String password)throws Exception{
  Socket s=new Socket("127.0.0.1",port);s.setSoTimeout(3000);OutputStream o=s.getOutputStream();InputStream in=s.getInputStream();
  o.write(new byte[]{5,1,2});o.flush();require(Arrays.equals(in.readNBytes(2),new byte[]{5,2}),"SOCKS auth selection");
  byte[] p=password.getBytes(StandardCharsets.UTF_8);o.write(new byte[]{1,1,'u',(byte)p.length});o.write(p);o.flush();byte[] reply=in.readNBytes(2);if(password.equals("p"))require(reply.length==2&&reply[1]==0,"valid auth");else require(reply.length==2&&reply[1]!=0,"bad auth denied");return s;
 }
 static void request(Socket s,int command,int port)throws Exception{s.getOutputStream().write(new byte[]{5,(byte)command,0,1,127,0,0,1,(byte)(port>>8),(byte)port});s.getOutputStream().flush();}
 public static void main(String[] args)throws Exception{
  try(HotspotSocks proxy=new HotspotSocks(null,null,"78.24.223.83","u","p")){
   proxy.start();try(Socket rejected=auth(proxy.port(),"bad")){}
   try(ServerSocket echo=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
    Thread t=new Thread(()->{try(Socket socket=echo.accept()){byte[] data=socket.getInputStream().readAllBytes();socket.getOutputStream().write(data);}catch(IOException e){throw new RuntimeException(e);}});t.start();
    try(Socket s=auth(proxy.port(),"p")){request(s,1,echo.getLocalPort());byte[] reply=s.getInputStream().readNBytes(10);require(reply.length==10&&reply[1]==0,"TCP CONNECT");s.getOutputStream().write("payload".getBytes());s.shutdownOutput();require(new String(s.getInputStream().readAllBytes()).equals("payload"),"TCP bidirectional payload");}t.join(3000);
   }
   try(DatagramSocket echo=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));Socket control=auth(proxy.port(),"p");DatagramSocket client=new DatagramSocket()){
    Thread t=new Thread(()->{try{DatagramPacket p=new DatagramPacket(new byte[100],100);echo.receive(p);echo.send(p);}catch(IOException e){}});t.start();
    request(control,3,0);byte[] reply=control.getInputStream().readNBytes(10);require(reply.length==10&&reply[1]==0,"UDP ASSOCIATE");int port=(reply[8]&255)*256+(reply[9]&255);
    int ep=echo.getLocalPort();byte[] packet={0,0,0,1,127,0,0,1,(byte)(ep>>8),(byte)ep,7,8,9};client.setSoTimeout(3000);client.send(new DatagramPacket(packet,packet.length,InetAddress.getLoopbackAddress(),port));DatagramPacket response=new DatagramPacket(new byte[100],100);client.receive(response);require(Arrays.equals(Arrays.copyOf(response.getData(),response.getLength()),packet),"UDP payload + SOCKS framing");t.join(3000);
    packet[2]=1;client.setSoTimeout(300);client.send(new DatagramPacket(packet,packet.length,InetAddress.getLoopbackAddress(),port));try{client.receive(response);throw new AssertionError("SOCKS fragments must be discarded");}catch(SocketTimeoutException expected){}
   }
  }
  // Reproduce the real report: 31 persistent TCP sessions consumed 62 of 64
  // shared workers; the UDP control + reader used the rest, leaving DNS no worker.
  try(HotspotSocks proxy=new HotspotSocks(null,null,"78.24.223.83","u","p");ServerSocket echo=new ServerSocket(0,50,InetAddress.getLoopbackAddress());DatagramSocket udpEcho=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){
   proxy.start();List<Socket> held=new ArrayList<>();java.util.concurrent.ExecutorService replies=java.util.concurrent.Executors.newCachedThreadPool();
   Thread accept=new Thread(()->{try{for(int i=0;i<31;i++){Socket peer=echo.accept();replies.execute(()->{try(peer){int c;while((c=peer.getInputStream().read())!=-1){peer.getOutputStream().write(c);peer.getOutputStream().flush();}}catch(IOException ignored){}});}}catch(IOException ignored){}});accept.start();
   try{
    for(int i=0;i<31;i++){Socket c=auth(proxy.port(),"p");held.add(c);request(c,1,echo.getLocalPort());require(c.getInputStream().readNBytes(10)[1]==0,"stress TCP connect");c.getOutputStream().write(42);c.getOutputStream().flush();require(c.getInputStream().read()==42,"stress TCP echo");}
    try(Socket control=auth(proxy.port(),"p");DatagramSocket client=new DatagramSocket()){
     Thread response=new Thread(()->{try{DatagramPacket p=new DatagramPacket(new byte[100],100);udpEcho.receive(p);udpEcho.send(p);}catch(IOException ignored){}});response.start();
     request(control,3,0);byte[] r=control.getInputStream().readNBytes(10);require(r.length==10&&r[1]==0,"UDP associate under TCP load");int port=(r[8]&255)*256+(r[9]&255),dest=udpEcho.getLocalPort();
     byte[] packet={0,0,0,1,127,0,0,1,(byte)(dest>>8),(byte)dest,42};client.setSoTimeout(3000);client.send(new DatagramPacket(packet,packet.length,InetAddress.getLoopbackAddress(),port));DatagramPacket reply=new DatagramPacket(new byte[100],100);client.receive(reply);require(reply.getData()[reply.getLength()-1]==42,"UDP/DNS work survives 31 persistent TCP sessions");response.join(3000);
    }
   }finally{for(Socket c:held)c.close();echo.close();replies.shutdownNow();accept.join(3000);}
  }
  class BoundNetwork extends android.net.Network {int tcp;@Override public void bindSocket(Socket s){tcp++;}}
  BoundNetwork before=new BoundNetwork(),after=new BoundNetwork();
  try(HotspotSocks proxy=new HotspotSocks(null,before,"78.24.223.83","u","p");ServerSocket echo=new ServerSocket(0,4,InetAddress.getLoopbackAddress())){
   proxy.start();try(Socket old=auth(proxy.port(),"p")){
    request(old,1,echo.getLocalPort());require(old.getInputStream().readNBytes(10)[1]==0,"initial physical bind");try(Socket peer=echo.accept()){
     proxy.updatePhysical(after);require(old.getInputStream().read()==-1,"old-network flow closes after handover");
     try(Socket fresh=auth(proxy.port(),"p")){request(fresh,1,echo.getLocalPort());require(fresh.getInputStream().readNBytes(10)[1]==0,"new flow after handover");try(Socket next=echo.accept()){fresh.getOutputStream().write(7);next.setSoTimeout(3000);require(next.getInputStream().read()==7,"new network forwards payload");}}
    }
   }require(before.tcp==1&&after.tcp==1,"each socket pinned to the selected network");
  }
  System.out.println("PASS: SOCKS authentication, TCP/UDP framing, 31-TCP + UDP starvation regression, physical network handover and cleanup");
 }
}
