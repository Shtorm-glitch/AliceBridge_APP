package dev.alice.bridge;

import android.content.*;
import android.net.*;
import android.os.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Runs only in the permission-approved Shizuku UserService (shell UID). */
public final class ShizukuNetworkService extends IShizukuNetwork.Stub {
    private final Context shell;
    private final ConnectivityManager connectivity;
    private final Object tethering, testManager;
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor();
    private final IBinder lifetime = new Binder();
    private ParcelFileDescriptor tun;
    private Object testInterface, tetherCallback;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network testNetwork;
    private volatile String upstream = "", iface = "", failure = "";
    private volatile boolean ownsHotspot, stopping, armed;
    private String testName = "", dnsResolver="";
    private IBinder owner;
    private final IBinder.DeathRecipient ownerDeath = () -> { stop(); };

    public ShizukuNetworkService(Context context) throws Exception {
        if (android.os.Process.myUid()!=2000 && android.os.Process.myUid()!=0) throw new SecurityException("Shizuku shell service required");
        shell = shellContext(context);
        connectivity = shell.getSystemService(ConnectivityManager.class);
        tethering = shell.getSystemService("tethering");
        testManager = shell.getSystemService("test_network");
        if(tethering==null || testManager==null)throw new IllegalStateException("TestNetworkManager/TetheringManager недоступен на этой прошивке");
    }
    private static Context shellContext(Context context) throws Exception {
        Context base=context instanceof ContextWrapper ? ((ContextWrapper)context).getBaseContext():context;
        Class<?> impl=Class.forName("android.app.ContextImpl"), threadType=Class.forName("android.app.ActivityThread"), apkType=Class.forName("android.app.LoadedApk");
        Field threadField=impl.getDeclaredField("mMainThread");threadField.setAccessible(true);Object thread=threadField.get(base);
        Context system=(Context)threadType.getDeclaredMethod("getSystemContext").invoke(thread);
        Context pkg=system.createPackageContext("com.android.shell",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
        Field apkField=impl.getDeclaredField("mPackageInfo");apkField.setAccessible(true);Object apk=apkField.get(pkg);
        for(Method m:impl.getDeclaredMethods())if(m.getName().equals("createAppContext")){
            Class<?>[] types=m.getParameterTypes();m.setAccessible(true);
            if(Arrays.equals(types,new Class[]{threadType,apkType,String.class}))return (Context)m.invoke(null,thread,apk,"com.android.shell");
        }
        Method m=impl.getDeclaredMethod("createAppContext",threadType,apkType);m.setAccessible(true);return (Context)m.invoke(null,thread,apk);
    }
    private static RuntimeException problem(Throwable e){while(e instanceof InvocationTargetException && e.getCause()!=null)e=e.getCause();return new IllegalStateException(e.getClass().getSimpleName()+": "+e.getMessage());}
    @Override public synchronized ParcelFileDescriptor prepare(String resolver, IBinder owner) {
        try {
            if(Build.VERSION.SDK_INT<33)throw new IllegalStateException("Режим Shizuku требует Android 13+");
            RootShell.validateIpv4(resolver);dnsResolver=resolver;stop();stopping=false;failure="";upstream="";
            for(String name:(String[])tethering.getClass().getMethod("getTetheredIfaces").invoke(tethering))if(wifi(name))throw new IllegalStateException("Отключите уже работающий хотспот и повторите");
            this.owner=owner;owner.linkToDeath(ownerDeath,0);
            LinkAddress[] addresses={LinkAddress.class.getConstructor(String.class).newInstance("198.18.77.1/30")};
            testInterface=testManager.getClass().getMethod("createTunInterface",LinkAddress[].class).invoke(testManager,(Object)addresses);
            tun=(ParcelFileDescriptor)testInterface.getClass().getMethod("getFileDescriptor").invoke(testInterface);
            testName=(String)testInterface.getClass().getMethod("getInterfaceName").invoke(testInterface);
            CountDownLatch published=new CountDownLatch(1);
            networkCallback=new ConnectivityManager.NetworkCallback(){
                private void capture(Network n,LinkProperties p){if(p!=null && testName.equals(p.getInterfaceName())){testNetwork=n;published.countDown();}}
                @Override public void onAvailable(Network n){capture(n,connectivity.getLinkProperties(n));}
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){capture(n,p);}
                @Override public void onLost(Network n){if(n.equals(testNetwork) && !stopping){failure="Тестовая сеть Shizuku потеряна";callbacks.execute(()->stop());}}
            };
            NetworkRequest request=new NetworkRequest.Builder().addTransportType(7).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED).build();
            connectivity.requestNetwork(request,networkCallback);
            LinkProperties properties=new LinkProperties();properties.setInterfaceName(testName);properties.setLinkAddresses(Arrays.asList(addresses));properties.setDnsServers(List.of(InetAddress.getByName(resolver)));properties.setMtu(1500);
            testManager.getClass().getMethod("setupTestNetwork",LinkProperties.class,boolean.class,IBinder.class).invoke(testManager,properties,true,lifetime);
            if(!published.await(12,TimeUnit.SECONDS))throw new IllegalStateException("Android не опубликовал тестовую сеть");
            tethering.getClass().getMethod("setPreferTestNetworks",boolean.class).invoke(tethering,true);
            Class<?> callbackType=Class.forName("android.net.TetheringManager$TetheringEventCallback");
            tetherCallback=java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(),new Class[]{callbackType},(p,m,args)->{
                switch(m.getName()){
                    case "onUpstreamChanged":
                        Network n=args[0] instanceof Network?(Network)args[0]:null;LinkProperties lp=n==null?null:connectivity.getLinkProperties(n);upstream=lp==null?"":String.valueOf(lp.getInterfaceName());
                        if(armed && ownsHotspot && !stopping && !testName.equals(upstream)){failure="Android сменил маршрут хотспота. Остановлено, чтобы DNS не обходился";callbacks.execute(()->stop());}
                        return null;
                    case "hashCode":return System.identityHashCode(p);
                    case "equals":return p==args[0];
                    case "toString":return "AliceBridge Shizuku tethering";
                    default:return null;
                }
            });
            tethering.getClass().getMethod("registerTetheringEventCallback",Executor.class,callbackType).invoke(tethering,callbacks,tetherCallback);
            return ParcelFileDescriptor.dup(tun.getFileDescriptor());
        }catch(Throwable e){stop();throw problem(e);}
    }
    @Override public synchronized String startHotspot(String ssid,String password){
        try{
            if(tun==null)throw new IllegalStateException("Сначала подготовьте тестовую сеть");
            // Refuse to take over an existing hotspot; only stop networks created by this session.
            String[] existing=(String[])tethering.getClass().getMethod("getTetheredIfaces").invoke(tethering);
            for(String name:existing)if(wifi(name))throw new IllegalStateException("Отключите уже работающую точку доступа в настройках Android и повторите");
            Class<?> config=Class.forName("android.net.wifi.SoftApConfiguration"),builderType=Class.forName("android.net.wifi.SoftApConfiguration$Builder");
            Object wifi=shell.getSystemService(Context.WIFI_SERVICE),builder=builderType.getConstructor().newInstance();
            builderType.getMethod("setSsid",String.class).invoke(builder,ssid);builderType.getMethod("setPassphrase",String.class,int.class).invoke(builder,password,1);builderType.getMethod("setBand",int.class).invoke(builder,1);
            Object applied=wifi.getClass().getMethod("setSoftApConfiguration",config).invoke(wifi,builderType.getMethod("build").invoke(builder));
            if(!Boolean.TRUE.equals(applied))throw new IllegalStateException("Android отказал в настройке точки доступа");
            Class<?> requestType=Class.forName("android.net.TetheringManager$TetheringRequest"),requestBuilder=Class.forName("android.net.TetheringManager$TetheringRequest$Builder"),cbType=Class.forName("android.net.TetheringManager$StartTetheringCallback");
            Object req=requestBuilder.getMethod("build").invoke(requestBuilder.getConstructor(int.class).newInstance(0));
            CountDownLatch latch=new CountDownLatch(1);int[] result={-1};
            Object cb=java.lang.reflect.Proxy.newProxyInstance(cbType.getClassLoader(),new Class[]{cbType},(p,m,args)->{
                if(m.getName().equals("onTetheringStarted")){result[0]=0;latch.countDown();}else if(m.getName().equals("onTetheringFailed")){result[0]=(int)args[0];latch.countDown();}
                return m.getName().equals("hashCode")?System.identityHashCode(p):m.getName().equals("equals")?p==args[0]:null;
            });
            // Mark ownership before the request so partial startup is cleaned on every failure.
            ownsHotspot=true;
            tethering.getClass().getMethod("startTethering",requestType,Executor.class,cbType).invoke(tethering,req,callbacks,cb);
            if(!latch.await(20,TimeUnit.SECONDS)||result[0]!=0)throw new IllegalStateException("Ошибка запуска хотспота: "+result[0]);
            long end=SystemClock.elapsedRealtime()+15000;
            while(SystemClock.elapsedRealtime()<end && failure.isEmpty()){
                for(String name:(String[])tethering.getClass().getMethod("getTetheredIfaces").invoke(tethering))if(wifi(name))iface=name;
                if(!iface.isEmpty() && testName.equals(upstream)){armed=true;return status();}
                Thread.sleep(200);
            }
            throw new IllegalStateException(failure.isEmpty()?"Android не выбрал тестовую сеть для хотспота":failure);
        }catch(Throwable e){stop();throw problem(e);}
    }
    private static boolean wifi(String n){return n.matches("(?i).*(wlan|wifi|swlan|ap).* ".trim());}
    @Override public String status(){try{return new JSONObject().put("interface",iface).put("test_interface",testName).put("upstream",upstream).put("ready",ownsHotspot && testName.equals(upstream)&&failure.isEmpty()).put("error",failure).toString();}catch(Exception e){throw problem(e);}}
    @Override public String neighbors(String name){
        if(!name.equals(iface)||!name.matches("[a-zA-Z0-9_.-]{1,32}"))throw new SecurityException("Unknown hotspot interface");
        try{java.lang.Process p=new ProcessBuilder("/system/bin/ip","-4","neigh","show","dev",name).redirectErrorStream(true).start();if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();return "";}return new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){return "";}
    }
    @Override public synchronized void stop(){
        stopping=true;armed=false;
        if(ownsHotspot)try{tethering.getClass().getMethod("stopTethering",int.class).invoke(tethering,0);}catch(Exception ignored){}ownsHotspot=false;iface="";
        if(tetherCallback!=null)try{tethering.getClass().getMethod("unregisterTetheringEventCallback",Class.forName("android.net.TetheringManager$TetheringEventCallback")).invoke(tethering,tetherCallback);}catch(Exception ignored){}tetherCallback=null;
        try{tethering.getClass().getMethod("setPreferTestNetworks",boolean.class).invoke(tethering,false);}catch(Exception ignored){}
        if(testNetwork!=null)try{testManager.getClass().getMethod("teardownTestNetwork",Network.class).invoke(testManager,testNetwork);}catch(Exception ignored){}testNetwork=null;
        if(networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}networkCallback=null;
        if(tun!=null)try{tun.close();}catch(Exception ignored){}tun=null;testInterface=null;
        if(owner!=null){owner.unlinkToDeath(ownerDeath,0);owner=null;}
    }
    @Override public String probe(){
        try{
            if(testNetwork==null)throw new IllegalStateException("Нет тестовой сети");
            byte[] query=DnsWire.query("quasar.yandex.net",1,12345);byte[] udp;
            try(DatagramSocket socket=new DatagramSocket(null)){
                testNetwork.bindSocket(socket);socket.bind(new InetSocketAddress(0));socket.connect(InetAddress.getByName(dnsResolver),53);socket.setSoTimeout(10000);socket.send(new DatagramPacket(query,query.length));DatagramPacket reply=new DatagramPacket(new byte[4096],4096);socket.receive(reply);udp=Arrays.copyOf(reply.getData(),reply.getLength());
            }
            byte[] tcp;
            try(java.net.Socket socket=new java.net.Socket()){
                testNetwork.bindSocket(socket);socket.connect(new InetSocketAddress(dnsResolver,53),10000);socket.setSoTimeout(10000);DnsWire.writeFrame(socket.getOutputStream(),query);tcp=DnsWire.readFrame(socket.getInputStream());
            }
            String http;
            try(java.net.Socket socket=new java.net.Socket()){
                testNetwork.bindSocket(socket);socket.connect(new InetSocketAddress(dnsResolver,80),10000);socket.setSoTimeout(10000);
                socket.getOutputStream().write("HEAD /generate_204 HTTP/1.1\r\nHost: scbh.yandex.net\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                http=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII)).readLine();
            }
            return new JSONObject().put("uid",android.os.Process.myUid()).put("udp",DnsWire.ipv4Answers(udp).toString()).put("tcp",DnsWire.ipv4Answers(tcp).toString()).put("http",http).toString();
        }catch(Throwable e){throw problem(e);}
    }
    @Override public void destroy(){stop();callbacks.shutdownNow();System.exit(0);}
}
