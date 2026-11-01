package dev.alicebridge.bridge;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Packet-preserving bridge. Only IP/transport metadata is logged, never payload. */
final class TunPacketBridge implements AutoCloseable {
    private final Context context;
    private final ParcelFileDescriptor tun,engine,relay;
    private volatile boolean closed;
    private volatile String failure="";
    private final AtomicLong outbound=new AtomicLong(),inbound=new AtomicLong();
    private final AtomicLong outboundLogged=new AtomicLong(),inboundLogged=new AtomicLong();
    private final AtomicLong outboundBytes=new AtomicLong(),inboundBytes=new AtomicLong();
    TunPacketBridge(Context context,ParcelFileDescriptor tun)throws Exception{
        this.context=context;this.tun=tun;
        FileDescriptor a=new FileDescriptor(),b=new FileDescriptor();
        Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM,0,a,b);
        try{engine=ParcelFileDescriptor.dup(a);relay=ParcelFileDescriptor.dup(b);}finally{Os.close(a);Os.close(b);}
    }
    int fd(){return engine.getFd();}
    void start(){new Thread(()->pump(tun.getFileDescriptor(),relay.getFileDescriptor(),true),"tun-client-to-engine").start();new Thread(()->pump(relay.getFileDescriptor(),tun.getFileDescriptor(),false),"tun-engine-to-client").start();}
    private void pump(FileDescriptor from,FileDescriptor to,boolean outgoing){
        byte[] packet=new byte[65535];
        try{
            while(!closed){StructPollfd wait=new StructPollfd();wait.fd=from;wait.events=(short)OsConstants.POLLIN;if(Os.poll(new StructPollfd[]{wait},1000)==0)continue;if(closed)break;int n;try{n=Os.read(from,packet,0,packet.length);}catch(ErrnoException e){if(e.errno==OsConstants.EAGAIN)continue;throw e;}if(n<=0)break;
                long deadline=android.os.SystemClock.elapsedRealtime()+5000;int sent;
                while(true)try{sent=Os.write(to,packet,0,n);break;}catch(ErrnoException blocked){
                    if(blocked.errno!=OsConstants.EAGAIN&&blocked.errno!=OsConstants.EINTR)throw blocked;
                    if(closed)return;if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("TUN backpressure exceeded 5 seconds");
                    StructPollfd writable=new StructPollfd();writable.fd=to;writable.events=(short)OsConstants.POLLOUT;Os.poll(new StructPollfd[]{writable},100);
                }
                if(sent!=n)throw new IOException("Incomplete TUN datagram");
                long count=(outgoing?outbound:inbound).incrementAndGet();(outgoing?outboundBytes:inboundBytes).addAndGet(n);
                if(outgoing && n>=20 && (packet[0]>>4)==4) BridgeEvents.client((packet[12]&255)+"."+(packet[13]&255)+"."+(packet[14]&255)+"."+(packet[15]&255));
                String metadata=PacketMetadata.describe(packet,n);
                if(count<=6 || (!metadata.contains("MULTICAST") && (outgoing?outboundLogged:inboundLogged).incrementAndGet()<=60))BridgeEvents.log(context,"NETWORK","Shizuku TUN",(outgoing?"Клиент → движок · ":"Движок → клиент · ")+metadata,"Пакет "+count);
            }
            if(!closed)throw new EOFException("TUN packet channel closed");
        }catch(Exception e){if(!closed){failure=e.getClass().getSimpleName()+": "+e.getMessage();BridgeEvents.log(context,"NETWORK","Shizuku TUN",failure,"Ошибка TUN");}}
    }
    String error(){return failure;}
    JSONObject snapshot(){try{return new JSONObject().put("client_to_engine_packets",outbound.get()).put("engine_to_client_packets",inbound.get()).put("client_to_engine_bytes",outboundBytes.get()).put("engine_to_client_bytes",inboundBytes.get()).put("error",failure);}catch(Exception e){return new JSONObject();}}
    @Override public void close(){closed=true;try{relay.close();}catch(IOException ignored){}try{engine.close();}catch(IOException ignored){}/* the controller owns the real TUN descriptor */}
}
