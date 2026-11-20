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
    public void onCreate(){super.onCreate();startForeground(1,MessageNotifications.ongoing(this));io.scheduleWithFixedDelay(()->check(this,main,()->stopped),0,15,TimeUnit.SECONDS);}
    public int onStartCommand(Intent i,int flags,int startId){return START_NOT_STICKY;}
    public void onTimeout(int startId,int type){stopSelf();}
    public void onDestroy(){stopped=true;io.shutdownNow();super.onDestroy();}
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
    private static JSONObject get(String assistant,String server,String token,String route)throws Exception{
        server=AssistantEndpoint.validate(assistant,server);AssistantEndpoint.requireOfficeAddress(assistant,server);
        HttpURLConnection c=(HttpURLConnection)new URL(server+route).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Authorization","Bearer "+token);
        try{if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1000000)throw new IOException("Response too large");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}}finally{c.disconnect();}
    }
    static void check(Context context,Handler main,Cancelled cancelled){
        if(cancelled.get()||(MessageNotifications.visible||MessageNotifications.busy))return;
        SharedPreferences prefs=context.getSharedPreferences("private",MODE_PRIVATE);
        try{
            String token=token(prefs);if(token.isEmpty())return;
            JSONArray chats=new JSONArray(prefs.getString("chats","[]"));
            for(int i=0;i<chats.length()&&!cancelled.get();i++){
                JSONObject chat=chats.optJSONObject(i);if(chat==null)continue;
                String assistant=chat.optString("assistant",AssistantEndpoint.GONETS),server=prefs.getString(AssistantEndpoint.DOMOVOY.equals(assistant)?"officeServer":"server","");if(server.isEmpty())continue;
                String chatId=chat.optString("id");JSONArray list=chat.optJSONArray("messages");if(list==null)continue;
                for(int j=0;j<list.length()&&!cancelled.get();j++){
                    JSONObject m=list.optJSONObject(j);if(m==null||!"pending".equals(m.optString("state"))||m.optString("jobId").isEmpty())continue;
                    try{JSONObject job=get(m.optString("assistantId",assistant),m.optString("endpoint",server),token,"/mobile/jobs/"+m.optString("jobId"));final String jobId=m.optString("jobId");main.post(()->{
                        if(cancelled.get()||(MessageNotifications.visible||MessageNotifications.busy))return;
                        update(context,prefs,chatId,jobId,job,null);
                    });}catch(Exception ignored){} // Preserve pending data for reconnect.
                }
                try{JSONArray replies=get(assistant,server,token,"/mobile/control/replies?chatId="+chatId).optJSONArray("replies");main.post(()->{if(!cancelled.get()&&!(MessageNotifications.visible||MessageNotifications.busy))update(context,prefs,chatId,null,null,replies);});}catch(Exception ignored){}
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
                        if(!version.equals(m.optString("notifiedVersion"))){MessageNotifications.received(c,chatId,chat.optString("title","Bionic"),key,m.optString("content"));m.put("notifiedVersion",version);}
                    }prefs.edit().putString("chats",chats.toString()).commit();}
            }
        }catch(Exception ignored){}
    }
}
