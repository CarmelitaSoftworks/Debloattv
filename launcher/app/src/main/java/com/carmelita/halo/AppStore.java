package com.carmelita.halo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lee las apps instaladas y guarda el orden y las apps ocultas. */
final class AppStore {
    static final String SETTINGS_KEY = "halo:settings";

    private static final String PREF_ORDER = "order";
    private static final String PREF_HIDDEN = "hidden";

    private final Context context;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    AppStore(Context context) {
        this.context = context.getApplicationContext();
        this.pm = context.getPackageManager();
        this.prefs = context.getSharedPreferences("halo", Context.MODE_PRIVATE);
    }

    /** Carga los iconos, así que conviene llamarlo fuera del hilo principal. */
    List<AppEntry> load() {
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        // Las apps de TV primero: su actividad leanback y su banner son los adecuados.
        collect(byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(byPackage, Intent.CATEGORY_LAUNCHER);

        List<AppEntry> apps = new ArrayList<>(byPackage.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));

        List<String> order = savedOrder();
        if (order.isEmpty()) return apps;
        final Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        // Orden estable: las apps nuevas (sin posición guardada) van al final, alfabéticamente.
        Collections.sort(apps, (a, b) -> {
            Integer ra = rank.get(a.key), rb = rank.get(b.key);
            if (ra == null && rb == null) return 0;
            if (ra == null) return 1;
            if (rb == null) return -1;
            return ra - rb;
        });
        return apps;
    }

    private void collect(Map<String, AppEntry> out, String category) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (out.containsKey(ai.packageName) || ai.packageName.equals(context.getPackageName())) continue;
            CharSequence label = ri.loadLabel(pm);
            out.put(ai.packageName, AppEntry.forActivity(
                    new ComponentName(ai.packageName, ai.name),
                    category,
                    label != null ? label.toString() : ai.packageName,
                    ai.loadBanner(pm),
                    ri.loadIcon(pm)));
        }
    }

    AppEntry settingsEntry() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new AppEntry(SETTINGS_KEY, context.getString(R.string.settings), null,
                context.getDrawable(R.drawable.ic_settings), intent);
    }

    private List<String> savedOrder() {
        String raw = prefs.getString(PREF_ORDER, "");
        return raw.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(raw.split("\n")));
    }

    void saveOrder(List<AppEntry> apps) {
        List<String> keys = new ArrayList<>();
        for (AppEntry e : apps) keys.add(e.key);
        prefs.edit().putString(PREF_ORDER, TextUtils.join("\n", keys)).apply();
    }

    void clearOrder() {
        prefs.edit().remove(PREF_ORDER).apply();
    }

    boolean isHidden(AppEntry e) {
        return prefs.getStringSet(PREF_HIDDEN, Collections.<String>emptySet()).contains(e.key);
    }

    void setHidden(AppEntry e, boolean hidden) {
        Set<String> set = new HashSet<>(prefs.getStringSet(PREF_HIDDEN, Collections.<String>emptySet()));
        if (hidden) set.add(e.key); else set.remove(e.key);
        prefs.edit().putStringSet(PREF_HIDDEN, set).apply();
    }
}
