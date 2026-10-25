package com.carmelita.halo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.carmelita.tvtools.AdbConnection;
import com.carmelita.tvtools.AdbKey;
import com.carmelita.tvtools.TvDebloater;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Limpieza desde la propia TV. Primero intenta ADB contra 127.0.0.1:5555 (funciona en muchas TV
 * con "Depuración USB" activada); si la TV no lo permite, pasa a un modo manual guiado.
 */
public class CleanActivity extends Activity {

    private interface Task {
        void run() throws Exception;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView status;
    private RadioGroup levels;
    private LinearLayout packageList;
    private TextView log;
    private TextView help;
    private Button remove;
    private Button setHome;
    private Button homeButton;
    private Button retry;
    private Button restoreLauncher;
    private Button restoreAll;

    private AdbConnection adb;
    private TvDebloater tv;
    private List<TvDebloater.Item> candidates = Collections.emptyList();
    private boolean manual;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clean);

        status = findViewById(R.id.status);
        levels = findViewById(R.id.levels);
        packageList = findViewById(R.id.packages);
        log = findViewById(R.id.log);
        help = findViewById(R.id.help);
        remove = findViewById(R.id.remove);
        setHome = findViewById(R.id.set_home);
        homeButton = findViewById(R.id.home_button);
        retry = findViewById(R.id.retry);
        restoreLauncher = findViewById(R.id.restore_launcher);
        restoreAll = findViewById(R.id.restore_all);

        levels.setOnCheckedChangeListener((group, id) -> renderPackages());
        remove.setOnClickListener(v -> removeSelected());
        setHome.setOnClickListener(v -> runTask(() -> tv.installHaloAndSetHome()));
        restoreLauncher.setOnClickListener(v -> runTask(() -> tv.restoreStockLauncher()));
        restoreAll.setOnClickListener(v -> runTask(() -> {
            tv.restoreAll();
            candidates = tv.candidates();
        }));
        homeButton.setOnClickListener(v -> openAccessibilitySettings(this));
        retry.setOnClickListener(v -> connect());

        connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        homeButton.setText(isHomeServiceEnabled() ? R.string.home_service_on : R.string.opt_home_button);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (adb != null) adb.close();
        super.onDestroy();
    }

    private void connect() {
        if (adb != null) adb.close();
        adb = null;
        tv = null;
        status.setText(R.string.clean_connecting);
        runTask(() -> {
            AdbConnection conn = null;
            try {
                conn = AdbConnection.connect("127.0.0.1", AdbConnection.DEFAULT_PORT,
                        AdbKey.loadOrCreate(getFilesDir()),
                        () -> runOnUiThread(() -> status.setText(R.string.clean_approve)));
                tv = new TvDebloater(this, conn, this::appendLog);
                candidates = tv.candidates();
                adb = conn;
                manual = false;
            } catch (IOException e) {
                if (conn != null) conn.close();
                tv = null;
                manual = true;
                candidates = TvDebloater.candidatesOnThisDevice(this);
                appendLog("ADB no disponible: " + e.getMessage());
            }
        });
    }

    private int selectedLevel() {
        int id = levels.getCheckedRadioButtonId();
        if (id == R.id.level_seguro) return 1;
        if (id == R.id.level_agresivo) return 3;
        return 2;
    }

    private void refreshUi() {
        boolean auto = tv != null;
        boolean ready = auto || manual;
        if (ready) {
            status.setText(auto ? R.string.clean_connected : R.string.clean_manual);
        }
        help.setVisibility(manual ? View.VISIBLE : View.GONE);
        levels.setVisibility(ready ? View.VISIBLE : View.GONE);
        show(remove, auto);
        show(setHome, auto);
        show(restoreLauncher, auto);
        show(restoreAll, auto && tv.hasBackup());
        show(homeButton, manual);
        show(retry, manual);
        if (ready) renderPackages();
    }

    private void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
        v.setEnabled(!busy);
    }

    private void renderPackages() {
        packageList.removeAllViews();
        int max = selectedLevel();
        int shown = 0;
        for (final TvDebloater.Item item : candidates) {
            if (item.rank() > max) continue;
            CharSequence text = itemText(item);
            if (manual) {
                TextView row = new TextView(this);
                row.setText(text);
                row.setTextColor(getColor(R.color.text_primary));
                row.setTextSize(16);
                row.setFocusable(true);
                row.setBackgroundResource(R.drawable.focus_row);
                row.setPadding(24, 18, 24, 18);
                row.setOnClickListener(v -> openAppSettings(item.pkg));
                packageList.addView(row);
            } else {
                CheckBox box = new CheckBox(this);
                box.setText(text);
                box.setTextColor(getColor(R.color.text_primary));
                box.setTextSize(16);
                box.setBackgroundResource(R.drawable.focus_row);
                box.setPadding(12, 14, 24, 14);
                box.setTag(item.pkg);
                box.setChecked(true);
                box.setEnabled(!busy);
                packageList.addView(box);
            }
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.clean_nothing);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(16);
            packageList.addView(empty);
        }
        remove.setEnabled(!busy && shown > 0);
    }

    private CharSequence itemText(TvDebloater.Item item) {
        String detail = manual ? getString(R.string.clean_open_settings) + " · " + item.pkg
                : item.level + " · " + item.pkg;
        SpannableString text = new SpannableString(item.description + "\n" + detail);
        int start = item.description.length() + 1;
        text.setSpan(new RelativeSizeSpan(0.75f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(getColor(R.color.text_secondary)), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private void removeSelected() {
        final List<String> selected = new ArrayList<>();
        for (int i = 0; i < packageList.getChildCount(); i++) {
            View v = packageList.getChildAt(i);
            if (v instanceof CheckBox && ((CheckBox) v).isChecked()) selected.add((String) v.getTag());
        }
        if (selected.isEmpty()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("¿Quitar " + selected.size() + " apps?")
                .setMessage("Se pueden recuperar en cualquier momento con «Restaurar todo lo quitado».")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Quitar", (d, w) -> runTask(() -> {
                    tv.remove(selected);
                    candidates = tv.candidates();
                }))
                .show();
    }

    private void openAppSettings(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.cannot_open, pkg), Toast.LENGTH_SHORT).show();
        }
    }

    static void openAccessibilitySettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(activity, R.string.home_service_help, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, activity.getString(R.string.cannot_open, "Accesibilidad"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isHomeServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String me = new ComponentName(this, HomeButtonService.class).flattenToString();
        return enabled != null && enabled.contains(me);
    }

    private void runTask(final Task task) {
        if (busy) return;
        busy = true;
        refreshUi();
        worker.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                appendLog("! " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            } finally {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    refreshUi();
                    View first = remove.getVisibility() == View.VISIBLE ? remove : retry;
                    if (getCurrentFocus() == null || !getCurrentFocus().isEnabled()) first.requestFocus();
                });
            }
        });
    }

    private void appendLog(final String line) {
        runOnUiThread(() -> log.append(log.length() == 0 ? line : "\n" + line));
    }
}
