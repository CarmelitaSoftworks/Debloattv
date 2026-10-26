package com.carmelita.halo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Limpieza desde la propia TV. Se conecta al ADB de la TV (activado con "Depuración USB"),
 * primero por 127.0.0.1 y, si no responde, por la IP WiFi/Ethernet de la propia TV, que es la
 * misma conexión que usaría un móvil o un PC. Si la TV no lo permite, pasa a un modo manual guiado.
 */
public class CleanActivity extends Activity {

    private interface Task {
        void run() throws Exception;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView conn;
    private View connDot;
    private TextView status;
    private View levels;
    private final TextView[] levelPills = new TextView[3];
    private TextView listHeader;
    private LinearLayout packageList;
    private TextView log;
    private TextView help;
    private Button remove;
    private Button operatorButton;
    /** Perfil de operador en modo manual (en modo automático lo guarda TvDebloater). */
    private String manualOperator;
    private Button chooseHome;
    private Button homeButton;
    private Button retry;
    private Button restoreLauncher;
    private Button restoreAll;

    private AdbConnection adb;
    private TvDebloater tv;
    private String connectedHost;
    private TvDebloater.Device device;
    private List<TvDebloater.Item> candidates = Collections.emptyList();
    private final Set<String> unchecked = new HashSet<>();
    private int level = 2;
    private boolean manual;
    private boolean busy;

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
        status = findViewById(R.id.status);
        levels = findViewById(R.id.levels);
        levelPills[0] = findViewById(R.id.level_seguro);
        levelPills[1] = findViewById(R.id.level_recomendado);
        levelPills[2] = findViewById(R.id.level_agresivo);
        listHeader = findViewById(R.id.list_header);
        packageList = findViewById(R.id.packages);
        log = findViewById(R.id.log);
        help = findViewById(R.id.help);
        remove = findViewById(R.id.remove);
        operatorButton = findViewById(R.id.operator);
        operatorButton.setOnClickListener(v -> chooseOperator());
        chooseHome = findViewById(R.id.choose_home);
        homeButton = findViewById(R.id.home_button);
        retry = findViewById(R.id.retry);
        restoreLauncher = findViewById(R.id.restore_launcher);
        restoreAll = findViewById(R.id.restore_all);

        for (int i = 0; i < levelPills.length; i++) {
            final int value = i + 1;
            levelPills[i].setOnClickListener(v -> {
                level = value;
                refreshUi();
            });
        }
        remove.setOnClickListener(v -> removeSelected());
        chooseHome.setOnClickListener(v -> showLauncherPicker());
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

    // ---- Conexión ------------------------------------------------------------------------------

