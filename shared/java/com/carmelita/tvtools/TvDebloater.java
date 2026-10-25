package com.carmelita.tvtools;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las mismas reglas que debloater/debloat.sh. Lo usan la app del teléfono (ADB por WiFi)
 * y Halo en la propia TV (ADB contra 127.0.0.1, o modo manual si no hay ADB).
 */
public final class TvDebloater {
    public static final String HALO_PKG = "com.carmelita.halo";
    private static final String HALO_HOME = HALO_PKG + "/.MainActivity";
    private static final String HALO_TMP = "/data/local/tmp/halo.apk";

    public static final List<String> STOCK_LAUNCHERS = Collections.unmodifiableList(Arrays.asList(
            "com.google.android.apps.tv.launcherx",    // Google TV
            "com.google.android.tungsten.setupwraith", // Google TV: reabre el launcher de stock con HOME
            "com.google.android.tvlauncher",           // Android TV 8+
            "com.google.android.leanbacklauncher",     // Android TV antiguo
            "com.tcl.waterfall.overseas",              // TCL Home (Android TV)
            "com.mitv.tvhome"));                       // Xiaomi PatchWall

    /** Paquetes que declaran HOME pero no son un launcher (pantallas de arranque de Ajustes). */
    private static final Set<String> NOT_LAUNCHERS = new HashSet<>(Arrays.asList(
            "android", "com.android.settings", "com.android.tv.settings", HALO_PKG));

