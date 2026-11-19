package ru.oldest.bionicpocket;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.security.keystore.*;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xff101916, PANEL=0xff1b2923, TEXT=0xffecf3ee, MUTED=0xff9fafa5, GREEN=0xffb0edce;
    private static final String DEFAULT_URL="https://vpn.4664702.ru:1236", REPO="OLDest/bionic-pocket";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences prefs;
    private JSONArray chats=new JSONArray(), projects=new JSONArray();
    private JSONObject current;
    private LinearLayout root, messages, composer, taskOptions;
    private TextView status, title;
    private ScrollView scroll;
    private EditText input, fileInput;
    private Spinner projectPicker, modePicker;
    private CheckBox readOnly;
    private Button send;
    private boolean foreground, networkBusy, rendering, pendingInstall;
    private String server, token="", pollError="";
    private long lastCheck;
    private final Runnable tick=new Runnable(){public void run(){if(foreground){poll();main.postDelayed(this,2500);}}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("private",MODE_PRIVATE);
        server=prefs.getString("server",DEFAULT_URL);
        try {token=decrypt(prefs.getString("token",""));}catch(Exception e){toast("Не удалось открыть токен. Введите его заново.");}
        if(token.isEmpty()) {
            try {
                JSONObject personal=new JSONObject(read(getAssets().open("personal.json"),4096));
                String preset=personal.getString("token"), endpoint=personal.getString("server");
                URI address=new URI(endpoint);
                if(preset.length()<32||!"https".equals(address.getScheme())||address.getHost()==null)throw new IOException("Invalid personal settings");
                token=preset;server=endpoint;
                prefs.edit().putString("server",server).putString("token",encrypt(token)).apply();
            } catch(FileNotFoundException ignored) {
                // Public builds have no personal settings.
            } catch(Exception e) { toast("Не удалось применить личные настройки подключения"); }
        }
        try {chats=new JSONArray(prefs.getString("chats","[]"));}catch(Exception ignored){}
        String selected=prefs.getString("selected","");
        for(int i=0;i<chats.length();i++)if(chats.optJSONObject(i).optString("id").equals(selected))current=chats.optJSONObject(i);
        if(current==null)newChat(false);
        build(); render(); connect(false);
    }
    @Override public void onResume(){super.onResume();foreground=true;main.removeCallbacks(tick);main.post(tick);if(pendingInstall&&getPackageManager().canRequestPackageInstalls()){pendingInstall=false;installUpdate();} if(System.currentTimeMillis()-prefs.getLong("updateCheck",0)>86400000L)checkUpdate(false);}
    @Override public void onPause(){foreground=false;main.removeCallbacks(tick);save();super.onPause();}
    @Override public void onDestroy(){main.removeCallbacksAndMessages(null);io.shutdown();super.onDestroy();}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable box(int color,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(radius));return b;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(44));b.setBackground(box(PANEL,14));b.setOnClickListener(v->action.run());return b;}
    private EditText field(String hint){EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(16);e.setHint(hint);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(box(PANEL,12));return e;}
    private void gap(LinearLayout parent,int h){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private void build(){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(12),dp(16),dp(10));
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,w)->{android.graphics.Insets bars=w.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets keyboard=w.getInsets(WindowInsets.Type.ime());root.setPadding(dp(16)+bars.left,dp(12)+bars.top,dp(16)+bars.right,dp(10)+Math.max(bars.bottom,keyboard.bottom));return w;});}
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(button("☰",()->history()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout headText=column();headText.setPadding(dp(12),0,0,0);title=text("Bionic Pocket",21,TEXT);title.setTypeface(null,Typeface.BOLD);headText.addView(title);status=text("Подключение…",12,MUTED);headText.addView(status);header.addView(headText,new LinearLayout.LayoutParams(0,-2,1));header.addView(button("＋",()->newChat(true)),new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(header);gap(root,12);
        LinearLayout selectors=new LinearLayout(this);projectPicker=new Spinner(this);modePicker=new Spinner(this);setItems(projectPicker,new String[]{"Без проекта"});setItems(modePicker,new String[]{"Общение","Задача с файлами"});selectors.addView(projectPicker,new LinearLayout.LayoutParams(0,dp(46),1));selectors.addView(modePicker,new LinearLayout.LayoutParams(0,dp(46),1));root.addView(selectors);
        projectPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!rendering){put(current,"project",pos==0?"":projects.optJSONObject(pos-1).optString("id"));save();}}});
        modePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){taskOptions.setVisibility(pos==1?View.VISIBLE:View.GONE);}});
        taskOptions=column();fileInput=field("Файлы: main.go, internal/audio/player.go");fileInput.setTextSize(13);taskOptions.addView(fileInput);readOnly=new CheckBox(this);readOnly.setText("Только анализ — без изменений файлов");readOnly.setTextColor(MUTED);readOnly.setChecked(true);taskOptions.addView(readOnly);taskOptions.setVisibility(View.GONE);root.addView(taskOptions);
        scroll=new ScrollView(this);scroll.setFillViewport(true);messages=column();messages.setPadding(0,dp(8),0,dp(12));scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        composer=new LinearLayout(this);composer.setGravity(Gravity.BOTTOM);input=field("Сообщение помощнику…");input.setMinLines(1);input.setMaxLines(5);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));send=button("↑",()->send());send.setTextSize(25);send.setTextColor(BG);send.setBackground(box(GREEN,16));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(52),dp(52));sp.leftMargin=dp(8);composer.addView(send,sp);root.addView(composer);
        TextView footer=text("Локальный Bionic · ответы сохраняются",11,MUTED);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(8),0,0);root.addView(footer);setContentView(root);
    }
    private void setItems(Spinner spinner,String[] items){ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items){public View getView(int p,View v,android.view.ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(TEXT);t.setTextSize(13);return t;}};spinner.setAdapter(a);}
    private void put(JSONObject j,String k,Object v){try{j.put(k,v);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    private void save(){if(prefs!=null)prefs.edit().putString("chats",chats.toString()).putString("selected",current.optString("id")).apply();}
    private JSONArray entries(){JSONArray a=current.optJSONArray("messages");if(a==null){a=new JSONArray();put(current,"messages",a);}return a;}
    private void newChat(boolean show){current=new JSONObject();put(current,"id",UUID.randomUUID().toString());put(current,"title","Новый разговор");put(current,"project","");put(current,"messages",new JSONArray());chats.put(current);if(show){input.setText("");render();save();}}
    private void render(){
        rendering=true;title.setText(current.optString("title","Bionic Pocket"));messages.removeAllViews();JSONArray list=entries();
        if(list.length()==0){LinearLayout welcome=column();welcome.setGravity(Gravity.CENTER);TextView mark=text("B",48,GREEN);mark.setTypeface(null,Typeface.BOLD);welcome.addView(mark);TextView h=text("Помощник под рукой",23,TEXT);h.setTypeface(null,Typeface.BOLD);welcome.addView(h);gap(welcome,12);TextView hint=text("Задайте вопрос, обсудите код или\nпоручите работу с файлами проекта.",15,MUTED);hint.setGravity(Gravity.CENTER);welcome.addView(hint);gap(welcome,20);welcome.addView(button("Проверить подключение",()->connect(true)));gap(welcome,8);welcome.addView(button("Настроить сервер и токен",()->settings()));messages.addView(welcome,new LinearLayout.LayoutParams(-1,-2));}
        boolean waiting=false;
        for(int i=0;i<list.length();i++){
            JSONObject m=list.optJSONObject(i);boolean user="user".equals(m.optString("role"));String state=m.optString("state","completed");
            LinearLayout bubble=column();bubble.setPadding(dp(14),dp(12),dp(14),dp(12));bubble.setBackground(box(user?0xff294a3a:PANEL,18));
            TextView label=text(user?"Вы":"Bionic",11,user?GREEN:MUTED);label.setTypeface(null,Typeface.BOLD);bubble.addView(label);gap(bubble,5);
            String content=m.optString("content");
            if("pending".equals(state)){waiting=true;content=m.optString("taskId").isEmpty()?"Готовлю ответ…":"Задача выполняется на ПК…";if(!pollError.isEmpty())content+="\n"+pollError;}
            if("error".equals(state))content="Не удалось получить ответ\n"+content;
            TextView body=text(content,16,TEXT);body.setTextIsSelectable(true);body.setLineSpacing(dp(3),1);bubble.addView(body);
            if(!m.optString("taskStatus").isEmpty()){gap(bubble,6);bubble.addView(text("Отчёт: "+m.optString("taskStatus")+" · интеграция отдельно",11,MUTED));}
            if("error".equals(state)){final JSONObject retry=m;bubble.addView(button("Повторить",()->retry(retry)));}
            final String copied=content;body.setOnLongClickListener(v->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Bionic",copied));toast("Скопировано");return true;});
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(10);if(user)bp.leftMargin=dp(32);else bp.rightMargin=dp(12);messages.addView(bubble,bp);
        }
        send.setEnabled(!waiting&&!networkBusy);send.setAlpha(send.isEnabled()?1f:.45f);
        String project=current.optString("project");int selected=0;for(int i=0;i<projects.length();i++)if(project.equals(projects.optJSONObject(i).optString("id")))selected=i+1;projectPicker.setSelection(selected);rendering=false;
        scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void history(){
        ArrayList<String> names=new ArrayList<>();names.add("⚙ Настройки и обновления");for(int i=chats.length()-1;i>=0;i--)names.add(chats.optJSONObject(i).optString("title"));
        new AlertDialog.Builder(this).setTitle("Разговоры").setItems(names.toArray(new String[0]),(d,n)->{if(n==0)settings();else{current=chats.optJSONObject(chats.length()-n);input.setText("");pollError="";render();save();}}).setNegativeButton("Закрыть",null).show();
    }
    private void settings(){
        LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),0);form.addView(text("Сервер HTTPS",13,MUTED));EditText url=field(DEFAULT_URL);url.setSingleLine(true);url.setText(server);form.addView(url);gap(form,12);form.addView(text("Токен доступа",13,MUTED));EditText secret=field(token.isEmpty()?"Вставьте токен":"Пустое поле — оставить текущий");secret.setSingleLine(true);secret.setTransformationMethod(PasswordTransformationMethod.getInstance());form.addView(secret);gap(form,12);form.addView(text("Токен шифруется ключом Android Keystore.\nИстория хранится только в приложении.",12,MUTED));gap(form,10);form.addView(button("Проверить обновления",()->checkUpdate(true)));gap(form,8);form.addView(button("Открыть исходный код",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/"+REPO)))));gap(form,8);form.addView(button("Удалить этот разговор",()->new AlertDialog.Builder(this).setMessage("Удалить разговор с телефона?").setPositiveButton("Удалить",(d,n)->{JSONArray keep=new JSONArray();for(int i=0;i<chats.length();i++)if(chats.optJSONObject(i)!=current)keep.put(chats.optJSONObject(i));chats=keep;newChat(true);}).setNegativeButton("Отмена",null).show()));
        ScrollView holder=new ScrollView(this);holder.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Bionic Pocket · "+version()).setView(holder).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{String base=url.getText().toString().trim();URI u=new URI(base);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||"/".equals(u.getPath())))throw new Exception("Укажите только HTTPS-адрес сервера без пути");String replacement=secret.getText().toString().trim();if(!replacement.isEmpty())token=replacement;if(token.length()<32)throw new Exception("Вставьте полный токен доступа");server=base.replaceAll("/+$","");prefs.edit().putString("server",server).putString("token",encrypt(token)).apply();dialog.dismiss();connect(true);}catch(Exception e){toast(e.getMessage());}}));dialog.show();
    }
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "0.1.0";}}
    private SecretKey key() throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias("bionic-token"))return (SecretKey)store.getKey("bionic-token",null);KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder("bionic-token",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return gen.generateKey();}
    private String encrypt(String s) throws Exception {Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return android.util.Base64.encodeToString(c.getIV(),2)+":"+android.util.Base64.encodeToString(c.doFinal(s.getBytes(StandardCharsets.UTF_8)),2);}
    private String decrypt(String s) throws Exception {if(s.isEmpty())return "";String[] p=s.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,android.util.Base64.decode(p[0],2)));return new String(c.doFinal(android.util.Base64.decode(p[1],2)),StandardCharsets.UTF_8);}
    private JSONObject api(String route,JSONObject payload) throws Exception {
        if(token.isEmpty())throw new Exception("Введите токен в настройках");
        HttpsURLConnection c=(HttpsURLConnection)new URL(server+route).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/json");
        try{if(payload!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();String data=read(code>=400?c.getErrorStream():c.getInputStream(),1000000);JSONObject result;try{result=new JSONObject(data);}catch(Exception e){throw new Exception("Сервер вернул некорректный ответ ("+code+")");}if(code<200||code>=300)throw new Exception(code==401?"Неверный токен доступа":result.optString("error","Ошибка HTTP "+code));return result;
        }finally{c.disconnect();}
    }
    private static String read(InputStream stream,int limit) throws Exception {if(stream==null)return "";try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Ответ слишком большой");out.write(buf,0,n);}return out.toString("UTF-8");}}
    private void connect(boolean visible){if(token.isEmpty()){status.setText("Нужен токен · откройте настройки");if(visible)settings();return;}status.setText("Проверяю связь…");io.execute(()->{try{JSONObject config=api("/mobile/config",null);main.post(()->{projects=config.optJSONArray("projects");if(projects==null)projects=new JSONArray();String[] names=new String[projects.length()+1];names[0]="Без проекта";for(int i=0;i<projects.length();i++)names[i+1]=projects.optJSONObject(i).optString("name");rendering=true;setItems(projectPicker,names);render();status.setText("● На связи · "+config.optString("model","Bionic"));if(visible)toast("Подключено");});}catch(Exception e){main.post(()->{status.setText("Нет связи · нажмите для проверки");status.setOnClickListener(v->connect(true));if(visible)toast(e.getMessage());});}});}
    private JSONArray context(){JSONArray result=new JSONArray();JSONArray all=entries();ArrayList<JSONObject> reverse=new ArrayList<>();int length=0;for(int i=all.length()-1;i>=0&&reverse.size()<10;i--){JSONObject m=all.optJSONObject(i);if(!"completed".equals(m.optString("state","completed"))||m.optString("content").isEmpty())continue;String body=m.optString("content");if(length+body.length()>10000)break;JSONObject item=new JSONObject();put(item,"role",m.optString("role"));put(item,"content",body);reverse.add(item);length+=body.length();}for(int i=reverse.size()-1;i>=0;i--)result.put(reverse.get(i));return result;}
    private void send(){String value=input.getText().toString().trim();if(value.isEmpty())return;if(value.length()>8000){toast("Сообщение слишком длинное (максимум 8000 символов)");return;}if(token.isEmpty()){settings();return;}try{
        JSONObject request=new JSONObject();request.put("requestId",UUID.randomUUID().toString());request.put("chatId",current.getString("id"));request.put("text",value);request.put("history",context());request.put("mode",modePicker.getSelectedItemPosition()==1?"task":"chat");String project=current.optString("project");if(!project.isEmpty())request.put("workspaceId",project);
        if("task".equals(request.getString("mode"))){if(project.isEmpty()){toast("Выберите проект для задачи");return;}JSONArray files=new JSONArray();for(String f:fileInput.getText().toString().split("[,\\n]")){if(!f.trim().isEmpty())files.put(f.trim().replace('\\','/'));}if(files.length()==0){toast("Укажите файлы задачи");return;}request.put("files",files);request.put("readOnly",readOnly.isChecked());if(!readOnly.isChecked()){new AlertDialog.Builder(this).setTitle("Разрешить изменения?").setMessage("Помощник изменит перечисленные файлы отдельной рабочей копии.\n\n"+files+"\n\nСлияние в main выполняется отдельно.").setPositiveButton("Отправить задачу",(d,n)->submitNew(request)).setNegativeButton("Отмена",null).show();return;}}
        submitNew(request);
    }catch(Exception e){toast(e.getMessage());}}
    private void submitNew(JSONObject request){JSONObject user=new JSONObject();put(user,"role","user");put(user,"state","completed");put(user,"content",request.optString("text"));entries().put(user);JSONObject answer=new JSONObject();put(answer,"role","assistant");put(answer,"state","pending");put(answer,"content","");put(answer,"jobId",request.optString("requestId"));put(answer,"request",request);entries().put(answer);if(entries().length()==2)put(current,"title",request.optString("text").substring(0,Math.min(32,request.optString("text").length())));input.setText("");pollError="";save();render();submit(answer);}
    private void submit(JSONObject answer){networkBusy=true;render();io.execute(()->{try{JSONObject job=api("/mobile/messages",answer.optJSONObject("request"));main.post(()->{apply(answer,job);networkBusy=false;save();render();});}catch(Exception e){main.post(()->{put(answer,"state","error");put(answer,"content",e.getMessage());networkBusy=false;save();render();});}});}
    private void retry(JSONObject answer){if(answer.optBoolean("retryNew")){String id=UUID.randomUUID().toString();put(answer,"jobId",id);put(answer.optJSONObject("request"),"requestId",id);put(answer,"retryNew",false);}put(answer,"state","pending");put(answer,"content","");pollError="";submit(answer);}
    private void apply(JSONObject answer,JSONObject job){String state=job.optString("state");if("completed".equals(state)){put(answer,"state","completed");put(answer,"content",job.optString("answer","Ответ получен"));put(answer,"taskStatus",job.optString("taskStatus"));}else if("failed".equals(state)){put(answer,"retryNew",true);put(answer,"state","error");put(answer,"content",job.optString("error",job.optString("answer","Задача завершилась с ошибкой")));}else{put(answer,"state","pending");put(answer,"taskId",job.optString("taskId"));}}
    private void poll(){if(networkBusy||token.isEmpty())return;JSONObject target=null;JSONArray all=entries();for(int i=0;i<all.length();i++)if("pending".equals(all.optJSONObject(i).optString("state"))){target=all.optJSONObject(i);break;}if(target==null)return;final JSONObject answer=target;networkBusy=true;io.execute(()->{try{JSONObject job=api("/mobile/jobs/"+answer.optString("jobId"),null);main.post(()->{apply(answer,job);pollError="";networkBusy=false;save();render();});}catch(Exception e){main.post(()->{pollError="Связь прервана. Ответ заберу после подключения.";networkBusy=false;render();});}});}
    private JSONObject publicJson(String location) throws Exception {HttpsURLConnection c=(HttpsURLConnection)new URL(location).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","BionicPocket");try{if(c.getResponseCode()!=200)throw new IOException("Обновления недоступны");return new JSONObject(read(c.getInputStream(),100000));}finally{c.disconnect();}}
    private void checkUpdate(boolean visible){io.execute(()->{try{JSONObject manifest=publicJson("https://github.com/"+REPO+"/releases/latest/download/update.json");int installed=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;prefs.edit().putLong("updateCheck",System.currentTimeMillis()).apply();if(manifest.getInt("versionCode")<=installed){if(visible)main.post(()->toast("Установлена последняя версия"));return;}main.post(()->new AlertDialog.Builder(this).setTitle("Доступна версия "+manifest.optString("versionName")).setMessage("Скачать обновление? Подпись приложения и SHA-256 будут проверены перед установкой.").setPositiveButton("Скачать",(d,n)->download(manifest)).setNegativeButton("Позже",null).show());}catch(Exception e){if(visible)main.post(()->toast("Не удалось проверить обновления. Повторите позже."));}});}
    private void download(JSONObject manifest){toast("Скачиваю обновление…");io.execute(()->{try{String location=manifest.getString("url");if(!location.startsWith("https://github.com/"+REPO+"/releases/download/"))throw new IOException("Неизвестный источник обновления");String expected=manifest.getString("sha256");if(!expected.matches("[0-9a-f]{64}"))throw new IOException("Некорректная контрольная сумма");HttpsURLConnection c=(HttpsURLConnection)new URL(location).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);File target=new File(getCacheDir(),"update.apk");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;try{if(c.getResponseCode()!=200)throw new IOException("Ошибка скачивания");try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1){total+=n;if(total>50000000)throw new IOException("Файл обновления слишком большой");digest.update(buf,0,n);out.write(buf,0,n);}}}finally{c.disconnect();}StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));if(!expected.equals(hex.toString())||total!=manifest.getLong("size"))throw new IOException("Контрольная сумма не совпала");verifyPackage(target);main.post(()->installUpdate());}catch(Exception e){new File(getCacheDir(),"update.apk").delete();main.post(()->toast("Обновление не установлено: "+e.getMessage()));}});}
    @SuppressWarnings("deprecation") private void verifyPackage(File file) throws Exception {PackageManager pm=getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES);PackageInfo own=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNATURES);if(archive==null||!getPackageName().equals(archive.packageName)||archive.versionCode<=own.versionCode||archive.signatures==null||own.signatures==null||archive.signatures.length!=1||own.signatures.length!=1||!MessageDigest.isEqual(archive.signatures[0].toByteArray(),own.signatures[0].toByteArray()))throw new IOException("Подпись или версия APK не подходит");}
    private void installUpdate(){try{File file=new File(getCacheDir(),"update.apk");verifyPackage(file);if(!getPackageManager().canRequestPackageInstalls()){pendingInstall=true;new AlertDialog.Builder(this).setTitle("Разрешение Android").setMessage("Разрешите Bionic Pocket устанавливать свои обновления. Android всё равно запросит подтверждение установки.").setPositiveButton("Открыть настройки",(d,n)->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).setNegativeButton("Позже",(d,n)->pendingInstall=false).show();return;}Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(Uri.parse("content://"+getPackageName()+".updates/update.apk"),"application/vnd.android.package-archive");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}catch(Exception e){toast("Не удалось открыть установщик: "+e.getMessage());}}
    private void toast(String value){Toast.makeText(this,value==null?"Ошибка":value,Toast.LENGTH_LONG).show();}
}
