package dev.alice.bridge;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.*;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.alice.bridge.ScreenUi.*;

/** Native, separate YAM panel: binary packages and Lua plugins have their own tabs. */
public final class YamActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private YamClient client;private LinearLayout root,body;private TextView status;private ProgressBar spinner;
    private JSONArray installed=new JSONArray(),catalog=new JSONArray();private boolean plugins,inCatalog,busy,ready;private String query="",page="list",message="Подключаемся к YAM…";private JSONObject selected;
    @Override public void onCreate(Bundle state){super.onCreate(state);loading();job("Проверяем YAM…",()->{client=new YamClient(this);client.progress=value->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()&&busy){message=value;status.setText(value);}});return client.connect();},value->{ready=(Boolean)value;if(ready){list();reload(false);}else installPage();});}
    private void loading(){root=root(this,"YAM",this::finish);statusRow();}
    private void statusRow(){LinearLayout row=row(this);spinner=new ProgressBar(this);row.addView(spinner,new LinearLayout.LayoutParams(dp(this,18),dp(this,18)));status=text(this,message,13,MUTED);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(this,10);row.addView(status,lp);root.addView(row,space(this,14));spinner.setVisibility(busy?View.VISIBLE:View.GONE);}
    private void installPage(){page="setup";root=root(this,"YAM",this::finish);root.addView(text(this,"Пакеты и плагины для Алисы",26,TEXT),space(this,24));root.addView(text(this,"Установите YAM, чтобы управлять бинарными .yap-пакетами и Lua-плагинами. Entware для него не нужен. "+(MaxPlan.matches(Session.stationInfo)?"На Макс запуск выполняется через root AliceBridge; для восстановления после перезагрузки включите соответствующую галочку в панели.":"Фоновая служба будет запускаться вместе с колонкой."),15,MUTED),space(this,16));statusRow();root.addView(button(this,"Установить YAM",()->confirm("Установить YAM?",(MaxPlan.matches(Session.stationInfo)?"YAM будет установлен и запущен через root Макс. После перезагрузки понадобится активная сессия AliceBridge и включённое восстановление root. ":"На колонку будет установлен менеджер YAM и добавлен его запуск при загрузке. ")+"Пакеты и плагины устанавливаются отдельно. Штатное автообновление YAM включено.",()->job("Устанавливаем YAM…",()->{client.install();return null;},x->{ready=true;list();reload(false);})),true),space(this,20));root.addView(button(this,"Проверить ещё раз",()->job("Проверяем…",()->client.connect(),value->{ready=(Boolean)value;if(ready){list();reload(false);}else{message="YAM ещё не установлен";installPage();}}),false),space(this,12));}
    private void list(){
        page="list";root=root(this,"YAM",this::finish);root.addView(text(this,DeviceNames.name(Session.stationInfo)+" · "+Session.station,13,MUTED),space(this,12));
        LinearLayout kinds=row(this);tab(kinds,"Пакеты",!plugins,()->{plugins=false;list();});tab(kinds,"Плагины",plugins,()->{plugins=true;list();});root.addView(kinds,space(this,18));
        root.addView(text(this,plugins?"Lua-плагины · совместимость проверяет YAM":"Бинарные .yap-пакеты · отдельно от Entware",12,MUTED),space(this,10));
        LinearLayout sources=row(this);tab(sources,"Установленные",!inCatalog,()->{inCatalog=false;list();reload(false);});tab(sources,"Каталог",inCatalog,()->{inCatalog=true;list();if(catalog.length()==0)reload(true);});root.addView(sources,space(this,12));
        EditText search=field(this,"Поиск",false);search.setText(query);root.addView(search,space(this,12));search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();render();}});
        statusRow();ScrollView scroll=new ScrollView(this);body=column(this);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=row(this);footer.addView(button(this,"Импорт .yap",this::importPackage,false),new LinearLayout.LayoutParams(0,-2,1));TextView reload=button(this,"↻",()->reload(inCatalog),false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(this,52),dp(this,52));lp.leftMargin=dp(this,10);footer.addView(reload,lp);root.addView(footer,space(this,10));render();
    }
    private void tab(LinearLayout row,String title,boolean selected,Runnable action){TextView t=button(this,title,action,selected);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(this,48),1);lp.setMargins(dp(this,3),0,dp(this,3),0);row.addView(t,lp);}
    private JSONObject manifest(JSONObject item){JSONObject m=item.optJSONObject("manifest");return m==null?item:m;}
    private String id(JSONObject item){return manifest(item).optString("id");}
    private JSONObject own(String id){for(int i=0;i<installed.length();i++){JSONObject x=installed.optJSONObject(i);if(x!=null&&id(x).equals(id))return x;}return null;}
    private String kind(JSONObject item){JSONObject m=manifest(item),l=m.optJSONObject("launch");return l==null?m.optString("kind","lua"):l.optString("kind","lua");}
    private void render(){if(!page.equals("list")||body==null)return;body.removeAllViews();JSONArray values=inCatalog?catalog:installed;int count=0;String filter=query.toLowerCase(Locale.ROOT);
        for(int i=0;i<values.length();i++){JSONObject x=values.optJSONObject(i);if(x==null||!kind(x).equals(plugins?"lua":"binary"))continue;JSONObject m=manifest(x);String name=m.optString("name",id(x)),desc=m.optString("description");if(!(name+" "+id(x)+" "+desc).toLowerCase(Locale.ROOT).contains(filter))continue;
            count++;LinearLayout card=card(this);card.addView(text(this,name,20,TEXT));JSONObject own=own(id(x));String state=own==null?"не установлен":own.optBoolean("running")?"работает":own.optBoolean("enabled")?"включён":"выключен";
            card.addView(text(this,m.optString("version")+" · "+state,12,own==null?MUTED:GREEN),space(this,8));if(!desc.isEmpty())card.addView(text(this,desc,13,MUTED),space(this,8));card.setOnClickListener(v->{selected=x;detail();});body.addView(card,space(this,12));
        }
        if(count==0)body.addView(text(this,busy?"Загружаем…":inCatalog?"Ничего не найдено":"Пока ничего не установлено. Откройте каталог или импортируйте .yap.",16,MUTED),space(this,18));
    }
    private void detail(){
        page="detail";JSONObject m=manifest(selected);String id=id(selected);JSONObject own=own(id);root=root(this,m.optString("name",id),this::list);root.addView(text(this,id+" · "+m.optString("version"),13,MUTED),space(this,16));
        ScrollView scroll=new ScrollView(this);LinearLayout content=column(this);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));content.addView(text(this,m.optString("description"),16,TEXT),space(this,16));
        if(own!=null){JSONObject launch=manifest(own).optJSONObject("launch");String mode=launch==null?"oneshot":launch.optString("mode","oneshot");boolean enabled=own.optBoolean("enabled"),compatible=own.optBoolean("compatible",true),binary=kind(own).equals("binary");
            content.addView(text(this,(own.optBoolean("running")?"Работает":enabled?"Включён":"Выключен")+" · "+mode,15,GREEN),space(this,12));if(!compatible)content.addView(text(this,"Несовместим: "+own.optString("incompatibility","неподходящая модель или возможности"),14,WARN),space(this,12));
            JSONArray permissions=manifest(own).optJSONArray("permissions");if(permissions!=null)content.addView(text(this,"Разрешения: "+permissions.toString(),13,MUTED),space(this,12));
            if(compatible||enabled)content.addView(button(this,enabled?"Выключить":"Включить",()->confirm(enabled?"Выключить?":"Включить?",enabled?"Остановить пакет и отключить его автозапуск?":"Разрешить выполнение «"+id+"» на колонке?"+(binary?" Для этого YAM разрешит запуск бинарников.":"")+(mode.equals("service")?" Служба запустится сразу и при перезагрузке.":""),()->mutate(id,"toggle",binary&&!enabled)),false),space(this,12));
            if(enabled&&compatible){content.addView(button(this,own.optBoolean("running")?"Остановить":"Запустить",()->{if(own.optBoolean("running"))mutate(id,"stop",binary);else confirm("Запустить?","Выполнить «"+id+"» на колонке?",()->mutate(id,mode.equals("service")?"service/start":"invoke",binary));},true),space(this,12));
                if(binary&&mode.equals("oneshot"))content.addView(button(this,"Открыть в терминале",()->startActivity(new Intent(this,TerminalActivity.class).putExtra("yam_package",id)),false),space(this,12));}
            content.addView(button(this,"Журнал",()->job("Читаем журнал…",()->client.logs(id),v->output((String)v)),false),space(this,12));content.addView(button(this,"Удалить",()->confirm("Удалить?","Удалить «"+id+"» с колонки вместе с его данными?",()->mutate(id,"delete",false)),false),space(this,12));
        }else content.addView(button(this,"Установить",()->{JSONObject target=selected;confirm("Установить?","Установить «"+id+"»? SHA-256 будет проверен перед передачей. Пакет останется выключенным до вашего разрешения.",()->job("Загружаем и устанавливаем…",()->{client.installPackage(target);installed=client.installed();return null;},v->{inCatalog=false;list();}));},true),space(this,12));
        statusRow();
    }
    private void mutate(String id,String action,boolean binary){job("Выполняем…",()->{client.action(id,action,binary);installed=client.installed();return null;},v->{if(action.equals("delete"))list();else detail();});}
    private void reload(boolean all){job("Читаем "+(all?"каталог":"установленные")+"…",()->{if(!client.connect())throw new IOException("YAM недоступен");installed=client.installed();if(all)catalog=client.catalog();return null;},v->{render();});}
    private void output(String text){page="output";root=root(this,"Журнал YAM",this::detail);ScrollView scroll=new ScrollView(this);TextView value=text(this,text.isBlank()?"Журнал пока пуст":text,13,TEXT);value.setTextIsSelectable(true);scroll.addView(value);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));}
    private void confirm(String title,String message,Runnable action){if(busy){Toast.makeText(this,"Дождитесь текущей операции",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Да",(d,w)->action.run()).setNegativeButton("Отмена",null).show();}
    private void importPackage(){if(busy)return;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,41);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;android.net.Uri uri=data.getData();confirm("Установить .yap?","Импортировать выбранный архив в YAM на колонке? Совместимость проверит YAM. Пакет не будет включён автоматически.",()->job("Передаём .yap…",()->{File file=File.createTempFile("yam-import-",".yap",getCacheDir());try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){if(in==null)throw new IOException("Не удалось открыть файл");byte[] b=new byte[65536];long total=0;for(int n;(n=in.read(b))!=-1;){total+=n;if(total>96L*1024*1024)throw new IOException("Архив больше 96 МиБ");out.write(b,0,n);}}client.upload(file);installed=client.installed();return null;}finally{file.delete();}},v->{inCatalog=false;list();}));}
    private interface Work{Object run()throws Exception;}
    private void job(String title,Work work,java.util.function.Consumer<Object> done){if(busy)return;busy=true;message=title;status.setText(title);status.setTextColor(MUTED);spinner.setVisibility(View.VISIBLE);io.execute(()->{Object result=null;String error="";try{result=work.run();}catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();Session.log(this,"YAM","Менеджер",error,"Ошибка");}Object value=result;String failure=error;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;message=failure.isEmpty()?"Готово":failure;status.setText(message);status.setTextColor(failure.isEmpty()?MUTED:WARN);spinner.setVisibility(View.GONE);if(failure.isEmpty())done.accept(value);else if(page.equals("list")&&body==null||client==null){root.addView(button(this,"Повторить",()->recreate(),true),space(this,16));}});});}
    @Override public void onBackPressed(){if(page.equals("detail")||page.equals("output"))list();else super.onBackPressed();}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
