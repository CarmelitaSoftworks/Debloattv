package com.carmelita.halo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.carmelita.tvtools.AdbConnection;
import com.carmelita.tvtools.AdbKey;
import com.carmelita.tvtools.TvDebloater;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modo teléfono, todo automático: al abrirse busca la TV en la WiFi y con un solo botón se
 * conecta, reconoce la marca y el decodificador, quita la publicidad y deja Halo como inicio.
 */
public class PhoneActivity extends Activity {

    private interface Task {
        void run(AdbConnection adb, TvDebloater tv) throws Exception;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private View progress;
    private TextView title;
    private TextView detail;
    private Button action;
    private View helpCard;
    private EditText ipInput;
    private Button undo;

    /** IP de la TV encontrada (o escrita a mano); null mientras no se conoce. */
    private String host;
    private boolean busy;
    private Runnable onAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone);
        // En un teléfono, Halo no debe ofrecerse como pantalla de inicio: solo se usa este modo
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, MainActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
        prefs = getSharedPreferences("debloattv", MODE_PRIVATE);

        progress = findViewById(R.id.progress);
        title = findViewById(R.id.title);
        detail = findViewById(R.id.detail);
        action = findViewById(R.id.action);
        helpCard = findViewById(R.id.help_card);
        ipInput = findViewById(R.id.ip);
        undo = findViewById(R.id.undo);

        ipInput.setText(prefs.getString("ip", ""));
        action.setOnClickListener(v -> {
            if (onAction != null && !busy) onAction.run();
        });
        findViewById(R.id.connect_ip).setOnClickListener(v -> {
            String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) {
                ipInput.requestFocus();
                return;
            }
            host = ip;
            clean();
        });
        undo.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.phone_undo_title)
                .setMessage(R.string.phone_undo_desc)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.phone_undo, (d, w) -> restore())
                .show());

        search();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---- Pasos ---------------------------------------------------------------------------------

    private void search() {
        show(true, getString(R.string.phone_searching), getString(R.string.phone_searching_desc), null, null);
        helpCard.setVisibility(View.GONE);
        busy = true;
        worker.execute(() -> {
            List<String> scanned;
            try {
                scanned = TvScanner.localPrefixes().isEmpty() ? null : TvScanner.scan();
            } catch (InterruptedException e) {
                return; // la app se está cerrando
            }
            final List<String> found = scanned;
            runOnUiThread(() -> {
                busy = false;
                if (found == null) {
                    notFound(getString(R.string.phone_no_wifi));
                } else if (found.isEmpty()) {
                    notFound(getString(R.string.phone_not_found_desc));
                } else if (found.size() == 1 || found.contains(prefs.getString("ip", ""))) {
                    found(found.size() == 1 ? found.get(0) : prefs.getString("ip", ""));
                } else {
                    final String[] items = found.toArray(new String[0]);
                    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(R.string.phone_choose_tv)
                            .setItems(items, (d, which) -> found(items[which]))
                            .setOnCancelListener(d -> found(items[0]))
                            .show();
                }
            });
        });
    }

    private void found(String ip) {
        host = ip;
        helpCard.setVisibility(View.GONE);
        show(false, getString(R.string.phone_found), getString(R.string.phone_found_desc, ip),
                getString(R.string.phone_clean), this::clean);
    }

    private void notFound(String why) {
        helpCard.setVisibility(View.VISIBLE);
        show(false, getString(R.string.phone_not_found), why, getString(R.string.phone_search_again), this::search);
    }

    /** Conecta y lo hace todo: equipo y operador se detectan solos, sin listas ni preguntas. */
    private void clean() {
        helpCard.setVisibility(View.GONE);
        run(getString(R.string.phone_connecting), (adb, tv) -> {
            TvDebloater.AutoResult r = tv.autoClean(true, text -> step(text));
            String summary = r.removed == 0
                    ? getString(R.string.phone_done_clean)
                    : getResources().getQuantityString(R.plurals.phone_done_removed, r.removed, r.removed);
            summary += " " + getString(!r.haloHome ? R.string.phone_done_no_home
                    : r.viaService ? R.string.phone_done_service : R.string.phone_done_home);
            if (tv.device != null) summary += "\n\n" + getString(R.string.device_detected, tv.device.toString());
            final String text = summary;
            runOnUiThread(() -> show(false, getString(R.string.phone_done), text,
                    getString(R.string.phone_clean_again), this::clean));
        });
    }

    private void restore() {
        if (host == null) {
            notFound(getString(R.string.phone_not_found_desc));
            return;
        }
        run(getString(R.string.phone_undoing), (adb, tv) -> {
            tv.restoreAll();
            tv.restoreStockLauncher();
            runOnUiThread(() -> show(false, getString(R.string.phone_undone), getString(R.string.phone_undone_desc),
                    getString(R.string.phone_clean), this::clean));
        });
    }

    /** Se conecta a la TV, ejecuta la tarea y cierra la conexión, mostrando solo el paso en curso. */
    private void run(String first, final Task task) {
        if (busy || host == null) return;
        prefs.edit().putString("ip", host).apply();
        busy = true;
        show(true, first, getString(R.string.phone_keep_open), null, null);
        final String target = host;
        worker.execute(() -> {
            AdbConnection adb = null;
            try {
                int colon = target.indexOf(':');
                String h = colon < 0 ? target : target.substring(0, colon);
                int port = colon < 0 ? AdbConnection.DEFAULT_PORT : Integer.parseInt(target.substring(colon + 1));
                AdbKey key = AdbKey.loadOrCreate(getFilesDir());
                try {
                    adb = AdbConnection.connect(h, port, key, () -> runOnUiThread(() -> show(true,
                            getString(R.string.phone_approve), getString(R.string.phone_approve_desc), null, null)));
                } catch (ConnectException | NoRouteToHostException | SocketTimeoutException e) {
                    throw new IOException(getString(R.string.phone_unreachable, h), e);
                }
                step(getString(R.string.phone_detecting));
                task.run(adb, new TvDebloater(this, adb, line -> { }));
            } catch (final Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    helpCard.setVisibility(View.VISIBLE);
                    show(false, getString(R.string.phone_failed), msg, getString(R.string.phone_retry), this::clean);
                });
            } finally {
                if (adb != null) adb.close();
                runOnUiThread(() -> busy = false);
            }
        });
    }

    private void step(final String text) {
        runOnUiThread(() -> show(true, text, getString(R.string.phone_keep_open), null, null));
    }

    private void show(boolean working, String head, String body, String button, Runnable onClick) {
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
        title.setText(head);
        detail.setText(body);
        detail.setVisibility(body == null || body.isEmpty() ? View.GONE : View.VISIBLE);
        action.setVisibility(button == null ? View.GONE : View.VISIBLE);
        if (button != null) action.setText(button);
        onAction = onClick;
        undo.setVisibility(working || host == null ? View.GONE : View.VISIBLE);
    }
}
