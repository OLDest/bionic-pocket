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
    private final Map<String,ExecutorService> assistantIo=new HashMap<>();
    private final ArrayList<String> assistantIds=new ArrayList<>();
    private final ExecutorService updateIo=Executors.newSingleThreadExecutor();
    private final ExecutorService fileIo=Executors.newSingleThreadExecutor();
    private static final int PICK_ATTACHMENT=120, MAX_ATTACHMENT_BYTES=10*1024*1024, ATTACHMENT_CHUNK=65536;
    private final Handler main=new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences prefs;
    private JSONArray chats=new JSONArray(), projects=new JSONArray();
    private JSONObject current;
    private LinearLayout root, messages, composer, taskOptions, attachmentBar;
    private TextView status, title;
    private ScrollView scroll;
    private EditText input, fileInput;
    private Spinner projectPicker, modePicker, assistantPicker, modelPicker;
    private CheckBox readOnly;
    private Button send;
    private boolean foreground, rendering, pendingInstall, importingAttachment, repliesTurn;
    private volatile boolean destroyed;
    private ActionMode messageSelection;
    private JSONObject selectionChat;
    private final Set<JSONObject> selectedMessages=Collections.newSetFromMap(new IdentityHashMap<JSONObject,Boolean>());
    private int replyChatIndex;
    private int inboxPollIndex;
    private boolean inboxTurn;
    private String server, token="", pollError="";
    private final Map<String,JSONArray> assistantProjects=new HashMap<>(), assistantModels=new HashMap<>();
    private final Map<String,String> assistantModelOrigins=new HashMap<>();
    private final ArrayList<String> modelIds=new ArrayList<>();
    private String renderedModelKey="";
    private final Map<String,Integer> activeNetwork=new HashMap<>(), pollCursor=new HashMap<>();
    private long lastCheck;
    private final Runnable tick=new Runnable(){public void run(){if(foreground){if(repliesTurn)pollDelivery();else poll();repliesTurn=!repliesTurn;main.postDelayed(this,MessageSyncService.hasPending(chats)?2500:15000);}}};
    /** Restore legacy chats as Gonets chats and retain their original delivery endpoint. */
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("private",MODE_PRIVATE);
        server=prefs.getString("server",DEFAULT_URL);
        try {token=decrypt(prefs.getString("token",""));}catch(Exception e){toast("Не удалось открыть токен. Введите его заново.");}
        if(token.isEmpty()||prefs.getString("officeServer","").isEmpty()) {
            try {
                JSONObject personal=new JSONObject(read(getAssets().open("personal.json"),4096));
                String preset=personal.getString("token"), endpoint=AssistantEndpoint.validate(AssistantEndpoint.GONETS,personal.getString("server"));
                String office=personal.optString("officeServer");
                if(!office.isEmpty())office=AssistantEndpoint.validate(AssistantEndpoint.DOMOVOY,office);
                if(preset.length()<32)throw new IOException("Invalid personal settings");
                android.content.SharedPreferences.Editor settings=prefs.edit();
                if(token.isEmpty()){token=preset;server=endpoint;settings.putString("server",server).putString("token",encrypt(token));}
                if(prefs.getString("officeServer","").isEmpty()&&!office.isEmpty())settings.putString("officeServer",office);
                settings.apply();
            } catch(FileNotFoundException ignored) {
                // Public builds have no personal settings.
            } catch(Exception e) { toast("Не удалось применить личные настройки подключения"); }
        }
        AssistantRegistry.migrate(prefs,DEFAULT_URL);
        try {chats=new JSONArray(prefs.getString("chats","[]"));}catch(Exception ignored){}
        for(int i=0;i<chats.length();i++){
            JSONObject chat=chats.optJSONObject(i);
            if(chat.optString("assistant").isEmpty())put(chat,"assistant",AssistantEndpoint.GONETS);
            JSONArray list=chat.optJSONArray("messages");
            if(list!=null)for(int j=0;j<list.length();j++){
                JSONObject message=list.optJSONObject(j);message.remove("retryOwnerChecking");
                if("pending".equals(message.optString("state"))&&message.optString("endpoint").isEmpty()){
                    restoreRoute(message,assistantId(chat),endpoint(assistantId(chat)));
                }
            }
        }
        String selected=prefs.getString("selected","");
        for(int i=0;i<chats.length();i++)if(chats.optJSONObject(i).optString("id").equals(selected))current=chats.optJSONObject(i);
        if(current==null)newChat(false);
        for(int i=0;i<chats.length();i++){JSONObject chat=chats.optJSONObject(i);JSONArray list=chat.optJSONArray("messages");if(list!=null)for(int j=0;j<list.length();j++){JSONObject m=list.optJSONObject(j);if(!m.optString("endpoint").isEmpty())try{AssistantRegistry.pin(prefs,m,m.optString("assistantId",assistantId(chat)),m.optString("endpoint"));if(chat.optString("endpoint").isEmpty()){put(chat,"endpoint",m.optString("endpoint"));put(chat,"connectionId",m.optString("connectionId"));}}catch(Exception ignored){/* Keep the original route; never substitute another profile. */}}}
        MessageNotifications.initialize(this,chats);
        build(); render(); connect(false);
    }
    @Override public void onResume(){super.onResume();MessageNotifications.visible=true;foreground=true;try{if(activeNetwork.values().stream().allMatch(n->n==0)){String id=current.optString("id");JSONArray restored=new JSONArray(prefs.getString("chats","[]"));for(int i=0;i<restored.length();i++)if(id.equals(restored.optJSONObject(i).optString("id"))){chats=restored;current=chats.optJSONObject(i);break;}}openNotification(getIntent());render();}catch(Exception ignored){}MessageNotifications.channels(this);MessageSyncService.schedule(this);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED){if(!prefs.getBoolean("notificationAsked",false)){prefs.edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},73);}}else startMessageSync();main.removeCallbacks(tick);main.post(tick);if(pendingInstall&&getPackageManager().canRequestPackageInstalls()){pendingInstall=false;installUpdate();} if(System.currentTimeMillis()-prefs.getLong("updateCheck",0)>86400000L)checkUpdate(false);}
    @Override public void onPause(){finishMessageSelection();foreground=false;main.removeCallbacks(tick);save();MessageNotifications.visible=false;if(!isChangingConfigurations())startMessageSync();super.onPause();}
    /** The programmatic layout resizes in place; retain requests, draft and work lanes on rotation. */
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(root!=null){root.requestLayout();root.requestApplyInsets();}}
    /** Stop the activity's independent network and file work lanes. */
    @Override public void onDestroy(){destroyed=true;foreground=false;main.removeCallbacksAndMessages(null);setNetworkBusy(false);io.shutdown();for(ExecutorService lane:assistantIo.values())lane.shutdown();updateIo.shutdown();fileIo.shutdown();super.onDestroy();}
    /** Results from a retired screen must never render, open a window or overwrite a new screen's state. */
    private void postAlive(Runnable action){if(destroyed)return;main.post(()->{if(!destroyed&&!isDestroyed()&&!isFinishing())action.run();});}
    private void setNetworkBusy(boolean active){MessageNotifications.setBusy(this,active);}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable box(int color,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(radius));return b;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(44));b.setBackground(box(PANEL,14));b.setOnClickListener(v->action.run());return b;}
    private EditText field(String hint){EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(16);e.setHint(hint);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(box(PANEL,12));return e;}
    private void gap(LinearLayout parent,int h){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    /** Build the conversation view with an explicit assistant selector. */
    private void build(){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(12),dp(16),dp(10));
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,w)->{android.graphics.Insets bars=w.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets keyboard=w.getInsets(WindowInsets.Type.ime());root.setPadding(dp(16)+bars.left,dp(12)+bars.top,dp(16)+bars.right,dp(10)+Math.max(bars.bottom,keyboard.bottom));return w;});}
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(button("☰",()->history()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout headText=column();headText.setPadding(dp(8),0,0,0);title=text("Bionic Pocket",18,TEXT);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setTypeface(null,Typeface.BOLD);headText.addView(title);status=text("Подключение…",11,MUTED);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);headText.addView(status);header.addView(headText,new LinearLayout.LayoutParams(0,-2,1));header.addView(button("＋",()->newChat(true)),new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(header);
        LinearLayout destination=new LinearLayout(this);destination.setGravity(Gravity.CENTER_VERTICAL);
        assistantPicker=new Spinner(this);assistantPicker.setContentDescription("Получатель разговора");setItems(assistantPicker,new String[]{"Добавьте помощника"});destination.addView(assistantPicker,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout advanced=column();advanced.setVisibility(View.GONE);
        Button options=button("Параметры ▾",()->advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));options.setContentDescription("Показать или скрыть модель, проект и режим задачи");destination.addView(options,new LinearLayout.LayoutParams(-2,dp(48)));root.addView(destination);root.addView(advanced);
        assistantPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!rendering&&pos>=0&&pos<assistantIds.size())selectAssistant(assistantIds.get(pos));}});
        modelPicker=new Spinner(this);modelPicker.setContentDescription("Модель выбранного помощника");advanced.addView(modelPicker);
        modelPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(rendering||pos!=modelPicker.getSelectedItemPosition()||pos<0||pos>=modelIds.size())return;String model=modelIds.get(pos);if(model!=null&&!model.equals(current.optString("model"))){put(current,"model",model);AssistantRegistry.selectModel(prefs,assistantId(current),model);save();}}});
        LinearLayout selectors=new LinearLayout(this);projectPicker=new Spinner(this);modePicker=new Spinner(this);setItems(projectPicker,new String[]{"Без проекта"});setItems(modePicker,new String[]{"Общение","Задача с файлами"});selectors.addView(projectPicker,new LinearLayout.LayoutParams(0,dp(46),1));selectors.addView(modePicker,new LinearLayout.LayoutParams(0,dp(46),1));advanced.addView(selectors);
        projectPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(rendering||pos<0||pos!=projectPicker.getSelectedItemPosition())return;JSONObject project=pos==0?null:projects.optJSONObject(pos-1);if(pos>0&&project==null)return;put(current,"project",project==null?"":project.optString("id"));save();}});
        modePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){taskOptions.setVisibility(pos==1?View.VISIBLE:View.GONE);}});
        taskOptions=column();fileInput=field("Файлы: main.go, internal/audio/player.go");fileInput.setTextSize(13);taskOptions.addView(fileInput);readOnly=new CheckBox(this);readOnly.setText("Только анализ — без изменений файлов");readOnly.setTextColor(MUTED);readOnly.setChecked(true);taskOptions.addView(readOnly);taskOptions.setVisibility(View.GONE);advanced.addView(taskOptions);
        scroll=new ScrollView(this);scroll.setFillViewport(true);messages=column();messages.setPadding(0,dp(8),0,dp(12));scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        attachmentBar=column();root.addView(attachmentBar);
        composer=new LinearLayout(this);composer.setGravity(Gravity.BOTTOM);Button attach=button("📎",()->pickAttachment());attach.setContentDescription("Attach file");composer.addView(attach,new LinearLayout.LayoutParams(dp(48),dp(52)));input=field("Сообщение помощнику…");input.setMinLines(1);input.setMaxLines(5);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));send=button("↑",()->send());send.setTextSize(25);send.setTextColor(BG);send.setBackground(box(GREEN,16));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(52),dp(52));sp.leftMargin=dp(8);composer.addView(send,sp);root.addView(composer);
        TextView footer=text("Локальный Bionic · ответы сохраняются",11,MUTED);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(8),0,0);root.addView(footer);setContentView(root);
    }
    private void setItems(Spinner spinner,String[] items){ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items){public View getView(int p,View v,android.view.ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(TEXT);t.setTextSize(13);return t;}};spinner.setAdapter(a);}
    private static void put(JSONObject j,String k,Object v){try{j.put(k,v);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    /** Save every assistant's messages and the selected conversation without storing extra tokens. */
    private void save(){if(prefs!=null)prefs.edit().putString("chats",chats.toString()).putString("selected",current.optString("id")).putString("selectedAssistant",assistantId(current)).apply();}
    private JSONArray entries(){JSONArray a=current.optJSONArray("messages");if(a==null){a=new JSONArray();put(current,"messages",a);}return a;}
    /** Keep unsent files with their conversation so assistant switching cannot send them to another host. */
    private JSONArray draftAttachments(){JSONArray files=current.optJSONArray("draftAttachments");if(files==null){files=new JSONArray();put(current,"draftAttachments",files);}return files;}
    /** Resolve only application-generated file IDs inside this app's private attachment directory. */
    private File attachmentFile(String id) throws IOException {
        if(!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IOException("Invalid attachment ID");
        File directory=new File(getFilesDir(),"attachments");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Attachment storage unavailable");return new File(directory,id);
    }
    /** Open Android's document picker without requesting broad storage permissions. */
    private void pickAttachment(){
        if(importingAttachment){toast("Preparing attachment…");return;}if(draftAttachments().length()>=5){toast("Maximum 5 files per message");return;}
        prefs.edit().putString("filePickerChatId",current.optString("id")).apply();
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.setType("*/*");picker.addCategory(Intent.CATEGORY_OPENABLE);picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(picker,PICK_ATTACHMENT);
    }
    /** Copy the selected document into private storage before the temporary URI permission can expire. */
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=PICK_ATTACHMENT||result!=RESULT_OK||data==null||data.getData()==null)return;
        final Uri uri=data.getData();final String chatId=prefs.getString("filePickerChatId","");importingAttachment=true;toast("Preparing attachment…");
        fileIo.execute(()->{
            File target=null;
            try{
                String name="attachment";try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}
                if(name==null||name.trim().isEmpty())name="attachment";name=name.replaceAll("[\\x00-\\x1f]","_");if(name.length()>160)name=name.substring(0,160);
                String id=UUID.randomUUID().toString(),mime=getContentResolver().getType(uri);target=attachmentFile(id);MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(target)){if(in==null)throw new IOException("Cannot open selected file");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){size+=n;if(size>MAX_ATTACHMENT_BYTES)throw new IOException("Maximum attachment size is 10 MiB");out.write(buffer,0,n);digest.update(buffer,0,n);}}
                StringBuilder checksum=new StringBuilder();for(byte b:digest.digest())checksum.append(String.format(Locale.ROOT,"%02x",b&255));
                final JSONObject file=new JSONObject();put(file,"id",id);put(file,"name",name);put(file,"mime",mime==null?"application/octet-stream":mime);put(file,"size",size);put(file,"sha256",checksum.toString());final File copied=target;
                postAlive(()->{importingAttachment=false;JSONObject owner=null;for(int i=0;i<chats.length();i++)if(chatId.equals(chats.optJSONObject(i).optString("id")))owner=chats.optJSONObject(i);
                    if(owner==null){copied.delete();return;}JSONArray files=owner.optJSONArray("draftAttachments");if(files==null){files=new JSONArray();put(owner,"draftAttachments",files);}if(files.length()>=5){copied.delete();toast("Maximum 5 files per message");return;}files.put(file);save();renderAttachments();});
            }catch(Exception error){if(target!=null)target.delete();postAlive(()->{importingAttachment=false;toast(error.getMessage());});}
        });
    }
    /** Show selected files with an explicit removal action before the message is sent. */
    private void renderAttachments(){
        if(attachmentBar==null)return;attachmentBar.removeAllViews();JSONArray files=draftAttachments();
        for(int i=0;i<files.length();i++){final int index=i;JSONObject file=files.optJSONObject(i);attachmentBar.addView(button("📎 "+file.optString("name")+" · Remove",()->{
            try{attachmentFile(draftAttachments().optJSONObject(index).optString("id")).delete();draftAttachments().remove(index);save();renderAttachments();}catch(Exception error){toast(error.getMessage());}
        }));}
    }
    /** Create a conversation for the selected assistant without mixing existing histories. */
    private void newChat(boolean show){finishMessageSelection();current=new JSONObject();put(current,"id",UUID.randomUUID().toString());String id=prefs.getString("selectedAssistant",AssistantRegistry.first(prefs));if(!AssistantRegistry.active(prefs,id))id=AssistantRegistry.first(prefs);put(current,"assistant",id);put(current,"model",AssistantRegistry.selectedModel(prefs,id));put(current,"title","Новый разговор");put(current,"project","");put(current,"messages",new JSONArray());chats.put(current);if(show){input.setText("");render();save();connect(false);}}
    /** Read the assistant identity, treating pre-profile conversations as Gonets conversations. */
    private String assistantId(JSONObject chat){return chat.optString("assistant",AssistantEndpoint.GONETS);}
    /** Read the selected assistant's origin; Domovoy is unconfigured until an office address is supplied. */
    private String endpoint(String assistant){return AssistantRegistry.origin(prefs,assistant);}
    private AssistantRegistry.Connection chatConnection(JSONObject chat)throws IOException{return AssistantRegistry.resolve(prefs,assistantId(chat),chat.optString("endpoint",endpoint(assistantId(chat))),chat.optString("connectionId"));}
    private String chatEndpoint(JSONObject chat){try{return chatConnection(chat).origin;}catch(Exception e){return "";}}
    private void renderAssistants(){assistantIds.clear();ArrayList<String> labels=new ArrayList<>();JSONArray all=AssistantRegistry.profiles(prefs);String selected=assistantId(current);for(int i=0;i<all.length();i++){JSONObject p=all.optJSONObject(i);if(p.optBoolean("disabled")&&!selected.equals(p.optString("id")))continue;assistantIds.add(p.optString("id"));labels.add(p.optString("name")+(p.optBoolean("disabled")?" (удалён)":""));}if(labels.isEmpty())labels.add("Добавьте помощника");setItems(assistantPicker,labels.toArray(new String[0]));int n=assistantIds.indexOf(selected);if(n>=0)assistantPicker.setSelection(n);}
    /** Keep office and remote network operations independent while serializing each assistant's requests. */
    private ExecutorService executor(String assistant){ExecutorService lane=assistantIo.get(assistant);if(lane==null){lane=Executors.newSingleThreadExecutor();assistantIo.put(assistant,lane);}return lane;}
    /** Switch assistants by creating a new conversation when the current one already contains messages. */
    private void selectAssistant(String assistant){
        if(assistant.equals(assistantId(current)))return;
        prefs.edit().putString("selectedAssistant",assistant).apply();
        if(entries().length()>0)newChat(false);else{put(current,"assistant",assistant);put(current,"project","");current.remove("model");current.remove("endpoint");current.remove("connectionId");}
        activateProjects(assistant);
        input.setText("");render();save();connect(false);
    }
    /** Replace project choices only when switching assistants or receiving that assistant's metadata. */
    private void activateProjects(String assistant){
        projects=assistantProjects.containsKey(assistant)?assistantProjects.get(assistant):new JSONArray();
        String[] names=new String[projects.length()+1];names[0]="Без проекта";
        for(int i=0;i<projects.length();i++)names[i+1]=projects.optJSONObject(i).optString("name");
        rendering=true;setItems(projectPicker,names);
    }
    /** Track all outstanding operations so one response cannot clear another operation's busy state. */
    private void beginNetwork(String assistant){activeNetwork.put(assistant,activeNetwork.getOrDefault(assistant,0)+1);setNetworkBusy(true);}
    /** Release one operation in its own assistant lane. */
    private void finishNetwork(String assistant){activeNetwork.put(assistant,Math.max(0,activeNetwork.getOrDefault(assistant,0)-1));setNetworkBusy(activeNetwork.values().stream().anyMatch(n->n>0));}
    /** Round-robin all pending conversations for one assistant, not only the visible conversation. */
    private JSONObject nextPending(String assistant){
        ArrayList<JSONObject> pending=new ArrayList<>();
        for(int i=0;i<chats.length();i++){
            if(chats.optJSONObject(i).optBoolean("archived"))continue;
            JSONArray list=chats.optJSONObject(i).optJSONArray("messages");
            if(list==null)continue;
            for(int j=0;j<list.length();j++){
                JSONObject answer=list.optJSONObject(j);
                if("pending".equals(answer.optString("state"))&&assistant.equals(answer.optString("assistantId",AssistantEndpoint.GONETS)))pending.add(answer);
            }
        }
        if(pending.isEmpty())return null;
        int index=pollCursor.getOrDefault(assistant,0)%pending.size();pollCursor.put(assistant,index+1);return pending.get(index);
    }
    /** Render the selected chat without changing its assistant or stealing input focus. */
    private void render(){
        if(foreground)MessageNotifications.readChat(this,current);
        renderAttachments();
        final boolean followBottom=scroll.getChildCount()==0||scroll.getChildAt(0).getHeight()-scroll.getHeight()-scroll.getScrollY()<=dp(80);
        rendering=true;renderAssistants();title.setText(current.optString("title","Bionic Pocket"));messages.removeAllViews();JSONArray list=entries();
        if(list.length()==0){LinearLayout welcome=column();welcome.setGravity(Gravity.CENTER);TextView mark=text("B",48,GREEN);mark.setTypeface(null,Typeface.BOLD);welcome.addView(mark);TextView h=text("Помощник под рукой",23,TEXT);h.setTypeface(null,Typeface.BOLD);welcome.addView(h);gap(welcome,12);TextView hint=text("Задайте вопрос, обсудите код или\nпоручите работу с файлами проекта.",15,MUTED);hint.setGravity(Gravity.CENTER);welcome.addView(hint);gap(welcome,20);welcome.addView(button("Проверить подключение",()->connect(true)));gap(welcome,8);welcome.addView(button("Настроить сервер и токен",()->settings()));messages.addView(welcome,new LinearLayout.LayoutParams(-1,-2));}
        boolean waiting=false;
        for(int i=0;i<list.length();i++){
            JSONObject m=list.optJSONObject(i);boolean user="user".equals(m.optString("role"));String state=m.optString("state","completed");
            LinearLayout bubble=column();bubble.setPadding(dp(14),dp(12),dp(14),dp(12));bubble.setBackground(box(selectedMessages.contains(m)?0xff375e50:(user?0xff294a3a:PANEL),18));
            LinearLayout messageHeader=new LinearLayout(this);messageHeader.setGravity(Gravity.CENTER_VERTICAL);
            if(messageSelection!=null){CheckBox checked=new CheckBox(this);checked.setContentDescription("Выбрать сообщение");checked.setChecked(selectedMessages.contains(m));checked.setOnCheckedChangeListener((v,value)->toggleMessage(m));messageHeader.addView(checked,new LinearLayout.LayoutParams(dp(44),dp(40)));}
            TextView label=text(user?"Вы":AssistantRegistry.name(prefs,assistantId(current)),11,user?GREEN:MUTED);label.setTypeface(null,Typeface.BOLD);messageHeader.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Button actions=button("⋮",()->messageActions(m));actions.setContentDescription("Действия с сообщением");messageHeader.addView(actions,new LinearLayout.LayoutParams(dp(44),dp(40)));bubble.addView(messageHeader);gap(bubble,5);
            String content=m.optString("content");
            if("pending".equals(state)){waiting=true;if(content.isEmpty())content=m.optString("taskId").isEmpty()?"Готовлю ответ…":"Задача выполняется на ПК…";String detail=m.optString("pollError");if(!detail.isEmpty())content+="\n"+detail;}
            if("error".equals(state))content="Не удалось получить ответ\n"+content;
            TextView body=null;
            if(!user&&"completed".equals(state)){MarkdownView.add(this,bubble,content,TEXT,MUTED,GREEN);}
            else {body=text(content,16,TEXT);body.setTextIsSelectable(true);body.setLineSpacing(dp(3),1);bubble.addView(body);}
            if(!m.optString("taskStatus").isEmpty()){gap(bubble,6);bubble.addView(text("Отчёт: "+m.optString("taskStatus")+" · интеграция отдельно",11,MUTED));}
            if("error".equals(state)&&!m.optBoolean("cancelledByOwner")){final JSONObject retry=m;bubble.addView(button("Повторить",()->retry(retry)));}
            if(user&&i+1<list.length()){long accepted=list.optJSONObject(i+1).optLong("requestAcceptedAt",0);if(accepted>0)put(m,"receivedAt",accepted);}String dates=timeLabel(m);if(!dates.isEmpty()){gap(bubble,6);bubble.addView(text(dates,11,MUTED));}
            View.OnLongClickListener selectMessage=v->{beginMessageSelection(m);return true;};bubble.setOnLongClickListener(selectMessage);label.setOnLongClickListener(selectMessage);if(body!=null)body.setOnLongClickListener(selectMessage);bubble.setOnClickListener(v->{if(messageSelection!=null)toggleMessage(m);});
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(10);if(user)bp.leftMargin=dp(32);else bp.rightMargin=dp(12);messages.addView(bubble,bp);
        }
        // An older reply or its status request must not lock the composer.
        // Each submission has its own request ID; capacity is enforced by the API.
        send.setEnabled(true);send.setAlpha(1f);
        String project=current.optString("project");int selected=0;for(int i=0;i<projects.length();i++)if(project.equals(projects.optJSONObject(i).optString("id")))selected=i+1;projectPicker.setSelection(selected);renderModels();rendering=false;
        // fullScroll also moves keyboard focus into the transcript. Polling must
        // never take focus (or the IME composing cursor) from either editor.
        if(followBottom)scroll.post(()->scroll.scrollTo(0,Math.max(0,messages.getHeight()-scroll.getHeight())));
    }
    private void copyMessage(JSONObject message){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Bionic",message.optString("content")));toast("Скопировано");}
    private void messageActions(JSONObject message){new AlertDialog.Builder(this).setTitle("Сообщение").setItems(new String[]{"Копировать","Выбрать","Удалить"},(d,n)->{if(n==0)copyMessage(message);else if(n==1)beginMessageSelection(message);else confirmDeleteMessages(current,new HashSet<>(Collections.singleton(message)));}).setNegativeButton("Отмена",null).show();}
    private void finishMessageSelection(){if(messageSelection!=null)messageSelection.finish();}
    private void toggleMessage(JSONObject message){if(selectedMessages.contains(message))selectedMessages.remove(message);else selectedMessages.add(message);if(messageSelection!=null){messageSelection.setTitle("Выбрано: "+selectedMessages.size());messageSelection.invalidate();}render();}
    private void beginMessageSelection(JSONObject message){
        if(messageSelection==null){selectionChat=current;messageSelection=startActionMode(new ActionMode.Callback(){
            public boolean onCreateActionMode(ActionMode mode,Menu menu){menu.add(0,1,0,"Все");menu.add(0,2,1,"Копировать");menu.add(0,3,2,"Удалить").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);return true;}
            public boolean onPrepareActionMode(ActionMode mode,Menu menu){menu.findItem(2).setEnabled(selectedMessages.size()==1);menu.findItem(3).setEnabled(!selectedMessages.isEmpty());return true;}
            public boolean onActionItemClicked(ActionMode mode,MenuItem item){if(item.getItemId()==1){selectedMessages.clear();JSONArray list=entries();for(int i=0;i<list.length();i++)if(list.optJSONObject(i)!=null)selectedMessages.add(list.optJSONObject(i));mode.setTitle("Выбрано: "+selectedMessages.size());mode.invalidate();render();}else if(item.getItemId()==2&&!selectedMessages.isEmpty())copyMessage(selectedMessages.iterator().next());else if(item.getItemId()==3)confirmDeleteMessages(selectionChat,new HashSet<>(selectedMessages));return true;}
            public void onDestroyActionMode(ActionMode mode){messageSelection=null;selectionChat=null;selectedMessages.clear();if(!destroyed)render();}
        });if(messageSelection==null){selectionChat=null;return;}messageSelection.setTitle("Выбрано: 0");}
        if(message!=null)toggleMessage(message);else render();
    }
    private void confirmDeleteMessages(JSONObject chat,Set<JSONObject> selected){if(chat==null||selected.isEmpty())return;new AlertDialog.Builder(this).setTitle("Удалить сообщения: "+selected.size()+"?").setMessage("Удаление только с этого телефона. Задачи на ПК продолжат работу; их поздние ответы не восстановят удалённые сообщения.").setPositiveButton("Удалить",(d,n)->{ChatHistory.delete(chat,selected);finishMessageSelection();MessageNotifications.clear(this,chat.optString("id"));save();render();MessageSyncService.schedule(this);}).setNegativeButton("Отмена",null).show();}
    private void clearChat(JSONObject chat){new AlertDialog.Builder(this).setTitle("Очистить сообщения?").setMessage("Удалить все сообщения этого чата с телефона? Настройки и черновик сохранятся. Задачи на ПК продолжат работу, удалённые ответы не появятся снова.").setPositiveButton("Очистить",(d,n)->{ChatHistory.clear(chat,System.currentTimeMillis());finishMessageSelection();MessageNotifications.clear(this,chat.optString("id"));save();render();MessageSyncService.schedule(this);}).setNegativeButton("Отмена",null).show();}
    /** Open the active conversation list with visible actions for each chat. */
    private void history(){history(false);}
    /** Show active or archived chats while keeping their histories and pending requests intact. */
    private void history(boolean archived){
        LinearLayout list=column();list.setPadding(dp(16),dp(8),dp(16),dp(8));
        list.addView(button("⚙ Настройки и обновления",()->settings()));
        ScrollView holder=new ScrollView(this);holder.addView(list);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(archived?"Archive":"Chats").setView(holder)
            .setNeutralButton(archived?"Chats":"Archive",(d,n)->history(!archived)).setNegativeButton("Close",null).create();
        int count=0;
        for(int i=chats.length()-1;i>=0;i--){
            final JSONObject chat=chats.optJSONObject(i);if(chat.optBoolean("archived")!=archived)continue;
            count++;gap(list,8);LinearLayout row=new LinearLayout(this);
            String name=(AssistantRegistry.name(prefs,assistantId(chat))+": ")+chat.optString("title");
            row.addView(button(name,()->{dialog.dismiss();openChat(chat);}),new LinearLayout.LayoutParams(0,-2,1));
            row.addView(button("⋮",()->{dialog.dismiss();chatActions(chat);}),new LinearLayout.LayoutParams(dp(52),-2));list.addView(row);
        }
        if(count==0){gap(list,12);list.addView(text(archived?"No archived chats":"No active chats",14,MUTED));}
        dialog.show();
    }
    /** Select a saved conversation without changing its assistant or delivery endpoints. */
    private void openChat(JSONObject chat){finishMessageSelection();current=chat;activateProjects(assistantId(chat));input.setText("");render();save();connect(false);}
    /** Move away from a removed or archived visible chat, preferring the same assistant's active history. */
    private void leaveChat(JSONObject chat){
        if(current!=chat)return;
        for(int i=chats.length()-1;i>=0;i--){JSONObject candidate=chats.optJSONObject(i);
            if(candidate!=chat&&!candidate.optBoolean("archived")&&assistantId(candidate).equals(assistantId(chat))){openChat(candidate);return;}
        }
        prefs.edit().putString("selectedAssistant",assistantId(chat)).apply();newChat(false);openChat(current);
    }
    /** Offer archive/restore and confirmed local deletion for the selected list entry. */
    private void chatActions(JSONObject chat){
        boolean archived=chat.optBoolean("archived");
        new AlertDialog.Builder(this).setTitle(chat.optString("title")).setItems(new String[]{archived?"Восстановить чат":"Архивировать чат","Очистить сообщения","Удалить чат"},(d,n)->{
            if(n==0){put(chat,"archived",!archived);if(!archived)leaveChat(chat);save();render();}
            else if(n==1)clearChat(chat);else deleteChat(chat);
        }).setNegativeButton("Cancel",null).show();
    }
    /** Delete only the phone's saved conversation after confirmation; remote tasks continue independently. */
    private void deleteChat(JSONObject chat){
        new AlertDialog.Builder(this).setTitle("Delete chat?").setMessage("Delete this conversation from this phone? Running tasks on the PC will continue.")
            .setPositiveButton("Delete",(d,n)->{JSONArray keep=new JSONArray();for(int i=0;i<chats.length();i++)if(chats.optJSONObject(i)!=chat)keep.put(chats.optJSONObject(i));chats=keep;leaveChat(chat);save();render();})
            .setNegativeButton("Cancel",null).show();
    }
    /** Configure independent assistant profiles and their encrypted credentials. */
    private void settings(){final AlertDialog[] settingsDialog=new AlertDialog[1];LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(12));form.addView(text("Помощники",18,TEXT));JSONArray all=AssistantRegistry.profiles(prefs);for(int i=0;i<all.length();i++){JSONObject p=all.optJSONObject(i);if(p.optBoolean("disabled"))continue;final String id=p.optString("id");form.addView(button(p.optString("name")+" · изменить",()->editAssistant(id)));}form.addView(button("＋ Добавить помощника",()->editAssistant("")));gap(form,10);form.addView(button("Названия моделей выбранного помощника",()->editModelNames()));form.addView(text("Список моделей приходит от выбранного помощника. Адрес и токен принятых запросов сохраняются до получения ответа.",12,MUTED));form.addView(button("Уведомления Android",()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))));form.addView(button("Проверить обновления",()->checkUpdate(true)));form.addView(button("Выбрать сообщения",()->{if(settingsDialog[0]!=null)settingsDialog[0].dismiss();beginMessageSelection(null);}));form.addView(button("Очистить сообщения",()->clearChat(current)));form.addView(button("Удалить этот разговор",()->deleteChat(current)));ScrollView holder=new ScrollView(this);holder.addView(form);settingsDialog[0]=new AlertDialog.Builder(this).setTitle("Bionic Pocket · "+version()).setView(holder).setPositiveButton("Готово",null).create();settingsDialog[0].show();}
    private void editAssistant(String id){LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(12));JSONObject existing=AssistantRegistry.profile(prefs,id);EditText name=field("Имя помощника"),url=field("https://адрес-моста"),secret=field("Токен доступа");name.setSingleLine(true);url.setSingleLine(true);secret.setSingleLine(true);secret.setTransformationMethod(PasswordTransformationMethod.getInstance());CheckBox lan=new CheckBox(this);lan.setText("Только частная LAN (разрешить HTTP)");lan.setTextColor(TEXT);if(existing!=null){name.setText(existing.optString("name"));url.setText(endpoint(id));secret.setHint("Пустое поле — оставить текущий токен");try{lan.setChecked(AssistantRegistry.current(prefs,id).privateLan);}catch(Exception ignored){}}form.addView(name);gap(form,8);form.addView(url);gap(form,8);form.addView(secret);form.addView(lan);form.addView(text("Токен зашифрован Android Keystore. Для HTTP проверяются все адреса LAN.",12,MUTED));if(existing!=null)form.addView(button("Удалить помощника",()->new AlertDialog.Builder(this).setTitle("Удалить помощника?").setMessage("История и исходные подключения уже отправленных запросов сохранятся.").setPositiveButton("Удалить",(d,n)->{AssistantRegistry.remove(prefs,id);if(id.equals(assistantId(current))){prefs.edit().putString("selectedAssistant",AssistantRegistry.first(prefs)).apply();newChat(false);}render();save();}).setNegativeButton("Отмена",null).show()));ScrollView holder=new ScrollView(this);holder.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Новый помощник":"Настройки помощника").setView(holder).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{String previous="";try{previous=AssistantRegistry.current(prefs,id).id;}catch(Exception ignored){}String saved=AssistantRegistry.save(prefs,id,name.getText().toString(),url.getText().toString(),lan.isChecked(),secret.getText().toString());if(id.isEmpty()){prefs.edit().putString("selectedAssistant",saved).apply();newChat(false);}else if(saved.equals(assistantId(current))&&!previous.equals(AssistantRegistry.current(prefs,saved).id)&&entries().length()>0){prefs.edit().putString("selectedAssistant",saved).apply();newChat(false);}assistantModels.remove(saved);assistantModelOrigins.remove(saved);renderedModelKey="";dialog.dismiss();render();save();connect(true);}catch(Exception e){toast(e.getMessage());}}));dialog.show();}
    private void editModelNames(){JSONArray models=availableModels();if(models.length()==0){toast("Сначала подключитесь к помощнику");connect(true);return;}String[] names=new String[models.length()];for(int i=0;i<models.length();i++){JSONObject m=models.optJSONObject(i);names[i]=AssistantRegistry.modelName(prefs,assistantId(current),m.optString("id"),m.optString("name",m.optString("id")));}new AlertDialog.Builder(this).setTitle("Названия моделей").setItems(names,(d,n)->{JSONObject m=models.optJSONObject(n);final String owner=assistantId(current),model=m.optString("id");EditText label=field(m.optString("name",model));label.setText(AssistantRegistry.modelName(prefs,owner,model,m.optString("name",model)));new AlertDialog.Builder(this).setTitle("Название модели").setView(label).setPositiveButton("Сохранить",(x,k)->{AssistantRegistry.setModelName(prefs,owner,model,label.getText().toString());renderedModelKey="";render();}).setNegativeButton("Отмена",null).show();}).setNegativeButton("Готово",null).show();}
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "0.1.0";}}
    private SecretKey key() throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias("bionic-token"))return (SecretKey)store.getKey("bionic-token",null);KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder("bionic-token",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return gen.generateKey();}
    private String encrypt(String s) throws Exception {Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return android.util.Base64.encodeToString(c.getIV(),2)+":"+android.util.Base64.encodeToString(c.doFinal(s.getBytes(StandardCharsets.UTF_8)),2);}
    private String decrypt(String s) throws Exception {if(s.isEmpty())return "";String[] p=s.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,android.util.Base64.decode(p[0],2)));return new String(c.doFinal(android.util.Base64.decode(p[1],2)),StandardCharsets.UTF_8);}
    /**
     * Custom exception for API errors with HTTP status preservation.
     */
    static class ApiException extends IOException {
        final int status;
        ApiException(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    /** Retain the legacy Gonets API entry point for existing callers and production-method checks. */
    private JSONObject api(String route,JSONObject payload) throws Exception {return apiConnection(chatConnection(current),route,payload);}
    /** Recover a saved request at its original assistant and origin, never at the currently selected server. */
    private JSONObject apiFor(JSONObject answer,String route,JSONObject payload) throws Exception {return apiConnection(AssistantRegistry.resolve(prefs,answer.optString("assistantId",AssistantEndpoint.GONETS),answer.optString("endpoint"),answer.optString("connectionId")),route,payload);}
    /** Send authenticated JSON with strict remote HTTPS and private-address validation for office HTTP. */
    private JSONObject apiAt(String assistant,String origin,String route,JSONObject payload) throws Exception {return apiConnection(AssistantRegistry.resolve(prefs,assistant,origin,""),route,payload);}
    private JSONObject apiConnection(AssistantRegistry.Connection connection,String route,JSONObject payload)throws Exception{String secret=connection.token();if(secret.isEmpty())throw new IOException("Введите токен помощника в настройках");String base=connection.validate();HttpURLConnection c=(HttpURLConnection)new URL(base+route).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setRequestProperty("Authorization","Bearer "+secret);c.setRequestProperty("Accept","application/json");try{if(payload!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}}int code=c.getResponseCode();String data=read(code>=400?c.getErrorStream():c.getInputStream(),1000000);JSONObject result;try{result=new JSONObject(data);}catch(Exception e){throw new ApiException("Сервер вернул некорректный ответ ("+code+")",code);}if(code<200||code>=300)throw new ApiException(code==401?"Неверный токен помощника":result.optString("error","Ошибка HTTP "+code),code);return result;}finally{c.disconnect();}}
    private static String read(InputStream stream,int limit) throws Exception {if(stream==null)return "";try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Ответ слишком большой");out.write(buf,0,n);}return out.toString("UTF-8");}}
    /** Fetch metadata for the selected assistant without updating another assistant's visible state. */
    private JSONArray availableModels(){
        String assistant=assistantId(current);
        try{if(!chatConnection(current).id.equals(assistantModelOrigins.get(assistant)))return new JSONArray();}catch(Exception e){return new JSONArray();}
        JSONArray models=assistantModels.get(assistant);return models==null?new JSONArray():models;
    }
    static void pinSelectedModel(JSONObject request,JSONObject chat,JSONArray models){
        String selected=chat.optString("model");if(selected.isEmpty())return;
        for(int i=0;i<models.length();i++){JSONObject m=models.optJSONObject(i);if(m!=null&&selected.equals(m.optString("id"))){put(request,"model",selected);return;}}
        throw new IllegalStateException("Выбранная модель недоступна у этого помощника. Проверьте связь или выберите модель по умолчанию.");
    }
    private void renderModels(){
        if(modelPicker==null)return;
        JSONArray models=availableModels();String selected=current.optString("model");
        String key=current.optString("id")+"|"+endpoint(assistantId(current))+"|"+models.toString()+"|"+selected;
        if(key.equals(renderedModelKey))return;renderedModelKey=key;modelIds.clear();
        ArrayList<String> labels=new ArrayList<>();labels.add("Модель: по умолчанию");modelIds.add("");int position=0;
        for(int i=0;i<models.length();i++){JSONObject m=models.optJSONObject(i);if(m==null)continue;String id=m.optString("id");if(id.isEmpty()||modelIds.contains(id))continue;modelIds.add(id);labels.add(AssistantRegistry.modelName(prefs,assistantId(current),id,m.optString("name",id)));if(id.equals(selected))position=modelIds.size()-1;}
        if(!selected.isEmpty()&&position==0){labels.add("Недоступна: "+selected);modelIds.add(null);position=modelIds.size()-1;}
        setItems(modelPicker,labels.toArray(new String[0]));modelPicker.setSelection(position);
    }
    private void connect(boolean visible){final String assistant=assistantId(current);final AssistantRegistry.Connection connection;try{connection=chatConnection(current);if(connection.origin.isEmpty())throw new IOException("Укажите адрес помощника");}catch(Exception e){status.setText("Настройте помощника");if(visible)settings();return;}status.setText("Проверяю связь…");executor(assistant).execute(()->{try{JSONObject config=apiConnection(connection,"/mobile/config",null);AssistantRegistry.identity(prefs,assistant,connection.id,config.optString("helperId"));postAlive(()->{JSONArray available=config.optJSONArray("projects");assistantProjects.put(assistant,available==null?new JSONArray():available);JSONArray choices=config.optJSONArray("models");assistantModels.put(assistant,choices==null?new JSONArray():choices);assistantModelOrigins.put(assistant,connection.id);try{if(!assistant.equals(assistantId(current))||!connection.id.equals(chatConnection(current).id))return;}catch(Exception e){return;}activateProjects(assistant);render();status.setText("● "+AssistantRegistry.name(prefs,assistant)+" · "+config.optString("model","Bionic"));if(visible)toast("Подключено");});}catch(Exception e){postAlive(()->{if(!assistant.equals(assistantId(current)))return;status.setText(AssistantRegistry.name(prefs,assistant)+": "+e.getMessage());status.setOnClickListener(v->connect(true));if(visible)toast(e.getMessage());});}});}
    /** Retain recent conversation text and at most five earlier file references for follow-up questions. */
    private JSONArray context(){JSONArray result=new JSONArray();JSONArray all=entries();ArrayList<JSONObject> reverse=new ArrayList<>();HashSet<String> seen=new HashSet<>();int length=0;for(int i=all.length()-1;i>=0&&reverse.size()<10;i--){JSONObject m=all.optJSONObject(i);if(!"completed".equals(m.optString("state","completed"))||m.optString("content").isEmpty())continue;String body=m.optString("content");if(length+body.length()>10000)break;JSONObject item=new JSONObject();put(item,"role",m.optString("role"));put(item,"content",body);JSONArray files=m.optJSONArray("attachments"),refs=new JSONArray();if(files!=null)for(int j=0;j<files.length()&&seen.size()<5;j++){JSONObject file=files.optJSONObject(j);if(seen.add(file.optString("id")))refs.put(file);}if(refs.length()>0)put(item,"attachments",refs);reverse.add(item);length+=body.length();}for(int i=reverse.size()-1;i>=0;i--)result.put(reverse.get(i));return result;}
    /** Prepare a request for this conversation's assistant and keep file-write confirmation explicit. */
    private void send(){String value=input.getText().toString().trim();if(value.isEmpty()){if(draftAttachments().length()==0)return;value="Analyze the attached files.";}if(value.length()>8000){toast("Сообщение слишком длинное (максимум 8000 символов)");return;}if(!AssistantRegistry.active(prefs,assistantId(current))||endpoint(assistantId(current)).isEmpty()){settings();return;}try{
        AssistantRegistry.Connection connection=AssistantRegistry.current(prefs,assistantId(current));connection.validate();if(connection.helperId.isEmpty()){toast("Сначала проверьте подключение к помощнику");connect(true);return;}if(entries().length()>0&&!current.optString("connectionId",connection.id).equals(connection.id)){String owner=assistantId(current);JSONArray draft=draftAttachments();prefs.edit().putString("selectedAssistant",owner).apply();newChat(false);put(current,"draftAttachments",draft);render();save();connect(true);toast("Подключение изменилось. Новый разговор готов; отправьте сообщение ещё раз.");return;}put(current,"connectionId",connection.id);put(current,"endpoint",connection.origin);
        JSONObject request=new JSONObject();request.put("requestId",UUID.randomUUID().toString());request.put("targetHelperId",connection.helperId);request.put("chatId",current.getString("id"));request.put("text",value);pinSelectedModel(request,current,availableModels());request.put("history",context());request.put("mode",modePicker.getSelectedItemPosition()==1?"task":"chat");String project=current.optString("project");if(!project.isEmpty())request.put("workspaceId",project);
        if(draftAttachments().length()>0)request.put("attachments",draftAttachments());
        if("task".equals(request.getString("mode"))){if(project.isEmpty()){toast("Выберите проект для задачи");return;}JSONArray files=new JSONArray();for(String f:fileInput.getText().toString().split("[,\\n]")){if(!f.trim().isEmpty())files.put(f.trim().replace('\\','/'));}if(files.length()==0){toast("Укажите файлы задачи");return;}request.put("files",files);request.put("readOnly",readOnly.isChecked());if(!readOnly.isChecked()){new AlertDialog.Builder(this).setTitle("Разрешить изменения?").setMessage("Помощник изменит перечисленные файлы отдельной рабочей копии.\n\n"+files+"\n\nСлияние в main выполняется отдельно.").setPositiveButton("Отправить задачу",(d,n)->submitNew(request)).setNegativeButton("Отмена",null).show();return;}}
        submitNew(request);
    }catch(Exception e){toast(e.getMessage());}}
    /** Persist the exact request and its immutable assistant origin before the first network call. */
    private void submitNew(JSONObject request){JSONObject user=new JSONObject();put(user,"sentAt",System.currentTimeMillis());put(user,"role","user");put(user,"state","completed");String content=request.optString("text");JSONArray files=request.optJSONArray("attachments");if(files!=null){put(user,"attachments",files);for(int i=0;i<files.length();i++)content+="\n📎 "+files.optJSONObject(i).optString("name");}put(user,"content",content);entries().put(user);JSONObject answer=new JSONObject();put(answer,"role","assistant");put(answer,"state","pending");put(answer,"content","");put(answer,"jobId",request.optString("requestId"));put(answer,"request",request);put(answer,"assistantId",assistantId(current));put(answer,"endpoint",chatEndpoint(current));put(answer,"connectionId",current.optString("connectionId"));entries().put(answer);if(entries().length()==2)put(current,"title",request.optString("text").substring(0,Math.min(32,request.optString("text").length())));put(current,"draftAttachments",new JSONArray());input.setText("");pollError="";save();render();submit(answer);}
    /** Resume each fixed-ID upload at the original assistant before submitting the immutable message. */
    private void uploadAttachments(JSONObject answer) throws Exception {
        JSONArray files=answer.optJSONObject("request").optJSONArray("attachments");if(files==null)return;
        for(int i=0;i<files.length();i++){
            JSONObject file=files.optJSONObject(i),remote;String route="/mobile/attachments/"+file.getString("id");
            try{remote=apiFor(answer,route,null);}catch(ApiException error){if(error.status!=404)throw error;remote=new JSONObject();}
            if(remote.has("sha256")&&(!file.getString("sha256").equals(remote.optString("sha256"))||file.getLong("size")!=remote.optLong("size")))throw new IOException("Attachment ID conflict");
            if("completed".equals(remote.optString("state")))continue;
            long size=file.getLong("size"),offset=remote.optLong("received");File local=attachmentFile(file.getString("id"));
            if(!local.isFile()||local.length()!=size)throw new IOException("Original attachment is unavailable on this phone");
            try(RandomAccessFile source=new RandomAccessFile(local,"r")){
                while(!"completed".equals(remote.optString("state"))){
                    if(offset<0||offset>size)throw new IOException("Invalid attachment upload offset");source.seek(offset);byte[] bytes=new byte[(int)Math.min(ATTACHMENT_CHUNK,size-offset)];source.readFully(bytes);
                    JSONObject chunk=new JSONObject(file.toString());chunk.put("offset",offset);chunk.put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));remote=apiFor(answer,route,chunk);
                    long received=remote.getLong("received");if(received<=offset&&offset<size)throw new IOException("Attachment upload made no progress");offset=received;
                    final String progress="Uploading "+file.optString("name")+" · "+(size==0?100:offset*100/size)+"%";postAlive(()->{put(answer,"content",progress);render();});
                }
            }
            if(!file.getString("sha256").equals(remote.optString("sha256"))||remote.getLong("received")!=size)throw new IOException("Attachment upload verification failed");
        }
    }
    /** Submit a persisted message through its own assistant's independent network lane. */
    private void submit(JSONObject answer) {
        if(answer.optBoolean("cancelledByOwner"))return;
        final String assistant = answer.optString("assistantId",AssistantEndpoint.GONETS);
        beginNetwork(assistant);
        render();
        final JSONObject request = answer.optJSONObject("request");
        executor(assistant).execute(() -> {
            try {
                uploadAttachments(answer);
                final JSONObject job = apiFor(answer,"/mobile/messages", request);
                postAlive(() -> {
                    apply(answer, job);notifyReceived();
                    put(answer,"pollError","");
                    finishNetwork(assistant);
                    save();
                    render();
                });
            } catch (Exception error) {
                final boolean retryable = error instanceof ApiException
                        ? DeliveryPolicy.isRetryable(((ApiException) error).status)
                        : error instanceof IOException;
                final String detail = error.getMessage();
                postAlive(() -> {
                    if (retryable) {
                        put(answer, "state", "pending");
                        put(answer,"pollError",DeliveryPolicy.retryMessage(error instanceof ApiException ? ((ApiException)error).status : 0));
                    } else {
                        put(answer, "state", "error");
                        put(answer, "content", detail == null ? "Ошибка отправки" : detail);
                        put(answer,"pollError","");
                    }
                    finishNetwork(assistant);
                    save();
                    render();
                });
            }
        });
    }
    /** Retry an explicit failed job with a new ID while retaining its original assistant and endpoint. */
    private void retry(JSONObject answer){if(answer.optBoolean("cancelledByOwner")||answer.optBoolean("retryOwnerChecking"))return;if(answer.optBoolean("retryNew")&&!answer.optBoolean("retryOwnerChecked")){put(answer,"retryOwnerChecking",true);String owner=answer.optString("assistantId",AssistantEndpoint.GONETS);beginNetwork(owner);executor(owner).execute(()->{try{JSONObject actual=apiFor(answer,"/mobile/jobs/"+answer.optString("jobId"),null);postAlive(()->{answer.remove("retryOwnerChecking");finishNetwork(owner);if(actual.optBoolean("cancelledByOwner")||!"failed".equals(actual.optString("state"))||actual.optJSONObject("codexReply")!=null){apply(answer,actual);save();render();return;}put(answer,"retryOwnerChecked",true);retry(answer);});}catch(Exception error){postAlive(()->{answer.remove("retryOwnerChecking");finishNetwork(owner);toast("Сначала проверьте состояние исходного запроса: "+error.getMessage());});}});return;}answer.remove("retryOwnerChecked");if(answer.optBoolean("retryNew")){String id=UUID.randomUUID().toString();put(answer,"jobId",id);put(answer.optJSONObject("request"),"requestId",id);put(answer,"retryNew",false);put(answer,"receivedAt",0);put(answer,"sentAt",0);put(answer,"notifiedVersion","");}put(answer,"state","pending");put(answer,"content","");put(answer,"pollError","");submit(answer);}
    /** Merge job and Codex reply identity while preserving upstream handoff timestamps and notification state. */
    static void apply(JSONObject answer,JSONObject job){if(job.optBoolean("cancelledByOwner")){put(answer,"state","error");put(answer,"content",job.optString("error","Запрос отменён владельцем"));put(answer,"cancelledByOwner",true);put(answer,"retryNew",false);for(String key:new String[]{"handoffId","taskId","codexReplyId","codexReplyStatus","codexReplyVersion","codexReplyUpdatedAt"})answer.remove(key);return;}if(answer.optBoolean("cancelledByOwner"))return;JSONObject reply=job.optJSONObject("codexReply");if(reply!=null){String handoff=job.optString("handoffId");if(!handoff.isEmpty())put(answer,"handoffId",handoff);long accepted=serverTime(job.optString("createdAt"));if(accepted>0)put(answer,"requestAcceptedAt",accepted);applyCodexReply(answer,reply);return;}if(!answer.optString("codexReplyId").isEmpty())return;String handoff=job.optString("handoffId");if(!handoff.isEmpty())put(answer,"handoffId",handoff);String state=job.optString("state");long accepted=serverTime(job.optString("createdAt"));if(accepted>0)put(answer,"requestAcceptedAt",accepted);if("completed".equals(state)||"failed".equals(state)){if(answer.optLong("receivedAt",0)==0)put(answer,"receivedAt",System.currentTimeMillis());long sent=serverTime(job.optString("completedAt"));if(sent>0)put(answer,"sentAt",sent);}if("completed".equals(state)){put(answer,"state","completed");put(answer,"content",job.optString("answer","Ответ получен"));put(answer,"taskStatus",job.optString("taskStatus"));}else if("failed".equals(state)){put(answer,"retryNew",true);put(answer,"state","error");put(answer,"content",job.optString("error",job.optString("answer","Задача завершилась с ошибкой")));}else{put(answer,"state","pending");put(answer,"taskId",job.optString("taskId"));put(answer,"content",job.optString("answer"));}}
    /** Recover one pending request per assistant in round-robin order across all conversations. */
    private Set<String> pendingAssistantIds(){Set<String> ids=new LinkedHashSet<>();for(int i=0;i<chats.length();i++){JSONObject chat=chats.optJSONObject(i);ids.add(assistantId(chat));JSONArray list=chat.optJSONArray("messages");if(list!=null)for(int j=0;j<list.length();j++){JSONObject m=list.optJSONObject(j);if(!m.optString("assistantId").isEmpty())ids.add(m.optString("assistantId"));}}return ids;}
    private void poll() {
        if (chats.length()==0) return;
        for(final String assistant:pendingAssistantIds()){
        if(activeNetwork.getOrDefault(assistant,0)>0)continue;
        final JSONObject answer=nextPending(assistant);
        if(answer==null)continue;
        beginNetwork(assistant);
        executor(assistant).execute(() -> {
            try {
                JSONObject job;
                try {
                    job = apiFor(answer,"/mobile/jobs/" + answer.optString("jobId"), null);
                } catch (ApiException lookupError) {
                    if (!DeliveryPolicy.isGetNotFound("GET", lookupError.status)) throw lookupError;
                    JSONObject request = answer.optJSONObject("request");
                    if (request == null) throw new IllegalStateException("Исходное сообщение не сохранено. Отправьте его заново.");
                    uploadAttachments(answer);
                    job = apiFor(answer,"/mobile/messages", request);
                }
                final JSONObject response = job;
                postAlive(() -> {
                    apply(answer, response);notifyReceived();
                    put(answer,"pollError","");
                    finishNetwork(assistant);
                    save();
                    render();
                });
            } catch (Exception error) {
                final boolean retryable = error instanceof ApiException
                        ? DeliveryPolicy.isRetryable(((ApiException) error).status)
                        : error instanceof IOException;
                final String detail = error.getMessage();
                postAlive(() -> {
                    if (retryable) {
                        put(answer, "state", "pending");
                        put(answer,"pollError",DeliveryPolicy.retryMessage(error instanceof ApiException ? ((ApiException)error).status : 0));
                    } else {
                        put(answer, "state", "error");
                        put(answer, "content", detail == null ? "Ошибка получения ответа" : detail);
                        put(answer,"pollError","");
                    }
                    finishNetwork(assistant);
                    save();
                    render();
                });
            }
        });
        }
    }
    /** Apply one coordinator version without regressing a completed or newer reply. */
    private static boolean applyCodexReply(JSONObject message,JSONObject reply) {
        if(message.optBoolean("cancelledByOwner"))return false;
        String id=reply.optString("handoffId"),version=reply.optString("version"),state=reply.optString("status");
        if(id.isEmpty()||version.isEmpty())return false;
        String previous=message.optString("codexReplyStatus"),updated=reply.optString("updatedAt"),priorTime=message.optString("codexReplyUpdatedAt");
        if("completed".equals(previous)&&!"completed".equals(state))return false;
        if(!updated.isEmpty()&&!priorTime.isEmpty()&&updated.compareTo(priorTime)<0)return false;
        if(id.equals(message.optString("codexReplyId"))&&version.equals(message.optString("codexReplyVersion"))&&state.equals(previous))return false;
        String prefix="Codex: ";
        if("received".equals(state))prefix="Codex получил задачу.\n";
        else if("in_progress".equals(state))prefix="Codex выполняет задачу.\n";
        else if("completed".equals(state))prefix="Результат Codex:\n";
        else if("blocked".equals(state))prefix="Codex: требуется действие.\n";
        boolean pending=!message.optString("jobId").isEmpty()&&("received".equals(state)||"in_progress".equals(state));
        put(message,"state",pending?"pending":"completed");put(message,"content",prefix+reply.optString("message"));
        put(message,"codexReplyId",id);put(message,"codexReplyVersion",version);put(message,"codexReplyStatus",state);put(message,"codexReplyUpdatedAt",updated);
        put(message,"receivedAt",System.currentTimeMillis());long sent=serverTime(reply.optString("updatedAt",reply.optString("createdAt")));if(sent>0)put(message,"sentAt",sent);
        return true;
    }
    /** Merge reply polling into its original request card and remove legacy duplicate cards by identity. */
    static boolean applyCodexReplies(JSONArray list,JSONArray updates) {
        boolean changed=false;
        if(list==null||updates==null)return false;
        // Heal older saved duplicates even when their reply is outside the server's recent feed.
        for(int i=list.length()-1;i>=0;i--){
            JSONObject duplicate=list.optJSONObject(i);if(duplicate==null||!"assistant".equals(duplicate.optString("role")))continue;
            String id=duplicate.optString("codexReplyId");if(id.isEmpty())continue;
            JSONObject original=null;
            for(int k=0;k<list.length();k++){JSONObject item=list.optJSONObject(k);if(item!=null&&item!=duplicate&&"assistant".equals(item.optString("role"))&&id.equals(item.optString("jobId"))){original=item;break;}}
            if(original==null)continue;
            if(original.optString("codexReplyId").isEmpty()){
                put(original,"codexReplyId",id);
                put(original,"codexReplyVersion",duplicate.optString("codexReplyVersion"));put(original,"codexReplyStatus",duplicate.optString("codexReplyStatus"));put(original,"codexReplyUpdatedAt",duplicate.optString("codexReplyUpdatedAt"));
                if(!duplicate.optString("notifiedVersion").isEmpty())put(original,"notifiedVersion",duplicate.optString("notifiedVersion"));
                if("completed".equals(duplicate.optString("codexReplyStatus"))||"blocked".equals(duplicate.optString("codexReplyStatus"))){put(original,"state","completed");put(original,"content",duplicate.optString("content"));}
                if("pending".equals(original.optString("state"))||original.optString("content").isEmpty())put(original,"content",duplicate.optString("content"));
            }
            list.remove(i);changed=true;
        }

        for(int i=0;i<updates.length();i++){
            JSONObject reply=updates.optJSONObject(i);if(reply==null)continue;
            String id=reply.optString("handoffId"),version=reply.optString("version"),request=reply.optString("requestId",id);
            if(id.isEmpty()||version.isEmpty())continue;
            JSONObject message=null;
            for(int j=0;j<list.length();j++){JSONObject item=list.optJSONObject(j);if(item!=null&&"assistant".equals(item.optString("role"))&&request.equals(item.optString("jobId"))){message=item;break;}}
            if(message==null)for(int j=0;j<list.length();j++){JSONObject item=list.optJSONObject(j);if(item!=null&&id.equals(item.optString("codexReplyId"))){message=item;break;}}
            if(message==null){message=new JSONObject();put(message,"role","assistant");list.put(message);}
            if(applyCodexReply(message,reply))changed=true;
            for(int j=list.length()-1;j>=0;j--){JSONObject item=list.optJSONObject(j);if(item!=null&&item!=message&&"assistant".equals(item.optString("role"))&&(id.equals(item.optString("codexReplyId"))||request.equals(item.optString("jobId")))){list.remove(j);changed=true;}}
        }
        return changed;
    }
    static boolean applyChatReplies(JSONObject chat,JSONArray updates){return applyCodexReplies(chat.optJSONArray("messages"),ChatHistory.visibleReplies(chat,updates));}
    /** Fetch a source inbox without needing a local chat, then acknowledge only persisted versions. */
    private void pollInbox(){
        ArrayList<AssistantRegistry.Connection> sources=PocketInbox.connections(prefs);if(sources.isEmpty())return;
        final AssistantRegistry.Connection connection=sources.get((inboxPollIndex++)%sources.size());final String assistant=connection.profileId;
        if(activeNetwork.getOrDefault(assistant,0)>0)return;beginNetwork(assistant);
        executor(assistant).execute(()->{try{
            JSONArray replies=apiConnection(connection,"/mobile/control/inbox",null).optJSONArray("replies");
            postAlive(()->{try{
                JSONObject receipts=new JSONObject(prefs.getString("pocketInboxReceipts","{}"));
                JSONArray acknowledgements=PocketInbox.merge(chats,receipts,assistant,connection.id,connection.origin,replies);
                if(!prefs.edit().putString("chats",chats.toString()).putString("pocketInboxReceipts",receipts.toString()).commit())return;
                notifyReceived();save();
                if(replies!=null&&replies.length()>0)render();
                executor(assistant).execute(()->{for(int i=0;i<acknowledgements.length();i++)try{apiConnection(connection,"/mobile/control/inbox/ack",acknowledgements.optJSONObject(i));}catch(Exception ignored){}});
            }catch(Exception ignored){}finally{finishNetwork(assistant);}});
        }catch(Exception error){postAlive(()->finishNetwork(assistant));}});
    }
    /** Alternate source inbox discovery with legacy per-chat reply polling. */
    private void pollDelivery(){inboxTurn=!inboxTurn;if(inboxTurn)pollInbox();else pollReplies();}
    /** Poll updates for an existing active chat through its original assistant. */
    private void pollReplies(){
        if(chats.length()==0)return;
        ArrayList<JSONObject> waiting=new ArrayList<>();for(int i=0;i<chats.length();i++){JSONObject item=chats.optJSONObject(i);if(!item.optBoolean("archived")&&SyncPolicy.pending(item.optJSONArray("messages")))waiting.add(item);}
        final JSONObject chat=waiting.isEmpty()?current:waiting.get((replyChatIndex++)%waiting.size());final String id=chat.optString("id"),assistant=assistantId(chat),origin=chatEndpoint(chat);
        if(chat.optBoolean("archived"))return;
        if(origin.isEmpty()||activeNetwork.getOrDefault(assistant,0)>0)return;beginNetwork(assistant);
        executor(assistant).execute(()->{try{
            JSONObject response=apiConnection(chatConnection(chat),"/mobile/control/replies?chatId="+id,null);
            postAlive(()->{finishNetwork(assistant);if(!chat.optBoolean("archived")&&applyChatReplies(chat,response.optJSONArray("replies"))){notifyReceived();save();if(current==chat)render();}});
        }catch(Exception error){postAlive(()->{finishNetwork(assistant);});}});
    }
    private JSONObject publicJson(String location) throws Exception {HttpsURLConnection c=(HttpsURLConnection)new URL(location).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","BionicPocket");c.setRequestProperty("Cache-Control","no-cache");try{if(c.getResponseCode()!=200)throw new IOException("Обновления недоступны");return new JSONObject(read(c.getInputStream(),100000));}finally{c.disconnect();}}
    /** Check release metadata without occupying either assistant's delivery lane. */
    private void checkUpdate(boolean visible){if(!visible)prefs.edit().putLong("updateCheck",System.currentTimeMillis()).apply();updateIo.execute(()->{try{JSONObject manifest=publicJson("https://github.com/"+REPO+"/releases/latest/download/update.json");int installed=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;prefs.edit().putLong("updateCheck",System.currentTimeMillis()).apply();if(manifest.getInt("versionCode")<=installed){if(visible)postAlive(()->toast("Установлена последняя версия"));return;}postAlive(()->{if(!foreground)return;new AlertDialog.Builder(this).setTitle("Доступна версия "+manifest.optString("versionName")).setMessage("Скачать обновление? Подпись приложения и SHA-256 будут проверены перед установкой.").setPositiveButton("Скачать",(d,n)->download(manifest)).setNegativeButton("Позже",null).show();});}catch(Exception e){if(visible)postAlive(()->toast("Не удалось проверить обновления. Повторите позже."));}});}
    /** Download and verify an update independently of chat traffic, preserving the existing signer checks. */
    private void download(JSONObject manifest){toast("Скачиваю обновление…");updateIo.execute(()->{try{String location=manifest.getString("url");if(!location.startsWith("https://github.com/"+REPO+"/releases/download/"))throw new IOException("Неизвестный источник обновления");String expected=manifest.getString("sha256");if(!expected.matches("[0-9a-f]{64}"))throw new IOException("Некорректная контрольная сумма");HttpsURLConnection c=(HttpsURLConnection)new URL(location).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);File target=new File(getCacheDir(),"update.apk");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;try{if(c.getResponseCode()!=200)throw new IOException("Ошибка скачивания");try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1){total+=n;if(total>50000000)throw new IOException("Файл обновления слишком большой");digest.update(buf,0,n);out.write(buf,0,n);}}}finally{c.disconnect();}StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));if(!expected.equals(hex.toString())||total!=manifest.getLong("size"))throw new IOException("Контрольная сумма не совпала");verifyPackage(target);postAlive(()->installUpdate());}catch(Exception e){new File(getCacheDir(),"update.apk").delete();postAlive(()->toast("Обновление не установлено: "+e.getMessage()));}});}
    @SuppressWarnings("deprecation") private void verifyPackage(File file) throws Exception {PackageManager pm=getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES);PackageInfo own=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNATURES);if(archive==null||!getPackageName().equals(archive.packageName)||archive.versionCode<=own.versionCode||archive.signatures==null||own.signatures==null||archive.signatures.length!=1||own.signatures.length!=1||!MessageDigest.isEqual(archive.signatures[0].toByteArray(),own.signatures[0].toByteArray()))throw new IOException("Подпись или версия APK не подходит");}
    private void installUpdate(){try{File file=new File(getCacheDir(),"update.apk");verifyPackage(file);if(!getPackageManager().canRequestPackageInstalls()){pendingInstall=true;new AlertDialog.Builder(this).setTitle("Разрешение Android").setMessage("Разрешите Bionic Pocket устанавливать свои обновления. Android всё равно запросит подтверждение установки.").setPositiveButton("Открыть настройки",(d,n)->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).setNegativeButton("Позже",(d,n)->pendingInstall=false).show();return;}Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(Uri.parse("content://"+getPackageName()+".updates/update.apk"),"application/vnd.android.package-archive");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}catch(Exception e){toast("Не удалось открыть установщик: "+e.getMessage());}}
    private void toast(String value){Toast.makeText(this,value==null?"Ошибка":value,Toast.LENGTH_LONG).show();}

    private void startMessageSync(){MessageSyncService.schedule(this);if(MessageNotifications.visible||!MessageSyncService.hasPending(chats)){stopService(new Intent(this,MessageSyncService.class));return;}try{startForegroundService(new Intent(this,MessageSyncService.class));}catch(Exception ignored){/* Scheduled job remains available if Android limits the service. */}}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==73&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startMessageSync();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(foreground){openNotification(intent);render();save();}}
    private void openNotification(Intent intent){String id=intent.getStringExtra("chatId");if(id==null||id.isEmpty())return;for(int i=0;i<chats.length();i++)if(id.equals(chats.optJSONObject(i).optString("id"))){current=chats.optJSONObject(i);break;}intent.removeExtra("chatId");}
    private static String timeLabel(JSONObject m){StringBuilder s=new StringBuilder();long sent=m.optLong("sentAt",0),received=m.optLong("receivedAt",0);java.text.DateFormat f=new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault());if(sent>0)s.append("Отправлено: ").append(f.format(new Date(sent)));if(received>0){if(s.length()>0)s.append("\n");s.append("Получено: ").append(f.format(new Date(received)));}return s.toString();}
    private static long serverTime(String value){try{return java.time.Instant.parse(value).toEpochMilli();}catch(Exception e){return 0;}}
    static void restoreRoute(JSONObject message,String assistant,String origin){
        if(message.optString("endpoint").isEmpty()){put(message,"assistantId",assistant);put(message,"endpoint",origin);}
    }
    static boolean claimNotification(JSONObject ledger,String chat,JSONObject m){
        if(!"assistant".equals(m.optString("role"))||m.optLong("receivedAt",0)==0||m.optString("content").trim().isEmpty())return false;
        String id=m.optString("jobId");if(id.isEmpty())id=m.optString("codexReplyId");if(id.isEmpty())return false;
        String key=chat+"/"+id,body=m.optString("content"),version=m.optString("codexReplyVersion",m.optString("state"));
        JSONObject prior=ledger.optJSONObject(key);long sent=m.optLong("sentAt",0);
        if(prior!=null&&prior.optLong("sentAt",0)>sent)return false;
        boolean same=prior!=null&&body.equals(prior.optString("body"));
        if(same&&prior.optLong("sentAt",0)==sent)return false;
        boolean legacy=!ledger.has(key)&&!version.isEmpty()&&version.equals(m.optString("notifiedVersion"));
        JSONObject seen=new JSONObject();put(seen,"body",body);put(seen,"sentAt",sent);put(ledger,key,seen);put(m,"notifiedVersion",version);
        return !legacy&&!same;
    }
    private void notifyReceived(){
        for(int i=0;i<chats.length();i++){JSONObject chat=chats.optJSONObject(i);if(chat.optBoolean("archived"))continue;JSONArray list=chat.optJSONArray("messages");if(list==null)continue;
            for(int j=0;j<list.length();j++){JSONObject m=list.optJSONObject(j);if(!"assistant".equals(m.optString("role"))||m.optLong("receivedAt",0)==0)continue;
                String key=m.optString("codexReplyId",m.optString("jobId")),version=m.optString("codexReplyVersion",m.optString("state"));
                if(MessageNotifications.claim(this,chat.optString("id"),m)&&!(foreground&&chat==current))MessageNotifications.received(this,chat.optString("id"),chat.optString("title","Bionic"),key,m.optString("content"));
            }
        }
        if(foreground)MessageNotifications.clear(this,current.optString("id"));
    }
}
