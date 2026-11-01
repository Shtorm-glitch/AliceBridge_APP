package dev.alice.bridge;

/** Bounded IP metadata parser; deliberately never decodes transport payloads. */
public final class PacketMetadata {
    private static int u16(byte[] p,int i){return (p[i]&255)*256+(p[i+1]&255);}
    private static String v4(byte[] p,int i){return (p[i]&255)+"."+(p[i+1]&255)+"."+(p[i+2]&255)+"."+(p[i+3]&255);}
    public static String describe(byte[] p,int n){
        if(n<1||n>p.length)return "truncated";
        int version=(p[0]&255)>>4;
        if(version==4){
            if(n<20)return "IPv4 truncated";int ihl=(p[0]&15)*4;if(ihl<20||ihl>n)return "IPv4 invalid IHL";
            int proto=p[9]&255;String kind=proto==6?"TCP":proto==17?"UDP":proto==1?"ICMP":"PROTO"+proto;
            String source=v4(p,12),target=v4(p,16);String ports="";
            if((proto==6||proto==17)&&n>=ihl+4&&(u16(p,6)&8191)==0){ports=" ports="+u16(p,ihl)+"→"+u16(p,ihl+2);if(proto==6&&n>=ihl+14)ports+=" flags="+Integer.toHexString(p[ihl+13]&255);}
            return "IPv4 "+kind+" "+source+" → "+target+ports+" bytes="+n+((p[16]&255)>=224&&(p[16]&255)<=239?" MULTICAST":"");
        }
        if(version==6)return "IPv6"+(n>=40?" next="+(p[6]&255):" truncated")+" bytes="+n;
        return "IP version="+version+" bytes="+n;
    }
}
