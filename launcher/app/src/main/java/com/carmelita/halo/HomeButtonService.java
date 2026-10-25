package com.carmelita.halo;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;

import com.carmelita.tvtools.TvDebloater;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Para televisores sin ADB: cuando el botón HOME abre el launcher de stock, se abre Halo encima.
 * Los launchers se detectan al arrancar el servicio, así que sirve para cualquier marca.
 */
public class HomeButtonService extends AccessibilityService {

    /** Responden a HOME pero no son launchers: redirigirlos rompería el arranque o la configuración. */
    private static final List<String> IGNORED = Arrays.asList(
            "android", "com.android.settings", "com.android.tv.settings",
            "com.google.android.tungsten.setupwraith");

    private final Set<String> launchers = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        launchers.clear();
        launchers.addAll(TvDebloater.STOCK_LAUNCHERS);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            launchers.add(ri.activityInfo.packageName);
        }
        launchers.removeAll(IGNORED);
        launchers.remove(getPackageName());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !launchers.contains(pkg.toString())) return;
        startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onInterrupt() {
    }
}