    private void connect() {
        if (busy) return;
        if (adb != null) adb.close();
        adb = null;
        tv = null;
        manual = false;
        status.setText("");
        setConn(getString(R.string.clean_connecting), R.color.status_warn);
        runTask(() -> {
            AdbKey key = AdbKey.loadOrCreate(getFilesDir());
            AdbConnection connection = null;
            String host = null;
            IOException last = null;
            for (String candidate : adbHosts()) {
                try {
                    connection = AdbConnection.connect(candidate, AdbConnection.DEFAULT_PORT, key,
                            () -> runOnUiThread(() -> status.setText(R.string.clean_approve)));
                    host = candidate;
                    break;
                } catch (ConnectException | NoRouteToHostException | SocketTimeoutException e) {
                    last = e; // nadie escucha en esa dirección: probar la siguiente
                } catch (IOException e) {
                    last = e; // la TV respondió pero rechazó la conexión: no insistir
                    break;
                }
            }
            if (connection != null) {
                try {
                    tv = new TvDebloater(this, connection, this::appendLog);
                    candidates = tv.candidates();
                    device = tv.device;
                    adb = connection;
                    connectedHost = host;
                    manual = false;
                    return;
                } catch (IOException e) {
                    connection.close();
                    tv = null;
                    last = e;
                }
            }
            manual = true;
            device = TvDebloater.detectThisDevice(this);
            manualOperator = device != null ? device.operator : null;
            candidates = TvDebloater.candidatesOnThisDevice(this, manualOperator);
            appendLog("ADB no disponible: " + (last != null ? last.getMessage() : "?"));
        });
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

    private void setConn(String text, int color) {
        conn.setText(text);
        connDot.getBackground().mutate().setTint(getColor(color));
    }

    // ---- Interfaz ------------------------------------------------------------------------------

    private void refreshUi() {
        boolean auto = tv != null;
        boolean ready = auto || manual;
        if (auto) {
            setConn("127.0.0.1".equals(connectedHost) ? getString(R.string.conn_local)
                    : getString(R.string.conn_wifi, connectedHost + ":" + AdbConnection.DEFAULT_PORT), R.color.status_ok);
            status.setText(withDeviceNote(getString(level == 1 ? R.string.level_seguro_desc
                    : level == 3 ? R.string.level_agresivo_desc : R.string.level_recomendado_desc)));
            status.setTextColor(getColor(level == 3 ? R.color.status_warn : R.color.text_secondary));
        } else if (manual) {
            setConn(getString(R.string.conn_none), R.color.status_warn);
            status.setText(withDeviceNote(getString(R.string.clean_manual)));
            status.setTextColor(getColor(R.color.text_secondary));
        }
        levels.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (ready) operatorButton.setText(getString(R.string.operator_button, operatorLabel()));
        show(operatorButton, ready);
        for (int i = 0; i < levelPills.length; i++) levelPills[i].setSelected(level == i + 1);
        show(remove, auto);
        show(chooseHome, auto);
        show(restoreAll, auto && tv.hasBackup());
        show(restoreLauncher, auto);
        show(homeButton, manual);
        show(retry, manual);
        help.setVisibility(manual ? View.VISIBLE : View.GONE);
        if (ready) renderPackages();
    }

    /** Añade el aviso del decodificador reconocido (lists/dispositivos.txt), si lo hay. */
    private String withDeviceNote(String text) {
        if (device == null) return text;
        String note = getString(R.string.device_detected, device.toString());
        if (!device.note.isEmpty()) note += " " + device.note;
        return text + "\n\n" + note;
    }

    private static String shortName(String name) {
        int paren = name.indexOf(" (");
        return paren < 0 ? name : name.substring(0, paren);
    }

    private void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
        v.setEnabled(!busy);
    }

    private List<TvDebloater.Item> visibleItems() {
        List<TvDebloater.Item> out = new ArrayList<>();
        for (TvDebloater.Item item : candidates) if (item.rank() <= level) out.add(item);
        return out;
    }

