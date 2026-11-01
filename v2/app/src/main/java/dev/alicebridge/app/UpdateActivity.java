package dev.alicebridge.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import java.io.File;
import static dev.alicebridge.app.ScreenUi.*;

public final class UpdateActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,action;
    private ProgressBar progress;
    private UpdateInfo.Release release;
    private File downloaded;
    private boolean waitingPermission,loading;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{release=UpdateInfo.parse(getIntent().getStringExtra("release"));}catch(Exception invalid){finish();return;}
        LinearLayout root=root(this,"Обновление",this::finish);LinearLayout body=column(this);ScrollView scroll=new ScrollView(this);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(text(this,"ALICEBRIDGE "+release.version(),12,ACCENT),space(this,26));body.addView(text(this,"Скоро всё будет готово",27,TEXT),space(this,12));
        body.addView(text(this,"Скачаем APK и откроем установку Android. Ваши настройки сохранятся.",15,MUTED),space(this,12));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);body.addView(progress,space(this,26));status=text(this,"Подготовка загрузки…",15,MUTED);body.addView(status,space(this,14));
        action=button(this,"Скачать ещё раз",()->{if(downloaded==null)download();else install();},true);action.setVisibility(android.view.View.GONE);body.addView(action,space(this,24));
        LinearLayout changes=card(this);changes.addView(text(this,"Что нового",18,TEXT));for(String line:release.changes())changes.addView(text(this,"• "+line,14,MUTED),space(this,12));body.addView(changes,space(this,24));
        download();
    }
    private void download(){
        if(loading)return;loading=true;action.setVisibility(android.view.View.GONE);progress.setIndeterminate(true);status.setText("Подключаемся к серверу…");
        new Thread(()->{
            try{
                File file=AppUpdates.download(getApplicationContext(),release,(received,total)->handler.post(()->{if(isDestroyed())return;progress.setIndeterminate(false);progress.setProgress((int)(received*100/total));status.setText(String.format(java.util.Locale.forLanguageTag("ru"),"Скачано %.1f из %.1f МБ",received/1048576d,total/1048576d));}));
                handler.post(()->{if(isFinishing()||isDestroyed())return;loading=false;downloaded=file;progress.setIndeterminate(false);progress.setProgress(100);status.setText("APK скачан и проверен. Готов к установке.");action.setText("Установить обновление");action.setVisibility(android.view.View.VISIBLE);install();});
            }catch(Exception error){handler.post(()->{if(isFinishing()||isDestroyed())return;loading=false;progress.setIndeterminate(false);status.setText("Не удалось скачать обновление: "+error.getMessage()+"\nПроверьте интернет и повторите загрузку.");action.setText("Повторить загрузку");action.setVisibility(android.view.View.VISIBLE);});}
        },"alicebridge-update-download").start();
    }
    private boolean busy(){
        AppSnapshot state=((BridgeApplication)getApplication()).getRepository().getState().getValue();
        return state.getBusy()||state.getTools().getBusy()||(state.getRunning()&&(state.getSession()==null||!state.getSession().getAdbVerified()));
    }
    private void install(){
        if(downloaded==null)return;
        if(busy()){status.setText("Обновление готово. Завершите текущую настройку колонки перед установкой.");return;}
        if(!getPackageManager().canRequestPackageInstalls()){
            status.setText("Разрешите AliceBridge устанавливать обновления на следующем экране, затем вернитесь сюда.");action.setText("Разрешить установку");waitingPermission=true;
            try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}catch(ActivityNotFoundException unavailable){waitingPermission=false;status.setText("Откройте настройки Android и разрешите установку приложений из AliceBridge.");}return;
        }
        waitingPermission=false;action.setText("Открыть установку ещё раз");
        Uri uri=UpdateProvider.uri(this,downloaded);Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);install.setClipData(ClipData.newUri(getContentResolver(),"AliceBridge update",uri));
        try{startActivity(install);status.setText("Подтвердите обновление в установщике Android. Если отменили, можно открыть его ещё раз.");}catch(ActivityNotFoundException unavailable){status.setText("На телефоне не найден установщик APK.");}
    }
    @Override protected void onResume(){super.onResume();if(waitingPermission&&getPackageManager().canRequestPackageInstalls())install();}
}
