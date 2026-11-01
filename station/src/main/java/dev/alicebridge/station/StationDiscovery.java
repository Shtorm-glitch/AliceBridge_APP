package dev.alicebridge.station;
import dev.alicebridge.bridge.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded local Wi-Fi discovery. Open ports are subsequently verified through ADB and version_full. */
public final class StationDiscovery {
    public static String soleSpeaker(Map<String,String> found){return found.size()==1?found.keySet().iterator().next():"";}
    public static List<String> candidates(String previous)throws Exception{
        LinkedHashSet<String> hosts=new LinkedHashSet<>();if(previous!=null&&!previous.isEmpty())hosts.add(previous);
        LinkedHashSet<String> prefixes=new LinkedHashSet<>();Set<String> own=new HashSet<>();
        for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())){
            if(!network.isUp()||!network.getName().matches("(?i).*(wlan|swlan|wifi|ap[0-9]).*"))continue;
            for(InterfaceAddress address:network.getInterfaceAddresses())if(address.getAddress() instanceof Inet4Address&&address.getAddress().isSiteLocalAddress()){
                String ip=address.getAddress().getHostAddress();own.add(ip);prefixes.add(ip.substring(0,ip.lastIndexOf('.')+1));
            }
        }
        ExecutorService scanner=Executors.newFixedThreadPool(20);List<Future<String>> tasks=new ArrayList<>();
        try{
            for(String prefix:prefixes)for(int n=1;n<255;n++){String host=prefix+n;if(own.contains(host)||host.equals(previous))continue;tasks.add(scanner.submit(()->{try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,5555),220);return host;}catch(Exception ignored){return "";}}));}
            for(Future<String> task:tasks){String host=task.get();if(!host.isEmpty())hosts.add(host);}
        }finally{scanner.shutdownNow();}
        return new ArrayList<>(hosts);
    }
}