    private void renderPackages() {
        packageList.removeAllViews();
        List<TvDebloater.Item> items = visibleItems();
        String where = device != null ? device.operator + " · " + shortName(device.name) : Build.MANUFACTURER;
        listHeader.setText(getString(R.string.clean_found, items.size(), where.toUpperCase(Locale.ROOT)));
        for (TvDebloater.Item item : items) packageList.addView(row(item));
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.clean_nothing);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(13);
            packageList.addView(empty);
        }
        updateRemoveButton();
    }

    private void updateRemoveButton() {
        int selected = selectedPackages().size();
        remove.setText(selected > 0
                ? getResources().getQuantityString(R.plurals.clean_remove_n, selected, selected)
                : getString(R.string.clean_remove));
        remove.setEnabled(!busy && selected > 0);
    }

    /** Fila del diseño de Figma: casilla redonda, nombre, paquete y etiqueta de nivel. */
    private View row(final TvDebloater.Item item) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.row_card);
        row.setFocusable(true);
        row.setClickable(true);
        row.setEnabled(!busy);
        row.setPadding(Math.round(12 * d), Math.round(9 * d), Math.round(12 * d), Math.round(9 * d));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Math.round(7 * d);
        row.setLayoutParams(rowLp);

        final ImageView mark = new ImageView(this);
        mark.setImageResource(manual ? R.drawable.ic_chevron
                : unchecked.contains(item.pkg) ? R.drawable.ic_check_off : R.drawable.ic_check_on);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(Math.round(16 * d), Math.round(16 * d));
        markLp.rightMargin = Math.round(10 * d);
        row.addView(mark, markLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(item.description);
        name.setTextColor(getColor(R.color.text_primary));
        name.setTextSize(13);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView pkg = new TextView(this);
        pkg.setText(manual ? getString(R.string.clean_open_settings) + " · " + item.pkg : item.pkg);
        pkg.setTextColor(getColor(R.color.text_secondary));
        pkg.setTextSize(10);
        pkg.setSingleLine(true);
        pkg.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(pkg);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = new TextView(this);
        chip.setText(item.level);
        chip.setTextColor(getColor(item.rank() == 3 ? R.color.status_warn : R.color.text_secondary));
        chip.setTextSize(10);
        chip.setBackgroundResource(R.drawable.pill);
        chip.setPadding(Math.round(7 * d), Math.round(3 * d), Math.round(7 * d), Math.round(3 * d));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = Math.round(10 * d);
        row.addView(chip, chipLp);

        row.setOnClickListener(v -> {
            if (manual) {
                openAppSettings(item.pkg);
                return;
            }
            if (!unchecked.remove(item.pkg)) unchecked.add(item.pkg);
            mark.setImageResource(unchecked.contains(item.pkg) ? R.drawable.ic_check_off : R.drawable.ic_check_on);
            updateRemoveButton();
        });
        return row;
    }

    private List<String> selectedPackages() {
        List<String> out = new ArrayList<>();
        if (manual) return out;
        for (TvDebloater.Item item : visibleItems()) if (!unchecked.contains(item.pkg)) out.add(item.pkg);
        return out;
    }

    // ---- Acciones ------------------------------------------------------------------------------

    private void removeSelected() {
        final List<String> selected = selectedPackages();
        if (selected.isEmpty()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("¿" + getResources().getQuantityString(R.plurals.clean_remove_n, selected.size(), selected.size()) + "?")
                .setMessage("Se pueden recuperar en cualquier momento con «Restaurar todo lo quitado».")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Quitar", (dlg, w) -> runTask(() -> {
                    tv.remove(selected);
                    candidates = tv.candidates();
                }))
                .show();
    }

    private String currentOperator() {
        return tv != null ? tv.operator() : manualOperator;
    }

    private String operatorLabel() {
        String op = currentOperator();
        if (op == null) return getString(R.string.operator_none);
        return device != null && op.equals(device.operator) ? getString(R.string.operator_auto, op) : op;
    }

    /** Elige el perfil de operador: el del equipo se propone solo, pero se puede cambiar. */
    private void chooseOperator() {
        final List<String> names = new ArrayList<>();
        try {
            names.addAll(TvDebloater.operators(this));
        } catch (IOException e) {
            appendLog("! " + e.getMessage());
            return;
        }
        names.add(0, getString(R.string.operator_none));
        String current = currentOperator();
        int checked = current == null ? 0 : Math.max(0, names.indexOf(current));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.operator_title)
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (d, which) -> {
                    d.dismiss();
                    final String chosen = which == 0 ? null : names.get(which);
                    runTask(() -> {
                        if (tv != null) {
                            tv.setOperator(chosen);
                            candidates = tv.candidates();
                        } else {
                            manualOperator = chosen;
                            candidates = TvDebloater.candidatesOnThisDevice(this, chosen);
                        }
                    });
                })
                .show();
    }

    /** Selector del diseño de Figma: Halo o LTvLauncher (código abierto). */
    private void showLauncherPicker() {
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_picker);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.findViewById(R.id.choice_halo).setOnClickListener(v -> {
            dialog.dismiss();
            runTask(() -> tv.installHaloAndSetHome(false));
        });
        dialog.findViewById(R.id.choice_ltv).setOnClickListener(v -> {
            dialog.dismiss();
            runTask(() -> tv.installLtvAndSetHome(false));
        });
        dialog.show();
        dialog.findViewById(R.id.choice_halo).requestFocus();
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
                    View focus = getCurrentFocus();
                    if (focus == null || !focus.isEnabled() || !focus.isShown()) {
                        (remove.getVisibility() == View.VISIBLE ? remove : retry).requestFocus();
                    }
                });
            }
        });
    }

    private void appendLog(final String line) {
        runOnUiThread(() -> log.append(log.length() == 0 ? line : "\n" + line));
    }
}
