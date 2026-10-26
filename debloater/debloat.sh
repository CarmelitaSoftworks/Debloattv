#!/usr/bin/env bash
# DebloatTV — limpia una Smart TV Android/Google TV por ADB y cambia el launcher por Halo.
# Todo es reversible: los paquetes se quitan solo para el usuario 0 y se registran en backups/
# y en una copia dentro de la propia TV (/sdcard/DebloatTV/backup.txt), compartida con las apps.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LISTS="$HERE/lists"
BACKUPS="$HERE/backups"
HALO_PKG="com.carmelita.halo"
HALO_HOME_SERVICE="$HALO_PKG/$HALO_PKG.HomeButtonService"
LTV_PKG="com.leanbitlab.ltvL"
LTV_URL="https://github.com/leanbitlab-org/LtvLauncher/releases/latest/download/LTvLauncher-%s-release.apk"
TV_BACKUP="/sdcard/DebloatTV/backup.txt"

# Launchers de stock conocidos; los de otras marcas se detectan por HOME (ver home_launchers)
STOCK_LAUNCHERS=(
  com.google.android.apps.tv.launcherx      # Google TV
  com.google.android.tvlauncher             # Android TV 8+
  com.google.android.leanbacklauncher       # Android TV antiguo
  com.mitv.tvhome                           # Xiaomi PatchWall
)
# El asistente de Google TV responde a HOME pero NUNCA se desactiva: guarda la red WiFi y
# desactivarlo la borra (la TV se queda sin WiFi ni ADB). Solo se reactiva al restaurar.
SETUPWRAITH=com.google.android.tungsten.setupwraith
NOT_LAUNCHERS=(android com.android.settings com.android.tv.settings "$SETUPWRAITH" "$HALO_PKG" "$LTV_PKG")

# Nunca se tocan, aunque aparezcan en una lista personalizada
PROTECTED=(
  android com.android.systemui com.android.settings com.android.tv.settings
  com.android.vending com.google.android.gms com.google.android.gsf
  com.google.android.leanback.ime com.google.android.inputmethod.latin
  com.google.android.tv.remote.service com.google.android.tvsetup "$SETUPWRAITH"
  com.android.providers.settings com.android.providers.tv com.android.providers.media
  com.android.packageinstaller com.google.android.packageinstaller
  com.android.shell com.android.bluetooth com.android.wifi.resources
  com.android.webview com.google.android.webview
  com.android.permissioncontroller com.google.android.permissioncontroller
  # TCL: imprescindibles para TV, entradas HDMI, mando y menús (ver lists/tcl.txt)
  com.tcl.tv com.tcl.tvinput com.tcl.suspension com.tcl.dashboard com.tcl.autopair
  com.tcl.systemserver com.tcl.providers.config com.tcl.globalkeyoverlay com.tcl.initsetup
  "$HALO_PKG" "$LTV_PKG"
)

LEVEL="recomendado"
ACTION="debloat"
DRY_RUN=0
ASSUME_YES=0
IP=""
SERIAL=""
APK=""
RESTORE_FILE=""
EXTRA_LISTS=()

c_red=$'\e[31m'; c_grn=$'\e[32m'; c_ylw=$'\e[33m'; c_dim=$'\e[2m'; c_bld=$'\e[1m'; c_off=$'\e[0m'
[[ -t 1 ]] || { c_red=""; c_grn=""; c_ylw=""; c_dim=""; c_bld=""; c_off=""; }
info() { echo "${c_bld}==>${c_off} $*"; }
ok()   { echo "  ${c_grn}✔${c_off} $*"; }
warn() { echo "  ${c_ylw}!${c_off} $*"; }
die()  { echo "${c_red}error:${c_off} $*" >&2; exit 1; }

