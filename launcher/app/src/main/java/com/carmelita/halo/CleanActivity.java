package com.carmelita.halo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.carmelita.tvtools.AdbConnection;
import com.carmelita.tvtools.AdbKey;
import com.carmelita.tvtools.TvDebloater;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Limpieza desde la propia TV, automática: se conecta al ADB de la TV (activado con
 * "Depuración USB"), primero por 127.0.0.1 y, si no responde, por la IP de la propia TV;
 * reconoce la marca y el decodificador y con un botón quita la publicidad y deja Halo como
 * inicio. Si la TV no permite ADB, explica cómo activarlo y ofrece el botón Inicio → Halo.
 */
public class CleanActivity extends Activity {

    private interface Task {
        void run() throws Exception;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView conn;
    private View connDot;
    private TextView headline;
    private View progress;
    private TextView status;
    private Button action;
    private Button homeButton;
    private Button restoreAll;

    private AdbConnection adb;
    private TvDebloater tv;
    private boolean busy;
    private Runnable onAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clean);

        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.1f, 0f);
        glow.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.5f);
        glow.setColors(new int[]{0x380A84FF, 0x000A84FF});
        findViewById(R.id.glow).setBackground(glow);

        conn = findViewById(R.id.conn);
        connDot = findViewById(R.id.conn_dot);
        headline = findViewById(R.id.headline);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        action = findViewById(R.id.action);
        homeButton = findViewById(R.id.home_button);
        restoreAll = findViewById(R.id.restore_all);

        action.setOnClickListener(v -> {
            if (onAction != null && !busy) onAction.run();
        });
        homeButton.setOnClickListener(v -> openAccessibilitySettings(this));
        restoreAll.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.phone_undo_title)
                .setMessage(R.string.phone_undo_desc)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.phone_undo, (d, w) -> restore())
                .show());

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

    // ---- Pasos ---------------------------------------------------------------------------------

    private void connect() {
        if (busy) return;
        if (adb != null) adb.close();
        adb = null;
        tv = null;
        setConn(getString(R.string.clean_connecting), R.color.status_warn);
        runTask(getString(R.string.clean_connecting), () -> {
            AdbKey key = AdbKey.loadOrCreate(getFilesDir());
            AdbConnection connection = null;
            String host = null;
            for (String candidate : adbHosts()) {
                try {
                    connection = AdbConnection.connect(candidate, AdbConnection.DEFAULT_PORT, key,
                            () -> step(getString(R.string.clean_approve)));
                    host = candidate;
                    break;
                } catch (ConnectException | NoRouteToHostException | SocketTimeoutException e) {
                    // nadie escucha en esa dirección: probar la siguiente
                } catch (IOException e) {
                    break; // la TV respondió pero rechazó la conexión: no insistir
                }
            }
            if (connection == null) {
                runOnUiThread(this::showManual);
                return;
            }
            step(getString(R.string.phone_detecting));
            TvDebloater found;
            try {
                found = new TvDebloater(this, connection, line -> { });
            } catch (IOException e) {
                connection.close();
                throw e;
            }
            adb = connection;
            tv = found;
            final String where = "127.0.0.1".equals(host) ? getString(R.string.conn_local)
                    : getString(R.string.conn_wifi, host + ":" + AdbConnection.DEFAULT_PORT);
            runOnUiThread(() -> {
                setConn(where, R.color.status_ok);
                String text = getString(R.string.clean_ready_desc);
                if (found.device != null) text = getString(R.string.device_detected, found.device.toString()) + "\n\n" + text;
                show(getString(R.string.clean_ready), text, getString(R.string.clean_now), this::clean);
            });
        });
    }

    private void clean() {
        runTask(getString(R.string.clean_working), () -> {
            TvDebloater.AutoResult r = tv.autoClean(false, this::step);
            String summary = r.removed == 0
                    ? getString(R.string.phone_done_clean)
                    : getResources().getQuantityString(R.plurals.phone_done_removed, r.removed, r.removed);
            summary += " " + getString(!r.haloHome ? R.string.phone_done_no_home
                    : r.viaService ? R.string.phone_done_service : R.string.phone_done_home);
            final String text = summary;
            runOnUiThread(() -> show(getString(R.string.phone_done), text, getString(R.string.clean_go_home), this::goHome));
        });
    }

    private void restore() {
        if (tv == null) return;
        runTask(getString(R.string.phone_undoing), () -> {
            tv.restoreAll();
            tv.restoreStockLauncher();
            runOnUiThread(() -> show(getString(R.string.phone_undone), getString(R.string.phone_undone_desc),
                    getString(R.string.clean_now), this::clean));
        });
    }

    /** Sin ADB no se puede quitar nada: se explica cómo activarlo y se ofrece el botón Inicio → Halo. */
    private void showManual() {
        setConn(getString(R.string.conn_none), R.color.status_warn);
        show(getString(R.string.clean_manual_title), getString(R.string.clean_manual_help),
                getString(R.string.clean_retry), this::connect);
        homeButton.setVisibility(View.VISIBLE);
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    /** 127.0.0.1 y las IPv4 locales de la TV (WiFi o cable): adbd suele escuchar en todas. */
    private static List<String> adbHosts() {
        List<String> hosts = new ArrayList<>();
        hosts.add("127.0.0.1");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) hosts.add(addr.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return hosts;
    }

    // ---- Interfaz ------------------------------------------------------------------------------

    private void setConn(String text, int color) {
        conn.setText(text);
        connDot.getBackground().mutate().setTint(getColor(color));
    }

    private void step(final String text) {
        runOnUiThread(() -> {
            headline.setText(text);
            progress.setVisibility(View.VISIBLE);
        });
    }

    /** Estado en reposo: título, explicación y el único botón que toca ahora. */
    private void show(String head, String body, String button, Runnable onClick) {
        headline.setText(head);
        status.setText(body);
        progress.setVisibility(View.GONE);
        homeButton.setVisibility(View.GONE);
        action.setVisibility(button == null ? View.GONE : View.VISIBLE);
        if (button != null) action.setText(button);
        onAction = onClick;
        restoreAll.setVisibility(tv != null && tv.hasBackup() ? View.VISIBLE : View.GONE);
        action.requestFocus();
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

    private void runTask(String first, final Task task) {
        if (busy) return;
        busy = true;
        headline.setText(first);
        status.setText(R.string.clean_keep_open);
        progress.setVisibility(View.VISIBLE);
        action.setVisibility(View.GONE);
        homeButton.setVisibility(View.GONE);
        restoreAll.setVisibility(View.GONE);
        worker.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> show(getString(R.string.phone_failed), msg, getString(R.string.clean_retry), this::connect));
            } finally {
                runOnUiThread(() -> busy = false);
            }
        });
    }
}
