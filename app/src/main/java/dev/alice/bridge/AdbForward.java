package dev.alice.bridge;

/** A forwarding belongs to a particular transport, not merely to any localhost listener. */
public final class AdbForward {
    public static boolean matches(String listing,String host,int port){
        if(port<1||port>65535)return false;
        for(String line:listing.split("\\R")){
            String[] fields=line.trim().split("\\s+");
            if(fields.length==3&&fields[0].equals(host+":5555")&&fields[1].equals("tcp:"+port)&&fields[2].equals("tcp:8099"))return true;
        }
        return false;
    }
}
