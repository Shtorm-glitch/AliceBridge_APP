package dev.alice.bridge;

import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import rikka.shizuku.Shizuku;
import hev.htproxy.TProxyService;

/** App-side owner of the Shizuku network, native TUN and loopback SOCKS bridge. */
public final class ShizukuNetwork implements AutoCloseable {
    public static volatile String lastStatus="{\"status\":\"not-started\"}";
    private final Context context;
    private IShizukuNetwork remote;
    private ParcelFileDescriptor tun;
    private HotspotSocks socks;
    private TunPacketBridge packets;
    private Network physical;
    private long physicalLostAt;
    private int physicalSwitches;
    private boolean bound;
    private final IBinder lifetime=new Binder();
    private final CountDownLatch connected=new CountDownLatch(1);
    private final Shizuku.UserServiceArgs args;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){remote=IShizukuNetwork.Stub.asInterface(binder);connected.countDown();}
        public void onServiceDisconnected(ComponentName name){remote=null;}
    };
    public ShizukuNetwork(Context context){this.context=context;args=new Shizuku.UserServiceArgs(new ComponentName(context,ShizukuNetworkService.class)).daemon(false).processNameSuffix("network").tag("alicebridge-network").version(BuildConfig.VERSION_CODE);}
    public static boolean selected(Context c){return c.getSharedPreferences("config",Context.MODE_PRIVATE).getString("connection_mode","root").equals("shizuku");}
    public static boolean permitted(){try{return Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(Exception e){return false;}}
    public void prepare(String resolver)throws Exception{
        lastStatus="{\"status\":\"starting\",\"ready\":false}";
        if(Build.VERSION.SDK_INT<33)throw new IOException("Режим Shizuku доступен с Android 13");
        if(!permitted())throw new IOException("Запустите Shizuku и разрешите доступ AliceBridge в инструкции");
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);physical=cm.getActiveNetwork();NetworkCapabilities caps=cm.getNetworkCapabilities(physical);
        if(!usable(caps))throw new IOException("Нужно активное интернет-соединение без другого VPN на телефоне");
        new Handler(Looper.getMainLooper()).post(()->{try{bound=true;Shizuku.bindUserService(args,connection);}catch(Exception e){connected.countDown();}});
        if(!connected.await(15,TimeUnit.SECONDS)||remote==null)throw new IOException("Не удалось запустить сетевую службу Shizuku");
        tun=remote.prepare(resolver,lifetime);if(tun==null)throw new IOException("Shizuku не вернул TUN");
        String user=UUID.randomUUID().toString(),pass=UUID.randomUUID().toString();
        socks=new HotspotSocks(context,physical,resolver,user,pass);socks.start();
        File config=new File(context.getCacheDir(),"shizuku-tunnel.yml");
        String yaml="tunnel:\n  offload:\n    tso: false\n    uso: false\n    jumbo: false\n  mtu: 1500\n  ipv4: 198.18.77.1\n  icmp: 'reply'\nsocks5:\n  address: 127.0.0.1\n  port: "+socks.port()+"\n  username: '"+user+"'\n  password: '"+pass+"'\n  udp: 'udp'\nmisc:\n  max-session-count: 256\n  log-level: warn\n";
        try(FileOutputStream out=new FileOutputStream(config)){out.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        packets=new TunPacketBridge(context,tun);packets.start();
        if(!TProxyService.TProxyStartService(config.getAbsolutePath(),packets.fd()))throw new IOException("Не удалось запустить HEV TUN");
        Thread.sleep(150);if(!TProxyService.TProxyIsRunning())throw new IOException("HEV завершился при запуске");
    }
    public String startHotspot(String ssid,String password)throws Exception{return new JSONObject(remote.startHotspot(ssid,password)).getString("interface");}
    public String probe()throws Exception{return remote.probe();}
    public String neighbors(String iface)throws Exception{check();return remote.neighbors(iface);}
    public void check()throws Exception{
        if(remote==null||!remote.asBinder().isBinderAlive())throw new IOException("Shizuku отключён. Запустите его заново");
        if(!TProxyService.TProxyIsRunning())throw new IOException("Сетевой движок Shizuku остановился");
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Network current=cm.getActiveNetwork();NetworkCapabilities caps=current==null?null:cm.getNetworkCapabilities(current);
        if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))throw new IOException("Отключите VPN на телефоне для режима Shizuku");
        if(current!=null&&usable(caps)&&!current.equals(physical)){
            socks.updatePhysical(current);physical=current;physicalLostAt=0;physicalSwitches++;
            Session.log(context,"NETWORK","Shizuku","Исходящие соединения переведены на новую интернет-сеть телефона; DNS и тестовый маршрут хотспота сохранены.","Интернет телефона переключён");
        }
        boolean available=physical!=null&&usable(cm.getNetworkCapabilities(physical));
        if(!available){if(physicalLostAt==0)physicalLostAt=SystemClock.elapsedRealtime();if(SystemClock.elapsedRealtime()-physicalLostAt>15000)throw new IOException("На телефоне нет исходящей интернет-сети более 15 секунд");}else physicalLostAt=0;
        if(packets!=null&&!packets.error().isEmpty())throw new IOException("Пакетный мост Shizuku остановился: "+packets.error());
        JSONObject state=new JSONObject(remote.status());lastStatus=state.put("physical_network",String.valueOf(physical)).put("physical_available",available).put("physical_switches",physicalSwitches).put("socks",new JSONObject(socks.snapshot())).put("native_running",TProxyService.TProxyIsRunning()).put("packets",packets==null?new JSONObject():packets.snapshot()).toString();if(!state.optBoolean("ready"))throw new IOException(state.optString("error","Маршрут хотспота Shizuku потерян"));
    }
    private static boolean usable(NetworkCapabilities caps){return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&!caps.hasTransport(7);}
    @Override public void close(){
        // Stop the downstream first; do not leave a hotspot using a physical fallback upstream.
        if(remote!=null)try{remote.stop();}catch(Exception ignored){}
        try{TProxyService.TProxyStopService();}catch(Throwable ignored){}
        if(packets!=null){packets.close();packets=null;}
        if(socks!=null){socks.close();socks=null;}if(tun!=null)try{tun.close();}catch(IOException ignored){}tun=null;
        new File(context.getCacheDir(),"shizuku-tunnel.yml").delete();
        if(bound){bound=false;new Handler(Looper.getMainLooper()).post(()->{try{Shizuku.unbindUserService(args,connection,true);}catch(Exception ignored){}});}remote=null;
        try{JSONObject previous=new JSONObject(lastStatus);previous.put("ready",false).put("status","stopped").put("native_running",false);lastStatus=previous.toString();}catch(Exception ignored){lastStatus="{\"status\":\"stopped\",\"ready\":false}";}
    }
}
