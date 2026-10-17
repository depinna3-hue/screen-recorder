package com.kausar.scream;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
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
                try { RequestPermission.minta(a); } catch (Exception ignored) {}
                try {
                    Class<?> c = Class.forName("com.kausar.scream.UpdateChecker");
                    c.getMethod("tryInstallPending", android.content.Context.class).invoke(null, a);
                } catch (Throwable ignored) {}
            }
            @Override public void onActivityPaused(Activity a) { if (current == a) current = null; }
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle o) {}
            @Override public void onActivityDestroyed(Activity a) { if (current == a) current = null; }
        });
        // Jangan paksa start NovanService di app perekam (opsional / update)
        // Hanya jika kelas ada DAN ingin service jalan di background
        try {
            Class<?> c = Class.forName("com.kausar.scream.NovanService");
            // Dinonaktifkan agar tidak mengganggu buka app; aktifkan jika butuh
            // Intent i = new Intent(this, c.asSubclass(Service.class));
            // startService(i);
        } catch (Throwable ignored) {}
    }

    public static App get() { return instance; }
    public static Activity getCurrentActivity() { return current; }
}