usage() {
  cat <<EOF
Uso: $(basename "$0") [opciones] [acción]

Acciones:
  (ninguna)               Debloat según el nivel elegido (por defecto: recomendado)
  --launcher [APK|ltv]    Instala Halo (o LTvLauncher con "ltv") y lo deja como inicio
  --restore-launcher      Vuelve al launcher de stock
  --restore ARCHIVO|tv    Reinstala/reactiva lo registrado en un backup ("tv": la copia de la TV)
  --scan                  Lista apps del sistema y del fabricante para revisarlas a mano

Opciones:
  -i, --ip IP[:PUERTO]    Conecta por red (basta con "Depuración USB" activada en la TV)
  -s, --serial SERIAL     Usa un dispositivo concreto si hay varios
  -l, --level NIVEL       seguro | recomendado | agresivo
      --list ARCHIVO      Añade una lista propia (mismo formato que lists/*.txt)
  -n, --dry-run           Muestra lo que haría sin cambiar nada
  -y, --yes               No pedir confirmación
  -h, --help              Esta ayuda

Ejemplos:
  $(basename "$0") -i 192.168.1.50 -n                 # ver qué se quitaría
  $(basename "$0") -i 192.168.1.50 -l recomendado     # debloat
  $(basename "$0") -i 192.168.1.50 --launcher         # poner Halo como inicio
  $(basename "$0") -i 192.168.1.50 --launcher ltv     # poner LTvLauncher (código abierto)
  $(basename "$0") -i 192.168.1.50 --restore tv       # deshacer todo lo registrado en la TV
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i|--ip) IP="$2"; shift 2 ;;
    -s|--serial) SERIAL="$2"; shift 2 ;;
    -l|--level) LEVEL="$2"; shift 2 ;;
    --list) EXTRA_LISTS+=("$2"); shift 2 ;;
    -n|--dry-run) DRY_RUN=1; shift ;;
    -y|--yes) ASSUME_YES=1; shift ;;
    --launcher)
      ACTION="launcher"; shift
      if [[ $# -gt 0 && "$1" != -* ]]; then APK="$1"; shift; fi ;;
    --restore-launcher) ACTION="restore-launcher"; shift ;;
    --restore) ACTION="restore"; RESTORE_FILE="$2"; shift 2 ;;
    --scan) ACTION="scan"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "opción desconocida: $1" ;;
  esac
done

level_rank() {
  case "$1" in
    seguro) echo 1 ;; recomendado) echo 2 ;; agresivo) echo 3 ;;
    *) echo 0 ;;
  esac
}
MAX_RANK="$(level_rank "$LEVEL")"
[[ "$MAX_RANK" -gt 0 ]] || die "nivel no válido: $LEVEL (usa seguro, recomendado o agresivo)"

command -v adb >/dev/null || die "no se encontró adb. Instala Android platform-tools: https://developer.android.com/tools/releases/platform-tools"

ADB=(adb)
if [[ -n "$IP" ]]; then
  [[ "$IP" == *:* ]] || IP="$IP:5555"
  info "Conectando a $IP (acepta el aviso de depuración en la TV si aparece)"
  adb connect "$IP" >/dev/null || true
  SERIAL="$IP"
fi
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")

# adb shell devuelve el código del comando remoto; los fallos se juzgan por la salida
sh_tv() { "${ADB[@]}" shell "$@" 2>&1 | tr -d '\r' || true; }

"${ADB[@]}" wait-for-device >/dev/null 2>&1 &
wait_pid=$!
for _ in $(seq 1 15); do kill -0 "$wait_pid" 2>/dev/null || break; sleep 1; done
if kill -0 "$wait_pid" 2>/dev/null; then
  kill "$wait_pid" 2>/dev/null || true
  die "no hay ningún dispositivo conectado. Activa la depuración por red/USB en la TV."
fi

MANUFACTURER="$(sh_tv getprop ro.product.manufacturer | tr '[:upper:]' '[:lower:]')"
MODEL="$(sh_tv getprop ro.product.model)"
SDK="$(sh_tv getprop ro.build.version.sdk)"
info "Dispositivo: ${c_bld}$MODEL${c_off} ($MANUFACTURER, API $SDK)"

# Decodificadores conocidos (lists/dispositivos.txt): por propiedades o por una app de sistema
DEVICE_LIST=""
trim() { local t="$1"; t="${t#"${t%%[![:space:]]*}"}"; printf '%s' "${t%"${t##*[![:space:]]}"}"; }
detect_device() {
  local identity system pattern operator name note list
  identity="$MANUFACTURER $MODEL $(sh_tv getprop ro.product.device) $(sh_tv getprop ro.product.name)"
  system="$(sh_tv pm list packages -s | sed 's/^package://')"
  while IFS='|' read -r pattern operator name note list; do
    pattern="$(trim "$pattern")"
    [[ -z "$pattern" || "$pattern" == \#* ]] && continue
    if [[ "$pattern" == sistema:* ]]; then
      grep -qxF "${pattern#sistema:}" <<<"$system" || continue
    else
      local hit=0 n
      IFS=',' read -ra names <<<"$pattern"
      for n in "${names[@]}"; do n="$(trim "$n")"; [[ -n "$n" ]] && grep -qiF "$n" <<<"$identity" && hit=1; done
      [[ "$hit" -eq 1 ]] || continue
    fi
    info "Equipo reconocido: ${c_bld}$(trim "$operator") · $(trim "$name")${c_off}"
    note="$(trim "${note:-}")"
    [[ -n "$note" ]] && warn "$note"
    DEVICE_LIST="$(trim "${list:-}")"
    return 0
  done <"$LISTS/dispositivos.txt"
}
detect_device

refresh_packages() {
  INSTALLED="$(sh_tv pm list packages | sed 's/^package://' | sort -u)"
  ENABLED="$(sh_tv pm list packages -e | sed 's/^package://' | sort -u)"
}
refresh_packages
is_installed() { grep -qxF "$1" <<<"$INSTALLED"; }
is_enabled() { grep -qxF "$1" <<<"$ENABLED"; }
in_list() { local x="$1" p; shift; for p in "$@"; do [[ "$p" == "$x" ]] && return 0; done; return 1; }
# Líneas "proteger" de las listas (apps de los operadores...); "paquete.*" protege un prefijo
PROTECT_LINES="$(cat "$LISTS"/*.txt "${EXTRA_LISTS[@]+"${EXTRA_LISTS[@]}"}" | awk '$1 == "proteger" {print $2}')"
is_protected() {
  local p
  in_list "$1" "${PROTECTED[@]}" && return 0
  while read -r p; do
    [[ -z "$p" ]] && continue
    if [[ "$p" == *'*' ]]; then [[ "$1" == "${p%\*}"* ]] && return 0; else [[ "$1" == "$p" ]] && return 0; fi
  done <<<"$PROTECT_LINES"
  return 1
}

# "paquete/.Actividad" de cada app que responde a HOME
home_components() {
  sh_tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    | grep -E '^ *[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+ *$' | tr -d ' ' || true
}

# Launchers de stock de esta TV: los conocidos más las apps del sistema que responden a HOME
home_launchers() {
  local system p
  system="$(sh_tv pm list packages -s | sed 's/^package://')"
  {
    printf '%s\n' "${STOCK_LAUNCHERS[@]}"
    home_components | cut -d/ -f1 | while read -r p; do
      in_list "$p" "${NOT_LAUNCHERS[@]}" && continue
      grep -qxF "$p" <<<"$system" && echo "$p"
    done
  } | sort -u | while read -r p; do is_installed "$p" && echo "$p"; done
}

current_home() {
  sh_tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    | grep -E '^ *[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+ *$' | tail -1 | tr -d ' ' || true
}

confirm() {
  [[ "$ASSUME_YES" -eq 1 || "$DRY_RUN" -eq 1 ]] && return 0
  read -r -p "$1 [s/N] " ans
  [[ "$ans" =~ ^[sSyY]$ ]]
}

new_backup() {
  mkdir -p "$BACKUPS"
  local slug
  slug="$(tr -cs '[:alnum:]' '-' <<<"$MODEL" | tr '[:upper:]' '[:lower:]' | sed 's/^-*//;s/-*$//')"
  BACKUP_FILE="$BACKUPS/${slug:-tv}-$(date +%Y%m%d-%H%M%S).txt"
  [[ "$DRY_RUN" -eq 1 ]] && return 0
  printf '# DebloatTV backup — %s (%s) — %s\n' "$MODEL" "$MANUFACTURER" "$(date)" >"$BACKUP_FILE"
}
# Se guarda en el PC y en la TV, para poder deshacerlo desde cualquier herramienta
record() {
  [[ "$DRY_RUN" -eq 1 ]] && return 0
  echo "$1 $2" >>"$BACKUP_FILE"
  sh_tv "mkdir -p /sdcard/DebloatTV && echo '$1 $2' >> $TV_BACKUP" >/dev/null
}

# pm uninstall --user 0 es lo más limpio; si el sistema no lo permite, se desactiva.
remove_pkg() {
  local pkg="$1" out
  if [[ "$DRY_RUN" -eq 1 ]]; then ok "${c_dim}(simulado)${c_off} $pkg"; return; fi
  out="$(sh_tv pm uninstall -k --user 0 "$pkg")"
  if [[ "$out" == *Success* ]]; then
    record uninstall "$pkg"; ok "$pkg"; return
  fi
  out="$(sh_tv pm disable-user --user 0 "$pkg")"
  if [[ "$out" == *disabled* ]]; then
    record disable "$pkg"; ok "$pkg ${c_dim}(desactivado)${c_off}"
  else
    warn "$pkg: $out"
  fi
}

vendor_list() {
  case "$MANUFACTURER" in
    *sony*) echo sony.txt ;;
    *tcl*) echo tcl.txt ;;
    *xiaomi*) echo xiaomi.txt ;;
    *hisense*) echo hisense.txt ;;
    *philips*|*tpvision*|*tp\ vision*) echo philips.txt ;;
  esac
}