    private static final Pattern COMPONENT = Pattern.compile("^\\s*([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+\\s*$");

    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.leanback.ime", "com.google.android.inputmethod.latin",
            "com.google.android.tv.remote.service", "com.google.android.tvsetup",
            "com.android.providers.settings", "com.android.providers.tv", "com.android.providers.media",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.shell", "com.android.bluetooth", "com.android.wifi.resources",
            HALO_PKG));

    public static final String[] LEVELS = {"seguro", "recomendado", "agresivo"};

    public interface Log {
        void line(String text);
    }

    private interface Installed {
        boolean contains(String pkg);
    }

    public static final class Item {
        public final String level;
        public final String pkg;
        public final String description;

        Item(String level, String pkg, String description) {
            this.level = level;
            this.pkg = pkg;
            this.description = description;
        }

        public int rank() {
            return levelRank(level);
        }
    }

    private final Context context;
    private final AdbConnection adb;
    private final Log log;
    public final String manufacturer;
    public final String model;
    private final File backupFile;
    private Set<String> installed;

    public TvDebloater(Context context, AdbConnection adb, Log log) throws IOException {
        this.context = context;
        this.adb = adb;
        this.log = log;
        manufacturer = adb.shell("getprop ro.product.manufacturer").toLowerCase(Locale.ROOT);
        model = adb.shell("getprop ro.product.model");
        String serial = adb.shell("getprop ro.serialno");
        String id = (serial.isEmpty() ? model : serial).replaceAll("[^A-Za-z0-9]+", "-");
        backupFile = new File(context.getFilesDir(), "backup-" + id + ".txt");
        refreshInstalled();
    }

    public static int levelRank(String level) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(level)) return i + 1;
        return 0;
    }

    private void refreshInstalled() throws IOException {
        installed = new HashSet<>();
        for (String line : adb.shell("pm list packages").split("\n")) {
            if (line.startsWith("package:")) installed.add(line.substring(8).trim());
        }
    }

    /** Todos los paquetes de las listas (Google + la marca detectada) que hay en esta TV. */
    public List<Item> candidates() throws IOException {
        return parseLists(context, manufacturer, pkg -> installed.contains(pkg));
    }

    public String vendorList() {
        return vendorList(manufacturer);
    }

    /**
     * Lo mismo sin ADB: se ejecuta en la propia TV y consulta el PackageManager.
     * Solo incluye apps activas, que son las que el usuario aún puede desactivar a mano.
     */
    public static List<Item> candidatesOnThisDevice(Context context) throws IOException {
        final PackageManager pm = context.getPackageManager();
        return parseLists(context, Build.MANUFACTURER.toLowerCase(Locale.ROOT), pkg -> {
            try {
                return pm.getApplicationInfo(pkg, 0).enabled;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        });
    }

    private static List<Item> parseLists(Context context, String manufacturer, Installed installed)
            throws IOException {
        List<String> files = new ArrayList<>();
        files.add("google.txt");
        String vendor = vendorList(manufacturer);
        if (vendor != null) files.add(vendor);

        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String file : files) {
            for (String raw : new String(readAsset(context, "lists/" + file), StandardCharsets.UTF_8).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 2 || levelRank(parts[0]) == 0) continue;
                String pkg = parts[1];
                if (PROTECTED.contains(pkg) || !installed.contains(pkg) || !seen.add(pkg)) continue;
                out.add(new Item(parts[0], pkg, parts.length > 2 ? parts[2] : pkg));
            }
        }
        return out;
    }

    private static String vendorList(String manufacturer) {
        if (manufacturer.contains("sony")) return "sony.txt";
        if (manufacturer.contains("tcl")) return "tcl.txt";
        if (manufacturer.contains("xiaomi")) return "xiaomi.txt";
        if (manufacturer.contains("hisense")) return "hisense.txt";
        if (manufacturer.contains("philips") || manufacturer.contains("tpvision")
                || manufacturer.contains("tp vision")) return "philips.txt";
        return null;
    }

    /** pm uninstall --user 0 es lo más limpio; si el sistema no lo permite, se desactiva. */
    public void remove(List<String> packages) throws IOException {
        for (String pkg : packages) {
            if (PROTECTED.contains(pkg)) continue;
            if (adb.shell("pm uninstall -k --user 0 " + pkg).contains("Success")) {
                record("uninstall", pkg);
                log.line("✔ " + pkg);
            } else if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled")) {
                record("disable", pkg);
                log.line("✔ " + pkg + " (desactivado)");
            } else {
                log.line("! no se pudo quitar " + pkg);
            }
        }
        refreshInstalled();
    }

    /**
     * Launchers de esta TV: la lista conocida más cualquier app del sistema que responda a HOME
     * (así también se encuentra el inicio de marcas con nombres de paquete que no conocemos).
     */
    private List<String> stockLaunchers() throws IOException {
        List<String> out = new ArrayList<>(STOCK_LAUNCHERS);
        Set<String> system = new HashSet<>();
        for (String line : adb.shell("pm list packages -s").split("\n")) {
            if (line.startsWith("package:")) system.add(line.substring(8).trim());
        }
        String homes = adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        for (String line : homes.split("\n")) {
            Matcher m = COMPONENT.matcher(line);
            if (!m.matches()) continue;
            String pkg = m.group(1);
            if (system.contains(pkg) && !NOT_LAUNCHERS.contains(pkg) && !out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    /**
     * Instala Halo si hace falta y lo deja como pantalla de inicio. Desde el teléfono se pulsa
     * HOME al final; desde la propia TV no, para que el registro quede a la vista.
     * Devuelve true si al terminar el botón Inicio abre Halo.
     */
    public boolean installHaloAndSetHome(boolean pressHome) throws IOException {
        if (!installed.contains(HALO_PKG)) {
            log.line("Copiando Halo a la TV…");
            adb.push(readAsset(context, "halo-launcher.apk"), HALO_TMP, 0100644);
            String result = adb.shell("pm install -r " + HALO_TMP);
            adb.shell("rm -f " + HALO_TMP);
            if (!result.contains("Success")) throw new IOException("No se pudo instalar Halo: " + result);
            log.line("✔ Halo instalado");
        } else {
            log.line("✔ Halo ya estaba instalado");
        }

        adb.shell("cmd package set-home-activity " + HALO_HOME);
        for (String pkg : stockLaunchers()) {
            if (!installed.contains(pkg)) continue;
            String out = adb.shell("pm disable-user --user 0 " + pkg);
            if (out.contains("disabled")) {
                record("home", pkg);
                log.line("✔ desactivado " + pkg);
                continue;
            }
            // Algunas TV no dejan desactivar el launcher pero sí quitarlo para el usuario
            String out2 = adb.shell("pm uninstall -k --user 0 " + pkg);
            if (out2.contains("Success")) {
                record("home-uninstall", pkg);
                log.line("✔ quitado " + pkg);
            } else {
                log.line("! no se pudo desactivar " + pkg + ": " + firstLine(out) + " / " + firstLine(out2));
            }
        }
        // Otra vez, por si el sistema eligió otro inicio al desaparecer el de stock
        String setHome = adb.shell("cmd package set-home-activity " + HALO_HOME);
        refreshInstalled();

        String home = currentHome();
        boolean ok = home.startsWith(HALO_PKG + "/");
        if (ok) {
            log.line("✔ El botón Inicio abre Halo.");
        } else {
            log.line("! El botón Inicio sigue abriendo: " + (home.isEmpty() ? "(desconocido)" : home));
            if (!setHome.isEmpty() && !setHome.contains("Success")) log.line("  set-home-activity: " + firstLine(setHome));
            log.line("  Activa «Botón Inicio → Halo» en Accesibilidad como alternativa.");
        }
        if (pressHome) adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        return ok;
    }

    /** Componente que abre ahora mismo el botón Inicio ("" si no se pudo averiguar). */
    private String currentHome() throws IOException {
        String out = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        String last = "";
        for (String line : out.split("\n")) {
            if (COMPONENT.matcher(line).matches()) last = line.trim();
        }
        return last;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    public void restoreStockLauncher() throws IOException {
        // Los conocidos más los que se quitaron desde aquí (un launcher desactivado ya no
        // aparece al consultar HOME, así que se leen del registro).
        Set<String> launchers = new HashSet<>(STOCK_LAUNCHERS);
        for (String[] entry : backupEntries()) {
            if ("home".equals(entry[0])) launchers.add(entry[1]);
            if ("home-uninstall".equals(entry[0])) {
                String out = adb.shell("cmd package install-existing " + entry[1]);
                if (!out.contains("installed")) out = adb.shell("pm install-existing " + entry[1]);
                log.line(out.contains("installed") ? "✔ reinstalado " + entry[1] : "! " + entry[1] + ": " + out);
            }
        }
        refreshInstalled();
        for (String pkg : launchers) {
            if (!installed.contains(pkg)) continue;
            log.line(adb.shell("pm enable " + pkg).contains("enabled")
                    ? "✔ activado " + pkg : "! no se pudo activar " + pkg);
        }
        adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        log.line("Launcher original reactivado.");
    }

    public boolean hasBackup() {
        return backupFile.exists() && backupFile.length() > 0;
    }

    /** Reinstala o reactiva todo lo que se quitó desde esta app en esta TV. */
    public void restoreAll() throws IOException {
        if (!hasBackup()) {
            log.line("No hay nada que restaurar en esta TV.");
            return;
        }
        for (String[] parts : backupEntries()) {
            String pkg = parts[1];
            if ("uninstall".equals(parts[0]) || "home-uninstall".equals(parts[0])) {
                String out = adb.shell("cmd package install-existing " + pkg);
                if (!out.contains("installed")) out = adb.shell("pm install-existing " + pkg);
                log.line(out.contains("installed") ? "✔ reinstalado " + pkg : "! " + pkg + ": " + out);
            } else if ("disable".equals(parts[0]) || "home".equals(parts[0])) {
                log.line(adb.shell("pm enable " + pkg).contains("enabled")
                        ? "✔ activado " + pkg : "! no se pudo activar " + pkg);
            }
        }
        File done = new File(backupFile.getPath() + ".restaurado");
        done.delete();
        backupFile.renameTo(done);
        refreshInstalled();
        log.line("Restauración terminada.");
    }

    /** Líneas "operación paquete" del registro de esta TV. */
    private List<String[]> backupEntries() throws IOException {
        List<String[]> out = new ArrayList<>();
        if (!backupFile.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(backupFile))) {
            for (String l; (l = r.readLine()) != null; ) {
                String[] parts = l.trim().split("\\s+");
                if (parts.length == 2) out.add(parts);
            }
        }
        return out;
    }

    private void record(String op, String pkg) throws IOException {
        try (FileOutputStream out = new FileOutputStream(backupFile, true)) {
            out.write((op + " " + pkg + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
