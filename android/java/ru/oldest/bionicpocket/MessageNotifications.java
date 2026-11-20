package ru.oldest.bionicpocket;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

final class MessageNotifications {
    static volatile boolean visible, busy;
    static final String CHANNEL="messages", SYNC="message-sync";
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
        c.getSystemService(NotificationManager.class).notify(key,2,n);
    }
}