do_debloat() {
  local files=("$LISTS/google.txt") vendor homes
  vendor="$(vendor_list)"
  [[ -n "$vendor" ]] && files+=("$LISTS/$vendor")
  [[ -n "$DEVICE_LIST" ]] && files+=("$LISTS/$DEVICE_LIST")
  files+=("${EXTRA_LISTS[@]+"${EXTRA_LISTS[@]}"}")
  # La app que hace de inicio nunca se quita: la TV se quedaría sin pantalla de inicio
  homes="$(home_launchers; home_components | cut -d/ -f1)"

  local targets=() descs=() f lvl entry pkg desc
  for f in "${files[@]}"; do
    [[ -f "$f" ]] || die "no existe la lista $f"
    while read -r lvl entry desc; do
      [[ -z "$lvl" || "$lvl" == \#* ]] && continue
      [[ "$(level_rank "$lvl")" -ge 1 && "$(level_rank "$lvl")" -le "$MAX_RANK" ]] || continue
      # Un "*" final abarca todos los paquetes activos que empiezan así
      while read -r pkg; do
        [[ -z "$pkg" ]] && continue
        in_list "$pkg" "${targets[@]+"${targets[@]}"}" && continue
        if is_protected "$pkg"; then warn "se ignora $pkg (protegido)"; continue; fi
        if grep -qxF "$pkg" <<<"$homes"; then warn "$pkg es la pantalla de inicio: cámbiala con --launcher"; continue; fi
        targets+=("$pkg"); descs+=("$lvl: $desc")
      done < <(if [[ "$entry" == *'*' ]]; then grep -F "${entry%\*}" <<<"$ENABLED" | grep "^${entry%\*}" || true
               else is_enabled "$entry" && echo "$entry"; fi)
    done <"$f"
  done

  if [[ ${#targets[@]} -eq 0 ]]; then
    info "Nada que quitar en el nivel '$LEVEL'. Tu TV ya está limpia."
    return
  fi

  info "Se quitarán ${#targets[@]} apps (nivel ${c_bld}$LEVEL${c_off}):"
  local i
  for i in "${!targets[@]}"; do
    printf '  %-52s %s\n' "${targets[$i]}" "${c_dim}${descs[$i]}${c_off}"
  done
  confirm "¿Continuar?" || { info "Cancelado."; return; }

  new_backup
  for pkg in "${targets[@]}"; do remove_pkg "$pkg"; done
  [[ "$DRY_RUN" -eq 1 ]] || info "Hecho. Para deshacerlo: $(basename "$0") --restore tv"
}

find_apk() {
  [[ -n "$APK" ]] && { echo "$APK"; return; }
  local c
  for c in "$HERE/../dist/halo-launcher.apk" \
           "$HERE"/../launcher/app/build/outputs/apk/release/*.apk \
           "$HERE"/../launcher/app/build/outputs/apk/debug/*.apk; do
    [[ -f "$c" ]] && { echo "$c"; return; }
  done
}

install_apk() {
  local apk="$1" label="$2" out
  info "Instalando $label"
  [[ "$DRY_RUN" -eq 1 ]] && return 0
  out="$("${ADB[@]}" install -r "$apk" 2>&1 | tr -d '\r' || true)"
  # Sin el launcher instalado no se puede tocar el de stock: la TV se quedaría sin inicio
  [[ "$out" == *Success* ]] || die "no se pudo instalar $label: $(tail -1 <<<"$out")"
  ok "$label instalado"
  refresh_packages
}

enable_halo_service() {
  local current
  current="$(sh_tv settings get secure enabled_accessibility_services)"
  [[ "$current" == null ]] && current=""
  if [[ "$current" != *"$HALO_HOME_SERVICE"* ]]; then
    sh_tv settings put secure enabled_accessibility_services "${current:+$current:}$HALO_HOME_SERVICE" >/dev/null
  fi
  sh_tv settings put secure accessibility_enabled 1 >/dev/null
  record a11y "$HALO_HOME_SERVICE"
}

disable_halo_service() {
  local current keep
  current="$(sh_tv settings get secure enabled_accessibility_services)"
  [[ "$current" == *"$HALO_HOME_SERVICE"* ]] || return 0
  keep="$(tr ':' '\n' <<<"$current" | grep -vxF "$HALO_HOME_SERVICE" | grep -v '^$' | paste -sd: - || true)"
  if [[ -n "$keep" ]]; then
    sh_tv settings put secure enabled_accessibility_services "$keep" >/dev/null
  else
    sh_tv settings delete secure enabled_accessibility_services >/dev/null
  fi
  ok "desactivado «Botón Inicio → Halo»"
}

# Deja PKG como inicio. Si la TV no lo acepta (algunas TCL con Google TV ignoran
# set-home-activity y, sin su launcher, HOME cae en una pantalla de recuperación), se deshace
# todo y, para Halo, se activa su servicio de accesibilidad, que abre Halo al pulsar HOME.
set_home() {
  local pkg="$1" label="$2" component stock=() changed=() p
  component="$(home_components | grep -m1 "^$pkg/" || true)"
  [[ -n "$component" || "$DRY_RUN" -eq 1 ]] || die "$label no declara una pantalla de inicio"

  while read -r p; do [[ -n "$p" ]] && stock+=("$p"); done < <(home_launchers)
  info "Se desactivará el launcher de stock: ${stock[*]:-ninguno}"
  confirm "¿Continuar?" || { info "Cancelado."; return; }
  new_backup
  if [[ "$DRY_RUN" -eq 1 ]]; then
    for p in "${stock[@]+"${stock[@]}"}"; do ok "${c_dim}(simulado)${c_off} desactivar $p"; done
    return
  fi

  sh_tv cmd package set-home-activity "$component" >/dev/null
  for p in "${stock[@]+"${stock[@]}"}"; do
    if [[ "$(sh_tv pm disable-user --user 0 "$p")" == *disabled* ]]; then
      changed+=("home $p"); ok "desactivado $p"
    elif [[ "$(sh_tv pm uninstall -k --user 0 "$p")" == *Success* ]]; then
      # Algunas TV no dejan desactivar el launcher pero sí quitarlo para el usuario
      changed+=("home-uninstall $p"); ok "quitado $p"
    else
      warn "no se pudo desactivar $p"
    fi
  done
  sh_tv cmd package set-home-activity "$component" >/dev/null

  local home op
  home="$(current_home)"
  if [[ -z "$home" ]]; then
    # Android antiguo sin "resolve-activity": no se puede comprobar, así que se deja hecho
    for p in "${changed[@]+"${changed[@]}"}"; do record ${p}; done
    warn "No se pudo comprobar qué abre Inicio: pulsa Inicio en el mando para verlo."
  elif [[ "$home" == "$pkg/"* ]]; then
    for p in "${changed[@]+"${changed[@]}"}"; do record ${p}; done
    ok "El botón Inicio abre $label"
  else
    warn "Esta TV no deja cambiar la app de inicio (Inicio abre: $home)."
    for p in "${changed[@]+"${changed[@]}"}"; do
      read -r op p <<<"$p"
      if [[ "$op" == home-uninstall ]]; then sh_tv cmd package install-existing "$p" >/dev/null; else sh_tv pm enable "$p" >/dev/null; fi
      ok "recuperado $p"
    done
    if [[ "$pkg" == "$HALO_PKG" ]]; then
      enable_halo_service
      ok "Activado «Botón Inicio → Halo»: al pulsar Inicio se abrirá Halo"
    else
      warn "Activa en Ajustes → Accesibilidad el servicio de inicio de $label"
    fi
  fi
  sh_tv am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null || true
  info "Para volver al launcher original: $(basename "$0") --restore-launcher"
}

do_launcher() {
  if [[ "$APK" == ltv ]]; then
    if ! is_installed "$LTV_PKG"; then
      command -v curl >/dev/null || die "hace falta curl para descargar LTvLauncher"
      local abi flavor tmp
      abi="$(sh_tv getprop ro.product.cpu.abi)"
      case "$abi" in arm64*) flavor=arm64-v8a ;; armeabi*) flavor=armeabi-v7a ;; *) flavor=universal ;; esac
      tmp="$(mktemp -d)"
      info "Descargando LTvLauncher ($flavor) de GitHub"
      # shellcheck disable=SC2059
      curl -fsSL -o "$tmp/ltv.apk" "$(printf "$LTV_URL" "$flavor")" || die "no se pudo descargar LTvLauncher"
      install_apk "$tmp/ltv.apk" "LTvLauncher"
      rm -f "$tmp/ltv.apk"; rmdir "$tmp"
    else
      ok "LTvLauncher ya está instalado"
    fi
    set_home "$LTV_PKG" "LTvLauncher"
    return
  fi

  if ! is_installed "$HALO_PKG"; then
    local apk; apk="$(find_apk)"
    [[ -n "$apk" ]] || die "no encuentro el APK de Halo. Compílalo (launcher/) o pásalo: --launcher ruta.apk"
    install_apk "$apk" "Halo"
  else
    ok "Halo ya está instalado"
  fi
  set_home "$HALO_PKG" "Halo"
}

do_restore_launcher() {
  local p launchers=("${STOCK_LAUNCHERS[@]}" "$SETUPWRAITH")
  [[ "$DRY_RUN" -eq 1 ]] || disable_halo_service
  # También los launchers de otras marcas que se quitaron (están en la copia de la TV)
  while read -r _ p; do [[ -n "${p:-}" ]] && launchers+=("$p"); done < <(sh_tv cat "$TV_BACKUP" 2>/dev/null | grep -E '^(home|home-uninstall) ' || true)
  for p in $(printf '%s\n' "${launchers[@]}" | sort -u); do
    if ! is_installed "$p"; then
      # Quitado para el usuario con el plan B de --launcher: se recupera si existe en el sistema
      [[ "$DRY_RUN" -eq 0 && "$(sh_tv cmd package install-existing "$p")" == *installed* ]] && ok "reinstalado $p" || continue
    fi
    is_enabled "$p" && continue
    if [[ "$DRY_RUN" -eq 1 ]]; then ok "${c_dim}(simulado)${c_off} activar $p"; continue; fi
    [[ "$(sh_tv pm enable "$p")" == *enabled* ]] && ok "activado $p" || warn "no se pudo activar $p"
  done
  [[ "$DRY_RUN" -eq 1 ]] && return
  sh_tv am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null || true
  info "Launcher de stock reactivado. Puedes desinstalar Halo con: adb uninstall $HALO_PKG"
}

do_restore() {
  local source op pkg out
  if [[ "$RESTORE_FILE" == tv ]]; then
    source="$(sh_tv cat "$TV_BACKUP")"
    [[ "$source" == *"No such file"* || -z "$source" ]] && die "la TV no tiene copia de seguridad ($TV_BACKUP)"
  else
    [[ -f "$RESTORE_FILE" ]] || die "no existe $RESTORE_FILE"
    source="$(cat "$RESTORE_FILE")"
  fi
  while read -r op pkg; do
    [[ -z "$op" || "$op" == \#* || -z "${pkg:-}" ]] && continue
    if [[ "$DRY_RUN" -eq 1 ]]; then ok "${c_dim}(simulado)${c_off} $op → $pkg"; continue; fi
    case "$op" in
      uninstall|home-uninstall)
        out="$(sh_tv cmd package install-existing "$pkg")"
        [[ "$out" == *installed* ]] || out="$(sh_tv pm install-existing "$pkg")"
        [[ "$out" == *installed* ]] && ok "reinstalado $pkg" || warn "$pkg: $out" ;;
      disable|home)
        [[ "$(sh_tv pm enable "$pkg")" == *enabled* ]] && ok "activado $pkg" || warn "no se pudo activar $pkg" ;;
      a11y)
        disable_halo_service ;;
    esac
  done < <(sort -u <<<"$source")
  if [[ "$RESTORE_FILE" == tv && "$DRY_RUN" -eq 0 ]]; then sh_tv mv "$TV_BACKUP" "$TV_BACKUP.restaurado" >/dev/null; fi
  info "Restauración terminada."
}

do_scan() {
  local listed
  listed="$( { grep -hv '|' "$LISTS"/*.txt | awk '!/^#/ && NF>=2 {print $2}'; printf '%s\n' "${STOCK_LAUNCHERS[@]}" "${PROTECTED[@]}"; } | sort -u)"
  info "Apps del sistema que no están en ninguna lista (revísalas con cuidado):"
  sh_tv pm list packages -s | sed 's/^package://' | sort -u | while read -r p; do
    grep -qxF "$p" <<<"$listed" && continue
    is_protected "$p" && continue
    case "$p" in
      android|com.android.*|com.google.android.gms*|com.google.android.ext.*|com.qualcomm.*|com.mediatek.*|com.amlogic.*|com.realtek.*) continue ;;
    esac
    echo "  $p"
  done
  echo
  info "Si quieres quitar alguna, crea un archivo con líneas 'recomendado paquete descripción' y usa --list archivo"
}

case "$ACTION" in
  debloat) do_debloat ;;
  launcher) do_launcher ;;
  restore-launcher) do_restore_launcher ;;
  restore) do_restore ;;
  scan) do_scan ;;
esac
