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
    private final ExecutorService officeIo=Executors.newSingleThreadExecutor();
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
    private Spinner projectPicker, modePicker, assistantPicker;
    private CheckBox readOnly;
    private Button send;
    private boolean foreground, rendering, pendingInstall, importingAttachment, repliesTurn;
    private String server, token="", pollError="";
    private final Map<String,JSONArray> assistantProjects=new HashMap<>();
    private final Map<String,Integer> activeNetwork=new HashMap<>(), pollCursor=new HashMap<>();
    private long lastCheck;
    private final Runnable tick=new Runnable(){public void run(){if(foreground){if(repliesTurn)pollReplies();else poll();repliesTurn=!repliesTurn;main.postDelayed(this,2500);}}};
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
        try {chats=new JSONArray(prefs.getString("chats","[]"));}catch(Exception ignored){}
        for(int i=0;i<chats.length();i++){
            JSONObject chat=chats.optJSONObject(i);
            if(chat.optString("assistant").isEmpty())put(chat,"assistant",AssistantEndpoint.GONETS);
            JSONArray list=chat.optJSONArray("messages");
            if(list!=null)for(int j=0;j<list.length();j++){
                JSONObject message=list.optJSONObject(j);
                if("pending".equals(message.optString("state"))&&message.optString("endpoint").isEmpty()){
                    put(message,"assistantId",assistantId(chat));put(message,"endpoint",server);
                }
            }
        }
        String selected=prefs.getString("selected","");
        for(int i=0;i<chats.length();i++)if(chats.optJSONObject(i).optString("id").equals(selected))current=chats.optJSONObject(i);
        if(current==null)newChat(false);
        build(); render(); connect(false);
    }
    @Override public void onResume(){super.onResume();foreground=true;main.removeCallbacks(tick);main.post(tick);if(pendingInstall&&getPackageManager().canRequestPackageInstalls()){pendingInstall=false;installUpdate();} if(System.currentTimeMillis()-prefs.getLong("updateCheck",0)>86400000L)checkUpdate(false);}
    @Override public void onPause(){foreground=false;main.removeCallbacks(tick);save();super.onPause();}
    /** Stop the activity's independent network and file work lanes. */
    @Override public void onDestroy(){main.removeCallbacksAndMessages(null);io.shutdown();officeIo.shutdown();updateIo.shutdown();fileIo.shutdown();super.onDestroy();}
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
        LinearLayout headText=column();headText.setPadding(dp(12),0,0,0);title=text("Bionic Pocket",21,TEXT);title.setTypeface(null,Typeface.BOLD);headText.addView(title);status=text("Подключение…",12,MUTED);headText.addView(status);header.addView(headText,new LinearLayout.LayoutParams(0,-2,1));header.addView(button("＋",()->newChat(true)),new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(header);gap(root,12);
        assistantPicker=new Spinner(this);setItems(assistantPicker,new String[]{"Гонец","Домовой · office LAN"});root.addView(assistantPicker);
        assistantPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!rendering)selectAssistant(pos==1?AssistantEndpoint.DOMOVOY:AssistantEndpoint.GONETS);}});
        LinearLayout selectors=new LinearLayout(this);projectPicker=new Spinner(this);modePicker=new Spinner(this);setItems(projectPicker,new String[]{"Без проекта"});setItems(modePicker,new String[]{"Общение","Задача с файлами"});selectors.addView(projectPicker,new LinearLayout.LayoutParams(0,dp(46),1));selectors.addView(modePicker,new LinearLayout.LayoutParams(0,dp(46),1));root.addView(selectors);
        projectPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!rendering){put(current,"project",pos==0?"":projects.optJSONObject(pos-1).optString("id"));save();}}});
        modePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){taskOptions.setVisibility(pos==1?View.VISIBLE:View.GONE);}});
        taskOptions=column();fileInput=field("Файлы: main.go, internal/audio/player.go");fileInput.setTextSize(13);taskOptions.addView(fileInput);readOnly=new CheckBox(this);readOnly.setText("Только анализ — без изменений файлов");readOnly.setTextColor(MUTED);readOnly.setChecked(true);taskOptions.addView(readOnly);taskOptions.setVisibility(View.GONE);root.addView(taskOptions);
        scroll=new ScrollView(this);scroll.setFillViewport(true);messages=column();messages.setPadding(0,dp(8),0,dp(12));scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        attachmentBar=column();root.addView(attachmentBar);
        composer=new LinearLayout(this);composer.setGravity(Gravity.BOTTOM);Button attach=button("📎",()->pickAttachment());attach.setContentDescription("Attach file");composer.addView(attach,new LinearLayout.LayoutParams(dp(48),dp(52)));input=field("Сообщение помощнику…");input.setMinLines(1);input.setMaxLines(5);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));send=button("↑",()->send());send.setTextSize(25);send.setTextColor(BG);send.setBackground(box(GREEN,16));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(52),dp(52));sp.leftMargin=dp(8);composer.addView(send,sp);root.addView(composer);
        TextView footer=text("Локальный Bionic · ответы сохраняются",11,MUTED);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(8),0,0);root.addView(footer);setContentView(root);
    }
    private void setItems(Spinner spinner,String[] items){ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items){public View getView(int p,View v,android.view.ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(TEXT);t.setTextSize(13);return t;}};spinner.setAdapter(a);}
    private void put(JSONObject j,String k,Object v){try{j.put(k,v);}catch(JSONException e){throw new IllegalArgumentException(e);}}
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
                main.post(()->{importingAttachment=false;JSONObject owner=null;for(int i=0;i<chats.length();i++)if(chatId.equals(chats.optJSONObject(i).optString("id")))owner=chats.optJSONObject(i);
                    if(owner==null){copied.delete();return;}JSONArray files=owner.optJSONArray("draftAttachments");if(files==null){files=new JSONArray();put(owner,"draftAttachments",files);}if(files.length()>=5){copied.delete();toast("Maximum 5 files per message");return;}files.put(file);save();renderAttachments();});
            }catch(Exception error){if(target!=null)target.delete();main.post(()->{importingAttachment=false;toast(error.getMessage());});}
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
    private void newChat(boolean show){current=new JSONObject();put(current,"id",UUID.randomUUID().toString());put(current,"assistant",prefs.getString("selectedAssistant",AssistantEndpoint.GONETS));put(current,"title","Новый разговор");put(current,"project","");put(current,"messages",new JSONArray());chats.put(current);if(show){input.setText("");render();save();}}
    /** Read the assistant identity, treating pre-profile conversations as Gonets conversations. */
    private String assistantId(JSONObject chat){return chat.optString("assistant",AssistantEndpoint.GONETS);}
    /** Read the selected assistant's origin; Domovoy is unconfigured until an office address is supplied. */
    private String endpoint(String assistant){return AssistantEndpoint.DOMOVOY.equals(assistant)?prefs.getString("officeServer",""):server;}
    /** Keep office and remote network operations independent while serializing each assistant's requests. */
    private ExecutorService executor(String assistant){return AssistantEndpoint.DOMOVOY.equals(assistant)?officeIo:io;}
    /** Switch assistants by creating a new conversation when the current one already contains messages. */
    private void selectAssistant(String assistant){
        if(assistant.equals(assistantId(current)))return;
        prefs.edit().putString("selectedAssistant",assistant).apply();
        if(entries().length()>0)newChat(false);else{put(current,"assistant",assistant);put(current,"project","");}
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
    private void beginNetwork(String assistant){activeNetwork.put(assistant,activeNetwork.getOrDefault(assistant,0)+1);}
    /** Release one operation in its own assistant lane. */
    private void finishNetwork(String assistant){activeNetwork.put(assistant,Math.max(0,activeNetwork.getOrDefault(assistant,0)-1));}
    /** Round-robin all pending conversations for one assistant, not only the visible conversation. */
    private JSONObject nextPending(String assistant){
        ArrayList<JSONObject> pending=new ArrayList<>();
        for(int i=0;i<chats.length();i++){
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
        renderAttachments();
        final boolean followBottom=scroll.getChildCount()==0||scroll.getChildAt(0).getHeight()-scroll.getHeight()-scroll.getScrollY()<=dp(80);
        rendering=true;assistantPicker.setSelection(AssistantEndpoint.DOMOVOY.equals(assistantId(current))?1:0);title.setText(current.optString("title","Bionic Pocket"));messages.removeAllViews();JSONArray list=entries();
        if(list.length()==0){LinearLayout welcome=column();welcome.setGravity(Gravity.CENTER);TextView mark=text("B",48,GREEN);mark.setTypeface(null,Typeface.BOLD);welcome.addView(mark);TextView h=text("Помощник под рукой",23,TEXT);h.setTypeface(null,Typeface.BOLD);welcome.addView(h);gap(welcome,12);TextView hint=text("Задайте вопрос, обсудите код или\nпоручите работу с файлами проекта.",15,MUTED);hint.setGravity(Gravity.CENTER);welcome.addView(hint);gap(welcome,20);welcome.addView(button("Проверить подключение",()->connect(true)));gap(welcome,8);welcome.addView(button("Настроить сервер и токен",()->settings()));messages.addView(welcome,new LinearLayout.LayoutParams(-1,-2));}
        boolean waiting=false;
        for(int i=0;i<list.length();i++){
            JSONObject m=list.optJSONObject(i);boolean user="user".equals(m.optString("role"));String state=m.optString("state","completed");
            LinearLayout bubble=column();bubble.setPadding(dp(14),dp(12),dp(14),dp(12));bubble.setBackground(box(user?0xff294a3a:PANEL,18));
            TextView label=text(user?"Вы":AssistantEndpoint.DOMOVOY.equals(assistantId(current))?"Домовой":"Гонец",11,user?GREEN:MUTED);label.setTypeface(null,Typeface.BOLD);bubble.addView(label);gap(bubble,5);
            String content=m.optString("content");
            if("pending".equals(state)){waiting=true;if(content.isEmpty())content=m.optString("taskId").isEmpty()?"Готовлю ответ…":"Задача выполняется на ПК…";String detail=m.optString("pollError");if(!detail.isEmpty())content+="\n"+detail;}
            if("error".equals(state))content="Не удалось получить ответ\n"+content;
            TextView body=null;
            if(!user&&"completed".equals(state)){MarkdownView.add(this,bubble,content,TEXT,MUTED,GREEN);}
            else {body=text(content,16,TEXT);body.setTextIsSelectable(true);body.setLineSpacing(dp(3),1);bubble.addView(body);}
            if(!m.optString("taskStatus").isEmpty()){gap(bubble,6);bubble.addView(text("Отчёт: "+m.optString("taskStatus")+" · интеграция отдельно",11,MUTED));}
            if("error".equals(state)){final JSONObject retry=m;bubble.addView(button("Повторить",()->retry(retry)));}
            final String copied=content;View.OnLongClickListener copyMessage=v->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Bionic",copied));toast("Скопировано");return true;};label.setOnLongClickListener(copyMessage);if(body!=null)body.setOnLongClickListener(copyMessage);
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(10);if(user)bp.leftMargin=dp(32);else bp.rightMargin=dp(12);messages.addView(bubble,bp);
        }
        // An older reply or its status request must not lock the composer.
        // Each submission has its own request ID; capacity is enforced by the API.
        send.setEnabled(true);send.setAlpha(1f);
        String project=current.optString("project");int selected=0;for(int i=0;i<projects.length();i++)if(project.equals(projects.optJSONObject(i).optString("id")))selected=i+1;projectPicker.setSelection(selected);rendering=false;
        // fullScroll also moves keyboard focus into the transcript. Polling must
        // never take focus (or the IME composing cursor) from either editor.
        if(followBottom)scroll.post(()->scroll.scrollTo(0,Math.max(0,messages.getHeight()-scroll.getHeight())));
    }
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
            String name=(AssistantEndpoint.DOMOVOY.equals(assistantId(chat))?"Домовой: ":"Гонец: ")+chat.optString("title");
            row.addView(button(name,()->{dialog.dismiss();openChat(chat);}),new LinearLayout.LayoutParams(0,-2,1));
            row.addView(button("⋮",()->{dialog.dismiss();chatActions(chat);}),new LinearLayout.LayoutParams(dp(52),-2));list.addView(row);
        }
        if(count==0){gap(list,12);list.addView(text(archived?"No archived chats":"No active chats",14,MUTED));}
        dialog.show();
    }
    /** Select a saved conversation without changing its assistant or delivery endpoints. */
    private void openChat(JSONObject chat){current=chat;activateProjects(assistantId(chat));input.setText("");render();save();connect(false);}
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
        new AlertDialog.Builder(this).setTitle(chat.optString("title")).setItems(new String[]{archived?"Restore chat":"Archive chat","Delete chat"},(d,n)->{
            if(n==0){put(chat,"archived",!archived);if(!archived)leaveChat(chat);save();render();}
            else deleteChat(chat);
        }).setNegativeButton("Cancel",null).show();
    }
    /** Delete only the phone's saved conversation after confirmation; remote tasks continue independently. */
    private void deleteChat(JSONObject chat){
        new AlertDialog.Builder(this).setTitle("Delete chat?").setMessage("Delete this conversation from this phone? Running tasks on the PC will continue.")
            .setPositiveButton("Delete",(d,n)->{JSONArray keep=new JSONArray();for(int i=0;i<chats.length();i++)if(chats.optJSONObject(i)!=chat)keep.put(chats.optJSONObject(i));chats=keep;leaveChat(chat);save();render();})
            .setNegativeButton("Cancel",null).show();
    }
    /** Configure both assistant origins and one shared Keystore-encrypted access token. */
    private void settings(){
        LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),0);
        form.addView(text("Гонец · HTTPS",13,MUTED));EditText url=field(DEFAULT_URL);url.setSingleLine(true);url.setText(server);form.addView(url);gap(form,12);
        form.addView(text("Домовой · office LAN",13,MUTED));EditText office=field("http://office-pc:1236");office.setSingleLine(true);office.setText(endpoint(AssistantEndpoint.DOMOVOY));form.addView(office);gap(form,12);
        form.addView(text("Shared access token",13,MUTED));EditText secret=field(token.isEmpty()?"Вставьте токен":"Пустое поле — оставить текущий");secret.setSingleLine(true);secret.setTransformationMethod(PasswordTransformationMethod.getInstance());form.addView(secret);gap(form,12);
        form.addView(text("One token for both assistants. Domovoy HTTP is office-LAN only.\nТокен хранится через Android Keystore.",12,MUTED));gap(form,10);form.addView(button("Проверить обновления",()->checkUpdate(true)));gap(form,8);form.addView(button("Открыть исходный код",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/"+REPO)))));gap(form,8);form.addView(button("Удалить этот разговор",()->deleteChat(current)));
        ScrollView holder=new ScrollView(this);holder.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Bionic Pocket · "+version()).setView(holder).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{
            String remote=AssistantEndpoint.validate(AssistantEndpoint.GONETS,url.getText().toString());
            String local=office.getText().toString().trim();if(!local.isEmpty())local=AssistantEndpoint.validate(AssistantEndpoint.DOMOVOY,local);
            String replacement=secret.getText().toString().trim(), shared=replacement.isEmpty()?token:replacement;
            if(shared.length()<32)throw new IOException("Вставьте полный токен доступа");
            String encrypted=encrypt(shared);prefs.edit().putString("server",remote).putString("officeServer",local).putString("token",encrypted).apply();
            server=remote;token=shared;dialog.dismiss();connect(true);
        }catch(Exception e){toast(e.getMessage());}}));dialog.show();
    }
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
    private JSONObject api(String route,JSONObject payload) throws Exception {return apiAt(AssistantEndpoint.GONETS,server,route,payload);}
    /** Recover a saved request at its original assistant and origin, never at the currently selected server. */
    private JSONObject apiFor(JSONObject answer,String route,JSONObject payload) throws Exception {return apiAt(answer.optString("assistantId",AssistantEndpoint.GONETS),answer.optString("endpoint"),route,payload);}
    /** Send authenticated JSON with strict remote HTTPS and private-address validation for office HTTP. */
    private JSONObject apiAt(String assistant,String origin,String route,JSONObject payload) throws Exception {
        if(token.isEmpty())throw new IOException("Введите токен в настройках");
        String base=AssistantEndpoint.validate(assistant,origin);AssistantEndpoint.requireOfficeAddress(assistant,base);
        HttpURLConnection c=(HttpURLConnection)new URL(base+route).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/json");
        try{if(payload!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();String data=read(code>=400?c.getErrorStream():c.getInputStream(),1000000);JSONObject result;try{result=new JSONObject(data);}catch(Exception e){throw new ApiException("Сервер вернул некорректный ответ ("+code+")",code);}if(code<200||code>=300)throw new ApiException(code==401?"Неверный токен доступа":result.optString("error","Ошибка HTTP "+code),code);return result;
        }finally{c.disconnect();}
    }
    private static String read(InputStream stream,int limit) throws Exception {if(stream==null)return "";try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Ответ слишком большой");out.write(buf,0,n);}return out.toString("UTF-8");}}
    /** Fetch metadata for the selected assistant without updating another assistant's visible state. */
    private void connect(boolean visible){
        final String assistant=assistantId(current), origin=endpoint(assistant);
        if(token.isEmpty()||origin.isEmpty()){status.setText(origin.isEmpty()?"Set Domovoy office address in Settings":"Нужен токен · откройте настройки");if(visible)settings();return;}
        status.setText("Проверяю связь…");executor(assistant).execute(()->{try{JSONObject config=apiAt(assistant,origin,"/mobile/config",null);main.post(()->{
            JSONArray available=config.optJSONArray("projects");assistantProjects.put(assistant,available==null?new JSONArray():available);
            if(!assistant.equals(assistantId(current))||!origin.equals(endpoint(assistant)))return;
            activateProjects(assistant);render();status.setText("● На связи · "+config.optString("model","Bionic"));if(visible)toast("Подключено");
        });}catch(Exception e){main.post(()->{if(!assistant.equals(assistantId(current))||!origin.equals(endpoint(assistant)))return;status.setText((AssistantEndpoint.DOMOVOY.equals(assistant)?"Domovoy unavailable: ":"Connection failed: ")+e.getMessage());status.setOnClickListener(v->connect(true));if(visible)toast(e.getMessage());});}});
    }
    /** Retain recent conversation text and at most five earlier file references for follow-up questions. */
    private JSONArray context(){JSONArray result=new JSONArray();JSONArray all=entries();ArrayList<JSONObject> reverse=new ArrayList<>();HashSet<String> seen=new HashSet<>();int length=0;for(int i=all.length()-1;i>=0&&reverse.size()<10;i--){JSONObject m=all.optJSONObject(i);if(!"completed".equals(m.optString("state","completed"))||m.optString("content").isEmpty())continue;String body=m.optString("content");if(length+body.length()>10000)break;JSONObject item=new JSONObject();put(item,"role",m.optString("role"));put(item,"content",body);JSONArray files=m.optJSONArray("attachments"),refs=new JSONArray();if(files!=null)for(int j=0;j<files.length()&&seen.size()<5;j++){JSONObject file=files.optJSONObject(j);if(seen.add(file.optString("id")))refs.put(file);}if(refs.length()>0)put(item,"attachments",refs);reverse.add(item);length+=body.length();}for(int i=reverse.size()-1;i>=0;i--)result.put(reverse.get(i));return result;}
    /** Prepare a request for this conversation's assistant and keep file-write confirmation explicit. */
    private void send(){String value=input.getText().toString().trim();if(value.isEmpty()){if(draftAttachments().length()==0)return;value="Analyze the attached files.";}if(value.length()>8000){toast("Сообщение слишком длинное (максимум 8000 символов)");return;}if(token.isEmpty()||endpoint(assistantId(current)).isEmpty()){settings();return;}try{
        AssistantEndpoint.validate(assistantId(current),endpoint(assistantId(current)));
        JSONObject request=new JSONObject();request.put("requestId",UUID.randomUUID().toString());request.put("chatId",current.getString("id"));request.put("text",value);request.put("history",context());request.put("mode",modePicker.getSelectedItemPosition()==1?"task":"chat");String project=current.optString("project");if(!project.isEmpty())request.put("workspaceId",project);
        if(draftAttachments().length()>0)request.put("attachments",draftAttachments());
        if("task".equals(request.getString("mode"))){if(project.isEmpty()){toast("Выберите проект для задачи");return;}JSONArray files=new JSONArray();for(String f:fileInput.getText().toString().split("[,\\n]")){if(!f.trim().isEmpty())files.put(f.trim().replace('\\','/'));}if(files.length()==0){toast("Укажите файлы задачи");return;}request.put("files",files);request.put("readOnly",readOnly.isChecked());if(!readOnly.isChecked()){new AlertDialog.Builder(this).setTitle("Разрешить изменения?").setMessage("Помощник изменит перечисленные файлы отдельной рабочей копии.\n\n"+files+"\n\nСлияние в main выполняется отдельно.").setPositiveButton("Отправить задачу",(d,n)->submitNew(request)).setNegativeButton("Отмена",null).show();return;}}
        submitNew(request);
    }catch(Exception e){toast(e.getMessage());}}
    /** Persist the exact request and its immutable assistant origin before the first network call. */
    private void submitNew(JSONObject request){JSONObject user=new JSONObject();put(user,"role","user");put(user,"state","completed");String content=request.optString("text");JSONArray files=request.optJSONArray("attachments");if(files!=null){put(user,"attachments",files);for(int i=0;i<files.length();i++)content+="\n📎 "+files.optJSONObject(i).optString("name");}put(user,"content",content);entries().put(user);JSONObject answer=new JSONObject();put(answer,"role","assistant");put(answer,"state","pending");put(answer,"content","");put(answer,"jobId",request.optString("requestId"));put(answer,"request",request);put(answer,"assistantId",assistantId(current));put(answer,"endpoint",endpoint(assistantId(current)));entries().put(answer);if(entries().length()==2)put(current,"title",request.optString("text").substring(0,Math.min(32,request.optString("text").length())));put(current,"draftAttachments",new JSONArray());input.setText("");pollError="";save();render();submit(answer);}
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
                    final String progress="Uploading "+file.optString("name")+" · "+(size==0?100:offset*100/size)+"%";main.post(()->{put(answer,"content",progress);render();});
                }
            }
            if(!file.getString("sha256").equals(remote.optString("sha256"))||remote.getLong("received")!=size)throw new IOException("Attachment upload verification failed");
        }
    }
    /** Submit a persisted message through its own assistant's independent network lane. */
    private void submit(JSONObject answer) {
        final String assistant = answer.optString("assistantId",AssistantEndpoint.GONETS);
        beginNetwork(assistant);
        render();
        final JSONObject request = answer.optJSONObject("request");
        executor(assistant).execute(() -> {
            try {
                uploadAttachments(answer);
                final JSONObject job = apiFor(answer,"/mobile/messages", request);
                main.post(() -> {
                    apply(answer, job);
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
                main.post(() -> {
                    if (retryable) {
                        put(answer, "state", "pending");
                        put(answer,"pollError","Нет связи или сервер временно недоступен. Повторная проверка при подключении.");
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
    private void retry(JSONObject answer){if(answer.optBoolean("retryNew")){String id=UUID.randomUUID().toString();put(answer,"jobId",id);put(answer.optJSONObject("request"),"requestId",id);put(answer,"retryNew",false);}put(answer,"state","pending");put(answer,"content","");put(answer,"pollError","");submit(answer);}
    /** Apply transport progress while retaining the distinct task outcome shown in the transcript. */
    private void apply(JSONObject answer,JSONObject job){String state=job.optString("state");if("completed".equals(state)){put(answer,"state","completed");put(answer,"content",job.optString("answer","Ответ получен"));put(answer,"taskStatus",job.optString("taskStatus"));}else if("failed".equals(state)){put(answer,"retryNew",true);put(answer,"state","error");put(answer,"content",job.optString("error",job.optString("answer","Задача завершилась с ошибкой")));}else{put(answer,"state","pending");put(answer,"taskId",job.optString("taskId"));put(answer,"content",job.optString("answer"));}}
    /** Recover one pending request per assistant in round-robin order across all conversations. */
    private void poll() {
        if (token.isEmpty()) return;
        for(final String assistant:new String[]{AssistantEndpoint.GONETS,AssistantEndpoint.DOMOVOY}){
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
                main.post(() -> {
                    apply(answer, response);
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
                main.post(() -> {
                    if (retryable) {
                        put(answer, "state", "pending");
                        put(answer,"pollError","Нет связи или сервер временно недоступен. Повторная проверка при подключении.");
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
    /** Update the original Codex reply card once per version without duplicating its history entry. */
    private boolean applyCodexReplies(JSONArray list,JSONArray updates) {
        boolean changed=false;
        if(list==null||updates==null)return false;
        for(int i=0;i<updates.length();i++){
            JSONObject r=updates.optJSONObject(i);if(r==null)continue;
            String id=r.optString("handoffId"),version=r.optString("version");if(id.isEmpty()||version.isEmpty())continue;
            JSONObject message=null;
            for(int j=0;j<list.length();j++){JSONObject item=list.optJSONObject(j);if(item!=null&&id.equals(item.optString("codexReplyId"))){message=item;break;}}
            if(message!=null&&version.equals(message.optString("codexReplyVersion")))continue;
            if(message==null){message=new JSONObject();put(message,"role","assistant");put(message,"codexReplyId",id);list.put(message);}
            String prefix="Codex: ";String state=r.optString("status");
            if("received".equals(state))prefix="Codex получил задачу.\n";
            else if("in_progress".equals(state))prefix="Codex выполняет задачу.\n";
            else if("completed".equals(state))prefix="Результат Codex:\n";
            else if("blocked".equals(state))prefix="Codex: требуется действие.\n";
            put(message,"state","completed");put(message,"content",prefix+r.optString("message"));put(message,"codexReplyVersion",version);changed=true;
        }
        return changed;
    }
    /** Poll Codex updates through the selected chat's assistant without blocking the other assistant. */
    private void pollReplies(){
        if(token.isEmpty())return;
        final JSONObject chat=current;final String id=chat.optString("id"),assistant=assistantId(chat),origin=endpoint(assistant);
        if(origin.isEmpty()||activeNetwork.getOrDefault(assistant,0)>0)return;beginNetwork(assistant);
        executor(assistant).execute(()->{try{
            JSONObject response=apiAt(assistant,origin,"/mobile/control/replies?chatId="+id,null);
            main.post(()->{finishNetwork(assistant);if(applyCodexReplies(chat.optJSONArray("messages"),response.optJSONArray("replies"))){save();if(current==chat)render();}});
        }catch(Exception error){main.post(()->{finishNetwork(assistant);});}});
    }
    private JSONObject publicJson(String location) throws Exception {HttpsURLConnection c=(HttpsURLConnection)new URL(location).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","BionicPocket");try{if(c.getResponseCode()!=200)throw new IOException("Обновления недоступны");return new JSONObject(read(c.getInputStream(),100000));}finally{c.disconnect();}}
    /** Check release metadata without occupying either assistant's delivery lane. */
    private void checkUpdate(boolean visible){updateIo.execute(()->{try{JSONObject manifest=publicJson("https://github.com/"+REPO+"/releases/latest/download/update.json");int installed=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;prefs.edit().putLong("updateCheck",System.currentTimeMillis()).apply();if(manifest.getInt("versionCode")<=installed){if(visible)main.post(()->toast("Установлена последняя версия"));return;}main.post(()->new AlertDialog.Builder(this).setTitle("Доступна версия "+manifest.optString("versionName")).setMessage("Скачать обновление? Подпись приложения и SHA-256 будут проверены перед установкой.").setPositiveButton("Скачать",(d,n)->download(manifest)).setNegativeButton("Позже",null).show());}catch(Exception e){if(visible)main.post(()->toast("Не удалось проверить обновления. Повторите позже."));}});}
    /** Download and verify an update independently of chat traffic, preserving the existing signer checks. */
    private void download(JSONObject manifest){toast("Скачиваю обновление…");updateIo.execute(()->{try{String location=manifest.getString("url");if(!location.startsWith("https://github.com/"+REPO+"/releases/download/"))throw new IOException("Неизвестный источник обновления");String expected=manifest.getString("sha256");if(!expected.matches("[0-9a-f]{64}"))throw new IOException("Некорректная контрольная сумма");HttpsURLConnection c=(HttpsURLConnection)new URL(location).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);File target=new File(getCacheDir(),"update.apk");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;try{if(c.getResponseCode()!=200)throw new IOException("Ошибка скачивания");try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1){total+=n;if(total>50000000)throw new IOException("Файл обновления слишком большой");digest.update(buf,0,n);out.write(buf,0,n);}}}finally{c.disconnect();}StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));if(!expected.equals(hex.toString())||total!=manifest.getLong("size"))throw new IOException("Контрольная сумма не совпала");verifyPackage(target);main.post(()->installUpdate());}catch(Exception e){new File(getCacheDir(),"update.apk").delete();main.post(()->toast("Обновление не установлено: "+e.getMessage()));}});}
    @SuppressWarnings("deprecation") private void verifyPackage(File file) throws Exception {PackageManager pm=getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES);PackageInfo own=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNATURES);if(archive==null||!getPackageName().equals(archive.packageName)||archive.versionCode<=own.versionCode||archive.signatures==null||own.signatures==null||archive.signatures.length!=1||own.signatures.length!=1||!MessageDigest.isEqual(archive.signatures[0].toByteArray(),own.signatures[0].toByteArray()))throw new IOException("Подпись или версия APK не подходит");}
    private void installUpdate(){try{File file=new File(getCacheDir(),"update.apk");verifyPackage(file);if(!getPackageManager().canRequestPackageInstalls()){pendingInstall=true;new AlertDialog.Builder(this).setTitle("Разрешение Android").setMessage("Разрешите Bionic Pocket устанавливать свои обновления. Android всё равно запросит подтверждение установки.").setPositiveButton("Открыть настройки",(d,n)->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).setNegativeButton("Позже",(d,n)->pendingInstall=false).show();return;}Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(Uri.parse("content://"+getPackageName()+".updates/update.apk"),"application/vnd.android.package-archive");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}catch(Exception e){toast("Не удалось открыть установщик: "+e.getMessage());}}
    private void toast(String value){Toast.makeText(this,value==null?"Ошибка":value,Toast.LENGTH_LONG).show();}
}
