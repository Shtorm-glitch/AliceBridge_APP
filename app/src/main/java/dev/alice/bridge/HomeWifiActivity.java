package dev.alice.bridge;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import static dev.alice.bridge.ScreenUi.*;

/** The final setup step is a real screen, with separate form, reconnection and verified states. */
public final class HomeWifiActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String rendered="";
    private TextView live,waitInfo,help,searchButton;
    private ProgressBar searchProgress;
    private boolean sent;
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,400);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);render();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private void send(String action,String key,String value){startForegroundService(new Intent(this,BridgeService.class).setAction(action).putExtra(key,value));}
    private void autofill(EditText ssid,EditText password,TextView status){
        String initial=ssid.getText().toString();
        new Thread(()->{
            CurrentWifi.Result result;
            try{result=CurrentWifi.read(getApplicationContext());}catch(Exception ignored){result=new CurrentWifi.Result("","");}
            CurrentWifi.Result value=result;
            handler.post(()->{
                if(isFinishing()||isDestroyed()||!rendered.equals("form")||!ssid.isAttachedToWindow())return;
                if(!ssid.getText().toString().equals(initial)||!password.getText().toString().isEmpty()){
                    status.setText("Введите данные сети и проверьте их перед подключением.");return;
                }
                if(value.ssid().isEmpty()){status.setText("Данные текущей Wi-Fi сети недоступны. Введите их вручную.");return;}
                ssid.setText(value.ssid());password.setText(value.password());
                status.setText(value.password().isEmpty()?"Название текущей сети подставлено. Введите её пароль.":"Данные текущей Wi-Fi сети подставлены. Проверьте сеть перед подключением.");
            });
        },"home-wifi-autofill").start();
    }
    private void render(){
        String key=Session.wifiDone?"done":Session.wifiSwitchPending?"pending":"form";
        if(!key.equals(rendered)){
            rendered=key;waitInfo=null;help=null;searchButton=null;searchProgress=null;LinearLayout root=root(this,"Домашний Wi-Fi",this::finish);
            ScrollView scroll=new ScrollView(this);LinearLayout body=column(this);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            TextView badge=text(this,Session.wifiDone?"ПОДКЛЮЧЕНИЕ ПОДТВЕРЖДЕНО":Session.wifiSwitchPending?"ПОИСК В ДОМАШНЕЙ СЕТИ":"ФИНАЛЬНЫЙ ШАГ",11,Session.wifiDone?GREEN:ACCENT);badge.setLetterSpacing(.12f);body.addView(badge,space(this,26));
            if(Session.wifiDone){
                body.addView(text(this,"Алиса в домашней сети",29,TEXT),space(this,14));live=text(this,Session.wifiStatus,16,MUTED);body.addView(live,space(this,14));body.addView(text(this,"Новый адрес: "+Session.station+":5555\nПрошивка: "+Session.stationInfo.getOrDefault("VERSION","—")+"\nADB проверен. Терминал доступен; YSE/Entware и SSH устанавливаются отдельно по кнопкам.",15,MUTED),space(this,16));
                body.addView(button(this,"Открыть панель управления",this::finish,true),space(this,28));
                body.addView(button(this,"Выбрать другую сеть",()->{Session.wifiDone=false;Session.wifiSwitchPending=false;Session.wifiEditing=true;rendered="";render();},false),space(this,12));
            }else if(Session.wifiSwitchPending){
                body.addView(text(this,"Ищем Алису в новой сети",29,TEXT),space(this,14));live=text(this,Session.wifiStatus,15,MUTED);body.addView(live,space(this,14));
                searchProgress=new ProgressBar(this);body.addView(searchProgress,new LinearLayout.LayoutParams(dp(this,30),dp(this,30)));
                waitInfo=text(this,"",13,ACCENT);body.addView(waitInfo,space(this,12));
                LinearLayout note=card(this);note.addView(text(this,"Подождите, пока Алиса сменит сеть",18,TEXT));note.addView(text(this,"1. Подключите телефон к «"+Session.homeSsid+"».\n2. Оставьте AliceBridge открытым: поиск запускается автоматически.\n3. После ответа ADB покажем новый IP и выключим временную точку доступа.",14,MUTED),space(this,10));body.addView(note,space(this,20));
                body.addView(button(this,"Открыть настройки Wi-Fi",()->startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)),false),space(this,12));
                help=text(this,"",14,WARN);body.addView(help,space(this,16));
                EditText ip=field(this,"IP колонки, если известен · необязательно",false);body.addView(ip,space(this,24));searchButton=button(this,"Повторить поиск сейчас",()->{if(Session.busy||Session.wifiSearching){Toast.makeText(this,"Поиск уже выполняется, подождите",Toast.LENGTH_SHORT).show();return;}String address=ip.getText().toString().trim();try{if(!address.isEmpty())RootShell.validateIpv4(address);send("find-home","host",address);}catch(IllegalArgumentException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}},true);body.addView(searchButton,space(this,14));
                body.addView(button(this,"Изменить название сети или пароль",()->{Session.wifiSwitchPending=false;Session.wifiEditing=true;rendered="";render();},false),space(this,12));
            }else{
                body.addView(text(this,"Подключим к вашей сети",29,TEXT),space(this,14));body.addView(text(this,"Выберите обычную Wi-Fi сеть 2,4 ГГц. После переключения найдём Алису на новом адресе и отключим временную точку доступа.",15,MUTED),space(this,14));
                body.addView(text(this,"Название сети",12,MUTED),space(this,26));EditText ssid=field(this,"SSID домашней сети",false);ssid.setText(getSharedPreferences("config",MODE_PRIVATE).getString("home_ssid",""));body.addView(ssid,space(this,8));
                body.addView(text(this,"Пароль",12,MUTED),space(this,20));EditText password=field(this,"Не менее 8 символов",true);body.addView(passwordField(this,password),space(this,8));
                live=text(this,"Пароль отправляется колонке через ADB и не записывается в журнал.",12,MUTED);body.addView(live,space(this,14));
                TextView autofill=text(this,"Читаем данные текущей Wi-Fi сети…",12,MUTED);body.addView(autofill,space(this,8));
                autofill(ssid,password,autofill);
                body.addView(button(this,"Подключить Алису",()->{
                    String name=ssid.getText().toString(),secret=password.getText().toString();
                    try{HomeWifi.script(name,secret);if(name.equals(getSharedPreferences("config",MODE_PRIVATE).getString("hotspot_ssid","AliceBridge")))throw new IllegalArgumentException("Выберите домашнюю сеть, а не AliceBridge");}
                    catch(Exception error){live.setText(error.getMessage());live.setTextColor(WARN);return;}
                    if(!Session.adbConnected){live.setText("Нет соединения ADB. Дождитесь подключения колонки.");live.setTextColor(WARN);return;}
                    sent=true;Session.wifiEditing=true;Session.wifiStatus="Сохраняем настройки домашней сети…";
                    startForegroundService(new Intent(this,BridgeService.class).setAction("home-wifi").putExtra("ssid",name).putExtra("password",secret));password.setText("");live.setText(Session.wifiStatus);
                },true),space(this,26));
            }
        }
        if(live!=null&&(sent||Session.wifiDone||Session.wifiSwitchPending))live.setText(Session.wifiStatus);
        if(waitInfo!=null){
            if(Session.wifiWaitStarted==0)Session.wifiWaitStarted=SystemClock.elapsedRealtime();
            long seconds=Math.max(0,(SystemClock.elapsedRealtime()-Session.wifiWaitStarted)/1000);
            waitInfo.setText((Session.wifiSearching?"Проверяем адреса и ADB":"Ждём колонку · поиск повторяется каждые 10 секунд")+"\nПрошло: "+seconds/60+" мин "+seconds%60+" с · попыток: "+Session.wifiSearchAttempts);
            searchProgress.setVisibility(Session.wifiSearching?android.view.View.VISIBLE:android.view.View.GONE);
            searchButton.setEnabled(!Session.wifiSearching);searchButton.setAlpha(Session.wifiSearching?.5f:1f);
            help.setText(seconds>=60?"Поиск затянулся? Проверьте, что телефон и Алиса в одной Wi-Fi сети, и повторите поиск. Если за 2–3 минуты связи нет, полностью закройте и снова откройте AliceBridge: при запуске приложение заново найдёт колонку. Сбрасывать Алису для этого не нужно.":"Переключение сети может занять некоторое время. Пока Алиса подключается, ADB временно недоступен — это ожидаемо.");
        }
    }
}
