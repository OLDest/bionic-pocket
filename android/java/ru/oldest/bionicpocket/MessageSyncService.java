package ru.oldest.bionicpocket;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.*;

/** Background network checks never invoke the model or resubmit requests. */
public final class MessageSyncService extends Service {
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    public IBinder onBind(Intent i){return null;}
    public void onCreate(){super.onCreate();startForeground(1,MessageNotifications.ongoing(this));io.scheduleWithFixedDelay(this::syncPending,0,15,TimeUnit.SECONDS);}
    public int onStartCommand(Intent i,int flags,int startId){return START_NOT_STICKY;}
    public void onTimeout(int startId,int type){stopSelf();}
    public void onDestroy(){stopped=true;io.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    static boolean hasPending(JSONArray chats){
        for(int i=0;i<chats.length();i++){
            JSONObject chat=chats.optJSONObject(i);if(chat==null)continue;
            JSONArray list=chat.optJSONArray("messages");if(list==null)continue;
            java.util.Set<String> finished=new java.util.HashSet<>();
            for(int j=0;j<list.length();j++){
                JSONObject m=list.optJSONObject(j);if(m==null)continue;
                String state=m.optString("codexReplyStatus");
                if("completed".equals(state)||"blocked".equals(state))finished.add(m.optString("codexReplyId"));
            }
            for(int j=0;j<list.length();j++){
                JSONObject m=list.optJSONObject(j);if(m==null||!"assistant".equals(m.optString("role")))continue;
                if("pending".equals(m.optString("state")))return true;
                String handoff=m.optString("handoffId"),reply=m.optString("codexReplyId"),state=m.optString("codexReplyStatus");
                if(!handoff.isEmpty()&&!finished.contains(handoff))return true;
                if(!reply.isEmpty()&&("received".equals(state)||"in_progress".equals(state))&&!finished.contains(reply))return true;
            }
        }
        return false;
    }
    static boolean hasPending(Context c){
        try{return hasPending(new JSONArray(c.getSharedPreferences("private",MODE_PRIVATE).getString("chats","[]")));}
        catch(Exception error){return false;}
    }
    private void syncPending(){
        if(MessageNotifications.visible||!hasPending(this)){main.post(this::stopSelf);return;}
        check(this,main,()->stopped);
    }
    interface Cancelled {boolean get();}
    static void schedule(Context c){
        JobInfo j=new JobInfo.Builder(7308,new ComponentName(c,MessageSyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).build();
        c.getSystemService(JobScheduler.class).schedule(j);
    }
    private static String token(SharedPreferences p)throws Exception{
        String value=p.getString("token","");if(value.isEmpty())return "";
        String[] parts=value.split(":",2);KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,(SecretKey)store.getKey("bionic-token",null),new GCMParameterSpec(128,android.util.Base64.decode(parts[0],2)));
        return new String(cipher.doFinal(android.util.Base64.decode(parts[1],2)),StandardCharsets.UTF_8);
    }
    private static JSONObject get(AssistantRegistry.Connection connection,String route)throws Exception{
        String server=connection.validate(),token=connection.token();if(token.isEmpty())throw new IOException("Токен исходного помощника недоступен");
        HttpURLConnection c=(HttpURLConnection)new URL(server+route).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Authorization","Bearer "+token);
        try{if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1000000)throw new IOException("Response too large");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}}finally{c.disconnect();}
    }
    static void check(Context context,Handler main,Cancelled cancelled){
        if(cancelled.get()||(MessageNotifications.visible||MessageNotifications.busy))return;
        SharedPreferences prefs=context.getSharedPreferences("private",MODE_PRIVATE);
        try{MessageNotifications.initialize(context,new JSONArray(prefs.getString("chats","[]")));}catch(JSONException ignored){}
        try{
            // Connections are resolved per original request; no selected/shared-token fallback.
            JSONArray chats=new JSONArray(prefs.getString("chats","[]"));
            for(int i=0;i<chats.length()&&!cancelled.get();i++){
                JSONObject chat=chats.optJSONObject(i);if(chat==null)continue;
                String assistant=chat.optString("assistant",AssistantEndpoint.GONETS),server=chat.optString("endpoint",AssistantRegistry.origin(prefs,assistant));if(server.isEmpty())continue;
                String chatId=chat.optString("id");JSONArray list=chat.optJSONArray("messages");if(list==null)continue;
                for(int j=0;j<list.length()&&!cancelled.get();j++){
                    JSONObject m=list.optJSONObject(j);if(m==null||!"pending".equals(m.optString("state"))||m.optString("jobId").isEmpty())continue;
                    try{JSONObject job=get(AssistantRegistry.resolve(prefs,m.optString("assistantId",assistant),m.optString("endpoint",server),m.optString("connectionId")),"/mobile/jobs/"+m.optString("jobId"));final String jobId=m.optString("jobId");main.post(()->{
                        if(cancelled.get()||(MessageNotifications.visible||MessageNotifications.busy))return;
                        update(context,prefs,chatId,jobId,job,null);
                    });}catch(Exception ignored){} // Preserve pending data for reconnect.
                }
                try{JSONArray replies=get(AssistantRegistry.resolve(prefs,assistant,server,chat.optString("connectionId")),"/mobile/control/replies?chatId="+chatId).optJSONArray("replies");main.post(()->{if(!cancelled.get()&&!(MessageNotifications.visible||MessageNotifications.busy))update(context,prefs,chatId,null,null,replies);});}catch(Exception ignored){}
            }
        }catch(Exception ignored){}
    }
    private static void update(Context c,SharedPreferences prefs,String chatId,String jobId,JSONObject job,JSONArray replies){
        try{
            JSONArray chats=new JSONArray(prefs.getString("chats","[]"));
            for(int i=0;i<chats.length();i++){
                JSONObject chat=chats.optJSONObject(i);if(!chatId.equals(chat.optString("id")))continue;
                JSONArray list=chat.optJSONArray("messages");boolean changed=false;
                if(job!=null)for(int j=0;j<list.length();j++){JSONObject m=list.optJSONObject(j);if(jobId.equals(m.optString("jobId"))&&"pending".equals(m.optString("state"))){MainActivity.apply(m,job);changed=true;}}
                if(replies!=null)changed=MainActivity.applyCodexReplies(list,replies)||changed;
                if(changed){prefs.edit().putString("chats",chats.toString()).commit();
                    for(int j=0;j<list.length();j++){JSONObject m=list.optJSONObject(j);if(!"assistant".equals(m.optString("role"))||m.optLong("receivedAt",0)==0)continue;
                        String key=m.optString("codexReplyId",m.optString("jobId"));String version=m.optString("codexReplyVersion",m.optString("state"));
                        if(MessageNotifications.claim(c,chatId,m))MessageNotifications.received(c,chatId,chat.optString("title","Bionic"),key,m.optString("content"));
                    }prefs.edit().putString("chats",chats.toString()).commit();}
            }
        }catch(Exception ignored){}
    }
}
