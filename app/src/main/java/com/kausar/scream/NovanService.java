package com.kausar.scream;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
public class NovanService extends Service {
    // Service ini tidak melakukan apa pun; jangan dihidupkan ulang otomatis oleh sistem.
    @Override public int onStartCommand(Intent intent,int flags,int startId){ return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent){ return null; }
}