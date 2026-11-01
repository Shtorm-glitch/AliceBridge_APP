package dev.alice.bridge;

import android.app.Activity;
import android.os.*;
import android.text.*;
import android.widget.*;
import android.view.Gravity;
import android.view.View;
import java.util.*;
import java.util.concurrent.*;
import static dev.alice.bridge.ScreenUi.*;

/** Full-screen YSE package browser; all package mutations use the actual station CLI. */
public final class YsePackages extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout root,body;private TextView status,ownTab,catalogTab;private ProgressBar progress;
    private List<YseCatalog.Item> installed=List.of(),catalog=List.of();
    private String query="",page="list";private boolean inCatalog,busy;private YseCatalog.Item selected;
    private final Runnable refresh=new Runnable(){public void run(){if(busy&&status!=null&&!Session.yseOperation.isEmpty())status.setText(Session.yseOperation);handler.postDelayed(this,500);}};
    @Override public void onCreate(Bundle b){super.onCreate(b);listPage();load(false);}
    private void listPage(){
        page="list";root=root(this,"Пакеты",this::finish);root.addView(text(this,"Entware · "+Session.station,13,MUTED),space(this,12));
        LinearLayout tabs=row(this);tabs.setBackground(bg(PANEL,16,this));ownTab=button(this,"Установленные",()->{inCatalog=false;listPage();load(false);},false);catalogTab=button(this,"Каталог",()->{inCatalog=true;listPage();if(catalog.isEmpty())load(true);},false);
        tabs.addView(ownTab,new LinearLayout.LayoutParams(0,dp(this,48),1));tabs.addView(catalogTab,new LinearLayout.LayoutParams(0,dp(this,48),1));root.addView(tabs,space(this,18));
        EditText search=field(this,"Поиск по названию и описанию",false);search.setText(query);root.addView(search,space(this,14));search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();render();}});
        statusRow();ScrollView scroll=new ScrollView(this);body=column(this);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=row(this);footer.addView(button(this,"Установить по имени",this::installPage,true),new LinearLayout.LayoutParams(0,-2,1));TextView reload=button(this,"↻",()->load(inCatalog),false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(this,52),dp(this,52));lp.leftMargin=dp(this,10);footer.addView(reload,lp);root.addView(footer,space(this,12));render();
    }
    private void statusRow(){LinearLayout row=row(this);progress=new ProgressBar(this);row.addView(progress,new LinearLayout.LayoutParams(dp(this,18),dp(this,18)));status=text(this,"",12,MUTED);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(this,10);row.addView(status,p);root.addView(row,space(this,12));progress.setVisibility(busy?View.VISIBLE:View.GONE);}
    private boolean installed(String id){return installed.stream().anyMatch(x->x.id().equals(id));}
    private void render(){if(!page.equals("list")||body==null)return;body.removeAllViews();ownTab.setBackground(bg(inCatalog?PANEL:0xff30334b,14,this));catalogTab.setBackground(bg(inCatalog?0xff30334b:PANEL,14,this));String filter=query.toLowerCase(Locale.ROOT);int count=0;
        for(YseCatalog.Item item:inCatalog?catalog:installed){if(!(item.id()+" "+item.description()).toLowerCase(Locale.ROOT).contains(filter))continue;if(count++>=80)continue;
            LinearLayout card=card(this);TextView name=text(this,item.name(),20,TEXT);name.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(name);card.addView(text(this,item.version()+" · "+item.source()+(installed(item.id())?" · установлен":""),12,installed(item.id())?GREEN:MUTED),space(this,8));if(!item.description().isEmpty())card.addView(text(this,item.description(),13,MUTED),space(this,10));card.setOnClickListener(v->{selected=item;detail();});body.addView(card,space(this,12));
        }
        if(count==0){LinearLayout empty=card(this);empty.addView(text(this,busy?"Загружаем пакеты…":query.isEmpty()?"Пакетов пока нет":"Ничего не найдено",20,TEXT));empty.addView(text(this,"Откройте каталог или укажите имя пакета для установки.",14,MUTED),space(this,14));body.addView(empty,space(this,18));}
        if(count>80)body.addView(text(this,"Найдено "+count+". Показаны первые 80 — уточните поиск.",13,MUTED),space(this,12));
    }
    private void detail(){page="detail";root=root(this,selected.name(),this::listPage);root.addView(text(this,selected.id()+" · "+selected.version(),15,MUTED),space(this,18));root.addView(text(this,selected.description(),16,TEXT),space(this,14));statusRow();
        if(installed(selected.id()))root.addView(button(this,"Открыть в терминале",()->startActivity(new android.content.Intent(this,TerminalActivity.class).putExtra("package",selected.id())),false),space(this,16));
        root.addView(button(this,"Сведения о пакете",()->submit("Читаем сведения…",()->YseClient.command(this,"info",selected.id()),this::showOutput),false),space(this,18));
        root.addView(button(this,installed(selected.id())?"Удалить пакет":"Установить пакет",()->{if(installed(selected.id()))confirmRemove();else mutate("install",selected.id());},true),space(this,16));
    }
    private void confirmRemove(){page="remove";root=root(this,"Удаление пакета",this::detail);root.addView(text(this,"Удалить «"+selected.name()+"»?",26,TEXT),space(this,28));root.addView(text(this,"YSE удалит этот пакет с колонки. Зависимости других пакетов проверит менеджер Entware.",15,MUTED),space(this,16));statusRow();root.addView(button(this,"Удалить",()->mutate("remove",selected.id()),true),space(this,26));root.addView(button(this,"Отмена",this::detail,false),space(this,12));}
    private void installPage(){page="install";root=root(this,"Установка пакета",this::listPage);root.addView(text(this,"Имя пакета",27,TEXT),space(this,24));root.addView(text(this,"Например: htop или nano. Для .yap-пакетов и плагинов откройте отдельную кнопку YAM.",15,MUTED),space(this,14));EditText id=field(this,"Название или источник:название",false);root.addView(id,space(this,20));statusRow();root.addView(button(this,"Установить",()->mutate("install",id.getText().toString().trim().replaceFirst("^ent:","")),true),space(this,22));}
    private void showOutput(String output){page="output";root=root(this,"Результат YSE",this::listPage);ScrollView scroll=new ScrollView(this);TextView value=text(this,output.isBlank()?"Выполнено":output,13,TEXT);value.setTextIsSelectable(true);scroll.addView(value);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));}
    private void mutate(String command,String id){
        if(command.equals("install")){
            if(!id.matches("(?:ent:)?[a-zA-Z0-9][a-zA-Z0-9+_.-]{0,100}")||id.contains("..")){Toast.makeText(this,"Введите имя Entware-пакета; .yap устанавливаются через YAM",Toast.LENGTH_LONG).show();return;}
            new android.app.AlertDialog.Builder(this).setTitle("Установить пакет?").setMessage("Вы действительно хотите установить «"+id+"» на колонку? Менеджер также установит необходимые зависимости.").setPositiveButton("Установить",(dialog,which)->runMutation(command,id)).setNegativeButton("Отмена",null).show();
        }else runMutation(command,id);
    }
    private void runMutation(String command,String id){submit(command.equals("install")?"Устанавливаем…":"Удаляем…",()->YseClient.command(this,command,id.startsWith("ent:")?id:"ent:"+id),output->{inCatalog=false;query="";listPage();load(false);});}
    private void load(boolean all){submit("Читаем пакеты…",()->{if(all)YseClient.command(this,"ent-update",null);return YseClient.command(this,all?"ent-catalog":"ent-list",null);},output->{if(all)catalog=YseCatalog.parse(output);else installed=YseCatalog.parse(output);render();});}
    private interface Job{String run()throws Exception;}
    private void submit(String title,Job job,java.util.function.Consumer<String> done){if(busy)return;busy=true;Session.yseOperation=title;status.setText(title);progress.setVisibility(View.VISIBLE);io.execute(()->{String result="",error="";try{result=job.run();}catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();}String value=result,failure=error;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;progress.setVisibility(View.GONE);if(!failure.isEmpty()){status.setText(failure);status.setTextColor(WARN);Session.log(this,"YSE","Пакеты",failure,"Ошибка");}else{status.setText("Готово");done.accept(value);}});});}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onBackPressed(){if(page.equals("list"))super.onBackPressed();else listPage();}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
