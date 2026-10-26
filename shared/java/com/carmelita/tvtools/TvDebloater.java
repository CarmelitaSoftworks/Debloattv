package com.carmelita.tvtools;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private static final String TMP_APK = "/data/local/tmp/debloattv.apk";

    /** Launcher alternativo de código abierto (GPL-3.0): https://github.com/leanbitlab-org/LtvLauncher */
    public static final String LTV_PKG = "com.leanbitlab.ltvL";
    private static final String LTV_URL =
            "https://github.com/leanbitlab-org/LtvLauncher/releases/latest/download/LTvLauncher-%s-release.apk";
    private static final int MAX_DOWNLOAD = 64 * 1024 * 1024;

    /** Launchers de stock conocidos; los de otras marcas se detectan por HOME en stockLaunchers(). */
    public static final List<String> STOCK_LAUNCHERS = Collections.unmodifiableList(Arrays.asList(
            "com.google.android.apps.tv.launcherx",    // Google TV
            "com.google.android.tvlauncher",           // Android TV 8+
            "com.google.android.leanbacklauncher",     // Android TV antiguo
            "com.mitv.tvhome"));                       // Xiaomi PatchWall

    /**
     * El asistente de Google TV. Responde a HOME, pero NUNCA se desactiva: guarda la red WiFi y
     * desactivarlo la borra (la TV se queda sin WiFi ni ADB). Versiones anteriores sí lo hacían,
     * así que se sigue reactivando al restaurar.
     */
    private static final String SETUPWRAITH = "com.google.android.tungsten.setupwraith";

    /** Paquetes que declaran HOME pero no son un launcher que se pueda desactivar. */
    private static final Set<String> NOT_LAUNCHERS = new HashSet<>(Arrays.asList(
            "android", "com.android.settings", "com.android.tv.settings", SETUPWRAITH, HALO_PKG, LTV_PKG));

    /** Servicio de accesibilidad de Halo que abre Halo cuando HOME muestra el launcher de stock. */
    private static final String HALO_HOME_SERVICE = HALO_PKG + "/" + HALO_PKG + ".HomeButtonService";

    /** Copia del registro dentro de la propia TV: sobrevive a desinstalar la app o cambiar de móvil. */
    private static final String TV_BACKUP = "/sdcard/DebloatTV/backup.txt";

    private static final Pattern COMPONENT = Pattern.compile("^\\s*([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+\\s*$");

    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.leanback.ime", "com.google.android.inputmethod.latin",
            "com.google.android.tv.remote.service", "com.google.android.tvsetup",
            "com.android.providers.settings", "com.android.providers.tv", "com.android.providers.media",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.shell", "com.android.bluetooth", "com.android.wifi.resources",
            "com.android.webview", "com.google.android.webview", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.google.android.tvsetup", SETUPWRAITH,
            // TCL: imprescindibles para TV, entradas HDMI, mando y menús (ver lists/tcl.txt)
            "com.tcl.tv", "com.tcl.tvinput", "com.tcl.suspension", "com.tcl.dashboard", "com.tcl.autopair",
            "com.tcl.systemserver", "com.tcl.providers.config", "com.tcl.globalkeyoverlay", "com.tcl.initsetup",
            HALO_PKG, LTV_PKG));

    /** Líneas "proteger" de las listas (p. ej. apps de los operadores); "pkg.*" protege un prefijo. */
    private static final Set<String> EXTRA_PROTECTED = new HashSet<>();

    static boolean isProtected(String pkg) {
        if (PROTECTED.contains(pkg)) return true;
        synchronized (EXTRA_PROTECTED) {
            for (String p : EXTRA_PROTECTED) {
                if (p.endsWith("*") ? pkg.startsWith(p.substring(0, p.length() - 1)) : p.equals(pkg)) return true;
            }
        }
        return false;
    }

    public static final String[] LEVELS = {"seguro", "recomendado", "agresivo"};

    public interface Log {
        void line(String text);
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
    /** Decodificador reconocido en lists/dispositivos.txt (o null), con su aviso. */
    public final Device device;
    private final File backupFile;
    private Set<String> installed;
    private Set<String> enabled;
    /** Se calcula fuera del hilo principal (lee la copia de la TV por ADB) y se consulta desde la UI. */
    private volatile boolean hasBackup;

    public TvDebloater(Context context, AdbConnection adb, Log log) throws IOException {
        this.context = context;
        this.adb = adb;
        this.log = log;
        manufacturer = adb.shell("getprop ro.product.manufacturer").toLowerCase(Locale.ROOT);
        model = adb.shell("getprop ro.product.model");
        String identity = manufacturer + " " + model + " " + adb.shell("getprop ro.product.device")
                + " " + adb.shell("getprop ro.product.name");
        final Set<String> system = packages("pm list packages -s");
        device = Device.detect(context, identity, system::contains);
        String serial = adb.shell("getprop ro.serialno");
        String id = (serial.isEmpty() ? model : serial).replaceAll("[^A-Za-z0-9]+", "-");
        backupFile = new File(context.getFilesDir(), "backup-" + id + ".txt");
        refreshInstalled();
        parseLists(context, manufacturer, device, Collections.<String>emptySet()); // carga las líneas "proteger"
        hasBackup = !backupEntries().isEmpty();
    }

    public static int levelRank(String level) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(level)) return i + 1;
        return 0;
    }

    private void refreshInstalled() throws IOException {
        installed = packages("pm list packages");
        enabled = packages("pm list packages -e");
    }

    private Set<String> packages(String command) throws IOException {
        Set<String> out = new HashSet<>();
        for (String line : adb.shell(command).split("\n")) {
            if (line.startsWith("package:")) out.add(line.substring(8).trim());
        }
        return out;
    }

    /**
     * Paquetes de las listas (Google + la marca detectada) que siguen activos en esta TV. Nunca
     * incluye la app que hace de inicio: quitarla dejaría la TV sin pantalla de inicio.
     */
    public List<Item> candidates() throws IOException {
        final Set<String> homes = new HashSet<>(stockLaunchers());
        Set<String> available = new HashSet<>(enabled);
        available.removeAll(homes);
        return parseLists(context, manufacturer, device, available);
    }

    /** Equipo reconocido y su aviso, para mostrarlo en la interfaz. */
    public static final class Device {
        public final String operator;
        public final String name;
        public final String note;
        /** Lista extra para este equipo (p. ej. wom.txt), o null. */
        final String list;

        Device(String operator, String name, String note, String list) {
            this.operator = operator;
            this.name = name;
            this.note = note;
            this.list = list;
        }

        @Override
        public String toString() {
            return operator + " · " + name;
        }

        interface SystemPackages {
            boolean contains(String pkg);
        }

        /** Busca el equipo en lists/dispositivos.txt por sus propiedades o por una app de sistema. */
        static Device detect(Context context, String identity, SystemPackages system) throws IOException {
            String text = new String(readAsset(context, "lists/dispositivos.txt"), StandardCharsets.UTF_8);
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\s*\\|\\s*", 5);
                if (f.length < 3) continue;
                boolean match = false;
                if (f[0].startsWith("sistema:")) {
                    match = system.contains(f[0].substring("sistema:".length()));
                } else {
                    for (String name : f[0].split(",")) {
                        String n = name.trim().toLowerCase(Locale.ROOT);
                        if (!n.isEmpty() && identity.toLowerCase(Locale.ROOT).contains(n)) match = true;
                    }
                }
                if (match) {
                    return new Device(f[1], f[2], f.length > 3 ? f[3] : "",
                            f.length > 4 && !f[4].trim().isEmpty() ? f[4].trim() : null);
                }
            }
            return null;
        }
    }

    /** El equipo de esta propia TV (modo manual de Halo, sin ADB). */
    public static Device detectThisDevice(Context context) throws IOException {
        final PackageManager pm = context.getPackageManager();
        String identity = Build.MANUFACTURER + " " + Build.MODEL + " " + Build.DEVICE + " " + Build.PRODUCT;
        return Device.detect(context, identity, pkg -> {
            try {
                return (pm.getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        });
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
        final Set<String> homes = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            homes.add(ri.activityInfo.packageName);
        }
        Set<String> available = new HashSet<>();
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.enabled && !homes.contains(ai.packageName)) available.add(ai.packageName);
        }
        return parseLists(context, Build.MANUFACTURER.toLowerCase(Locale.ROOT), detectThisDevice(context), available);
    }

    /**
     * Lee las listas que tocan a esta TV y devuelve lo que se puede quitar de entre "available"
     * (paquetes activos que no hacen de inicio). Un "*" final abarca todos los que empiezan así.
     */
    private static List<Item> parseLists(Context context, String manufacturer, Device device, Set<String> available)
            throws IOException {
        List<String> files = new ArrayList<>();
        files.add("google.txt");
        String vendor = vendorList(manufacturer);
        if (vendor != null) files.add(vendor);
        if (device != null && device.list != null) files.add(device.list);

        List<String[]> lines = new ArrayList<>();
        Set<String> protect = new HashSet<>();
        for (String file : files) {
            for (String raw : new String(readAsset(context, "lists/" + file), StandardCharsets.UTF_8).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 2) continue;
                if ("proteger".equals(parts[0])) protect.add(parts[1]);
                else lines.add(parts);
            }
        }
        synchronized (EXTRA_PROTECTED) {
            EXTRA_PROTECTED.addAll(protect);
        }

        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> sorted = new ArrayList<>(available);
        Collections.sort(sorted);
        for (String[] parts : lines) {
            if (levelRank(parts[0]) == 0) continue;
            String desc = parts.length > 2 ? parts[2] : parts[1];
            List<String> pkgs = new ArrayList<>();
            if (parts[1].endsWith("*")) {
                String prefix = parts[1].substring(0, parts[1].length() - 1);
                for (String pkg : sorted) if (pkg.startsWith(prefix)) pkgs.add(pkg);
            } else if (available.contains(parts[1])) {
                pkgs.add(parts[1]);
            }
            for (String pkg : pkgs) {
                if (isProtected(pkg) || !seen.add(pkg)) continue;
                out.add(new Item(parts[0], pkg, desc)); // la interfaz ya muestra el paquete debajo
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
            if (isProtected(pkg)) continue;
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
            installApk(readAsset(context, "halo-launcher.apk"), "Halo");
        } else {
            log.line("✔ Halo ya estaba instalado");
        }
        return setHome(HALO_PKG, "Halo", pressHome);
    }

    /** Descarga la última versión de LTvLauncher (código abierto) y la deja como inicio. */
    public boolean installLtvAndSetHome(boolean pressHome) throws IOException {
        if (!installed.contains(LTV_PKG)) {
            String abi = adb.shell("getprop ro.product.cpu.abi");
            String flavor = abi.startsWith("arm64") ? "arm64-v8a"
                    : abi.startsWith("armeabi") ? "armeabi-v7a" : "universal";
            log.line("Descargando LTvLauncher (" + flavor + ") de GitHub…");
            byte[] apk = download(String.format(LTV_URL, flavor));
            log.line("Instalando LTvLauncher (" + (apk.length / (1024 * 1024)) + " MB)…");
            installApk(apk, "LTvLauncher");
        } else {
            log.line("✔ LTvLauncher ya estaba instalado");
        }
        return setHome(LTV_PKG, "LTvLauncher", pressHome);
    }

    private void installApk(byte[] apk, String label) throws IOException {
        adb.push(apk, TMP_APK, 0100644);
        String result = adb.shell("pm install -r " + TMP_APK);
        adb.shell("rm -f " + TMP_APK);
        if (!result.contains("Success")) throw new IOException("No se pudo instalar " + label + ": " + result);
        log.line("✔ " + label + " instalado");
        refreshInstalled();
    }

    /**
     * Deja pkg como pantalla de inicio desactivando los launchers de stock. Si la TV no lo acepta
     * (algunas TCL con Google TV ignoran set-home-activity y, sin su launcher, HOME cae en una
     * pantalla de recuperación), se deshace todo y, para Halo, se activa su servicio de
     * accesibilidad, que abre Halo cada vez que HOME muestra el launcher de stock.
     */
    private boolean setHome(String pkg, String label, boolean pressHome) throws IOException {
        String component = homeComponentOf(pkg);
        if (component == null) throw new IOException(label + " no declara una pantalla de inicio");

        adb.shell("cmd package set-home-activity " + component);
        List<String[]> changed = new ArrayList<>();
        for (String stock : stockLaunchers()) {
            if (!installed.contains(stock)) continue;
            String out = adb.shell("pm disable-user --user 0 " + stock);
            if (out.contains("disabled")) {
                changed.add(new String[]{"home", stock});
                log.line("✔ desactivado " + stock);
                continue;
            }
            // Algunas TV no dejan desactivar el launcher pero sí quitarlo para el usuario
            String out2 = adb.shell("pm uninstall -k --user 0 " + stock);
            if (out2.contains("Success")) {
                changed.add(new String[]{"home-uninstall", stock});
                log.line("✔ quitado " + stock);
            } else {
                log.line("! no se pudo desactivar " + stock + ": " + firstLine(out) + " / " + firstLine(out2));
            }
        }
        // Otra vez, por si el sistema eligió otro inicio al desaparecer el de stock
        adb.shell("cmd package set-home-activity " + component);
        refreshInstalled();

        String home = currentHome();
        boolean ok = home.startsWith(pkg + "/");
        if (home.isEmpty()) {
            // Android antiguo sin "resolve-activity": no se puede comprobar, así que se deja hecho
            for (String[] c : changed) record(c[0], c[1]);
            log.line("? No se pudo comprobar qué abre Inicio: pulsa Inicio en el mando para verlo.");
            ok = true;
        } else if (ok) {
            for (String[] c : changed) record(c[0], c[1]);
            log.line("✔ El botón Inicio abre " + label + ".");
        } else {
            log.line("! Esta TV no deja cambiar la app de inicio (Inicio abre: " + home + ").");
            for (String[] c : changed) undo(c[0], c[1]);
            refreshInstalled();
            if (HALO_PKG.equals(pkg)) {
                enableHaloHomeService();
                log.line("✔ Activado «Botón Inicio → Halo»: al pulsar Inicio se abrirá Halo.");
            } else {
                log.line("  Activa en Ajustes → Accesibilidad el servicio de inicio de " + label + ".");
            }
        }
        if (pressHome) adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        return ok;
    }

    private void undo(String op, String pkg) throws IOException {
        String out = op.endsWith("uninstall")
                ? adb.shell("cmd package install-existing " + pkg)
                : adb.shell("pm enable " + pkg);
        log.line((out.contains("installed") || out.contains("enabled") ? "↺ recuperado " : "! no se pudo recuperar ") + pkg);
    }

    /** Activa por ADB el servicio de accesibilidad de Halo (sin pasar por los menús). */
    private void enableHaloHomeService() throws IOException {
        String current = adb.shell("settings get secure enabled_accessibility_services");
        if ("null".equals(current)) current = "";
        if (!current.contains(HALO_HOME_SERVICE)) {
            adb.shell("settings put secure enabled_accessibility_services "
                    + (current.isEmpty() ? HALO_HOME_SERVICE : current + ":" + HALO_HOME_SERVICE));
        }
        adb.shell("settings put secure accessibility_enabled 1");
        record("a11y", HALO_HOME_SERVICE);
    }

    private void disableHaloHomeService() throws IOException {
        String current = adb.shell("settings get secure enabled_accessibility_services");
        if (!current.contains(HALO_HOME_SERVICE)) return;
        List<String> keep = new ArrayList<>();
        for (String service : current.split(":")) {
            if (!service.isEmpty() && !service.equals(HALO_HOME_SERVICE)) keep.add(service);
        }
        adb.shell(keep.isEmpty() ? "settings delete secure enabled_accessibility_services"
                : "settings put secure enabled_accessibility_services " + android.text.TextUtils.join(":", keep));
        log.line("✔ desactivado «Botón Inicio → Halo»");
    }

    /** "paquete/.Actividad" que pkg declara para HOME, o null. */
    private String homeComponentOf(String pkg) throws IOException {
        String homes = adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        for (String line : homes.split("\n")) {
            Matcher m = COMPONENT.matcher(line);
            if (m.matches() && m.group(1).equals(pkg)) return line.trim();
        }
        // Android antiguo sin query-activities: Halo se conoce de antemano
        return HALO_PKG.equals(pkg) ? HALO_HOME : null;
    }

    static byte[] download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "DebloatTV");
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("Descarga fallida (HTTP " + conn.getResponseCode() + "): " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(conn.getContentLength(), 1 << 16));
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_DOWNLOAD) throw new IOException("Descarga demasiado grande: " + url);
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
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
        disableHaloHomeService();
        // Los conocidos más los que se quitaron desde aquí (un launcher desactivado ya no
        // aparece al consultar HOME, así que se leen del registro). setupwraith se incluye por si
        // lo desactivó una versión anterior.
        Set<String> launchers = new HashSet<>(STOCK_LAUNCHERS);
        launchers.add(SETUPWRAITH);
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
            if (!installed.contains(pkg) || enabled.contains(pkg)) continue;
            log.line(adb.shell("pm enable " + pkg).contains("enabled")
                    ? "✔ activado " + pkg : "! no se pudo activar " + pkg);
        }
        refreshInstalled();
        adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        log.line("Launcher original reactivado.");
    }

    public boolean hasBackup() {
        return hasBackup;
    }

    /** Reinstala o reactiva todo lo que se quitó desde esta app en esta TV. */
    public void restoreAll() throws IOException {
        List<String[]> entries = backupEntries();
        if (entries.isEmpty()) {
            log.line("No hay nada que restaurar en esta TV.");
            return;
        }
        for (String[] parts : entries) {
            String pkg = parts[1];
            if ("uninstall".equals(parts[0]) || "home-uninstall".equals(parts[0])) {
                String out = adb.shell("cmd package install-existing " + pkg);
                if (!out.contains("installed")) out = adb.shell("pm install-existing " + pkg);
                log.line(out.contains("installed") ? "✔ reinstalado " + pkg : "! " + pkg + ": " + out);
            } else if ("disable".equals(parts[0]) || "home".equals(parts[0])) {
                log.line(adb.shell("pm enable " + pkg).contains("enabled")
                        ? "✔ activado " + pkg : "! no se pudo activar " + pkg);
            } else if ("a11y".equals(parts[0])) {
                disableHaloHomeService();
            }
        }
        File done = new File(backupFile.getPath() + ".restaurado");
        done.delete();
        backupFile.renameTo(done);
        adb.shell("mv " + TV_BACKUP + " " + TV_BACKUP + ".restaurado");
        hasBackup = false;
        refreshInstalled();
        log.line("Restauración terminada.");
    }

    /**
     * Líneas "operación paquete" del registro de esta TV: el de esta app más la copia guardada en
     * la propia TV (la escriben también la otra app y los scripts), sin repetir.
     */
    private List<String[]> backupEntries() throws IOException {
        List<String> lines = new ArrayList<>();
        if (backupFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(backupFile))) {
                for (String l; (l = r.readLine()) != null; ) lines.add(l);
            }
        }
        String tvCopy = adb.shell("cat " + TV_BACKUP + " 2>/dev/null");
        lines.addAll(Arrays.asList(tvCopy.split("\n")));

        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String l : lines) {
            String[] parts = l.trim().split("\\s+");
            if (parts.length == 2 && !parts[0].startsWith("#") && seen.add(parts[0] + " " + parts[1])) out.add(parts);
        }
        return out;
    }

    private void record(String op, String pkg) throws IOException {
        try (FileOutputStream out = new FileOutputStream(backupFile, true)) {
            out.write((op + " " + pkg + "\n").getBytes(StandardCharsets.UTF_8));
        }
        adb.shell("mkdir -p /sdcard/DebloatTV && echo '" + op + " " + pkg + "' >> " + TV_BACKUP);
        hasBackup = true;
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
