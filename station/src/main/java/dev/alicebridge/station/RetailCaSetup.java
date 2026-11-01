package dev.alicebridge.station;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Recoverable factory → retail transition, tied to station MAC and boot ID. */
public final class RetailCaSetup {
    private final StationState state;
    private String ssid="",password="";
    private long lastRootRequest;
    RetailCaSetup(StationState state){this.state=state;}
    void credentials(String ssid,String password){this.ssid=ssid;this.password=password;}
    private SharedPreferences prefs(){return state.context.getSharedPreferences("retail-ca",Context.MODE_PRIVATE);}
    private String key(){return state.stationInfo.getOrDefault("MAC","").replace(":","").toLowerCase(Locale.ROOT);}
    boolean pending(){return !key().isEmpty()&&prefs().getBoolean(key()+".waiting",false);}
    private void step(String id,String title,String detail){state.setupSteps=List.of(new StationSetup.Step(id,title,"Выполняется",detail));state.log(state.context,"RETAIL CA",title,detail,"В процессе");}
    private static void require(NativeAdb.Result result)throws IOException{if(result.code()!=0)throw new IOException("Retail CA: команда или проверка завершилась с кодом "+result.code()+". "+result.output());}
    private byte[] asset(String name)throws Exception{try(InputStream in=state.context.getAssets().open("retail-ca/"+name)){return dev.alicebridge.bridge.Streams.readBounded(in,512*1024);}}
    boolean advance(String model)throws Exception {
        var info=state.stationInfo;String key=key(),boot=info.getOrDefault("BOOT","");
        boolean factory=RetailCaPlan.factory(info);
        if(!factory){
            String expected=prefs().getString(key+".sha","");
            if(!expected.isEmpty()){
                if(!"0".equals(info.get("UID")))throw new IOException("Retail загрузился, но root ADB ещё не подтверждён. Оставьте точку и DNS AliceBridge включёнными");
                if(boot.equals(prefs().getString(key+".old_boot","")))throw new IOException("Новая retail-загрузка не подтверждена");
                NativeAdb.Result hash=state.adbClient.shell("sha256sum /system/vendor/quasar/certs/ca-certificates.crt | awk '{print $1}'");require(hash);
                if(!hash.output().trim().equals(expected))throw new IOException("Retail CA после перезагрузки не совпал. Дальнейшая настройка остановлена");
                prefs().edit().putBoolean(key+".waiting",false).putString(key+".verified_boot",boot).commit();
                state.log(state.context,"RETAIL CA",model,"Новая retail-загрузка, UID 0 и SHA256 CA подтверждены","Готово");
            }
            return true;
        }
        if(pending()){
            if(boot.equals(prefs().getString(key+".old_boot",""))){
                step("ca-reboot","Ждём перезагрузку","CA записан и проверен. Сейчас колонка должна загрузить обычную прошивку. Точка и DNS должны оставаться включёнными.");
                if(System.currentTimeMillis()-prefs().getLong(key+".time",0)>180000)throw new IOException("Перезагрузка в retail не подтверждена. Перезапустите питание колонки; повторная замена CA не выполнялась");
                return false;
            }
            throw new IOException("После перезагрузки колонка снова в factory. Проверьте загрузку normal; CA повторно не записывался");
        }
        if(!"0".equals(info.get("UID"))){
            step("factory-root","Проверяем root factory","Запрашиваем adb root перед работой с retail-разделом");
            if(System.currentTimeMillis()-lastRootRequest>10000){lastRootRequest=System.currentTimeMillis();NativeAdb.Result root=state.adbClient.rootStation();require(root);if(root.output().toLowerCase(Locale.ROOT).contains("cannot run as root"))throw new IOException("Factory ADB не разрешил root; CA не изменялся");state.adbConnected=false;}
            return false;
        }
        RetailCaPlan.requireFactory(model,info);
        step("factory-ca","Проверяем factory и retail","Определяем раздел system и его UBI-том rootfs; номер раздела не подставляется наугад");
        boolean mounted=false;File original=null,updated=null,wifi=null;
        try{
            NativeAdb.Result probe=state.adbClient.shell(RetailCaPlan.mountScript());require(probe);mounted=true;
            var layout=StationSetup.parseInfo(probe.output());String oldHash=layout.getOrDefault("CA_SHA","");
            if(!oldHash.matches("[0-9a-f]{64}"))throw new IOException("Не получен хеш retail CA");
            File backups=new File(state.context.getFilesDir(),"retail-ca-backups/"+key);if(!backups.isDirectory()&&!backups.mkdirs())throw new IOException("Нет места для резервной копии CA");
            original=new File(backups,"before-"+oldHash+".pem");require(state.adbClient.pull(RetailCaPlan.TARGET,original));
            byte[] before;try(InputStream in=new FileInputStream(original)){before=dev.alicebridge.bridge.Streams.readBounded(in,512*1024);}
            if(!RetailCaPlan.sha(before).equals(oldHash))throw new IOException("Копия исходного CA не прошла проверку");
            byte[] patched=RetailCaPlan.patched(model,before,asset("lite2-bundle.pem"),asset("aaa-root.pem"));String expected=RetailCaPlan.sha(patched);
            require(state.adbClient.shell("mkdir -p "+RetailCaPlan.STAGE+"; chmod 700 "+RetailCaPlan.STAGE));
            updated=File.createTempFile("retail-ca-", ".pem",state.context.getCacheDir());try(OutputStream out=new FileOutputStream(updated)){out.write(patched);}require(state.adbClient.push(updated,RetailCaPlan.STAGE+"/ca.new"));
            if(model.equals("mini3")){
                String[] credentials=HomeWifi.credentials(ssid,password).split("\n");
                String config="ctrl_interface=/var/run/wpa_supplicant\nupdate_config=0\nnetwork={\n ssid="+credentials[0]+"\n psk="+credentials[1]+"\n key_mgmt=WPA-PSK\n priority=1\n}\n";
                wifi=File.createTempFile("retail-wifi-", ".conf",state.context.getCacheDir());try(OutputStream out=new FileOutputStream(wifi)){out.write(config.getBytes(StandardCharsets.UTF_8));}
                require(state.adbClient.push(wifi,RetailCaPlan.STAGE+"/wifi.new"));require(state.adbClient.shell("chmod 600 "+RetailCaPlan.STAGE+"/wifi.new"));
            }
            step("retail-ca","Добавляем доверие retail",model.equals("mini3")?"Сохраняем исходный CA, добавляем AAA, проверяем маркеры ADB и Wi-Fi перед перезагрузкой":"Сохраняем исходный CA, записываем проверенный бандл и сверяем SHA256");
            require(state.adbClient.shell(RetailCaPlan.writeScript(expected,model.equals("mini3"))));
            if(!prefs().edit().putString(key+".sha",expected).putString(key+".old_boot",boot).putString(key+".model",model).commit())throw new IOException("Не сохранён этап CA. Автоматическая перезагрузка отменена");
            step("ca-reboot","Возвращаемся в обычную прошивку","CA и настройки проверены. Сейчас колонка перезагрузится; после загрузки найдём её снова и проверим root");
            require(state.adbClient.shell("rm -f "+RetailCaPlan.STAGE+"/wifi.new"));
            require(state.adbClient.shell(RetailCaPlan.returnScript()));mounted=false;
            if(!prefs().edit().putBoolean(key+".waiting",true).putLong(key+".time",System.currentTimeMillis()).commit())throw new IOException("Не сохранён этап перезагрузки. Перезапустите колонку вручную");
            state.adbConnected=false;require(state.adbClient.rebootStation());return false;
        }finally{
            if(mounted&&state.adbConnected)try{state.adbClient.shell(RetailCaPlan.cleanupScript());}catch(Exception e){state.log(state.context,"RETAIL CA","Размонтирование",e.toString(),"Проверьте подключение");}
            if(updated!=null)updated.delete();if(wifi!=null)wifi.delete();
            if(state.adbConnected)try{state.adbClient.shell("rm -f "+RetailCaPlan.STAGE+"/wifi.new");}catch(Exception ignored){}
        }
    }
}
