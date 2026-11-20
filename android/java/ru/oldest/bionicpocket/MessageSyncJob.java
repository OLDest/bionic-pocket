package ru.oldest.bionicpocket;
import android.app.job.*;
import android.os.*;
public final class MessageSyncJob extends JobService {
    private volatile boolean stopped;
    public boolean onStartJob(JobParameters p){stopped=false;new Thread(()->{MessageSyncService.check(this,new Handler(Looper.getMainLooper()),()->stopped);new Handler(Looper.getMainLooper()).post(()->jobFinished(p,false));},"message-sync").start();return true;}
    public boolean onStopJob(JobParameters p){stopped=true;return true;}
}
