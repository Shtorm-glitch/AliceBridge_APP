package dev.alice.bridge;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;

/** Stable, compact native view: an explanation, measured progress and four evidence rows. */
public final class ProgressCard extends LinearLayout {
    private static final int INK=0xffedf0fc, MUTED=0xff909ab3, PURPLE=0xffb6a1ff, GREEN=0xff72e1bc, WARN=0xffffba9e;
    private final TextView heading,hint,badge,amount,percent,freshness,current,target;
    private final TextView[] icons=new TextView[4], titles=new TextView[4], captions=new TextView[4];
    private final ProgressBar bar;
    private final LinearLayout meter,versions;
    private String previous="";
    public ProgressCard(Context context, Runnable journal) {
        super(context); setOrientation(VERTICAL); setPadding(dp(20),dp(18),dp(20),dp(18));
        GradientDrawable panel=shape(0xff141a29,24); panel.setStroke(dp(1),0xff303448); setBackground(panel);
        LinearLayout top=row();TextView label=text("ЧТО ПРОИСХОДИТ СЕЙЧАС",10,PURPLE);label.setLetterSpacing(.10f);
        top.addView(label,new LayoutParams(0,-2,1));badge=text("",10,MUTED);badge.setPadding(dp(9),dp(5),dp(9),dp(5));badge.setBackground(shape(0xff22283a,20));top.addView(badge);addView(top);
        heading=text("",23,INK);heading.setTypeface(null,Typeface.BOLD);addView(heading,spaced(14));
        hint=text("",14,MUTED);hint.setLineSpacing(dp(3),1);addView(hint,spaced(8));
        meter=new LinearLayout(context);meter.setOrientation(VERTICAL);addView(meter,spaced(14));
        LinearLayout progressLabel=row();amount=text("",12,MUTED);percent=text("",14,PURPLE);percent.setTypeface(null,Typeface.BOLD);
        progressLabel.addView(amount,new LayoutParams(0,-2,1));progressLabel.addView(percent);meter.addView(progressLabel);
        bar=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgressTintList(ColorStateList.valueOf(PURPLE));bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff2a3041));meter.addView(bar,new LayoutParams(-1,dp(9)));
        String[] labels={"Колонка в сети","Прошивка","Проверка после перезапуска","Подключение ADB"};
        for(int i=0;i<4;i++){
            LinearLayout line=row();line.setGravity(Gravity.TOP);line.setPadding(0,dp(14),0,dp(2));
            icons[i]=text("",13,PURPLE);icons[i].setTypeface(null,Typeface.BOLD);icons[i].setGravity(Gravity.CENTER);
            LayoutParams iconParams=new LayoutParams(dp(30),dp(30));iconParams.setMargins(0,dp(2),dp(12),0);line.addView(icons[i],iconParams);
            LinearLayout copy=new LinearLayout(context);copy.setOrientation(VERTICAL);titles[i]=text(labels[i],14,INK);titles[i].setTypeface(null,Typeface.BOLD);captions[i]=text("",12,MUTED);captions[i].setPadding(0,dp(3),0,0);captions[i].setLineSpacing(dp(2),1);copy.addView(titles[i]);copy.addView(captions[i]);line.addView(copy,new LayoutParams(0,-2,1));addView(line);
        }
        versions=new LinearLayout(context);versions.setOrientation(VERTICAL);versions.setVisibility(GONE);
        TextView toggle=text("Версии прошивки  ▾",12,PURPLE);toggle.setPadding(0,dp(16),0,dp(10));toggle.setMinHeight(dp(48));toggle.setOnClickListener(v->versions.setVisibility(versions.getVisibility()==GONE?VISIBLE:GONE));
        // The container is kept in place so periodic updates never change the user's scroll position.
        current=text("",12,MUTED);target=text("",12,MUTED);current.setTextIsSelectable(true);target.setTextIsSelectable(true);versions.addView(current);versions.addView(target);addView(toggle);addView(versions);
        View divider=new View(context);divider.setBackgroundColor(0xff2a3041);addView(divider,new LayoutParams(-1,dp(1)));
        LinearLayout footer=row();freshness=text("",11,MUTED);footer.addView(freshness,new LayoutParams(0,-2,1));TextView details=text("Журнал  ↗",12,PURPLE);details.setGravity(Gravity.CENTER);details.setPadding(dp(10),dp(12),0,dp(12));details.setMinHeight(dp(48));details.setOnClickListener(v->journal.run());footer.addView(details);addView(footer,spaced(7));
    }
    public void update(ProgressInfo data,String station,boolean adbVerified,long attempts,String adbStatus,String linkError) {
        long age=data.updatedAt()==0?-1:Math.max(0,(SystemClock.elapsedRealtime()-data.updatedAt())/1000);
        String error=linkError;
        if(error.isEmpty()&&age>45)error="Статус сервера устарел";
        ProgressInfo.View view=data.view(!station.isEmpty(),adbVerified,error);
        String freshnessText=age<0?"Ожидаем ответ сервера":age<15?"Статус обновлён только что":"Обновлено "+age+" с назад";
        String key=data+"|"+station+"|"+adbVerified+"|"+attempts+"|"+adbStatus+"|"+error+"|"+(age<15?0:age/5);
        if(key.equals(previous))return;previous=key;
        heading.setText(view.title());hint.setText(view.hint());heading.setTextColor(view.warning()?WARN:INK);
        badge.setText(view.complete()?"ГОТОВО":view.warning()?"ВНИМАНИЕ":"В ПРОЦЕССЕ");badge.setTextColor(view.complete()?GREEN:view.warning()?WARN:PURPLE);
        meter.setVisibility(view.percent()>=0?VISIBLE:GONE);bar.setProgress(Math.max(0,view.percent()));percent.setText(view.percent()+"%");amount.setText(view.percent()==100&&data.transferred()?"Файл передан колонке":view.amount());
        boolean[] done={!station.isEmpty(),data.transferred()||data.versionConfirmed(),data.versionConfirmed(),adbVerified};
        String[] descriptions={station.isEmpty()?"Ждём первые запросы":"Запросы колонки получены",
            data.versionConfirmed()?"Целевая версия уже на колонке":data.transferred()?"Файл передан полностью":data.total()>0&&data.sent()>0?"Идёт передача файла":data.downloadState().equals("loading")?"Образ загружается на сервер":data.target().isEmpty()?"Ждём проверку обновлений":"Версия выбрана; ждём скачивание",
            data.versionConfirmed()?"Целевая версия подтверждена":data.tlsRejected()?"Проверку блокирует ошибка сертификата":data.transferred()?"Ждём новую версию от колонки":"Начнём после передачи прошивки",
            adbVerified?"Колонка ответила на shell id":station.isEmpty()?"Начнём после подключения колонки":adbStatus.toLowerCase(java.util.Locale.ROOT).contains("авторизац")?"Нужно разрешить ключ ADB":attempts>0?"Попытка №"+attempts+" · пока нет ответа, повторим":"Подключаемся автоматически"};
        for(int i=0;i<4;i++){
            boolean active=!done[i]&&i==view.step();int color=done[i]?GREEN:active?(view.warning()?WARN:PURPLE):MUTED;
            icons[i].setText(done[i]?"✓":String.valueOf(i+1));icons[i].setTextColor(color);icons[i].setBackground(shape(done[i]?0xff19382f:active?0xff302944:0xff222839,30));
            titles[i].setTextColor(done[i]?INK:color);captions[i].setText(descriptions[i]);
        }
        current.setText("Последняя полученная версия:\n"+(data.version().isEmpty()?"ещё не получена":data.version()));target.setText("Целевая версия:\n"+(data.target().isEmpty()?"ещё не выбрана":data.target()));freshness.setText(freshnessText);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LayoutParams spaced(int top){LayoutParams p=new LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
}
