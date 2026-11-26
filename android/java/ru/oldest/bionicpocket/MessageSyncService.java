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
    private long started;
    public IBinder onBind(Intent i){return null;}
    public void onCreate(){super.onCreate();started=SystemClock.elapsedRealtime();startForeground(1,MessageNotifications.ongoing(this));io.scheduleWithFixedDelay(this::syncPending,0,SyncPolicy.BURST_INTERVAL_SECONDS,TimeUnit.SECONDS);}
    public int onStartCommand(Intent i,int flags,int startId){return START_NOT_STICKY;}
    public void onTimeout(int startId,int type){stopSelf();}
    public void onDestroy(){stopped=true;io.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    /** Keep short polling bursts only for requests in nonarchived conversations. */
    static boolean hasPending(JSONArray chats){
        for(int i=0;i<chats.length();i++){
            JSONObject chat=chats.optJSONObject(i);if(chat==null||chat.optBoolean("archived"))continue;
            JSONArray list=chat.optJSONArray("messages");if(list==null)continue;
            if(SyncPolicy.pending(list))return true;
        }
        return false;
    }
    static boolean hasPending(Context c){
        try{return hasPending(new JSONArray(c.getSharedPreferences("private",MODE_PRIVATE).getString("chats","[]")));}
        catch(Exception error){return false;}
    }
    private void syncPending(){
        if(MessageNotifications.visible||!hasPending(this)||SyncPolicy.burstExpired(started,SystemClock.elapsedRealtime())){main.post(()->{schedule(this);stopSelf();});return;}
        check(this,main,()->stopped||SyncPolicy.burstExpired(started,SystemClock.elapsedRealtime()));
    }
    interface Cancelled {boolean get();}
    /** Keep the periodic inbox check available even when no local chat is waiting. */
    static void schedule(Context c){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(!hasPending(c)&&PocketInbox.connections(c.getSharedPreferences("private",MODE_PRIVATE)).isEmpty()){scheduler.cancel(7308);return;}
        if(scheduler.getPendingJob(7308)!=null)return;
        JobInfo j=new JobInfo.Builder(7308,new ComponentName(c,MessageSyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(SyncPolicy.JOB_INTERVAL_MS).build();
        scheduler.schedule(j);
    }
    private static String token(SharedPreferences p)throws Exception{
        String value=p.getString("token","");if(value.isEmpty())return "";
        String[] parts=value.split(":",2);KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,(SecretKey)store.getKey("bionic-token",null),new GCMParameterSpec(128,android.util.Base64.decode(parts[0],2)));
        return new String(cipher.doFinal(android.util.Base64.decode(parts[1],2)),StandardCharsets.UTF_8);
    }
    /** Read an authenticated source without substituting another assistant's credentials. */
    private static JSONObject get(AssistantRegistry.Connection connection,String route)throws Exception{return request(connection,route,null);}
    /** Send a reply receipt or read source data with bounded network timeouts. */
    private static JSONObject request(AssistantRegistry.Connection connection,String route,JSONObject payload)throws Exception{
        String server=connection.validate(),token=connection.token();if(token.isEmpty())throw new IOException("Токен исходного помощника недоступен");
        HttpURLConnection c=(HttpURLConnection)new URL(server+route).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(payload!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}}
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1000000)throw new IOException("Response too large");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}
        }finally{c.disconnect();}
    }
    /** Poll at most two enabled source inboxes, committing on the main thread before network receipts. */
    private static void checkInbox(Context context,SharedPreferences prefs,Handler main,Cancelled cancelled){
        java.util.ArrayList<AssistantRegistry.Connection> sources=PocketInbox.connections(prefs);if(sources.isEmpty())return;
        int cursor=prefs.getInt("inboxCursor",0);
        for(int n=0;n<Math.min(2,sources.size())&&!cancelled.get();n++){
            AssistantRegistry.Connection connection=sources.get((cursor%sources.size()+n)%sources.size());
            prefs.edit().putInt("inboxCursor",(cursor+n+1)%sources.size()).apply();
            try{
                JSONArray replies=get(connection,"/mobile/control/inbox").optJSONArray("replies");
                FutureTask<JSONArray> merge=new FutureTask<>(()->{
                    if(cancelled.get()||MessageNotifications.visible||MessageNotifications.busy)return new JSONArray();
                    JSONArray chats=new JSONArray(prefs.getString("chats","[]"));JSONObject receipts=new JSONObject(prefs.getString("pocketInboxReceipts","{}"));
                    JSONArray acknowledgements=PocketInbox.merge(chats,receipts,connection.profileId,connection.id,connection.origin,replies);
                    if(!prefs.edit().putString("chats",chats.toString()).putString("pocketInboxReceipts",receipts.toString()).commit())return new JSONArray();
                    for(int i=0;i<chats.length();i++){
                        JSONObject chat=chats.optJSONObject(i);if(chat.optBoolean("archived"))continue;
                        JSONArray messages=chat.optJSONArray("messages");if(messages==null)continue;
                        for(int j=0;j<messages.length();j++){JSONObject message=messages.optJSONObject(j);if(MessageNotifications.claim(context,chat.optString("id"),message))MessageNotifications.received(context,chat.optString("id"),chat.optString("title","Bionic"),message.optString("codexReplyId"),message.optString("content"));}
                    }
                    return acknowledgements;
                });
                main.post(merge);JSONArray acknowledgements=merge.get(5,TimeUnit.SECONDS);
                for(int i=0;i<acknowledgements.length()&&!cancelled.get();i++)request(connection,"/mobile/control/inbox/ack",acknowledgements.optJSONObject(i));
            }catch(Exception ignored){} // The bridge retains unacknowledged versions for the next scheduled check.
        }
    }
    /** Check source inboxes and existing active requests without running a model. */
    static void check(Context context,Handler main,Cancelled cancelled){
        if(cancelled.get()||(MessageNotifications.visible||MessageNotifications.busy))return;
        SharedPreferences prefs=context.getSharedPreferences("private",MODE_PRIVATE);
        try{MessageNotifications.initialize(context,new JSONArray(prefs.getString("chats","[]")));}catch(JSONException ignored){}
        try{
            checkInbox(context,prefs,main,cancelled);
            // Connections are resolved per original request; no selected/shared-token fallback.
            JSONArray chats=new JSONArray(prefs.getString("chats","[]"));
            int cursor=prefs.getInt("syncCursor",0),visited=0;
            for(int n=0;n<chats.length()&&!cancelled.get()&&visited<2;n++){
                int i=(cursor%Math.max(1,chats.length())+n)%Math.max(1,chats.length());
                JSONObject chat=chats.optJSONObject(i);if(chat==null||chat.optBoolean("archived"))continue;
                String assistant=chat.optString("assistant",AssistantEndpoint.GONETS),server=chat.optString("endpoint",AssistantRegistry.origin(prefs,assistant));if(server.isEmpty())continue;
                String chatId=chat.optString("id");JSONArray list=chat.optJSONArray("messages");if(!SyncPolicy.pending(list))continue;
                visited++;
                prefs.edit().putInt("syncCursor",i+1).apply();
                int jobCursor=prefs.getInt("syncJob:"+chatId,0);
                for(int k=0;k<list.length()&&!cancelled.get();k++){
                    int j=(jobCursor%Math.max(1,list.length())+k)%Math.max(1,list.length());
                    JSONObject m=list.optJSONObject(j);if(m==null||m.optBoolean("cancelledByOwner")||!"pending".equals(m.optString("state"))||m.optString("jobId").isEmpty())continue;
                    prefs.edit().putInt("syncJob:"+chatId,j+1).apply();
                    try{JSONObject job=get(AssistantRegistry.resolve(prefs,m.optString("assistantId",assistant),m.optString("endpoint",server),m.optString("connectionId")),"/mobile/jobs/"+m.optString("jobId"));final String jobId=m.optString("jobId");main.post(()->{
                        if(cancelled.get()||(MessageNotifications.visible||MessageNotifications.busy))return;
                        update(context,prefs,chatId,jobId,job,null);
                    });}catch(Exception ignored){} // Preserve pending data for reconnect.
                    break; // At most one job plus its reply feed per active chat, two chats per pass.
                }
                if(cancelled.get()||MessageNotifications.visible||MessageNotifications.busy)break;
                try{JSONArray replies=get(AssistantRegistry.resolve(prefs,assistant,server,chat.optString("connectionId")),"/mobile/control/replies?chatId="+chatId).optJSONArray("replies");main.post(()->{if(!cancelled.get()&&!(MessageNotifications.visible||MessageNotifications.busy))update(context,prefs,chatId,null,null,replies);});}catch(Exception ignored){}
            }
            main.post(()->{if(!cancelled.get())schedule(context);});
        }catch(Exception ignored){}
    }
    /** Merge a legacy response only while its original local chat remains active. */
    private static void update(Context c,SharedPreferences prefs,String chatId,String jobId,JSONObject job,JSONArray replies){
        try{
            JSONArray chats=new JSONArray(prefs.getString("chats","[]"));
            for(int i=0;i<chats.length();i++){
                JSONObject chat=chats.optJSONObject(i);if(chat.optBoolean("archived")||!chatId.equals(chat.optString("id")))continue;
                JSONArray list=chat.optJSONArray("messages");boolean changed=false;
                if(job!=null)for(int j=0;j<list.length();j++){JSONObject m=list.optJSONObject(j);if(jobId.equals(m.optString("jobId"))&&"pending".equals(m.optString("state"))){MainActivity.apply(m,job);changed=true;}}
                if(replies!=null)changed=MainActivity.applyChatReplies(chat,replies)||changed;
                if(changed){prefs.edit().putString("chats",chats.toString()).commit();
                    for(int j=0;j<list.length();j++){JSONObject m=list.optJSONObject(j);if(!"assistant".equals(m.optString("role"))||m.optLong("receivedAt",0)==0)continue;
                        String key=m.optString("codexReplyId",m.optString("jobId"));String version=m.optString("codexReplyVersion",m.optString("state"));
                        if(MessageNotifications.claim(c,chatId,m))MessageNotifications.received(c,chatId,chat.optString("title","Bionic"),key,m.optString("content"));
                    }prefs.edit().putString("chats",chats.toString()).commit();}
            }
        }catch(Exception ignored){}
    }
}
