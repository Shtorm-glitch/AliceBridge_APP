package dev.alice.station;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Standalone API-23 DEX, executed by the Station 1 root shell, never by the phone. */
public final class StationWifi {
    private static Object manager;
    private static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{
        return target.getClass().getMethod(name,types).invoke(target,args);
    }
    private static Object get(String name)throws Exception{return call(manager,name,new Class<?>[0]);}
    private static boolean network(String name,int id)throws Exception{return Boolean.TRUE.equals(call(manager,name,new Class<?>[]{int.class},id));}
    private static boolean enable(int id)throws Exception{return Boolean.TRUE.equals(call(manager,"enableNetwork",new Class<?>[]{int.class,boolean.class},id,true));}
    private static boolean save()throws Exception{return Boolean.TRUE.equals(get("saveConfiguration"));}
    private static int current()throws Exception{return ((Integer)call(get("getConnectionInfo"),"getNetworkId",new Class<?>[0])).intValue();}
    private static String state()throws Exception{return String.valueOf(call(get("getConnectionInfo"),"getSupplicantState",new Class<?>[0]));}
    private static String decode(String hex)throws Exception{
        if(!hex.matches("[0-9a-f]{2,64}")||hex.length()%2!=0)throw new IOException("Invalid SSID");
        byte[] bytes=new byte[hex.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return new String(bytes,"UTF-8");
    }
    @SuppressWarnings("unchecked") private static List<Object> configs()throws Exception{
        return (List<Object>)get("getPrivilegedConfiguredNetworks");
    }
    public static void main(String[] args){
        int code=1;
        try{
            Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
            Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
            Object context=at.getMethod("getSystemContext").invoke(thread);
            manager=Class.forName("android.content.Context").getMethod("getSystemService",String.class).invoke(context,"wifi");
            if(manager==null)throw new IOException("Wi-Fi service missing");
            List<Object> configured=configs();if(configured==null)throw new IOException("Cannot back up Wi-Fi profiles");
            if(args.length==1&&args[0].equals("probe")){
                System.out.println("STATION1_WIFI_API_OK");System.out.println("STATE="+state());System.out.println("PROFILE_COUNT="+configured.size());code=0;
                Object wifi=get("getConnectionInfo");byte[] ssidBytes;
                try{Object raw=call(wifi,"getWifiSsid",new Class<?>[0]);ssidBytes=(byte[])call(raw,"getOctets",new Class<?>[0]);}
                catch(Exception unavailable){String ssid=String.valueOf(call(wifi,"getSSID",new Class<?>[0]));if(ssid.startsWith("\"")&&ssid.endsWith("\""))ssid=ssid.substring(1,ssid.length()-1);ssidBytes=ssid.equals("<unknown ssid>")?new byte[0]:ssid.getBytes("UTF-8");}
                StringBuilder encoded=new StringBuilder();for(byte b:ssidBytes)encoded.append(String.format(java.util.Locale.US,"%02x",b&255));System.out.println("SSID_HEX="+encoded);
            }else if(args.length==2&&args[0].equals("switch"))code=switchNetwork(new File(args[1]),configured);
            else throw new IOException("Invalid operation");
        }catch(Exception error){Throwable cause=error instanceof InvocationTargetException?((InvocationTargetException)error).getTargetException():error;System.err.println("Wi-Fi API: "+cause.getClass().getSimpleName());}
        System.exit(code);
    }
    private static int switchNetwork(File file,List<Object> configured)throws Exception{
        if(!file.getCanonicalPath().startsWith("/data/quasar/alicebridge-home-wifi/"))throw new IOException("Invalid credentials path");
        String ssid,psk;
        try{BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"));try{ssid=decode(reader.readLine());psk=reader.readLine();}finally{reader.close();}}
        finally{file.delete();}
        if(psk==null||!psk.matches("[0-9a-f]{64}"))throw new IOException("Invalid PSK");
        String quoted="\""+ssid.replace("\\","\\\\").replace("\"","\\\"")+"\"";
        Class<?> type=Class.forName("android.net.wifi.WifiConfiguration");Object previous=null;
        int old=current(),chosen=-1;
        for(Object config:configured)if(quoted.equals(type.getField("SSID").get(config))){
            Object secret=type.getField("preSharedKey").get(config);
            if(secret==null||secret.equals("*"))throw new IOException("Cannot back up existing profile");
            previous=type.getConstructor(type).newInstance(config);break;
        }
        Object config=type.getConstructor().newInstance();type.getField("SSID").set(config,quoted);type.getField("preSharedKey").set(config,psk);
        ((BitSet)type.getField("allowedKeyManagement").get(config)).set(1);
        try{
            String method=previous==null?"addNetwork":"updateNetwork";
            if(previous!=null)type.getField("networkId").setInt(config,type.getField("networkId").getInt(previous));
            chosen=((Integer)call(manager,method,new Class<?>[]{type},config)).intValue();
            if(chosen<0||!save())throw new IOException("Cannot save network");
            Thread.sleep(3000);
            if(!enable(chosen)||!Boolean.TRUE.equals(get("reconnect")))throw new IOException("Cannot connect");
            long deadline=System.currentTimeMillis()+45000;
            while(System.currentTimeMillis()<deadline){
                if(current()==chosen&&state().equals("COMPLETED")&&((Integer)call(get("getConnectionInfo"),"getIpAddress",new Class<?>[0])).intValue()!=0){
                    if(!save())throw new IOException("Cannot persist network");System.out.println("CONNECTED");return 0;
                }
                Thread.sleep(1000);
            }
            throw new IOException("Connection timeout");
        }catch(Exception failure){
            if(previous!=null)call(manager,"updateNetwork",new Class<?>[]{type},previous);
            else if(chosen>=0)network("removeNetwork",chosen);
            if(old>=0){enable(old);get("reconnect");}save();
            System.out.println("ROLLED_BACK");return 1;
        }
    }
}
