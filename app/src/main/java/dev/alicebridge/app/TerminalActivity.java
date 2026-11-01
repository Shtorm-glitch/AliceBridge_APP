package dev.alicebridge.app;
import dev.alicebridge.station.*;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import static dev.alicebridge.app.ScreenUi.*;

/** A real PTY shell over the app's private ADB, rendered by bundled xterm.js. */
public final class TerminalActivity extends Activity {
    private StationTools tools;
    private static final String PAGE="file:///android_asset/terminal/index.html";
    private final ExecutorService reader=Executors.newCachedThreadPool(),writer=Executors.newSingleThreadExecutor(),control=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private final Object inputLock=new Object();
    private WebView web;private TextView status;private volatile Process process;private volatile OutputStream input;
    private volatile NativeAdb client;private volatile boolean disposed,ready;private volatile int cols=80,rows=24;
    private volatile long shellRevision;private final AtomicBoolean switching=new AtomicBoolean();
    private volatile String token="";private String host,initial="",yamPackage="";
    private final Runnable resizeTask=()->{NativeAdb adb=client;String current=token;if(adb==null||current.isEmpty())return;try{adb.shell(TerminalCommands.resize(current,cols,rows));}catch(Exception ignored){}};
    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state){
        super.onCreate(state);tools=((BridgeApplication)getApplication()).getRepository().getActiveTools();
        if(tools==null){finish();return;}host=tools.state.station;
        if(host.isEmpty()||!tools.state.adbConnected){Toast.makeText(this,"Сначала подключитесь к колонке по ADB",Toast.LENGTH_LONG).show();finish();return;}
        initial=getIntent().getStringExtra("package");if(initial!=null)try{initial=TerminalCommands.packageCommand(initial);}catch(IllegalArgumentException e){initial="";}
        if(initial==null)initial="";
        String yam=getIntent().getStringExtra("yam_package");if(yam!=null)try{yamPackage=yam; if(!yam.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}"))throw new IllegalArgumentException("Неверный пакет");}catch(IllegalArgumentException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();finish();return;}
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root=root(this,"Терминал",this::finish);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top+dp(this,10),bars.right,bars.bottom+dp(this,8));return insets;});
        root.getChildAt(0).setPadding(dp(this,16),0,dp(this,16),0);
        status=text(this,host+" · подключаем PTY…",12,MUTED);status.setPadding(dp(this,16),0,dp(this,16),0);root.addView(status,space(this,10));
        LinearLayout actions=row(this);actions.setPadding(dp(this,12),0,dp(this,7),0);addAction(actions,"Клавиатура",this::keyboard);addAction(actions,"Вставить",this::paste);addAction(actions,"Заново",()->connect());root.addView(actions,space(this,12));
        web=new WebView(this);web.setBackgroundColor(BG);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setBlockNetworkLoads(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setDomStorageEnabled(false);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !PAGE.equals(request.getUrl().toString());}@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){if(!request.getUrl().toString().startsWith("file:///android_asset/terminal/"))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));return null;}});
        web.addJavascriptInterface(new Bridge(),"AndroidTerminal");root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        HorizontalScrollView keys=new HorizontalScrollView(this);keys.setHorizontalScrollBarEnabled(false);LinearLayout bar=row(this);
        String[][] values={{"Esc","\u001b"},{"Tab","\t"},{"Ctrl+C","\u0003"},{"Ctrl+D","\u0004"},{"↑","\u001b[A"},{"↓","\u001b[B"},{"←","\u001b[D"},{"→","\u001b[C"},{"F1","\u001bOP"},{"F2","\u001bOQ"},{"F3","\u001bOR"},{"F4","\u001bOS"},{"F5","\u001b[15~"},{"F6","\u001b[17~"},{"F7","\u001b[18~"},{"F8","\u001b[19~"},{"F9","\u001b[20~"},{"F10","\u001b[21~"},{"PgUp","\u001b[5~"},{"PgDn","\u001b[6~"}};
        for(String[] key:values){TextView button=button(this,key[0],()->evaluate("terminalKey("+JSONObject.quote(key[1])+")"),false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(this,46));lp.rightMargin=dp(this,7);bar.addView(button,lp);}keys.addView(bar);root.addView(keys,space(this,8));web.loadUrl(PAGE);
    }
    private void addAction(LinearLayout row,String title,Runnable click){TextView b=button(this,title,click,false);b.setTextSize(12);b.setMinHeight(dp(this,40));b.setPadding(dp(this,5),dp(this,8),dp(this,5),dp(this,8));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(this,42),1);lp.rightMargin=dp(this,5);row.addView(b,lp);}
    private void keyboard(){web.requestFocus();evaluate("focusTerminal()");((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(web,InputMethodManager.SHOW_IMPLICIT);}
    private void paste(){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard.hasPrimaryClip()&&clipboard.getPrimaryClip()!=null&&clipboard.getPrimaryClip().getItemCount()>0){CharSequence text=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);if(text!=null)evaluate("pasteText("+JSONObject.quote(text.toString())+")");}}
    private void evaluate(String js){if(!disposed&&web!=null)web.evaluateJavascript("window."+js,null);}
    private void connect(){
        if(!ready||disposed)return;int attempt=generation.incrementAndGet();stopProcess();status.setText(host+" · подключаем PTY…");
        reader.execute(()->{
            Process local=null;NativeAdb adb=tools.state.adbClient;String id=UUID.randomUUID().toString().replace("-","");
            try{
                if(!tools.state.adbConnected||!host.equals(tools.state.station)||tools.state.stopRequested)throw new IOException("Соединение с колонкой завершено");
                if(!yamPackage.isEmpty()){initial=tools.yamTerminal(yamPackage);}
                if(disposed||attempt!=generation.get())return;
                shellRevision=tools.state.zshRevision;
                local=adb.terminal(id,cols,rows);
                if(disposed||attempt!=generation.get()){local.destroyForcibly();return;}
                client=adb;token=id;process=local;synchronized(inputLock){input=local.getOutputStream();}
                ui.post(()->{if(attempt==generation.get()&&!disposed){status.setText(host+(Station1Plan.matches(tools.state.stationInfo)?" · root · интерактивный PTY":" · ADB · интерактивный PTY"));status.setTextColor(GREEN);evaluate("focusTerminal()");}});
                if(!initial.isEmpty())send(initial+"\r");
                try(InputStream out=local.getInputStream()){
                    byte[] bytes=new byte[4096];for(int n;(n=out.read(bytes))!=-1&&attempt==generation.get()&&!disposed;){
                        String encoded=android.util.Base64.encodeToString(bytes,0,n,android.util.Base64.NO_WRAP);CountDownLatch shown=new CountDownLatch(1);
                        ui.post(()->{if(!disposed&&attempt==generation.get())web.evaluateJavascript("window.appendOutput("+JSONObject.quote(encoded)+")",ignored->shown.countDown());else shown.countDown();});shown.await(5,TimeUnit.SECONDS);
                    }
                }
                if(!disposed&&attempt==generation.get())ui.post(()->{status.setText("Сессия завершена · «Заново» для подключения");status.setTextColor(MUTED);});
            }catch(Exception error){if(!disposed&&attempt==generation.get())ui.post(()->{status.setText("Терминал: "+error.getMessage());status.setTextColor(WARN);});}
            finally{
                if(local!=null)local.destroyForcibly();
                if(attempt==generation.get()){synchronized(inputLock){input=null;}process=null;client=null;token="";}
                try{adb.shell(TerminalCommands.cleanup(id));}catch(Exception ignored){}
            }
        });
    }
    private void send(String data){if(disposed||data.length()>65536)return;writer.execute(()->{try{synchronized(inputLock){OutputStream out=input;if(out!=null){out.write(data.getBytes(StandardCharsets.UTF_8));out.flush();}}}catch(IOException ignored){}});}
    private void stopProcess(){synchronized(inputLock){try{if(input!=null)input.close();}catch(IOException ignored){}input=null;}Process current=process;if(current!=null)current.destroyForcibly();process=null;client=null;token="";}
    private final Runnable shellWatch=new Runnable(){public void run(){
        if(!disposed&&tools.state.zshReady&&tools.state.zshRevision!=shellRevision&&input!=null&&switching.compareAndSet(false,true)){
            NativeAdb adb=client;String id=token;long revision=tools.state.zshRevision;
            if(adb==null||id.isEmpty()){switching.set(false);}else control.execute(()->{
                try{
                    NativeAdb.Result idle=adb.shell(TerminalCommands.foreground(id));
                    if(idle.code()==0&&idle.output().trim().equals("IDLE")&&!disposed&&id.equals(token)){
                        send("\u0015exec /data/opt/bin/alicebridge-login\r");shellRevision=revision;
                        ui.post(()->{if(!disposed)status.setText(host+" · Zsh · тема adam1");});
                    }else ui.post(()->{if(!disposed)status.setText("Zsh готов · переключим оболочку после текущей команды");});
                }catch(Exception ignored){}finally{switching.set(false);}
            });
        }
        if(!disposed)ui.postDelayed(this,1000);
    }};
    @Override protected void onResume(){super.onResume();ui.post(shellWatch);}
    @Override protected void onPause(){ui.removeCallbacks(shellWatch);super.onPause();}
    private final class Bridge{
        @JavascriptInterface public void ready(int columns,int lines){ui.post(()->{if(disposed)return;cols=Math.max(10,Math.min(500,columns));rows=Math.max(3,Math.min(300,lines));ready=true;connect();});}
        @JavascriptInterface public void send(String text){TerminalActivity.this.send(text);}
        @JavascriptInterface public void resize(int columns,int lines){cols=Math.max(10,Math.min(500,columns));rows=Math.max(3,Math.min(300,lines));ui.removeCallbacks(resizeDispatch);ui.postDelayed(resizeDispatch,250);}
    }
    private final Runnable resizeDispatch=()->{if(!disposed)control.execute(resizeTask);};
    @Override protected void onDestroy(){disposed=true;generation.incrementAndGet();ui.removeCallbacksAndMessages(null);stopProcess();reader.shutdownNow();writer.shutdownNow();control.shutdownNow();if(web!=null){web.removeJavascriptInterface("AndroidTerminal");web.destroy();web=null;}super.onDestroy();}
}
