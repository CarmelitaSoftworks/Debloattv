/*
 * Las mismas reglas que TvDebloater.java y debloater/debloat.sh, para la versión web.
 * Las listas (debloater/lists/*.txt) llegan como un objeto { "google.txt": "texto", ... }.
 */
(function (root) {
  'use strict';

  const HALO_PKG = 'com.carmelita.halo';
  const HALO_HOME = HALO_PKG + '/.MainActivity';
  const LTV_PKG = 'com.leanbitlab.ltvL';
  const TMP_APK = '/data/local/tmp/debloattv.apk';
  const STOCK_LAUNCHERS = [
    'com.google.android.apps.tv.launcherx', // Google TV
    'com.google.android.tvlauncher',        // Android TV 8+
    'com.google.android.leanbacklauncher',  // Android TV antiguo
    'com.mitv.tvhome',                      // Xiaomi PatchWall
  ];
  // Asistente de Google TV: responde a HOME pero NUNCA se desactiva (borra la red WiFi guardada)
  const SETUPWRAITH = 'com.google.android.tungsten.setupwraith';
  const NOT_LAUNCHERS = ['android', 'com.android.settings', 'com.android.tv.settings', SETUPWRAITH, HALO_PKG, LTV_PKG];
  const HALO_HOME_SERVICE = HALO_PKG + '/' + HALO_PKG + '.HomeButtonService';
  const TV_BACKUP = '/sdcard/DebloatTV/backup.txt';
  const COMPONENT = /^\s*([A-Za-z0-9_.]+)\/[A-Za-z0-9_.$]+\s*$/;
  const HOME_QUERY = '-a android.intent.action.MAIN -c android.intent.category.HOME';

  const PROTECTED = new Set([
    'android', 'com.android.systemui', 'com.android.settings', 'com.android.tv.settings',
    'com.android.vending', 'com.google.android.gms', 'com.google.android.gsf',
    'com.google.android.leanback.ime', 'com.google.android.inputmethod.latin',
    'com.google.android.tv.remote.service', 'com.google.android.tvsetup',
    'com.android.providers.settings', 'com.android.providers.tv', 'com.android.providers.media',
    'com.android.packageinstaller', 'com.google.android.packageinstaller',
    'com.android.shell', 'com.android.bluetooth', 'com.android.wifi.resources',
    'com.android.webview', 'com.google.android.webview', 'com.android.permissioncontroller',
    'com.google.android.permissioncontroller', SETUPWRAITH,
    // TCL: imprescindibles para TV, entradas HDMI, mando y menús (ver lists/tcl.txt)
    'com.tcl.tv', 'com.tcl.tvinput', 'com.tcl.suspension', 'com.tcl.dashboard', 'com.tcl.autopair',
    'com.tcl.systemserver', 'com.tcl.providers.config', 'com.tcl.globalkeyoverlay', 'com.tcl.initsetup',
    HALO_PKG, LTV_PKG,
  ]);

  const LEVELS = ['seguro', 'recomendado', 'agresivo'];
  const levelRank = (level) => LEVELS.indexOf(level) + 1;

  const VENDORS = [
    { name: 'Google / Android TV', file: null, match: [] },
    { name: 'TCL', file: 'tcl.txt', match: ['tcl'] },
    { name: 'Sony', file: 'sony.txt', match: ['sony'] },
    { name: 'Xiaomi', file: 'xiaomi.txt', match: ['xiaomi'] },
    { name: 'Hisense', file: 'hisense.txt', match: ['hisense'] },
    { name: 'Philips', file: 'philips.txt', match: ['philips', 'tpvision', 'tp vision'] },
  ];

  function vendorList(manufacturer) {
    for (const v of VENDORS) if (v.match.some((m) => manufacturer.includes(m))) return v.file;
    return null;
  }

  function lines(text) {
    return (text || '').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  }

  /** Perfiles de lists/operadores.txt: [{ name, file }]. */
  function operators(lists) {
    return lines(lists['operadores.txt']).map((l) => l.split(/\s*\|\s*/)).filter((f) => f.length >= 2)
      .map((f) => ({ name: f[0], file: f[1] }));
  }

  /** Equipo de lists/dispositivos.txt que coincide con las propiedades o con una app de sistema. */
  function detectDevice(lists, identity, systemPackages) {
    const id = identity.toLowerCase();
    for (const line of lines(lists['dispositivos.txt'])) {
      const f = line.split(/\s*\|\s*/);
      if (f.length < 3) continue;
      const match = f[0].startsWith('sistema:')
        ? systemPackages.has(f[0].slice('sistema:'.length))
        : f[0].split(',').some((n) => n.trim() && id.includes(n.trim().toLowerCase()));
      if (match) return { operator: f[1], name: f[2], note: f.slice(3).join(' | ') };
    }
    return null;
  }

  /** Líneas «nivel paquete descripción» de Google + marca + operador, y las «proteger». */
  function listEntries(lists, vendorFile, operator) {
    const files = ['google.txt'];
    if (vendorFile) files.push(vendorFile);
    if (operator) for (const o of operators(lists)) if (o.name.toLowerCase() === operator.toLowerCase()) files.push(o.file);
    const entries = [], protect = [];
    for (const file of files) {
      for (const line of lines(lists[file])) {
        const m = line.match(/^(\S+)\s+(\S+)\s*(.*)$/);
        if (!m) continue;
        if (m[1] === 'proteger') protect.push(m[2]);
        else if (levelRank(m[1])) entries.push({ level: m[1], pkg: m[2], description: m[3] || m[2], source: file });
      }
    }
    return { entries, protect };
  }

  function makeProtected(extra) {
    return (pkg) => PROTECTED.has(pkg)
      || extra.some((p) => (p.endsWith('*') ? pkg.startsWith(p.slice(0, -1)) : p === pkg));
  }

  // ---- Con la TV conectada ---------------------------------------------------------------

  class TvDebloater {
    /** store: {get(k), set(k, v)} para la copia local del registro (puede fallar o ser null). */
    constructor(adb, lists, log, store) {
      this.adb = adb;
      this.lists = lists;
      this.log = log || (() => {});
      this.store = store;
    }

    async init() {
      const sh = (c) => this.adb.shell(c);
      this.manufacturer = (await sh('getprop ro.product.manufacturer')).toLowerCase();
      this.model = await sh('getprop ro.product.model');
      const identity = [this.manufacturer, this.model, await sh('getprop ro.product.device'), await sh('getprop ro.product.name')].join(' ');
      this.system = await this.packages('pm list packages -s');
      this.device = detectDevice(this.lists, identity, this.system);
      const serial = await sh('getprop ro.serialno');
      this.backupKey = 'backup-' + (serial || this.model).replace(/[^A-Za-z0-9]+/g, '-');
      this.operator = this.device ? this.device.operator : null;
      this.android = await sh('getprop ro.build.version.release');
      await this.refreshInstalled();
      this.hasBackup = (await this.backupEntries()).length > 0;
      return this;
    }

    get vendorFile() {
      return vendorList(this.manufacturer);
    }

    async refreshInstalled() {
      this.installed = await this.packages('pm list packages');
      this.enabled = await this.packages('pm list packages -e');
    }

    async packages(command) {
      const out = new Set();
      for (const line of (await this.adb.shell(command)).split('\n')) {
        if (line.startsWith('package:')) out.add(line.slice(8).trim());
      }
      return out;
    }

    /** Lo que se puede quitar en esta TV: activo, en las listas, sin proteger y que no sea el inicio. */
    async candidates() {
      const homes = new Set(await this.stockLaunchers());
      const { entries, protect } = listEntries(this.lists, this.vendorFile, this.operator);
      this.isProtected = makeProtected(protect);
      const available = [...this.enabled].filter((p) => !homes.has(p)).sort();
      const out = [], seen = new Set();
      for (const e of entries) {
        const pkgs = e.pkg.endsWith('*')
          ? available.filter((p) => p.startsWith(e.pkg.slice(0, -1)))
          : available.includes(e.pkg) ? [e.pkg] : [];
        for (const pkg of pkgs) {
          if (this.isProtected(pkg) || seen.has(pkg)) continue;
          seen.add(pkg);
          out.push({ level: e.level, pkg, description: e.description });
        }
      }
      return out;
    }

    /**
     * Todo de una vez, como la app: quita lo del nivel recomendado del equipo y operador detectados
     * y deja Halo como inicio. getHalo() devuelve el APK de Halo si hace falta instalarlo (o null).
     */
    async autoClean(getHalo, progress) {
      progress('Buscando publicidad y apps de fábrica…');
      const pkgs = (await this.candidates()).filter((i) => levelRank(i.level) <= 2).map((i) => i.pkg);
      let removed = 0;
      if (pkgs.length) {
        progress('Quitando ' + pkgs.length + (pkgs.length === 1 ? ' app…' : ' apps…'));
        removed = await this.remove(pkgs);
      }
      if (!this.installed.has(HALO_PKG)) {
        const apk = await getHalo();
        if (!apk) return { removed, haloHome: false, viaService: false, needsHalo: true };
        progress('Instalando Halo en la TV…');
        await this.installApk(apk, 'Halo');
      }
      progress('Poniendo Halo como inicio…');
      const ok = await this.setHome(HALO_PKG, 'Halo', true);
      const service = !ok && (await this.adb.shell('settings get secure enabled_accessibility_services')).includes(HALO_HOME_SERVICE);
      return { removed, haloHome: ok || service, viaService: service, needsHalo: false };
    }

    /** pm uninstall --user 0 es lo más limpio; si el sistema no lo permite, se desactiva. */
    async remove(pkgs) {
      const isProtected = this.isProtected || makeProtected([]);
      let removed = 0;
      for (const pkg of pkgs) {
        if (isProtected(pkg)) continue;
        if ((await this.adb.shell('pm uninstall -k --user 0 ' + pkg)).includes('Success')) {
          await this.record('uninstall', pkg);
          this.log('✔ ' + pkg);
        } else if ((await this.adb.shell('pm disable-user --user 0 ' + pkg)).includes('disabled')) {
          await this.record('disable', pkg);
          this.log('✔ ' + pkg + ' (desactivado)');
        } else {
          this.log('! no se pudo quitar ' + pkg);
          continue;
        }
        removed++;
      }
      await this.refreshInstalled();
      return removed;
    }

    /** Launchers conocidos más cualquier app de sistema que responda a HOME. */
    async stockLaunchers() {
      const out = [...STOCK_LAUNCHERS];
      const system = await this.packages('pm list packages -s');
      for (const line of (await this.adb.shell('cmd package query-activities --brief ' + HOME_QUERY)).split('\n')) {
        const m = line.match(COMPONENT);
        if (m && system.has(m[1]) && !NOT_LAUNCHERS.includes(m[1]) && !out.includes(m[1])) out.push(m[1]);
      }
      return out;
    }

    /** Instala un APK (Uint8Array) con «pm install». */
    async installApk(apk, label) {
      this.log('Copiando ' + label + ' a la TV (' + (apk.length / 1048576).toFixed(1) + ' MB)…');
      await this.adb.push(apk, TMP_APK, 0o100644);
      const result = await this.adb.shell('pm install -r ' + TMP_APK);
      await this.adb.shell('rm -f ' + TMP_APK);
      if (!result.includes('Success')) throw new Error('No se pudo instalar ' + label + ': ' + result);
      this.log('✔ ' + label + ' instalado');
      await this.refreshInstalled();
    }

    async abiFlavor() {
      const abi = await this.adb.shell('getprop ro.product.cpu.abi');
      return abi.startsWith('arm64') ? 'arm64-v8a' : abi.startsWith('armeabi') ? 'armeabi-v7a' : 'universal';
    }

    /**
     * Deja pkg como pantalla de inicio desactivando los launchers de stock. Si la TV no lo acepta
     * (TCL con Google TV), se deshace todo y, para Halo, se activa su servicio de accesibilidad.
     */
    async setHome(pkg, label, pressHome) {
      const component = await this.homeComponentOf(pkg);
      if (!component) throw new Error(label + ' no declara una pantalla de inicio');
      await this.adb.shell('cmd package set-home-activity ' + component);
      const changed = [];
      for (const stock of await this.stockLaunchers()) {
        if (!this.installed.has(stock)) continue;
        const out = await this.adb.shell('pm disable-user --user 0 ' + stock);
        if (out.includes('disabled')) {
          changed.push(['home', stock]);
          this.log('✔ desactivado ' + stock);
          continue;
        }
        const out2 = await this.adb.shell('pm uninstall -k --user 0 ' + stock);
        if (out2.includes('Success')) {
          changed.push(['home-uninstall', stock]);
          this.log('✔ quitado ' + stock);
        } else {
          this.log('! no se pudo desactivar ' + stock + ': ' + out.split('\n')[0] + ' / ' + out2.split('\n')[0]);
        }
      }
      await this.adb.shell('cmd package set-home-activity ' + component);
      await this.refreshInstalled();

      const home = await this.currentHome();
      let ok = home.startsWith(pkg + '/');
      if (!home) {
        for (const [op, p] of changed) await this.record(op, p);
        this.log('? No se pudo comprobar qué abre Inicio: pulsa Inicio en el mando para verlo.');
        ok = true;
      } else if (ok) {
        for (const [op, p] of changed) await this.record(op, p);
        this.log('✔ El botón Inicio abre ' + label + '.');
      } else {
        this.log('! Esta TV no deja cambiar la app de inicio (Inicio abre: ' + home + ').');
        for (const [op, p] of changed) await this.undo(op, p);
        await this.refreshInstalled();
        if (pkg === HALO_PKG) {
          await this.enableHaloHomeService();
          this.log('✔ Activado «Botón Inicio → Halo»: al pulsar Inicio se abrirá Halo.');
        } else {
          this.log('  Activa en Ajustes → Accesibilidad el servicio de inicio de ' + label + '.');
        }
      }
      if (pressHome) await this.adb.shell('am start ' + HOME_QUERY);
      return ok;
    }

    async undo(op, pkg) {
      const out = op.endsWith('uninstall')
        ? await this.adb.shell('cmd package install-existing ' + pkg)
        : await this.adb.shell('pm enable ' + pkg);
      this.log((out.includes('installed') || out.includes('enabled') ? '↺ recuperado ' : '! no se pudo recuperar ') + pkg);
    }

    async enableHaloHomeService() {
      let current = await this.adb.shell('settings get secure enabled_accessibility_services');
      if (current === 'null') current = '';
      if (!current.includes(HALO_HOME_SERVICE)) {
        await this.adb.shell('settings put secure enabled_accessibility_services '
          + (current ? current + ':' + HALO_HOME_SERVICE : HALO_HOME_SERVICE));
      }
      await this.adb.shell('settings put secure accessibility_enabled 1');
      await this.record('a11y', HALO_HOME_SERVICE);
    }

    async disableHaloHomeService() {
      const current = await this.adb.shell('settings get secure enabled_accessibility_services');
      if (!current.includes(HALO_HOME_SERVICE)) return;
      const keep = current.split(':').filter((s) => s && s !== HALO_HOME_SERVICE);
      await this.adb.shell(keep.length ? 'settings put secure enabled_accessibility_services ' + keep.join(':')
        : 'settings delete secure enabled_accessibility_services');
      this.log('✔ desactivado «Botón Inicio → Halo»');
    }

    async homeComponentOf(pkg) {
      for (const line of (await this.adb.shell('cmd package query-activities --brief ' + HOME_QUERY)).split('\n')) {
        const m = line.match(COMPONENT);
        if (m && m[1] === pkg) return line.trim();
      }
      return pkg === HALO_PKG ? HALO_HOME : null; // Android antiguo sin query-activities
    }

    async currentHome() {
      let last = '';
      for (const line of (await this.adb.shell('cmd package resolve-activity --brief ' + HOME_QUERY)).split('\n')) {
        if (COMPONENT.test(line)) last = line.trim();
      }
      return last;
    }

    async restoreStockLauncher() {
      await this.disableHaloHomeService();
      const launchers = new Set([...STOCK_LAUNCHERS, SETUPWRAITH]);
      for (const [op, pkg] of await this.backupEntries()) {
        if (op === 'home') launchers.add(pkg);
        if (op === 'home-uninstall') await this.reinstall(pkg);
      }
      await this.refreshInstalled();
      for (const pkg of launchers) {
        if (!this.installed.has(pkg) || this.enabled.has(pkg)) continue;
        this.log((await this.adb.shell('pm enable ' + pkg)).includes('enabled') ? '✔ activado ' + pkg : '! no se pudo activar ' + pkg);
      }
      await this.refreshInstalled();
      await this.adb.shell('am start ' + HOME_QUERY);
      this.log('Launcher original reactivado.');
    }

    async reinstall(pkg) {
      let out = await this.adb.shell('cmd package install-existing ' + pkg);
      if (!out.includes('installed')) out = await this.adb.shell('pm install-existing ' + pkg);
      this.log(out.includes('installed') ? '✔ reinstalado ' + pkg : '! ' + pkg + ': ' + out);
    }

    /** Reinstala o reactiva todo lo que se quitó en esta TV (desde la web, la app o los scripts). */
    async restoreAll() {
      const entries = await this.backupEntries();
      if (!entries.length) {
        this.log('No hay nada que restaurar en esta TV.');
        return;
      }
      for (const [op, pkg] of entries) {
        if (op === 'uninstall' || op === 'home-uninstall') await this.reinstall(pkg);
        else if (op === 'disable' || op === 'home') {
          this.log((await this.adb.shell('pm enable ' + pkg)).includes('enabled') ? '✔ activado ' + pkg : '! no se pudo activar ' + pkg);
        } else if (op === 'a11y') await this.disableHaloHomeService();
      }
      this.storeSet(this.backupKey, '');
      await this.adb.shell('mv ' + TV_BACKUP + ' ' + TV_BACKUP + '.restaurado');
      this.hasBackup = false;
      await this.refreshInstalled();
      this.log('Restauración terminada.');
    }

    /** Registro de esta TV: la copia del navegador más la guardada en la propia TV, sin repetir. */
    async backupEntries() {
      const text = (this.storeGet(this.backupKey) || '') + '\n' + await this.adb.shell('cat ' + TV_BACKUP + ' 2>/dev/null');
      const out = [], seen = new Set();
      for (const l of text.split('\n')) {
        const parts = l.trim().split(/\s+/);
        if (parts.length === 2 && !parts[0].startsWith('#') && !seen.has(parts.join(' '))) {
          seen.add(parts.join(' '));
          out.push(parts);
        }
      }
      return out;
    }

    async record(op, pkg) {
      this.storeSet(this.backupKey, (this.storeGet(this.backupKey) || '') + op + ' ' + pkg + '\n');
      await this.adb.shell("mkdir -p /sdcard/DebloatTV && echo '" + op + ' ' + pkg + "' >> " + TV_BACKUP);
      this.hasBackup = true;
    }

    storeGet(k) {
      try { return this.store ? this.store.get(k) : null; } catch (e) { return null; }
    }

    storeSet(k, v) {
      try { if (this.store) this.store.set(k, v); } catch (e) { /* solo queda la copia de la TV */ }
    }
  }

  // ---- Sin cable: generador de comandos ---------------------------------------------------

  /** Entradas de las listas para una marca/operador y nivel (sin TV no se pueden expandir los «*»). */
  function plannedEntries(lists, vendorFile, operator, level) {
    const { entries, protect } = listEntries(lists, vendorFile, operator);
    const isProtected = makeProtected(protect);
    const seen = new Set();
    return entries.filter((e) => levelRank(e.level) <= levelRank(level) && !isProtected(e.pkg)
      && !seen.has(e.pkg) && seen.add(e.pkg));
  }

  function shQuote(s) {
    return "'" + s.replace(/'/g, "'\\''") + "'";
  }

  /**
   * Script para pegar en «adb shell». Hace lo mismo que la app: nunca quita la app de inicio ni
   * nada protegido, desinstala para el usuario 0 (o desactiva) y anota todo en la TV para poder
   * restaurarlo luego desde la app, los scripts o esta web.
   */
  function removalScript(pkgs) {
    return [
      '# DebloatTV: pégalo en «adb shell» (o guárdalo y ejecútalo con «adb shell sh archivo.sh»)',
      ...quitarFunction(),
      ...pkgs.map(quitarLine),
      'echo "Listo. Para deshacerlo, usa «Restaurar todo» en la app o en la web."',
    ].join('\n');
  }

  function quitarLine(p) {
    return 'quitar ' + shQuote(p.endsWith('*') ? p.slice(0, -1).replace(/\./g, '\\.') + '.*' : p.replace(/\./g, '\\.'));
  }

  /** Función «quitar PATRÓN» del script: nunca toca la app de inicio ni lo protegido y lo anota todo en la TV. */
  function quitarFunction() {
    const protectedList = [...PROTECTED].join(' ');
    return [
      'mkdir -p /sdcard/DebloatTV',
      'HOMES=$(cmd package query-activities --brief ' + HOME_QUERY + ' 2>/dev/null | sed -n "s#^ *\\([A-Za-z0-9_.]*\\)/.*#\\1#p" | tr "\\n" " ")',
      'HOMES="$HOMES ' + STOCK_LAUNCHERS.join(' ') + '"',
      'PROTEGIDOS=' + shQuote(protectedList),
      'quitar() {',
      '  for p in $(pm list packages -e | sed "s/^package://" | grep -x "$1"); do',
      '    case " $HOMES $PROTEGIDOS " in *" $p "*) echo "= $p (se deja)"; continue;; esac',
      '    if pm uninstall -k --user 0 "$p" | grep -q Success; then echo "uninstall $p" >> ' + TV_BACKUP + '; echo "✔ $p"',
      '    elif pm disable-user --user 0 "$p" | grep -q disabled; then echo "disable $p" >> ' + TV_BACKUP + '; echo "✔ $p (desactivado)"',
      '    else echo "! no se pudo quitar $p"; fi',
      '  done',
      '}',
    ];
  }

  /**
   * Script automático para «adb shell»: reconoce en la propia TV la marca y el decodificador
   * (como la app) y quita lo del nivel recomendado de Google, la marca y el operador.
   */
  function autoScript(lists) {
    const stem = (f) => f.replace(/\.txt$/, '').replace(/[^A-Za-z0-9]/g, '_');
    const out = ['# DebloatTV automático: pégalo en «adb shell». Reconoce la TV solo y quita la publicidad.'];
    out.push(...quitarFunction());
    // Una función por lista con lo que se quita en el nivel recomendado
    const files = ['google.txt', ...VENDORS.filter((v) => v.file).map((v) => v.file), ...operators(lists).map((o) => o.file)];
    for (const file of [...new Set(files)]) {
      const pkgs = [];
      for (const line of lines(lists[file])) {
        const m = line.match(/^(\S+)\s+(\S+)/);
        if (m && levelRank(m[1]) && levelRank(m[1]) <= 2 && !PROTECTED.has(m[2])) pkgs.push(m[2]);
      }
      out.push('lista_' + stem(file) + '() {', '  :', ...pkgs.map((p) => '  ' + quitarLine(p)), '}');
    }
    out.push(
      'MARCA=$(getprop ro.product.manufacturer | tr "A-Z" "a-z")',
      'ID=$(echo "$MARCA $(getprop ro.product.model) $(getprop ro.product.device) $(getprop ro.product.name)" | tr "A-Z" "a-z")',
      'SISTEMA=$(pm list packages -s)',
      'V=""; OP=""; EQUIPO=""');
    out.push('case "$MARCA" in');
    for (const v of VENDORS) if (v.file) out.push('  ' + v.match.map((m) => '*' + shQuote(m) + '*').join('|') + ') V=' + stem(v.file) + ';;');
    out.push('esac');
    const opFile = Object.fromEntries(operators(lists).map((o) => [o.name.toLowerCase(), stem(o.file)]));
    for (const line of lines(lists['dispositivos.txt'])) {
      const f = line.split(/\s*\|\s*/);
      const op = f.length >= 3 && opFile[f[1].toLowerCase()];
      if (!op) continue;
      const test = f[0].startsWith('sistema:')
        ? 'echo "$SISTEMA" | grep -qx ' + shQuote('package:' + f[0].slice(8))
        : 'case "$ID" in ' + f[0].split(',').map((n) => '*' + shQuote(n.trim().toLowerCase()) + '*').join('|') + ') true;; *) false;; esac';
      out.push('[ -z "$OP" ] && ' + test + ' && { OP=' + op + '; EQUIPO=' + shQuote(f[1] + ' · ' + f[2]) + '; }');
    }
    out.push(
      'echo "TV: $ID"',
      '[ -n "$EQUIPO" ] && echo "Equipo reconocido: $EQUIPO"',
      'lista_google',
      '[ -n "$V" ] && lista_$V',
      '[ -n "$OP" ] && lista_$OP',
      'echo "Listo. Para deshacerlo, usa «Deshacer todo» en la app o pega el script de restaurar."');
    return out.join('\n');
  }

  /** Script para «adb shell» que deshace lo anotado en /sdcard/DebloatTV/backup.txt. */
  function restoreScript() {
    return [
      '# DebloatTV: restaurar todo lo anotado en la TV. Pégalo en «adb shell».',
      'while read op p; do',
      '  case "$op" in',
      '    uninstall|home-uninstall) cmd package install-existing "$p" >/dev/null 2>&1 || pm install-existing "$p"; echo "✔ reinstalado $p";;',
      '    disable|home) pm enable "$p" >/dev/null && echo "✔ activado $p";;',
      '    a11y) v=$(settings get secure enabled_accessibility_services | tr : "\\n" | grep -vx -e "$p" -e null | paste -sd: -)',
      '          if [ -n "$v" ]; then settings put secure enabled_accessibility_services "$v"; else settings delete secure enabled_accessibility_services; fi;;',
      '  esac',
      'done < ' + TV_BACKUP,
      'pm enable ' + SETUPWRAITH + ' >/dev/null 2>&1',
      'mv ' + TV_BACKUP + ' ' + TV_BACKUP + '.restaurado',
      'am start ' + HOME_QUERY,
    ].join('\n');
  }

  const api = {
    TvDebloater, LEVELS, VENDORS, HALO_PKG, LTV_PKG, STOCK_LAUNCHERS, SETUPWRAITH,
    levelRank, operators, detectDevice, vendorList, plannedEntries, removalScript, restoreScript, autoScript,
    LTV_URL: 'https://github.com/leanbitlab-org/LtvLauncher/releases/latest/download/LTvLauncher-%s-release.apk',
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Debloat = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
