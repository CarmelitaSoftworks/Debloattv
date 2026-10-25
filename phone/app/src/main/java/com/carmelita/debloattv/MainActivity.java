package com.carmelita.debloattv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private interface Task {
        void run() throws Exception;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private EditText ipInput;
    private Button searchButton;
    private Button connectButton;
    private TextView status;
    private View connectedPanel;
    private RadioGroup levels;
    private TextView levelDesc;
    private LinearLayout packageList;
    private Button removeButton;
    private Button installHalo;
    private Button restoreLauncher;
    private Button restoreAll;
    private View logCard;
    private TextView log;

    private AdbConnection adb;
    private TvDebloater tv;
    private List<TvDebloater.Item> candidates = Collections.emptyList();
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("debloattv", MODE_PRIVATE);

        ipInput = findViewById(R.id.ip);
        searchButton = findViewById(R.id.search);
        connectButton = findViewById(R.id.connect);
        status = findViewById(R.id.status);
        connectedPanel = findViewById(R.id.connected_panel);
        levels = findViewById(R.id.levels);
        levelDesc = findViewById(R.id.level_desc);
        packageList = findViewById(R.id.packages);
        removeButton = findViewById(R.id.remove);
        installHalo = findViewById(R.id.install_halo);
        restoreLauncher = findViewById(R.id.restore_launcher);
        restoreAll = findViewById(R.id.restore_all);
        logCard = findViewById(R.id.log_card);
        log = findViewById(R.id.log);

        ipInput.setText(prefs.getString("ip", ""));
        searchButton.setOnClickListener(v -> search());
        connectButton.setOnClickListener(v -> {
            if (adb == null) connect(); else disconnect();
        });
        levels.setOnCheckedChangeListener((group, id) -> {
            levelDesc.setText(levelDescription());
            levelDesc.setTextColor(getColor(id == R.id.level_agresivo ? R.color.warning : R.color.text_secondary));
            renderPackages();
        });
        removeButton.setOnClickListener(v -> removeSelected());
        installHalo.setOnClickListener(v -> confirm(
                "¿Instalar Halo?",
                "Se instalará Halo en la TV y se desactivará el launcher de stock. Puedes volver atrás con «Volver al original».",
                () -> runTask(() -> tv.installHaloAndSetHome())));
        restoreLauncher.setOnClickListener(v -> runTask(() -> tv.restoreStockLauncher()));
        restoreAll.setOnClickListener(v -> runTask(() -> {
            tv.restoreAll();
            candidates = tv.candidates();
        }));
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (adb != null) adb.close();
        super.onDestroy();
    }

    // ---- Conexión ------------------------------------------------------------------------------

    private void search() {
        setStatus("Buscando televisores en tu WiFi…");
        runTask(() -> {
            if (TvScanner.localPrefixes().isEmpty()) {
                throw new IOException("El teléfono no está conectado a ninguna red WiFi.");
            }
            final List<String> found = TvScanner.scan();
            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    setStatus("No se encontró ninguna TV con la depuración activada. Revisa los pasos de arriba "
                            + "o escribe la IP a mano.");
                } else if (found.size() == 1) {
                    ipInput.setText(found.get(0));
                    setStatus("TV encontrada: " + found.get(0));
                } else {
                    final String[] items = found.toArray(new String[0]);
                    setStatus("Se encontraron " + items.length + " equipos.");
                    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Elige tu TV")
                            .setItems(items, (d, which) -> ipInput.setText(items[which]))
                            .show();
                }
            });
        });
    }

    private void connect() {
        String input = ipInput.getText().toString().trim();
        if (input.isEmpty()) {
            setStatus("Escribe la IP de la TV o pulsa «Buscar TV».");
            return;
        }
        prefs.edit().putString("ip", input).apply();
        final String host;
        final int port;
        int colon = input.indexOf(':');
        try {
            host = colon < 0 ? input : input.substring(0, colon);
            port = colon < 0 ? AdbConnection.DEFAULT_PORT : Integer.parseInt(input.substring(colon + 1));
        } catch (NumberFormatException e) {
            setStatus("La IP no es válida.");
            return;
        }

        setStatus("Conectando con " + host + "…");
        runTask(() -> {
            AdbKey key = AdbKey.loadOrCreate(getFilesDir());
            AdbConnection conn;
            try {
                conn = AdbConnection.connect(host, port, key, () -> runOnUiThread(() -> setStatus(
                        "Mira la TV: acepta «¿Permitir depuración?» y marca «Permitir siempre desde este equipo».")));
            } catch (ConnectException | NoRouteToHostException | SocketTimeoutException e) {
                throw new IOException("La TV no responde en " + host + ":" + port + ". Comprueba que la IP es "
                        + "correcta y que «Depuración USB» está activada. Algunos modelos necesitan además "
                        + "«Depuración por red» o ejecutar una vez «adb tcpip 5555» desde un PC por USB.", e);
            }
            try {
                tv = new TvDebloater(this, conn, this::appendLog);
                candidates = tv.candidates();
            } catch (IOException e) {
                conn.close();
                tv = null;
                throw e;
            }
            adb = conn;
            final String vendor = tv.vendorList();
            runOnUiThread(() -> {
                setStatus("Conectado a " + tv.model + " (" + tv.manufacturer + ")"
                        + (vendor != null ? " · lista de " + vendor.replace(".txt", "") + " incluida" : ""));
                appendLog("Conectado a " + tv.model);
            });
        });
    }

    private void disconnect() {
        if (adb != null) adb.close();
        adb = null;
        tv = null;
        candidates = Collections.emptyList();
        setStatus("Desconectado.");
        refreshUi();
    }

    // ---- Debloat -------------------------------------------------------------------------------

    private int selectedLevel() {
        int id = levels.getCheckedRadioButtonId();
        if (id == R.id.level_seguro) return 1;
        if (id == R.id.level_agresivo) return 3;
        return 2;
    }

    private int levelDescription() {
        switch (selectedLevel()) {
            case 1: return R.string.level_seguro_desc;
            case 3: return R.string.level_agresivo_desc;
            default: return R.string.level_recomendado_desc;
        }
    }

    private void renderPackages() {
        packageList.removeAllViews();
        int max = selectedLevel();
        int shown = 0;
        for (TvDebloater.Item item : candidates) {
            if (item.rank() > max) continue;
            CheckBox box = new CheckBox(this);
            String pkgLine = item.level + " · " + item.pkg;
            SpannableString text = new SpannableString(item.description + "\n" + pkgLine);
            int start = item.description.length() + 1;
            text.setSpan(new RelativeSizeSpan(0.8f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(getColor(R.color.text_secondary)), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            box.setText(text);
            box.setTextColor(getColor(R.color.text_primary));
            box.setTag(item.pkg);
            box.setChecked(true);
            box.setPadding(box.getPaddingLeft(), 12, 0, 12);
            packageList.addView(box);
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.nothing_found);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(0, 16, 0, 0);
            packageList.addView(empty);
        }
        removeButton.setEnabled(!busy && shown > 0);
    }

    private void removeSelected() {
        final List<String> selected = new ArrayList<>();
        for (int i = 0; i < packageList.getChildCount(); i++) {
            View v = packageList.getChildAt(i);
            if (v instanceof CheckBox && ((CheckBox) v).isChecked()) selected.add((String) v.getTag());
        }
        if (selected.isEmpty()) return;
        confirm("¿Quitar " + selected.size() + " apps?",
                "Se pueden recuperar en cualquier momento con «Restaurar todo».",
                () -> runTask(() -> {
                    tv.remove(selected);
                    candidates = tv.candidates();
                }));
    }

    // ---- Utilidades ----------------------------------------------------------------------------

    private void runTask(final Task task) {
        if (busy) return;
        busy = true;
        refreshUi();
        worker.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    setStatus(msg);
                    appendLog("! " + msg);
                });
                if (adb != null && e instanceof IOException) {
                    // Si se cortó la conexión a mitad, obligamos a reconectar.
                    try {
                        adb.shell("true");
                    } catch (IOException dead) {
                        runOnUiThread(this::disconnect);
                    }
                }
            } finally {
                runOnUiThread(() -> {
                    busy = false;
                    refreshUi();
                });
            }
        });
    }

    private void refreshUi() {
        boolean connected = adb != null && tv != null;
        connectedPanel.setVisibility(connected ? View.VISIBLE : View.GONE);
        connectButton.setText(connected ? R.string.disconnect : R.string.connect);
        connectButton.setEnabled(!busy);
        searchButton.setEnabled(!busy && !connected);
        ipInput.setEnabled(!busy && !connected);
        installHalo.setEnabled(!busy);
        restoreLauncher.setEnabled(!busy);
        restoreAll.setEnabled(!busy && connected && tv.hasBackup());
        if (connected) renderPackages();
    }

    private void setStatus(String text) {
        status.setVisibility(View.VISIBLE);
        status.setText(text);
    }

    private void appendLog(final String line) {
        runOnUiThread(() -> {
            logCard.setVisibility(View.VISIBLE);
            log.append(log.length() == 0 ? line : "\n" + line);
        });
    }

    private void confirm(String title, String message, Runnable onYes) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (d, w) -> onYes.run())
                .show();
    }
}
