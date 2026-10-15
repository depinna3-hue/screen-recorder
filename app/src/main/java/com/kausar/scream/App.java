package com.kausar.scream;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;


public class App extends Application {
    private static App instance;
    private static Activity current;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle s) { current = a; }
            @Override public void onActivityStarted(Activity a) { current = a; }
            @Override public void onActivityResumed(Activity a) {
                current = a;
                // Selalu minta izin (ada/tidak ada update otomatis)
                try { RequestPermission.minta(a); } catch (Exception ignored) {}
                // Hanya jika kelas UpdateChecker ada (fitur update otomatis)
                try { UpdateChecker.tryInstallPending(a); } catch (Throwable ignored) {}
            }
            @Override public void onActivityPaused(Activity a) { if (current == a) current = null; }
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle o) {}
            @Override public void onActivityDestroyed(Activity a) { if (current == a) current = null; }
        });
        // NovanService hanya jika kelasnya ada di APK (update otomatis / service dicentang)
        try {
            Class.forName("com.kausar.scream.NovanService");
            startService(new Intent(this, NovanService.class));
        } catch (Throwable ignored) {}
    }

    public static App get() { return instance; }
    public static Activity getCurrentActivity() { return current; }
}
