package dev.alice.bridge;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Live ADB evidence and individual setup outcomes, separate from firmware transfer progress. */
public final class StationCard extends LinearLayout {
    private final TextView title,details, status, error, yam, wifi, packages, wifiButton;
    private final TextView[] steps = new TextView[3];
    private final TextView retry;
    public StationCard(Context c, Runnable retryAction, Runnable packagesAction, Runnable yamAction, Runnable wifiAction) {
        super(c); setOrientation(VERTICAL); setPadding(dp(20),dp(18),dp(20),dp(18));
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xff141a29);bg.setCornerRadius(dp(24));setBackground(bg);
        title=text("ВАША АЛИСА",11,0xffb6a1ff);title.setLetterSpacing(.12f);addView(title);
        status=text("Читаем данные через ADB…",20,0xffedf0fc);status.setTypeface(null,Typeface.BOLD);addView(status,spaced(10));
        details=text("",13,0xff909ab3);details.setTextIsSelectable(true);details.setLineSpacing(dp(4),1);addView(details,spaced(10));
        for(int i=0;i<3;i++){steps[i]=text("",14,0xffedf0fc);addView(steps[i],spaced(13));}
        error=text("",13,0xffffba9e);addView(error,spaced(12));
        retry=text("Повторить настройку  ↻",14,0xffb6a1ff);retry.setMinHeight(dp(48));retry.setGravity(android.view.Gravity.CENTER_VERTICAL);retry.setOnClickListener(v->retryAction.run());addView(retry);
        yam=text("",14,0xffb6a1ff);addView(yam,spaced(12));
        TextView yamButton=text("YAM · пакеты и плагины  →",15,0xffb6a1ff);yamButton.setMinHeight(dp(48));yamButton.setGravity(android.view.Gravity.CENTER_VERTICAL);yamButton.setOnClickListener(v->yamAction.run());addView(yamButton,spaced(12));
        packages=text("Пакеты Entware  →",15,0xffb6a1ff);packages.setMinHeight(dp(48));packages.setGravity(android.view.Gravity.CENTER_VERTICAL);packages.setOnClickListener(v->packagesAction.run());addView(packages);
        wifi=text("",14,0xff909ab3);addView(wifi,spaced(12));
        wifiButton=text("Подключить домашний Wi-Fi  →",15,0xffb6a1ff);wifiButton.setMinHeight(dp(48));wifiButton.setGravity(android.view.Gravity.CENTER_VERTICAL);wifiButton.setOnClickListener(v->wifiAction.run());addView(wifiButton);
        TextView note=text("Перед изменениями сохраняем исходные настройки на колонке. Работу постоянного ADB после перезагрузки ещё нужно подтвердить.",11,0xff909ab3);addView(note,spaced(12));
    }
    public void update() {
        setVisibility(Session.adbConnected || !Session.stationInfo.isEmpty() ? VISIBLE : GONE);
        Map<String,String> info=Session.stationInfo;
        List<StationSetup.Step> values=Session.setupSteps;
        boolean failed=values.stream().anyMatch(s->s.status().equals("Ошибка"));
        status.setText(!Session.adbConnected?"Связь с Алисой потеряна":StationSetup.complete(values)?Session.wifiDone?"Алиса настроена":"Подключите домашний Wi-Fi":failed?"Нужно проверить настройку":"Настраиваем Алису");
        String uptime=info.getOrDefault("UPTIME","");
        try {uptime=((long)Double.parseDouble(uptime)/60)+" мин";}catch(NumberFormatException ignored){uptime="—";}
        String free=info.getOrDefault("DATA","");
        try {free=String.format(Locale.forLanguageTag("ru"),"%.0f МБ",Long.parseLong(free)/1024d);}catch(NumberFormatException ignored){free="—";}
        String platform=Session.progress.platform();
        String model=DeviceNames.name(info);title.setText("ВАША АЛИСА · "+model);
        details.setText(model+" · "+Session.station+":5555\nПрошивка: "+info.getOrDefault("VERSION","читаем…")+"\nДоступ: "+("0".equals(info.get("UID"))?"root · UID 0":info.containsKey("UID")?"UID "+info.get("UID"):"проверяем")+"\nВремя работы: "+uptime+" · свободно /data: "+free+"\nЯдро: "+info.getOrDefault("KERNEL","—"));
        for(int i=0;i<steps.length;i++){
            StationSetup.Step step=values.get(i);boolean done=step.status().equals("Готово"),bad=step.status().equals("Ошибка");
            if(Station1Plan.matches(info)){steps[i].setText((done?"✓  ":bad?"!  ":"○  ")+step.title()+" · "+step.status());}else steps[i].setText((done?"✓  ":bad?"!  ":"○  ")+step.title()+" · "+(done?step.id().equals("adb")?"маркеры проверены":step.id().equals("ota")?"отключены":"отключена":step.status()));
            steps[i].setTextColor(done?0xff72e1bc:bad?0xffffba9e:0xff909ab3);
        }
        error.setText(Session.stationInfoError.isEmpty()?(failed?"Один из шагов не выполнен. Ответ колонки записан в журнал.":""):Session.stationInfoError);
        error.setVisibility(error.getText().length()==0?View.GONE:View.VISIBLE);
        retry.setVisibility(Session.adbConnected&&failed?VISIBLE:GONE);
        yam.setText(Session.yseStatus); packages.setVisibility(Session.yseReady&&Session.adbConnected?VISIBLE:GONE);
        wifi.setText(Session.wifiStatus);
        wifiButton.setText(Session.wifiSwitchPending?"Найти Алису в домашней сети  →":Session.wifiDone?"Сменить домашнюю сеть  →":"Подключить домашний Wi-Fi  →");
        wifiButton.setVisibility(StationSetup.complete(values)||Session.wifiSwitchPending?VISIBLE:GONE);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private LayoutParams spaced(int n){LayoutParams p=new LayoutParams(-1,-2);p.topMargin=dp(n);return p;}
}
