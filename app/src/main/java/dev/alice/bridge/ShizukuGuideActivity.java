package dev.alice.bridge;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.*;
import rikka.shizuku.Shizuku;

public final class ShizukuGuideActivity extends Activity {
    private TextView state;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Shizuku.OnRequestPermissionResultListener permission=(code,result)->{if(code==71)update();};
    private final Runnable tick=new Runnable(){public void run(){update();handler.postDelayed(this,1500);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);Shizuku.addRequestPermissionResultListener(permission);
        LinearLayout root=ScreenUi.root(this,"Shizuku",this::finish);ScrollView scroll=new ScrollView(this);LinearLayout page=ScreenUi.column(this);scroll.addView(page);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        add(page,ScreenUi.text(this,"Хотспот без root телефона",24,ScreenUi.TEXT),22);
        add(page,ScreenUi.text(this,"Экспериментальный режим · Android 13+\nDFRoot и KernelSU для этого режима не нужны. Shizuku запускается с правами ADB. Совместимость зависит от прошивки телефона.",15,ScreenUi.MUTED),12);
        state=ScreenUi.text(this,"Проверяем Shizuku…",16,ScreenUi.ACCENT);add(page,state,18);
        step(page,"1. Скачайте приложение для своего телефона","Установите один из вариантов ниже и разрешите ему работу в фоне.");
        add(page,ScreenUi.button(this,"HyperOS — скачать Shevery ↗",()->browser("https://github.com/HmnDev-Tech/shevery/releases"),false),12);
        add(page,ScreenUi.button(this,"Другие смартфоны — скачать Shizuku ↗",()->browser("https://github.com/RikkaApps/Shizuku/releases/latest"),false),10);
        step(page,"2. Включите беспроводную отладку","Настройки телефона → Для разработчиков → Беспроводная отладка. Телефон должен быть подключён к Wi-Fi.");
        step(page,"3. Запустите Shizuku или Shevery","В выбранном менеджере выберите запуск через беспроводную отладку → Сопряжение. Откройте «Подключить устройство с помощью кода сопряжения» и введите код в уведомлении выбранного приложения. Вернитесь в выбранный менеджер и нажмите «Запустить».");
        LinearLayout mobile=ScreenUi.card(this);
        mobile.addView(ScreenUi.text(this,"Для проверки лучше мобильный интернет",17,ScreenUi.TEXT));
        mobile.addView(ScreenUi.text(this,"Сначала запустите Shizuku или Shevery через беспроводную отладку по Wi-Fi. Затем используйте мобильный интернет телефона для раздачи хотспота. Так проще исключить перехват DNS домашним роутером. Если после переключения служба остановилась, запустите её заново.",14,ScreenUi.MUTED),ScreenUi.space(this,8));
        add(page,mobile,18);
        step(page,"4. Разрешите AliceBridge","Вернитесь сюда и нажмите кнопку ниже. В запросе менеджера разрешите доступ приложению AliceBridge.");
        add(page,ScreenUi.button(this,"Разрешить доступ AliceBridge",this::request,false),10);
        step(page,"5. Начните настройку","Отключите другой VPN и уже работающий хотспот. AliceBridge создаст точку доступа и проверит маршрут DNS. После появления названия сети добавьте колонку в «Доме с Алисой» и вернитесь сюда.");
        add(page,ScreenUi.button(this,"Начать через Shizuku",()->{
            if(Build.VERSION.SDK_INT<33){toast("Нужен Android 13 или новее");return;}if(!ShizukuNetwork.permitted()){request();return;}
            if(Session.flowActive){toast("Сначала завершите текущую сессию");return;}
            getSharedPreferences("config",MODE_PRIVATE).edit().putString("connection_mode","shizuku").apply();
            startForegroundService(new Intent(this,BridgeService.class).setAction(BridgeService.START));finish();
        },true),18);
        add(page,ScreenUi.text(this,"После перезагрузки телефона Shizuku нужно запускать снова. Если Android не поддерживает нужные сетевые API, приложение покажет ошибку — можно выбрать режим Root. Этот режим не устраняет ошибки сертификата самой колонки.",13,ScreenUi.MUTED),16);
    }
    private void step(LinearLayout p,String title,String detail){add(p,ScreenUi.text(this,title,18,ScreenUi.TEXT),22);add(p,ScreenUi.text(this,detail,15,ScreenUi.MUTED),7);}
    private void add(LinearLayout p,android.view.View v,int top){p.addView(v,ScreenUi.space(this,top));}
    private void request(){try{if(!Shizuku.pingBinder()){toast("Сначала запустите службу в приложении Shizuku");return;}if(ShizukuNetwork.permitted()){toast("Доступ уже разрешён");update();return;}Shizuku.requestPermission(71);}catch(Exception e){toast("Не удалось запросить доступ. Откройте Shizuku → Приложения → AliceBridge");}}
    private void update(){if(state==null)return;state.setText(Build.VERSION.SDK_INT<33?"Нужен Android 13 или новее":ShizukuNetwork.permitted()?"✓ Shizuku работает · доступ разрешён":"Shizuku не запущен или доступ ещё не разрешён");}
    private void browser(String url){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.post(tick);}
    @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){Shizuku.removeRequestPermissionResultListener(permission);super.onDestroy();}
}
