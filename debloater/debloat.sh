#!/usr/bin/env bash
# DebloatTV — limpia una Smart TV Android/Google TV por ADB y cambia el launcher por Halo.
# Todo es reversible: los paquetes se quitan solo para el usuario 0 y se registran en backups/.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LISTS="$HERE/lists"
BACKUPS="$HERE/backups"
HALO_PKG="com.carmelita.halo"
HALO_HOME="$HALO_PKG/.MainActivity"

# Launchers de stock que se desactivan al instalar Halo (se reactivan con --restore-launcher)
STOCK_LAUNCHERS=(
  com.google.android.apps.tv.launcherx      # Google TV
  com.google.android.tungsten.setupwraith   # Google TV: reabre el launcher de stock al pulsar HOME
  com.google.android.tvlauncher             # Android TV 8+
  com.google.android.leanbacklauncher       # Android TV antiguo
  com.tcl.waterfall.overseas                # TCL Home (Android TV)
  com.mitv.tvhome                           # Xiaomi PatchWall
)

# Nunca se tocan, aunque aparezcan en una lista personalizada
PROTECTED=(
  android com.android.systemui com.android.settings com.android.tv.settings
  com.android.vending com.google.android.gms com.google.android.gsf
  com.google.android.leanback.ime com.google.android.inputmethod.latin
  com.google.android.tv.remote.service com.google.android.tvsetup
  com.android.providers.settings com.android.providers.tv com.android.providers.media
  com.android.packageinstaller com.google.android.packageinstaller
  com.android.shell com.android.bluetooth com.android.wifi.resources
  "$HALO_PKG"
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
  --launcher [APK]        Instala Halo Launcher y desactiva el launcher de stock
  --restore-launcher      Reactiva el launcher de stock
  --restore ARCHIVO       Reinstala/reactiva todo lo registrado en un backup
  --scan                  Lista apps del sistema y del fabricante para revisarlas a mano

Opciones:
  -i, --ip IP[:PUERTO]    Conecta por red (depuración ADB inalámbrica)
  -s, --serial SERIAL     Usa un dispositivo concreto si hay varios
  -l, --level NIVEL       seguro | recomendado | agresivo
      --list ARCHIVO      Añade una lista propia (mismo formato que lists/*.txt)
  -n, --dry-run           Muestra lo que haría sin cambiar nada
  -y, --yes               No pedir confirmación
  -h, --help              Esta ayuda

Ejemplos:
  $(basename "$0") -i 192.168.1.50 -n                 # ver qué se quitaría
  $(basename "$0") -i 192.168.1.50 -l recomendado     # debloat
  $(basename "$0") -i 192.168.1.50 --launcher         # poner Halo como launcher
  $(basename "$0") --restore backups/chromecast-20260101-1200.txt
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

INSTALLED="$(sh_tv pm list packages | sed 's/^package://' | sort -u)"
is_installed() { grep -qxF "$1" <<<"$INSTALLED"; }
is_protected() { local p; for p in "${PROTECTED[@]}"; do [[ "$p" == "$1" ]] && return 0; done; return 1; }

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
record() { [[ "$DRY_RUN" -eq 1 ]] || echo "$1 $2" >>"$BACKUP_FILE"; }

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
  local files=("$LISTS/google.txt") vendor
  vendor="$(vendor_list)"
  [[ -n "$vendor" ]] && files+=("$LISTS/$vendor")
  files+=("${EXTRA_LISTS[@]+"${EXTRA_LISTS[@]}"}")

  local targets=() descs=() f lvl pkg desc
  for f in "${files[@]}"; do
    [[ -f "$f" ]] || die "no existe la lista $f"
    while read -r lvl pkg desc; do
      [[ -z "$lvl" || "$lvl" == \#* ]] && continue
      [[ "$(level_rank "$lvl")" -ge 1 && "$(level_rank "$lvl")" -le "$MAX_RANK" ]] || continue
      is_installed "$pkg" || continue
      if is_protected "$pkg"; then warn "se ignora $pkg (protegido)"; continue; fi
      targets+=("$pkg"); descs+=("$lvl: $desc")
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
  [[ "$DRY_RUN" -eq 1 ]] || info "Hecho. Para deshacerlo: $(basename "$0") --restore $BACKUP_FILE"
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

do_launcher() {
  if ! is_installed "$HALO_PKG"; then
    local apk; apk="$(find_apk)"
    [[ -n "$apk" ]] || die "no encuentro el APK de Halo. Compílalo (launcher/) o pásalo: --launcher ruta.apk"
    info "Instalando $(basename "$apk")"
    if [[ "$DRY_RUN" -eq 0 ]]; then
      local out
      out="$("${ADB[@]}" install -r "$apk" 2>&1 | tr -d '\r' || true)"
      # Sin Halo instalado no se puede tocar el launcher de stock: la TV se quedaría sin inicio
      [[ "$out" == *Success* ]] || die "no se pudo instalar Halo: $(tail -1 <<<"$out")"
      ok "Halo instalado"
    fi
  else
    ok "Halo ya está instalado"
  fi

  local stock=() p
  for p in "${STOCK_LAUNCHERS[@]}"; do is_installed "$p" && stock+=("$p"); done
  info "Se desactivará el launcher de stock: ${stock[*]:-ninguno}"
  confirm "¿Continuar?" || { info "Cancelado."; return; }

  new_backup
  if [[ "$DRY_RUN" -eq 0 ]]; then
    sh_tv cmd package set-home-activity "$HALO_HOME" >/dev/null || true
  fi
  for p in "${stock[@]+"${stock[@]}"}"; do
    if [[ "$DRY_RUN" -eq 1 ]]; then ok "${c_dim}(simulado)${c_off} desactivar $p"; continue; fi
    if [[ "$(sh_tv pm disable-user --user 0 "$p")" == *disabled* ]]; then
      record disable "$p"; ok "desactivado $p"
    else
      warn "no se pudo desactivar $p"
    fi
  done
  [[ "$DRY_RUN" -eq 1 ]] && return
  sh_tv am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null || true
  info "Listo. Pulsa HOME en el mando: debería abrirse Halo."
  info "Para volver al launcher original: $(basename "$0") --restore-launcher"
}

do_restore_launcher() {
  local p
  for p in "${STOCK_LAUNCHERS[@]}"; do
    is_installed "$p" || continue
    if [[ "$DRY_RUN" -eq 1 ]]; then ok "${c_dim}(simulado)${c_off} activar $p"; continue; fi
    [[ "$(sh_tv pm enable "$p")" == *enabled* ]] && ok "activado $p" || warn "no se pudo activar $p"
  done
  [[ "$DRY_RUN" -eq 1 ]] && return
  sh_tv am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null || true
  info "Launcher de stock reactivado. Puedes desinstalar Halo con: adb uninstall $HALO_PKG"
}

do_restore() {
  [[ -f "$RESTORE_FILE" ]] || die "no existe $RESTORE_FILE"
  local op pkg out
  while read -r op pkg; do
    [[ -z "$op" || "$op" == \#* ]] && continue
    if [[ "$DRY_RUN" -eq 1 ]]; then ok "${c_dim}(simulado)${c_off} $op → $pkg"; continue; fi
    case "$op" in
      uninstall)
        out="$(sh_tv cmd package install-existing "$pkg")"
        [[ "$out" == *installed* ]] || out="$(sh_tv pm install-existing "$pkg")"
        [[ "$out" == *installed* ]] && ok "reinstalado $pkg" || warn "$pkg: $out" ;;
      disable)
        [[ "$(sh_tv pm enable "$pkg")" == *enabled* ]] && ok "activado $pkg" || warn "no se pudo activar $pkg" ;;
    esac
  done <"$RESTORE_FILE"
  info "Restauración terminada."
}

do_scan() {
  local listed
  listed="$( { cat "$LISTS"/*.txt | awk '!/^#/ && NF>=2 {print $2}'; printf '%s\n' "${STOCK_LAUNCHERS[@]}"; } | sort -u)"
  info "Apps del sistema que no están en ninguna lista (revísalas con cuidado):"
  sh_tv pm list packages -s | sed 's/^package://' | sort -u | while read -r p; do
    grep -qxF "$p" <<<"$listed" && continue
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
