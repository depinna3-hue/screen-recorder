package com.kausar.scream;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;

import java.util.ArrayList;


public final class RequestPermission {
    public static final int REQ_CODE = 9911;
    private static boolean sudahDiminta = false;

    private RequestPermission() {}

    
    public static void minta(Activity act) {
        if (act == null || sudahDiminta) return;
        if (Build.VERSION.SDK_INT < 23) {
            sudahDiminta = true;
            return;
        }
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(
                    act.getPackageName(), PackageManager.GET_PERMISSIONS);
            String[] diminta = pi.requestedPermissions;
            if (diminta == null || diminta.length == 0) {
                sudahDiminta = true;
                return;
            }
            ArrayList<String> perlu = new ArrayList<>();
            for (String p : diminta) {
                try {
                    PermissionInfo info = act.getPackageManager().getPermissionInfo(p, 0);
                    int level = info.protectionLevel & 0xf;
                    if (level == PermissionInfo.PROTECTION_DANGEROUS) {
                        if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                            perlu.add(p);
                        }
                    }
                } catch (Exception ignored) {}
            }
            sudahDiminta = true;
            if (!perlu.isEmpty()) {
                act.requestPermissions(perlu.toArray(new String[0]), REQ_CODE);
            }
        } catch (Exception ignored) {
            sudahDiminta = true;
        }
    }

    
    public static void mintaLagi(Activity act) {
        sudahDiminta = false;
        minta(act);
    }
}