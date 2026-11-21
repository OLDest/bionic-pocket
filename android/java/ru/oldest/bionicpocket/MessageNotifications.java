package ru.oldest.bionicpocket;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.*;

final class MessageNotifications {
    static volatile boolean visible, busy;
    static final String CHANNEL="messages", SYNC="message-sync";
    static synchronized void initialize(Context c,JSONArray chats){
        SharedPreferences p=c.getSharedPreferences("private",Context.MODE_PRIVATE);
        if(p.getBoolean("notificationLedgerReady",false))return;
        JSONObject ledger=new JSONObject();
        for(int i=0;i<chats.length();i++){JSONObject chat=chats.optJSONObject(i);if(chat==null)continue;JSONArray list=chat.optJSONArray("messages");if(list==null)continue;for(int j=0;j<list.length();j++){JSONObject m=list.optJSONObject(j);if(m!=null)MainActivity.claimNotification(ledger,chat.optString("id"),m);}}
        p.edit().putString("notificationLedger",ledger.toString()).putBoolean("notificationLedgerReady",true).commit();
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if(n.getId()==2)manager.cancel(n.getTag(),n.getId());
    }
    static synchronized boolean claim(Context c,String chat,JSONObject m){
        SharedPreferences p=c.getSharedPreferences("private",Context.MODE_PRIVATE);
        try{String saved=p.getString("notificationLedger","{}");JSONObject ledger=new JSONObject(saved);boolean fresh=MainActivity.claimNotification(ledger,chat,m);String updated=ledger.toString();if(!saved.equals(updated)&&!p.edit().putString("notificationLedger",updated).commit())return false;return fresh;}catch(JSONException e){return false;}
    }
    static void clear(Context c,String chat){c.getSystemService(NotificationManager.class).cancel("chat:"+chat,2);}
    static synchronized void readChat(Context c,JSONObject chat){
        SharedPreferences p=c.getSharedPreferences("private",Context.MODE_PRIVATE);
        try{String saved=p.getString("notificationLedger","{}");JSONObject ledger=new JSONObject(saved);JSONArray list=chat.optJSONArray("messages");if(list!=null)for(int i=0;i<list.length();i++){JSONObject m=list.optJSONObject(i);if(m!=null)MainActivity.claimNotification(ledger,chat.optString("id"),m);}String updated=ledger.toString();if(!saved.equals(updated))p.edit().putString("notificationLedger",updated).commit();}catch(JSONException ignored){}
        clear(c,chat.optString("id"));
    }
    static void channels(Context c){
        NotificationManager n=c.getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel(CHANNEL,"Ответы помощников",NotificationManager.IMPORTANCE_HIGH));
        n.createNotificationChannel(new NotificationChannel(SYNC,"Получение сообщений",NotificationManager.IMPORTANCE_LOW));
    }
    static PendingIntent open(Context c,String chat){
        Intent i=new Intent(c,MainActivity.class).putExtra("chatId",chat).setData(android.net.Uri.parse("bionic://chat/"+chat));
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static Notification ongoing(Context c){channels(c);return new Notification.Builder(c,SYNC).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Bionic Pocket").setContentText("Ожидание сообщений").setContentIntent(open(c,"")).setOngoing(true).build();}
    static void received(Context c,String chat,String title,String key,String body){
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)return;
        channels(c);
        String preview=body.length()>240?body.substring(0,240)+"…":body;
        Notification n=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(preview).setStyle(new Notification.BigTextStyle().bigText(preview)).setContentIntent(open(c,chat)).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).build();
        c.getSystemService(NotificationManager.class).notify("chat:"+chat,2,n);
    }
}
