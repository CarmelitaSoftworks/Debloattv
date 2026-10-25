package com.carmelita.debloattv;

import android.content.Context;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Las mismas reglas que debloater/debloat.sh, ejecutadas desde el teléfono. */
final class TvDebloater {
    static final String HALO_PKG = "com.carmelita.halo";
    private static final String HALO_HOME = HALO_PKG + "/.MainActivity";
    private static final String HALO_TMP = "/data/local/tmp/halo.apk";

    static final List<String> STOCK_LAUNCHERS = Arrays.asList(
            "com.google.android.apps.tv.launcherx",    // Google TV
            "com.google.android.tungsten.setupwraith", // Google TV: reabre el launcher de stock con HOME
            "com.google.android.tvlauncher",           // Android TV 8+
            "com.google.android.leanbacklauncher",     // Android TV antiguo
            "com.mitv.tvhome");                        // Xiaomi PatchWall

    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.leanback.ime", "com.google.android.inputmethod.latin",
            "com.google.android.tv.remote.service", "com.google.android.tvsetup",
            "com.android.providers.settings", "com.android.providers.tv", "com.android.providers.media",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.shell", "com.android.bluetooth", "com.android.wifi.resources",
            HALO_PKG));

    static final String[] LEVELS = {"seguro", "recomendado", "agresivo"};

    interface Log {
        void line(String text);
    }

    static final class Item {
        final String level;
        final String pkg;
        final String description;

        Item(String level, String pkg, String description) {
            this.level = level;
            this.pkg = pkg;
            this.description = description;
        }

        int rank() {
            return levelRank(level);
        }
    }

    private final Context context;
    private final AdbConnection adb;
    private final Log log;
    final String manufacturer;
    final String model;
    private final File backupFile;
    private Set<String> installed;

    TvDebloater(Context context, AdbConnection adb, Log log) throws IOException {
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

    static int levelRank(String level) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(level)) return i + 1;
        return 0;
    }

    private void refreshInstalled() throws IOException {
        installed = new HashSet<>();
        for (String line : adb.shell("pm list packages").split("\n")) {
            if (line.startsWith("package:")) installed.add(line.substring(8).trim());
        }
    }

    boolean isInstalled(String pkg) {
        return installed.contains(pkg);
    }

    /** Todos los paquetes de las listas (Google + la marca detectada) que hay en esta TV. */
    List<Item> candidates() throws IOException {
        List<String> files = new ArrayList<>();
        files.add("google.txt");
        String vendor = vendorList();
        if (vendor != null) files.add(vendor);

        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String file : files) {
            for (String raw : readAsset("lists/" + file).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 2 || levelRank(parts[0]) == 0) continue;
                String pkg = parts[1];
                if (!installed.contains(pkg) || PROTECTED.contains(pkg) || !seen.add(pkg)) continue;
                out.add(new Item(parts[0], pkg, parts.length > 2 ? parts[2] : pkg));
            }
        }
        return out;
    }

    String vendorList() {
        if (manufacturer.contains("sony")) return "sony.txt";
        if (manufacturer.contains("tcl")) return "tcl.txt";
        if (manufacturer.contains("xiaomi")) return "xiaomi.txt";
        if (manufacturer.contains("hisense")) return "hisense.txt";
        if (manufacturer.contains("philips") || manufacturer.contains("tpvision")
                || manufacturer.contains("tp vision")) return "philips.txt";
        return null;
    }

    /** pm uninstall --user 0 es lo más limpio; si el sistema no lo permite, se desactiva. */
    void remove(List<String> packages) throws IOException {
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

    void installHaloAndSetHome() throws IOException {
        if (!installed.contains(HALO_PKG)) {
            log.line("Copiando Halo a la TV…");
            adb.push(readAssetBytes("halo-launcher.apk"), HALO_TMP, 0100644);
            String result = adb.shell("pm install -r " + HALO_TMP);
            adb.shell("rm -f " + HALO_TMP);
            if (!result.contains("Success")) throw new IOException("No se pudo instalar Halo: " + result);
            log.line("✔ Halo instalado");
        } else {
            log.line("✔ Halo ya estaba instalado");
        }

        adb.shell("cmd package set-home-activity " + HALO_HOME);
        for (String pkg : STOCK_LAUNCHERS) {
            if (!installed.contains(pkg)) continue;
            if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled")) {
                record("disable", pkg);
                log.line("✔ desactivado " + pkg);
            } else {
                log.line("! no se pudo desactivar " + pkg);
            }
        }
        adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        refreshInstalled();
        log.line("Listo. Pulsa HOME en el mando de la TV.");
    }

    void restoreStockLauncher() throws IOException {
        for (String pkg : STOCK_LAUNCHERS) {
            if (!installed.contains(pkg)) continue;
            log.line(adb.shell("pm enable " + pkg).contains("enabled")
                    ? "✔ activado " + pkg : "! no se pudo activar " + pkg);
        }
        adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        log.line("Launcher original reactivado.");
    }

    boolean hasBackup() {
        return backupFile.exists() && backupFile.length() > 0;
    }

    /** Reinstala o reactiva todo lo que se quitó desde este teléfono en esta TV. */
    void restoreAll() throws IOException {
        if (!hasBackup()) {
            log.line("No hay nada que restaurar en esta TV.");
            return;
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(backupFile))) {
            for (String l; (l = r.readLine()) != null; ) lines.add(l);
        }
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2) continue;
            String pkg = parts[1];
            if ("uninstall".equals(parts[0])) {
                String out = adb.shell("cmd package install-existing " + pkg);
                if (!out.contains("installed")) out = adb.shell("pm install-existing " + pkg);
                log.line(out.contains("installed") ? "✔ reinstalado " + pkg : "! " + pkg + ": " + out);
            } else if ("disable".equals(parts[0])) {
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

    private void record(String op, String pkg) throws IOException {
        try (FileOutputStream out = new FileOutputStream(backupFile, true)) {
            out.write((op + " " + pkg + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readAsset(String name) throws IOException {
        return new String(readAssetBytes(name), StandardCharsets.UTF_8);
    }

    private byte[] readAssetBytes(String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
