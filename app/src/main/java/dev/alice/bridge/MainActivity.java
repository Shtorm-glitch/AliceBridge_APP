package dev.alice.bridge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** A single action starts the workflow; technical controls stay in an optional sheet. */
public final class MainActivity extends Activity {
    private static final int BG = 0xff090d17, PANEL = 0xff141a29, TEXT = 0xffedf0fc,
        MUTED = 0xff909ab3, PURPLE = 0xffb6a1ff, GREEN = 0xff72e1bc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout page;
    private TextView logText;
    private boolean detailedJournal,collectingDiagnostics,checkingUpdates,legacyWpaRequested,modeChoicesExpanded,stationInfoExpanded;
    private Dialog updateDialog;
    private ProgressCard progressCard;
    private StationCard stationCard;
    private TextView connectedInfo, connectedStatus;
    private String rendered = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 400); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Diagnostics.installCrashHandler(getApplicationContext());
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        // WPA-1 is an explicit choice for this setup, never a saved default.
        prefs.edit().remove("hotspot_security").apply();
        if(!Session.flowActive&&state==null)prefs.edit().putBoolean("station1_manual",false).putString("connection_mode","root").apply();
        int boot = android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        int previous = prefs.getInt("workflow_boot", -1);
        if (boot >= 0 && previous >= 0 && boot != previous) prefs.edit().putBoolean("reboot_required", false).apply();
        if (!prefs.contains("hotspot_password")) prefs.edit().putString("hotspot_ssid", "AliceBridge")
            .putString("hotspot_password", "alice-" + String.format(Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1000000))).apply();
        prefs.edit().remove("inspect").apply();
        if (!prefs.getBoolean("local_backend_v1", false)) {
            SharedPreferences.Editor migration = prefs.edit().putBoolean("local_backend_v1", true);
            if (prefs.getString("resolver", "78.24.223.83").equals("179.254.167.126")) migration.putString("resolver", "78.24.223.83");
            migration.apply();
        }
        if (!prefs.getBoolean("local_backend_v2", false)) {
            SharedPreferences.Editor migration=prefs.edit().putBoolean("local_backend_v2",true);
            if(prefs.getString("resolver", "78.24.223.83").equals("192.168.1.115"))migration.putString("resolver","78.24.223.83");
            migration.apply();
        }
        if (!prefs.getBoolean("public_backend_v1", false)) {
            SharedPreferences.Editor migration=prefs.edit().putBoolean("public_backend_v1",true);
            String oldResolver=prefs.getString("resolver", "78.24.223.83");
            if (oldResolver.equals("192.168.3.30") || oldResolver.equals("192.168.1.115") || oldResolver.equals("179.254.167.126"))
                migration.putString("resolver","78.24.223.83");
            migration.apply();
        }
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        LinearLayout root = column(); root.setBackgroundColor(BG); root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(22), bars.top + dp(12), dp(22), bars.bottom + dp(8)); return insets;
        });
        LinearLayout top = row(); TextView brand = text("◉  AliceBridge", 22, TEXT); brand.setTypeface(null, Typeface.BOLD);
        brand.setMaxLines(1); brand.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams journalButton = new LinearLayout.LayoutParams(-2, dp(48)); journalButton.setMargins(dp(10), 0, dp(8), 0);
        top.addView(button("Журнал", this::showLog, false), journalButton);
        TextView settingsButton = button("⚙", this::showSettings, false); settingsButton.setTextSize(22); settingsButton.setPadding(0, 0, 0, 0); settingsButton.setContentDescription("Настройки");
        top.addView(settingsButton, new LinearLayout.LayoutParams(dp(48), dp(48))); root.addView(top);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        page = column(); page.setPadding(0, dp(20), 0, dp(12)); page.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.addView(page); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); root.requestFocus(); render();
        if (!Session.flowActive) startForegroundService(new Intent(this, BridgeService.class).setAction("discover").putExtra("host", prefs.getString("last_station", "")));
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
    }
    @Override protected void onStart(){super.onStart();checkForUpdates(false);}
    private void checkForUpdates(boolean manual){
        if(checkingUpdates||(updateDialog!=null&&updateDialog.isShowing()))return;
        checkingUpdates=true;
        if(manual)toast("Проверяем обновления…");
        new Thread(()->{
            try{
                UpdateInfo.Release release=AppUpdates.check();long current=AppUpdates.installedCode(this);
                handler.post(()->{checkingUpdates=false;if(isFinishing()||isDestroyed())return;if(release.code()>current)showUpdate(release);else if(manual)toast("Установлена последняя версия AliceBridge");});
            }catch(Exception error){handler.post(()->{checkingUpdates=false;if(isFinishing()||isDestroyed())return;if(manual)toast("Не удалось проверить обновления. Проверьте интернет.");});}
        },"alicebridge-update-check").start();
    }
    private void showUpdate(UpdateInfo.Release release){
        if(updateDialog!=null&&updateDialog.isShowing())return;
        Dialog dialog=new Dialog(this,android.R.style.Theme_Material_NoActionBar);updateDialog=dialog;
        LinearLayout layout=column();layout.setPadding(dp(24),dp(26),dp(24),dp(20));layout.setBackgroundColor(BG);
        layout.setOnApplyWindowInsetsListener((view,insets)->{
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(dp(24)+bars.left,dp(26)+bars.top,dp(24)+bars.right,dp(20)+bars.bottom);return insets;
        });
        TextView badge=text("НОВАЯ ВЕРСИЯ",11,PURPLE);badge.setLetterSpacing(.14f);layout.addView(badge);space(layout,14);
        TextView title=text("AliceBridge "+release.version(),27,TEXT);title.setTypeface(null,Typeface.BOLD);layout.addView(title);space(layout,8);
        layout.addView(text("Доступно обновление · "+String.format(Locale.forLanguageTag("ru"),"%.1f МБ",release.size()/1048576d),14,MUTED));space(layout,22);
        ScrollView scroll=new ScrollView(this);LinearLayout changes=column();changes.addView(text("Что нового",17,TEXT));for(String line:release.changes()){space(changes,12);changes.addView(text("• "+line,14,MUTED));}scroll.addView(changes);layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));space(layout,22);
        layout.addView(button("Обновить",()->{if(Session.busy){toast("Дождитесь завершения текущей операции");return;}dialog.dismiss();startActivity(new Intent(this,UpdateActivity.class).putExtra("release",releaseJson(release)));},true));
        layout.addView(button("Пропустить обновление",dialog::dismiss,false));
        dialog.setOnDismissListener(d->{if(updateDialog==dialog)updateDialog=null;});dialog.setContentView(layout);dialog.show();
        Window window=dialog.getWindow();if(window!=null){
            window.setBackgroundDrawableResource(android.R.color.transparent);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDecorFitsSystemWindows(false);window.setStatusBarColor(BG);window.setNavigationBarColor(BG);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);layout.requestApplyInsets();
        }
    }
    private String releaseJson(UpdateInfo.Release release){
        try{return new org.json.JSONObject().put("versionCode",release.code()).put("versionName",release.version()).put("url",release.url()).put("sha256",release.sha256()).put("size",release.size()).put("changes",new org.json.JSONArray(release.changes())).toString();}catch(org.json.JSONException invalid){throw new IllegalStateException(invalid);}
    }
    private void render() {
        boolean working=Session.busy||Session.setupSteps.stream().anyMatch(step->step.status().equals("Выполняется"));
        if(working)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Session.flowActive && (Session.adbConnected || !Session.stationInfo.isEmpty())) {
            renderConnected(); if(logText!=null)setChanged(logText,journal()); return;
        }
        String key = prefs.getString("connection_mode","root") + "|" + Session.phase + "|" + Session.problem + "|" + Session.station + "|" + Session.stopRequested + "|" + Session.adbConnected + "|" + Session.directMode + "|" + Session.discoveryRunning + "|" + Session.discoveredStations + "|" + Session.discoveryStatus;
        if (!key.equals(rendered)) {
            rendered = key; page.removeAllViews(); progressCard = null; stationCard = null;
            Session.Phase phase = Session.phase;
            if (phase == Session.Phase.IDLE || phase == Session.Phase.ERROR) {
                renderStart(phase);
            } else {
                TextView label = text("ПОДКЛЮЧЕНИЕ АЛИСЫ", 10, PURPLE); label.setLetterSpacing(.18f); page.addView(label);
                LinearLayout steps = row(); int active = step(phase);
                boolean withoutRoot=ShizukuNetwork.selected(this);
                String[] names = withoutRoot?new String[]{"Shizuku","Wi-Fi","Алиса","ADB"}:new String[]{"Root","Права","Wi-Fi","Алиса","ADB"};
                if(withoutRoot)active=phase==Session.Phase.HOTSPOT?1:phase==Session.Phase.WAITING?2:phase==Session.Phase.ROOT?0:3;
                for (int n = 0; n < names.length; n++) {
                    TextView item = text((n < active ? "✓" : "0" + (n + 1)) + "\n" + names[n], 12, n < active ? GREEN : n == active ? PURPLE : MUTED);
                    item.setGravity(Gravity.CENTER); steps.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
                }
                page.addView(steps); space(page, 22);
                if (phase == Session.Phase.ROOT || phase == Session.Phase.HOTSPOT) {
                    page.addView(new SignalView(this), new LinearLayout.LayoutParams(-1, dp(160)));
                    title(page, phase == Session.Phase.ROOT ? "Получаем root" : "Создаём точку доступа");
                    page.addView(text(phase == Session.Phase.ROOT ? "Запускаем встроенный DFRoot. Дальше потребуется выдать права в KernelSU." : "Настраиваем Wi-Fi 2,4 ГГц, раздачу интернета и DNS. Инструкция появится после успешного запуска.", 15, MUTED));
                    ProgressBar progress = new ProgressBar(this); page.addView(progress, new LinearLayout.LayoutParams(dp(38), dp(38)));
                } else if (phase == Session.Phase.ROOT_PERMISSION) {
                    title(page, "Разрешите root в KernelSU");
                    LinearLayout card = card(page);
                    card.addView(text("1. Откройте KernelSU.\n2. Перейдите в «Суперпользователи».\n3. Найдите AliceBridge.\n4. Включите права суперпользователя для приложения.", 16, TEXT));
                    card.addView(button("Открыть KernelSU", this::openManager, true));
                    card.addView(text("Без этого su не будет работать. Во время настройки телефон не перезагружайте: временный root слетит.", 13, MUTED));
                    if (!Session.problem.isEmpty()) page.addView(text(Session.problem, 13, 0xffffb6a1));
                    page.addView(button("Права выданы — продолжить  →", () -> action(BridgeService.RESUME), true));
                } else {
                    title(page, phase == Session.Phase.COMPLETE ? "Алиса настроена" : phase == Session.Phase.WAITING ? "Добавьте Алису в умный дом" : "Алиса найдена");
                    if (phase == Session.Phase.COMPLETE) page.addView(text(Station1Plan.matches(Session.stationInfo)?"Root boot Станции 1 и root ADB проверены. SSH и пакеты устанавливаются отдельно по кнопкам.":"ADB подключён через домашнюю сеть. OTA и аналитика отключены. YSE, Entware и SSH можно установить отдельно по кнопкам.", 15, GREEN));
                    else if (phase == Session.Phase.MONITORING) page.addView(text("Колонка появилась в сети. Ниже — текущий этап и что произойдёт дальше.", 15, GREEN));
                    else page.addView(text(ShizukuNetwork.selected(this)?"Проверка интернета телефона пройдена. Теперь подключите колонку — её запросы и ADB проверим отдельно.":"Точка доступа успешно создана. Мониторинг уже включён — первые запросы не потеряются.", 15, GREEN));
                    int statusPosition = page.getChildCount();
                    LinearLayout wifi=hotspotCredentials(page);
                    if(Session.directMode||Session.wifiDone)wifi.setVisibility(View.GONE);
                    if (phase == Session.Phase.WAITING) {
                        page.addView(text("Откройте «Дом с Алисой» и добавьте колонку. Во время подключения выберите сеть выше и введите пароль. Затем вернитесь в AliceBridge — здесь будут видны загрузка прошивки, перезапуск и подключение ADB.", 15, TEXT));
                        page.addView(button("Открыть «Дом с Алисой»", () -> openPackage("com.yandex.iot", "https://play.google.com/store/apps/details?id=com.yandex.iot"), true));
                    }
                    progressCard = new ProgressCard(this, this::showLog);
                    LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(-1, -2);
                    progressLayout.setMargins(0, dp(16), 0, dp(12)); page.addView(progressCard, phase == Session.Phase.WAITING ? page.getChildCount() : statusPosition, progressLayout);
                    stationCard = new StationCard(this, () -> action("configure"), this::showPackages, this::showYam, this::homeWifi);
                    LinearLayout.LayoutParams stationLayout = new LinearLayout.LayoutParams(-1, -2);
                    stationLayout.setMargins(0, dp(12), 0, dp(12)); page.addView(stationCard, stationLayout);
                    page.addView(button(Session.wifiDone ? "Завершить работу" : "Остановить настройку", () -> action(BridgeService.STOP), false));
                    if (prefs.getBoolean("reboot_required", false)) rebootNote(page);
                }
                if (Session.stopRequested) page.addView(text("Останавливаем сценарий после текущей операции…", 13, PURPLE));
            }
        }
        if (progressCard != null) progressCard.update(Session.progress, Session.station,
            Session.adbConnected, Session.adbAttempts, Session.adb, Session.backendError);
        if (stationCard != null) stationCard.update();
        if (logText != null) setChanged(logText, journal());
    }
    private void renderStart(Session.Phase phase) {
        boolean failed=phase==Session.Phase.ERROR,shizuku=ShizukuNetwork.selected(this);
        TextView tag=text(failed?"НАСТРОЙКА ПРИОСТАНОВЛЕНА":"НАСТРОЙКА КОЛОНКИ",10,PURPLE);tag.setLetterSpacing(.14f);page.addView(tag);space(page,10);
        title(page,failed?"Не удалось продолжить":"Настроим вашу Алису");space(page,8);
        page.addView(text(failed?"Проверьте причину ниже и повторите этот шаг.":"Поднимем Wi-Fi для колонки и проведём по настройке. Ход работы будет виден здесь.",15,MUTED));
        if(failed){
            LinearLayout error=card(page);error.addView(text(Session.problem.isEmpty()?"Не удалось завершить текущий шаг.":Session.problem,14,TEXT));
            error.addView(button("Открыть журнал",this::showLog,false));
        }
        if(!Session.flowActive){
            LinearLayout method=card(page);LinearLayout heading=row();
            LinearLayout description=column();description.addView(text(shizuku?"Shizuku · без root":"DFRoot · основной способ",17,TEXT));
            description.addView(text(shizuku?"Сначала запустите Shizuku или совместимый менеджер.":"Приложение само получает временный root телефона.",13,MUTED));
            heading.addView(description,new LinearLayout.LayoutParams(0,-2,1));
            TextView change=button(modeChoicesExpanded?"Скрыть":"Изменить",()->{modeChoicesExpanded=!modeChoicesExpanded;rendered="";render();},false);
            LinearLayout.LayoutParams changeLayout=new LinearLayout.LayoutParams(-2,dp(48));changeLayout.leftMargin=dp(8);heading.addView(change,changeLayout);method.addView(heading);
            if(modeChoicesExpanded){
                space(method,10);method.addView(button("DFRoot · автоматический root",()->selectMode("root"),!shizuku));
                method.addView(button("Shizuku · без root телефона",()->selectMode("shizuku"),shizuku));
            }
            if(shizuku){
                method.addView(button(ShizukuNetwork.permitted()?"Shizuku подключена · инструкция":"Подключить Shizuku",()->startActivity(new Intent(this,ShizukuGuideActivity.class)),false));
            }else{
                space(method,12);method.addView(text("Перед стартом скачайте и установите KernelSU",14,TEXT));
                LinearLayout links=row();
                TextView samsung=button("Samsung ↗",()->browser("https://github.com/diabl0w/KernelSU/releases"),false);
                TextView other=button("Другие телефоны ↗",()->browser("https://github.com/tiann/KernelSU/releases"),false);
                samsung.setTextSize(12);other.setTextSize(12);
                LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(48),1);left.rightMargin=dp(8);links.addView(samsung,left);links.addView(other,new LinearLayout.LayoutParams(0,dp(48),1));
                method.addView(links,ScreenUi.space(this,8));
                method.addView(text("Если DFRoot не сработает, попробуйте Shizuku через «Изменить».",12,MUTED),ScreenUi.space(this,8));
            }
            CheckBox firstStation=new CheckBox(this);firstStation.setText("У меня Станция 1");firstStation.setTextColor(TEXT);firstStation.setTextSize(14);firstStation.setPadding(dp(4),dp(8),dp(4),dp(4));firstStation.setMinHeight(dp(48));
            firstStation.setChecked(prefs.getBoolean("station1_manual",false));
            firstStation.setOnCheckedChangeListener((view,enabled)->prefs.edit().putBoolean("station1_manual",enabled).apply());page.addView(firstStation);
            if(!shizuku){
                CheckBox legacy=new CheckBox(this);legacy.setText("WPA-1 · если Станция 1 не видит сеть");legacy.setTextColor(MUTED);legacy.setTextSize(13);legacy.setPadding(dp(16),0,dp(4),dp(4));legacy.setMinHeight(dp(48));legacy.setChecked(legacyWpaRequested);
                legacy.setOnCheckedChangeListener((view,enabled)->legacyWpaRequested=enabled);page.addView(legacy);
            }
        }
        space(page,12);page.addView(button(failed?Session.flowActive?"Завершить текущую сессию":"Повторить настройку":"Начать настройку  →",()->{if(Session.flowActive)action(BridgeService.CLEAN);else beginSelected();},true));
        space(page,8);page.addView(text(shizuku?"Точку доступа создаст Shizuku. Затем появится инструкция по подключению колонки.":"Телефон заранее рутовать не нужно. После DFRoot выдайте AliceBridge права в KernelSU → «Суперпользователи».",12,MUTED));
        if(failed&&Session.dfrootFailed&&!shizuku&&Build.VERSION.SDK_INT>=33&&!Session.flowActive){
            page.addView(button("DFRoot не подошёл? Попробовать Shizuku",()->{selectMode("shizuku");startActivity(new Intent(this,ShizukuGuideActivity.class));},false));
        }
        if(!Session.flowActive){
            LinearLayout ready=card(page);ready.addView(text("Уже есть доступ ADB?",17,TEXT));
            ready.addView(text(Session.discoveryRunning?"Ищем готовые колонки в вашей сети. Начать настройку можно сразу.":Session.discoveredStations.isEmpty()?"Подключитесь к настроенной колонке, чтобы открыть панель управления.":"Найдены колонки в вашей сети. Выберите нужную.",13,MUTED));
            for(Map.Entry<String,String> entry:Session.discoveredStations.entrySet())ready.addView(button(entry.getValue()+" · "+entry.getKey(),()->startForegroundService(new Intent(this,BridgeService.class).setAction("direct").putExtra("host",entry.getKey())),false));
            ready.addView(button("Подключиться по IP",this::connectDirect,false));
        }
        if(prefs.getBoolean("reboot_required",false))rebootNote(page);
    }
    private LinearLayout hotspotCredentials(LinearLayout parent){
        LinearLayout wifi=card(parent);wifi.addView(text("СЕТЬ ДЛЯ ПОДКЛЮЧЕНИЯ АЛИСЫ",10,MUTED));
        credentialRow(wifi,"Название сети",prefs.getString("hotspot_ssid","AliceBridge"),false);
        credentialRow(wifi,"Пароль",prefs.getString("hotspot_password",""),true);
        wifi.addView(text("Временная точка доступа · 2,4 ГГц",12,MUTED));return wifi;
    }
    private void credentialRow(LinearLayout parent,String label,String value,boolean secret){
        LinearLayout line=row(),content=column();content.addView(text(label,12,MUTED));
        TextView displayed=text(value,secret?17:22,secret?PURPLE:TEXT);displayed.setTextIsSelectable(true);content.addView(displayed);
        line.addView(content,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton copy=new ImageButton(this);copy.setImageResource(R.drawable.ic_copy);copy.setContentDescription(secret?"Скопировать пароль":"Скопировать название сети");
        copy.setPadding(dp(12),dp(12),dp(12),dp(12));copy.setScaleType(ImageView.ScaleType.CENTER);
        copy.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30b6a1ff),background(0xff202438,14),null));
        copy.setOnClickListener(v->{
            ClipData clip=ClipData.newPlainText(label,value);
            if(secret&&Build.VERSION.SDK_INT>=33){PersistableBundle flags=new PersistableBundle();flags.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true);clip.getDescription().setExtras(flags);}
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
            if(Build.VERSION.SDK_INT<33)toast(secret?"Пароль скопирован":"Название сети скопировано");
        });
        LinearLayout.LayoutParams position=new LinearLayout.LayoutParams(dp(48),dp(48));position.leftMargin=dp(12);line.addView(copy,position);parent.addView(line);
    }
    private int step(Session.Phase phase) {
        return switch (phase) { case ROOT -> 0; case ROOT_PERMISSION -> 1; case HOTSPOT -> 2; case WAITING -> 3; default -> 4; };
    }
    private void rebootNote(LinearLayout parent) {
        LinearLayout note = card(parent);
        note.addView(text("После завершения обязательно перезагрузите телефон", 14, PURPLE));
        note.addView(text("Перезагрузка уберёт временный root и изменения DFRoot, чтобы они не мешали обычной работе телефона. Во время подключения перезагружаться не нужно.", 12, MUTED));
    }
    private void connectDirect() {
        EditText ip = new EditText(this); ip.setSingleLine(); ip.setHint("IP колонки, порт 5555"); ip.setText(prefs.getString("last_station", ""));
        new AlertDialog.Builder(this).setTitle("Подключиться по ADB").setView(ip).setPositiveButton("Подключиться",(d,w)-> {
            String host=ip.getText().toString().trim();try{RootShell.validateIpv4(host);startForegroundService(new Intent(this,BridgeService.class).setAction("direct").putExtra("host",host));}catch(IllegalArgumentException e){toast(e.getMessage());}
        }).setNegativeButton("Отмена",null).show();
    }
    private void showPackages() {
        if(!Session.yseReady||!Session.adbConnected){toast("Дождитесь подключения YSE");return;}
        startActivity(new Intent(this,YsePackages.class));
    }
    private void terminal(){startActivity(new Intent(this,TerminalActivity.class));}
    private void showYam(){if(!Session.adbConnected){toast("Сначала подключитесь к Алисе по ADB");return;}startActivity(new Intent(this,YamActivity.class));}
    private void homeWifi() {startActivity(new Intent(this,HomeWifiActivity.class));}
    private void renderConnected() {
        String mode="control";
        if(!Session.directMode&&!Session.wifiDone){
            mode="yse";
            for(StationSetup.Step step:Session.setupSteps)if(!step.status().equals("Готово")){mode=step.id();break;}
            if(StationSetup.complete(Session.setupSteps))mode="control";
        }
        String key="connected|"+mode+"|"+Session.wifiDone+"|"+Session.setupSteps.stream().map(StationSetup.Step::status).collect(java.util.stream.Collectors.joining(","))+"|"+Session.adbConnected+"|"+Session.yseReady+"|"+Session.sshReady+"|"+Session.zshReady+"|"+DeviceNames.name(Session.stationInfo)+"|"+Session.apkgStatus;
        if(!key.equals(rendered)){
            rendered=key;page.removeAllViews();progressCard=null;stationCard=null;connectedInfo=null;connectedStatus=null;
            if(mode.equals("control")){
                TextView tag=text("ПАНЕЛЬ УПРАВЛЕНИЯ",10,PURPLE);tag.setLetterSpacing(.14f);page.addView(tag);title(page,"Ваша Алиса — "+DeviceNames.name(Session.stationInfo));
                connectedStatus=text("",14,GREEN);page.addView(connectedStatus);
                boolean max=MaxPlan.matches(Session.stationInfo);
                if(max){LinearLayout note=card(page);note.addView(text("Макс YNDX-0008 · экспериментально",17,PURPLE));note.addView(text("Root и Permissive действуют до перезагрузки. Cache guard проверен отдельно; полное отключение OTA не подтверждено. Интерактивный root-терминал, SSH и смена Wi-Fi для Макс пока не проверены.",13,MUTED));}
                if(max&&!MaxRootBridge.ready(Session.station))page.addView(button("Получить root Макс",()->action("configure"),true));
                if(max){
                    CheckBox restore=new CheckBox(this);restore.setText("Восстанавливать root и установленный YAM после перезагрузки");restore.setTextColor(TEXT);restore.setChecked(MaxRestore.enabled(this,Session.stationInfo));
                    restore.setEnabled(!MaxPlan.restoreKey(Session.stationInfo).isEmpty()&&(MaxRootBridge.ready(Session.station)||restore.isChecked()));
                    restore.setOnCheckedChangeListener((v,checked)->MaxRestore.set(this,Session.stationInfo,checked));page.addView(restore);
                    page.addView(text("Работает, пока сессия AliceBridge активна и ADB колонки доступен. Сначала получите root вручную. После каждой перезагрузки повторно проверяем сборку и запускаем root один раз. Это не постоянный root без телефона.",12,MUTED));
                }
                boolean homePending=!max&&!Session.directMode&&!Session.wifiDone;
                if(homePending){
                    LinearLayout home=card(page);home.addView(text("Осталось подключить домашний Wi-Fi",17,TEXT));
                    home.addView(text("Переведём колонку с временной сети телефона на вашу обычную сеть.",13,MUTED));
                    home.addView(button("Подключить домашний Wi-Fi  →",this::homeWifi,true));
                }
                if(homePending&&Session.running)hotspotCredentials(page);
                LinearLayout tools=card(page);tools.addView(text("Пакеты и инструменты",17,TEXT));
                tools.addView(button("YAM · пакеты и плагины  →",this::showYam,!homePending));
                if(!max)tools.addView(button("Терминал  →",this::terminal,false));
                tools.addView(button(Session.yseReady?"Пакеты Entware  →":"Установить YSE и Entware",()->{if(Session.yseReady)showPackages();else action("yse-reconnect");},false));
                if(!max)tools.addView(button(Session.sshReady?"SSH настроен · порт "+SshClient.port():"Установить SSH",()->action("ssh"),false));
                if(!max)tools.addView(button(Session.zshReady?"Настроить Zsh":"Установить Zsh",()->action("zsh"),false));
                tools.addView(button("Установить apkg",()->action("apkg"),false));
                tools.addView(text(Session.apkgStatus,12,MUTED));
                tools.addView(text("Установка запускается только по вашей кнопке и после подтверждения.",12,MUTED));
                if(!homePending&&!max)page.addView(button("Домашний Wi-Fi",this::homeWifi,false));
                LinearLayout info=card(page);info.addView(button(stationInfoExpanded?"Скрыть сведения о колонке":"Сведения о колонке",()->{stationInfoExpanded=!stationInfoExpanded;rendered="";render();},false));
                connectedInfo=text("",14,MUTED);connectedInfo.setTextIsSelectable(true);connectedInfo.setVisibility(stationInfoExpanded?View.VISIBLE:View.GONE);info.addView(connectedInfo);
                info.addView(button("Проверить настройку по шагам",()->action("configure"),false));
                page.addView(button("Завершить сессию",()->action(BridgeService.STOP),false));
                if(prefs.getBoolean("reboot_required",false))rebootNote(page);
            }else{
                int stepIndex=switch(mode){case "ota"->1;case "analytics"->2;case "adb"->3;case "boot"->1;case "reboot"->2;case "verify"->3;case "fastfetch"->4;case "maxroot"->1;case "maxguard"->2;case "maxpermissive"->3;case "yse"->4;case "ssh"->5;default->6;};
                TextView tag=text(mode.equals("fastfetch")?"ДОПОЛНИТЕЛЬНЫЙ ШАГ":"НАСТРОЙКА · ШАГ "+stepIndex+" ИЗ 3",10,PURPLE);tag.setLetterSpacing(.14f);page.addView(tag);
                String heading=switch(mode){case "fastfetch"->"Устанавливаем fastfetch";case "maxroot"->"Получаем root Макс";case "maxguard"->"Защищаем cache recovery";case "maxpermissive"->"Включаем Permissive";case "boot"->"Устанавливаем root boot";case "reboot"->"Перезагружаем Станцию 1";case "verify"->"Проверяем root ADB";case "ota"->"Отключаем обновления";case "analytics"->"Отключаем аналитику";case "adb"->"Сохраняем доступ ADB";case "yse"->"Устанавливаем YSE";case "ssh"->"Настраиваем SSH";default->"Подключим домашний Wi-Fi";};
                title(page,heading);
                String hint=switch(mode){case "fastfetch"->"Системный информер устанавливается автоматически из APK. Если установка не удалась, можно пропустить этот шаг.";case "maxroot"->"Экспериментально: Макс YNDX-0008 без Zigbee, только сборка 5000000921. Процессный root действует до перезагрузки; boot не записывается.";case "maxguard"->"Проверяем права cache/recovery. Это дополнительная защита, полное отключение OTA не подтверждено.";case "maxpermissive"->"Сверяем layout ядра и включаем runtime Permissive. После перезагрузки колонки его нужно поднять заново.";case "boot"->"Сохраняем заводской boot на телефон, проверяем образ и записываем его только на совместимую Станцию 1.";case "reboot"->"Boot записан и проверен по SHA-256. Ждём загрузку колонки; её IP может измениться.";case "verify"->"Ищем ту же колонку по MAC и проверяем root ADB после перезагрузки.";case "ota"->"Запрещаем автоматическую установку новой прошивки, чтобы сохранить доступ к колонке.";case "analytics"->"Блокируем домены аналитики из выбранного сценария. Исходный hosts сохраняется перед изменением.";case "adb"->"Создаём и проверяем маркеры постоянного доступа. Проверка после перезагрузки выполняется отдельно.";case "ssh"->"Устанавливаем Dropbear и включаем автозапуск. Пользователь: root. Пароль по умолчанию: 1. Порт: 2222.";case "yse"->"Устанавливаем YSE и Entware. Пакеты будут доступны в отдельном окне через ADB.";default->"Последний шаг — обычная сеть вместо временного хотспота. После проверки нового адреса отключим точку доступа.";};
                page.addView(text(hint,16,MUTED));LinearLayout statusCard=card(page);connectedStatus=text("",15,TEXT);statusCard.addView(connectedStatus);connectedInfo=text("",12,MUTED);statusCard.addView(connectedInfo,ScreenUi.space(this,10));
                if(Session.running&&!Session.directMode&&!Session.wifiDone)hotspotCredentials(page);
                if(mode.equals("wifi")){page.addView(button(Session.wifiSwitchPending?"Продолжить подключение Wi-Fi  →":"Выбрать домашнюю сеть  →",this::homeWifi,true));}
                else {
                    boolean failed=Session.setupSteps.stream().anyMatch(s->s.status().equals("Ошибка"));
                    if(mode.equals("fastfetch"))page.addView(button("Пропустить fastfetch",()->action("skip-fastfetch"),false));
                    if(mode.equals("ssh"))page.addView(button("Повторить установку SSH",()->action("ssh"),false));
                    if(failed||mode.startsWith("max")||mode.equals("yse")&&!Session.yseReady)page.addView(button(mode.startsWith("max")?"Получить root Макс":"Повторить текущую настройку",()->action("configure"),false));
                    if(failed&&Station1Plan.matches(Session.stationInfo))page.addView(button("Восстановление boot",()->new AlertDialog.Builder(this).setTitle("Заводской boot Станции 1").setMessage("Сброс аккаунта и настроек не откатывает boot. Перед записью сохраняем исходный образ на телефон. Заводской boot также доступен на сервере. Восстановление выполняется отдельно через ADB; автоматической повторной записи нет.\n\nSHA-256: "+Station1Plan.STOCK_HASH).setPositiveButton("Скачать boot.img",(dialog,which)->browser("http://78.24.223.83/station1/boot.img")).setNegativeButton("Закрыть",null).show(),false));
                }
                page.addView(button("Открыть журнал",this::showLog,false));
            }
        }
        Map<String,String> info=Session.stationInfo;
        if(mode.equals("control")){
            if(connectedStatus!=null)setChanged(connectedStatus,Session.wifiSwitchPending?Session.wifiStatus:Session.adbConnected?Session.yseStatus+(!Session.wifiDone?" · следующий шаг — домашний Wi-Fi":""):"Связь потеряна · пытаемся подключиться снова");
            if(connectedStatus!=null&&Session.adbConnected&&MaxPlan.matches(info))setChanged(connectedStatus,MaxRootBridge.ready(Session.station)?"Root worker активен · runtime Permissive · до перезагрузки":"ADB подключён · root Макс ещё не запущен");
            if(connectedStatus!=null&&Session.adbConnected&&Station1Plan.matches(info)&&!Station1Setup.otaProtected(this))setChanged(connectedStatus,Session.stationInfoError.isEmpty()?"Проверяем root и блокировку OTA перед переходом на домашнюю сеть…":Session.stationInfoError);
            if(connectedInfo!=null)setChanged(connectedInfo,"IP: "+Session.station+":5555\nПрошивка: "+info.getOrDefault("VERSION","получаем…")+"\nДоступ: "+("0".equals(info.get("UID"))?"root":"UID "+info.getOrDefault("UID","—"))+"\nWi-Fi: "+info.getOrDefault("SSID","—")+"\nЯдро: "+info.getOrDefault("KERNEL","—")+"\nZsh: "+Session.zshStatus+"\nSSH: "+Session.sshStatus+(Session.sshReady?"\nssh -p "+SshClient.port()+" root@"+Session.station:""));
        }else{
            String status=mode.equals("ssh")?Session.sshStatus:mode.equals("yse")?Session.yseStatus:mode.equals("wifi")?Session.wifiStatus:"Выполняем команды на колонке…";
            for(StationSetup.Step step:Session.setupSteps)if(step.id().equals(mode)&&!step.output().isEmpty())status=step.status().equals("Ошибка")?"Не удалось завершить шаг. Подробности — в журнале.":"Изменения проверены";
            if(Station1Plan.matches(Session.stationInfo)||MaxPlan.matches(Session.stationInfo)){
                for(StationSetup.Step step:Session.setupSteps)if(step.id().equals(mode))status=step.output().isEmpty()?step.status():step.output();
            }
            if(!Session.adbConnected)status=Station1Setup.pending(this)?"Станция 1 перезагружается после записи root boot. Не отключайте питание. Ждём загрузку и ищем новый адрес автоматически.":"Связь с колонкой прервалась. Если она перезагружается, дождитесь загрузки. Повторяем подключение ADB автоматически.";
            if(connectedStatus!=null)setChanged(connectedStatus,status);
            if(connectedInfo!=null)setChanged(connectedInfo,"ADB · "+Session.station+"\nМодель: "+DeviceNames.name(info)+"\nПлатформа: "+DeviceNames.identifier(info)+"\nПрошивка: "+info.getOrDefault("VERSION","получаем…"));
        }
    }
    private void action(String action) {
        if("apkg".equals(action)){
            new AlertDialog.Builder(this).setTitle("Установить apkg?").setMessage("Установить пакетный менеджер initialfox/yandex-station-repo? Бинарник будет передан из APK через ADB. После установки выполните apkg update для загрузки каталога.").setPositiveButton("Установить",(dialog,which)->dispatchAction("apkg")).setNegativeButton("Отмена",null).show();return;
        }
        if("zsh".equals(action)){
            if(!Session.yseReady){new AlertDialog.Builder(this).setTitle("Сначала установите Entware").setMessage("Нажмите «Установить YSE и Entware» в панели управления. Затем можно будет установить Zsh.").setPositiveButton("Понятно",null).show();return;}
            new AlertDialog.Builder(this).setTitle("Установить Zsh?").setMessage("Вы действительно хотите установить Zsh с темой adam1, историей и автодополнением? Zsh станет оболочкой root для новых сессий терминала и SSH. Настройка сохраняется после перезагрузки. Пакет и зависимости будут взяты из APK.").setPositiveButton("Установить",(dialog,which)->dispatchAction(action)).setNegativeButton("Отмена",null).show();return;
        }
        if ("yse-reconnect".equals(action) || "ssh".equals(action)) {
            boolean ssh="ssh".equals(action);
            new AlertDialog.Builder(this).setTitle(ssh?"Установить SSH?":"Установить YSE и Entware?")
                .setMessage(ssh?"Вы действительно хотите установить SSH Dropbear на колонку? Будет включён автозапуск. Пользователь: root, начальный пароль: 1, порт: "+SshClient.port()+".":"Вы действительно хотите установить YSE и Entware на колонку? Базовые пакеты, curl и сертификаты будут переданы из APK через ADB.")
                .setPositiveButton("Установить",(dialog,which)->dispatchAction(action)).setNegativeButton("Отмена",null).show();
            return;
        }
        dispatchAction(action);
    }
    private void dispatchAction(String action) {
        if (Session.busy && !BridgeService.STOP.equals(action) && !(BridgeService.START.equals(action) && Session.discoveryRunning)) { toast("Дождитесь текущего шага"); return; }
        if (BridgeService.START.equals(action)) {
            if (Session.flowActive) return;
            Session.phase = Session.Phase.ROOT;
        }
        Intent request=new Intent(this,BridgeService.class).setAction(action);
        if(BridgeService.START.equals(action)){request.putExtra("legacy_wpa",legacyWpaRequested&&!ShizukuNetwork.selected(this));legacyWpaRequested=false;}
        startForegroundService(request); rendered = ""; render();
    }
    private void selectMode(String mode) {
        if(Session.flowActive){toast("Сначала завершите текущую сессию");return;}
        if(mode.equals("shizuku") && android.os.Build.VERSION.SDK_INT<33){toast("Режим Shizuku требует Android 13+");return;}
        if(!mode.equals("root"))legacyWpaRequested=false;
        prefs.edit().putString("connection_mode",mode).apply();modeChoicesExpanded=false;rendered="";render();
    }
    private void beginSelected() {
        if(ShizukuNetwork.selected(this)) {
            if(ShizukuNetwork.permitted()) action(BridgeService.START);
            else startActivity(new Intent(this,ShizukuGuideActivity.class));
        } else chooseManager(true);
    }
    private void openManager() {
        chooseManager(false);
    }
    private void chooseManager(boolean beginWorkflow) {
        if (Session.busy && !(beginWorkflow && Session.discoveryRunning)) { toast("Дождитесь завершения текущего шага"); return; }
        io.execute(() -> {
            java.util.List<String> packages = new ArrayList<>(), labels = new ArrayList<>();
            for (android.content.pm.ApplicationInfo app : getPackageManager().getInstalledApplications(0)) {
                if (app.nativeLibraryDir != null && new File(app.nativeLibraryDir, "libksud.so").isFile()) {
                    packages.add(app.packageName); labels.add(app.loadLabel(getPackageManager()) + "\n" + app.packageName);
                }
            }
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (packages.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle("SU Manager не найден")
                        .setMessage("Установите KernelSU-совместимый менеджер с libksud.so. Затем повторите запуск.")
                        .setPositiveButton("Инструкция", (d,w) -> browser("https://github.com/diabl0w/DFRoot#usage")).setNegativeButton("Закрыть", null).show(); return;
                }
                new AlertDialog.Builder(this).setTitle(beginWorkflow ? "Выберите SU Manager для DFRoot" : "В каком приложении выдать права?")
                    .setItems(labels.toArray(new String[0]), (d,index) -> io.execute(() -> {
                        boolean saved = createDeviceProtectedStorageContext().getSharedPreferences("dfroot", MODE_PRIVATE).edit()
                            .putString("su_manager", packages.get(index)).commit();
                        handler.post(() -> {
                            if (!saved) { toast("Не удалось сохранить выбор менеджера"); return; }
                            if (beginWorkflow) action(BridgeService.START);
                            else openPackage(packages.get(index), "https://github.com/diabl0w/DFRoot#usage");
                        });
                    })).setNegativeButton("Отмена", null).show();
            });
        });
    }
    private String journal() {
        List<JournalView.Entry> entries=new ArrayList<>();
        for(Session.Event e:Session.snapshot())entries.add(new JournalView.Entry(e.time(),e.kind(),e.source(),e.detail(),e.status()));
        List<String> secrets=new ArrayList<>();for(Map.Entry<String,?> e:prefs.getAll().entrySet())if(DiagnosticRedactor.secretKey(e.getKey())&&e.getValue() instanceof String)secrets.add((String)e.getValue());
        return new DiagnosticRedactor(secrets).clean(JournalView.format(entries,detailedJournal));
    }
    private LinearLayout dialogLayout(Dialog dialog, String title) {
        LinearLayout layout = column(); layout.setBackgroundColor(BG); layout.setFocusableInTouchMode(true);
        layout.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(22), bars.top + dp(12), dp(22), bars.bottom + dp(12)); return insets;
        });
        LinearLayout toolbar = row(); TextView name = text(title, 25, TEXT); name.setTypeface(null, Typeface.BOLD);
        toolbar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = button("✕", dialog::dismiss, false); close.setContentDescription("Закрыть"); close.setPadding(0, 0, 0, 0);
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48))); layout.addView(toolbar); space(layout, 12);
        return layout;
    }
    private void openDialog(Dialog dialog, LinearLayout layout) {
        dialog.setContentView(layout);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setStatusBarColor(BG); window.setNavigationBarColor(BG);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
        if (window != null) window.setLayout(-1, -1);
        layout.requestFocus();
    }
    private void showLog() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        LinearLayout layout = dialogLayout(dialog, "Журнал");
        layout.addView(text("События настройки и подключения. Повторы ADB и DNS сгруппированы; полный вывод сохраняется в файл диагностики.", 13, MUTED));
        Switch details=new Switch(this);details.setText("Подробности ADB и DNS");details.setTextColor(MUTED);details.setChecked(detailedJournal);
        details.setOnCheckedChangeListener((v,checked)->{detailedJournal=checked;if(logText!=null)logText.setText(journal());});layout.addView(details);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout contents = column(); contents.setPadding(0, dp(4), 0, dp(14));
        LinearLayout events = card(contents); events.addView(text("СОБЫТИЯ ПОДКЛЮЧЕНИЯ", 10, PURPLE));
        logText = text(journal(), 13, TEXT); logText.setTypeface(Typeface.MONOSPACE); logText.setTextIsSelectable(true); events.addView(logText);
        scroll.addView(contents); layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        layout.addView(button("Поделиться диагностикой · ZIP",()->{dialog.dismiss();shareDiagnostics();},true));
        dialog.setOnDismissListener(d -> logText = null); openDialog(dialog, layout);
    }
    private void showSettings() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        LinearLayout layout = dialogLayout(dialog, "Настройки");
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false);
        LinearLayout contents = column(); contents.setPadding(0, 0, 0, dp(16));
        contents.addView(text("Подключение работает автоматически. Здесь можно изменить сеть и публичный DNS.", 13, MUTED));
        LinearLayout network = card(contents); network.addView(text("ТОЧКА ДОСТУПА", 10, PURPLE));
        EditText name = field(network, "Имя сети", prefs.getString("hotspot_ssid", "AliceBridge"));
        EditText pass = field(network, "Пароль Wi-Fi", prefs.getString("hotspot_password", ""));
        EditText dns = field(network, "IP сервера DNS и прошивок", prefs.getString("resolver", "78.24.223.83"));
        network.addView(text("По умолчанию — WPA2. Поддержка WPA-1 включается отдельно перед началом настройки, только в режиме Root / DFRoot.",12,MUTED));
        LinearLayout updates=card(contents);updates.addView(text("ОБНОВЛЕНИЯ ПРИЛОЖЕНИЯ",10,PURPLE));
        updates.addView(button("Проверить обновления",()->{dialog.dismiss();checkForUpdates(true);},false));
        LinearLayout support = card(contents); support.addView(text("ДИАГНОСТИКА", 10, PURPLE));
        EditText adbHost = field(support, "IP колонки · встроенный ADB, порт 5555", Session.station.isEmpty() ? prefs.getString("adb_host", "") : Session.station);
        support.addView(button("Проверить через встроенный ADB", () -> {
            if (Session.busy) { toast("Дождитесь текущей операции"); return; }
            String host = adbHost.getText().toString().trim();
            try { RootShell.validateIpv4(host); } catch (IllegalArgumentException e) { toast("Укажите IPv4 колонки"); return; }
            prefs.edit().putString("adb_host", host).apply(); dialog.dismiss();
            startForegroundService(new Intent(this, BridgeService.class).setAction("adb").putExtra("host", host)); showLog();
        }, false));
        support.addView(button("Shizuku · запуск без root", () -> startActivity(new Intent(this,ShizukuGuideActivity.class)), false));
        support.addView(button("Инструкция DFRoot / SU Manager", () -> browser("https://github.com/diabl0w/DFRoot#usage"), false));
        support.addView(button("Очистить правила после сбоя", () -> { dialog.dismiss(); action(BridgeService.CLEAN); }, false));
        support.addView(text("Все обычные DNS-запросы клиентов хотспота по IPv4 UDP/TCP идут через выбранный DNS без списков доменов. HTTP/HTTPS не перехватываем. DNS-статусы не подтверждают успешную настройку колонки. ADB проверяется отдельно.", 12, MUTED));
        scroll.addView(contents); layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        layout.addView(button("Сохранить настройки", () -> {
            if (Session.flowActive || Session.busy) { toast("Сначала завершите сценарий"); return; }
            try {
                String ssid = name.getText().toString(), password = pass.getText().toString(), resolver = dns.getText().toString().trim();
                RootShell.validateIpv4(resolver);
                if (ssid.isBlank() || ssid.getBytes(StandardCharsets.UTF_8).length > 32 || password.length() < 8 || password.length() > 63 || !password.matches("[\\x20-\\x7e]+"))
                    throw new IllegalArgumentException("SSID: 1–32 байта. Пароль: 8–63 латинских символа.");
                prefs.edit().putString("hotspot_ssid", ssid).putString("hotspot_password", password).putString("resolver", resolver).apply();
                dialog.dismiss(); rendered = ""; render();
            } catch (IllegalArgumentException e) { toast(e.getMessage()); }
        }, true)); openDialog(dialog, layout);
    }
    private void shareDiagnostics(){
        if(collectingDiagnostics){toast("Диагностика уже собирается…");return;}
        collectingDiagnostics=true;
        LinearLayout progress=column();progress.setPadding(dp(24),dp(16),dp(24),dp(16));progress.addView(new ProgressBar(this));progress.addView(text("Собираем журнал, сеть и сведения о колонке. Обычно это занимает до минуты.",14,MUTED));
        AlertDialog waiting=new AlertDialog.Builder(this).setTitle("Диагностика").setView(progress).setCancelable(false).create();waiting.show();
        io.execute(()->{
            File report=null;
            try{
                File directory=DiagnosticProvider.directory(this);if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось подготовить файл диагностики");
                File[] old=directory.listFiles();if(old!=null)for(File file:old)if(file.getName().startsWith("AliceBridge-diagnostics-")&&System.currentTimeMillis()-file.lastModified()>7L*24*60*60*1000)file.delete();
                String prefix="AliceBridge-diagnostics-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+"-";
                report=File.createTempFile(prefix,".zip",directory);
                try(OutputStream out=new FileOutputStream(report)){Diagnostics.write(this,out);}
                Uri uri=DiagnosticProvider.uri(this,report);
                handler.post(()->{
                    collectingDiagnostics=false;if(isFinishing()||isDestroyed())return;waiting.dismiss();
                    Intent share=new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_SUBJECT,"AliceBridge · диагностика").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    share.setClipData(ClipData.newUri(getContentResolver(),"AliceBridge diagnostics",uri));
                    try{startActivity(Intent.createChooser(share,"Отправить диагностику"));}catch(ActivityNotFoundException e){toast("Нет приложения для отправки ZIP");}
                });
            }catch(Exception error){
                if(report!=null)report.delete();
                handler.post(()->{collectingDiagnostics=false;if(isFinishing()||isDestroyed())return;waiting.dismiss();toast("Не удалось собрать диагностику: "+error.getMessage());});
            }
        });
    }
    private void openPackage(String pkg, String fallback) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg); if (i != null) startActivity(i); else browser(fallback);
    }
    private void browser(String url) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void setChanged(TextView view, String value) { if (!view.getText().toString().equals(value)) view.setText(value); }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setPadding(0, dp(6), 0, dp(6)); t.setLineSpacing(dp(3), 1); return t; }
    private void title(LinearLayout l, String value) { TextView t = text(value, 29, TEXT); t.setTypeface(null, Typeface.BOLD); l.addView(t); }
    private void space(LinearLayout l, int height) { l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout card(LinearLayout parent) { LinearLayout l = column(); l.setPadding(dp(18), dp(14), dp(18), dp(14)); l.setBackground(background(PANEL, 24)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(14), 0, dp(8)); parent.addView(l, p); return l; }
    private TextView button(String value, Runnable action, boolean filled) { TextView b = text(value, 14, filled ? BG : PURPLE); b.setGravity(Gravity.CENTER); b.setTypeface(null, Typeface.BOLD); b.setPadding(dp(14), dp(14), dp(14), dp(14)); b.setMinHeight(dp(48)); b.setBackground(background(filled ? PURPLE : 0xff202438, 16)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(3), dp(5), dp(3), dp(5)); b.setLayoutParams(p); b.setOnClickListener(v -> action.run()); return b; }
    private EditText field(LinearLayout parent, String label, String value) {
        parent.addView(text(label, 12, MUTED)); EditText f = new EditText(this);
        f.setSingleLine(true); f.setText(value); f.setTextColor(TEXT); f.setTextSize(15);
        f.setPadding(dp(14), dp(12), dp(14), dp(12)); f.setBackground(background(BG, 12));
        f.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52)); p.setMargins(0, 0, 0, dp(10)); parent.addView(f, p); return f;
    }
    private final class SignalView extends View {
        private final Paint paint = new Paint(3);
        private Shader glow;
        SignalView(Context c) { super(c); setContentDescription("Сигнал Алисы"); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { glow = new RadialGradient(w/2f, h/2f, dp(66), new int[]{0xffc4b5ff, 0xff7564d4, 0x007564d4}, null, Shader.TileMode.CLAMP); }
        @Override protected void onDraw(Canvas canvas) { float cx = getWidth()/2f, cy = getHeight()/2f; paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xff25283e); for (int i=1;i<=3;i++) canvas.drawCircle(cx,cy,dp(27+i*17),paint); paint.setStyle(Paint.Style.FILL); paint.setShader(glow); canvas.drawCircle(cx,cy,dp(66),paint); paint.setShader(null); paint.setColor(TEXT); paint.setStrokeWidth(dp(4)); paint.setStrokeCap(Paint.Cap.ROUND); for(int i=-2;i<=2;i++){float h=dp(8+(2-Math.abs(i))*7);canvas.drawLine(cx+dp(i*10),cy-h/2,cx+dp(i*10),cy+h/2,paint);} }
    }
}
