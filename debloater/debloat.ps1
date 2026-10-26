<#
.SYNOPSIS
  DebloatTV — limpia una Smart TV Android/Google TV por ADB y cambia el launcher por Halo.
.EXAMPLE
  .\debloat.ps1 -Ip 192.168.1.50 -DryRun
  .\debloat.ps1 -Ip 192.168.1.50 -Level recomendado
  .\debloat.ps1 -Ip 192.168.1.50 -Launcher
  .\debloat.ps1 -Ip 192.168.1.50 -Launcher -Apk ltv     # LTvLauncher (código abierto)
  .\debloat.ps1 -Ip 192.168.1.50 -Restore tv            # deshacer lo registrado en la TV
#>
[CmdletBinding()]
param(
  [string]$Ip,
  [string]$Serial,
  [ValidateSet('seguro', 'recomendado', 'agresivo')][string]$Level = 'recomendado',
  [string[]]$List = @(),
  [switch]$DryRun,
  [switch]$Yes,
  [switch]$Launcher,
  [string]$Apk,
  [switch]$RestoreLauncher,
  [string]$Restore,
  [switch]$Scan
)
$ErrorActionPreference = 'Stop'

$Here = $PSScriptRoot
$Lists = Join-Path $Here 'lists'
$Backups = Join-Path $Here 'backups'
$HaloPkg = 'com.carmelita.halo'
$HaloHomeService = "$HaloPkg/$HaloPkg.HomeButtonService"
$LtvPkg = 'com.leanbitlab.ltvL'
$LtvUrl = 'https://github.com/leanbitlab-org/LtvLauncher/releases/latest/download/LTvLauncher-{0}-release.apk'
$TvBackup = '/sdcard/DebloatTV/backup.txt'

# Launchers de stock conocidos; los de otras marcas se detectan por HOME (ver Get-HomeLaunchers)
$StockLaunchers = @(
  'com.google.android.apps.tv.launcherx',     # Google TV
  'com.google.android.tvlauncher',            # Android TV 8+
  'com.google.android.leanbacklauncher',      # Android TV antiguo
  'com.mitv.tvhome'                           # Xiaomi PatchWall
)
# El asistente de Google TV responde a HOME pero NUNCA se desactiva: guarda la red WiFi y
# desactivarlo la borra (la TV se queda sin WiFi ni ADB). Solo se reactiva al restaurar.
$Setupwraith = 'com.google.android.tungsten.setupwraith'
$NotLaunchers = @('android', 'com.android.settings', 'com.android.tv.settings', $Setupwraith, $HaloPkg, $LtvPkg)

$Protected = @(
  'android', 'com.android.systemui', 'com.android.settings', 'com.android.tv.settings',
  'com.android.vending', 'com.google.android.gms', 'com.google.android.gsf',
  'com.google.android.leanback.ime', 'com.google.android.inputmethod.latin',
  'com.google.android.tv.remote.service', 'com.google.android.tvsetup',
  'com.android.providers.settings', 'com.android.providers.tv', 'com.android.providers.media',
  'com.android.packageinstaller', 'com.google.android.packageinstaller',
  'com.android.shell', 'com.android.bluetooth', 'com.android.wifi.resources',
  'com.android.webview', 'com.google.android.webview',
  'com.android.permissioncontroller', 'com.google.android.permissioncontroller', $Setupwraith,
  # TCL: imprescindibles para TV, entradas HDMI, mando y menús (ver lists\tcl.txt)
  'com.tcl.tv', 'com.tcl.tvinput', 'com.tcl.suspension', 'com.tcl.dashboard', 'com.tcl.autopair',
  'com.tcl.systemserver', 'com.tcl.providers.config', 'com.tcl.globalkeyoverlay', 'com.tcl.initsetup',
  $HaloPkg, $LtvPkg
)

$Ranks = @{ seguro = 1; recomendado = 2; agresivo = 3 }

function Info($m) { Write-Host "==> $m" -ForegroundColor White }
function Ok($m) { Write-Host "  + $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  ! $m" -ForegroundColor Yellow }
function Die($m) { Write-Host "error: $m" -ForegroundColor Red; exit 1 }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
  Die 'no se encontró adb. Instala Android platform-tools: https://developer.android.com/tools/releases/platform-tools'
}

# Los fallos se juzgan por la salida. En Windows PowerShell 5.1, con 'Stop', cualquier texto que
# adb escriba en stderr detendría el script, así que aquí se relaja la preferencia.
function Invoke-Adb([string[]]$argv) {
  $ErrorActionPreference = 'Continue'
  ((& adb @AdbArgs @argv 2>&1) | ForEach-Object { "$_" } | Out-String).Trim()
}
function Tv([string[]]$cmd) { Invoke-Adb (@('shell') + $cmd) }

$AdbArgs = @()
if ($Ip) {
  if ($Ip -notmatch ':') { $Ip = "${Ip}:5555" }
  Info "Conectando a $Ip (acepta el aviso de depuración en la TV si aparece)"
  Invoke-Adb @('connect', $Ip) | Out-Null
  $Serial = $Ip
}
if ($Serial) { $AdbArgs = @('-s', $Serial) }

$state = Invoke-Adb @('get-state')
if ($state -ne 'device') { Die 'no hay ningún dispositivo conectado. Activa la depuración por red/USB en la TV.' }

$Manufacturer = (Tv @('getprop', 'ro.product.manufacturer')).ToLower()
$Model = Tv @('getprop', 'ro.product.model')
$Sdk = Tv @('getprop', 'ro.build.version.sdk')
Info "Dispositivo: $Model ($Manufacturer, API $Sdk)"

# Decodificadores conocidos (lists\dispositivos.txt): por propiedades o por una app de sistema
$script:DeviceList = $null
function Find-Device {
  $identity = "$Manufacturer $Model $(Tv @('getprop', 'ro.product.device')) $(Tv @('getprop', 'ro.product.name'))"
  $system = (Tv @('pm', 'list', 'packages', '-s')) -split "`r?`n" | ForEach-Object { $_ -replace '^package:', '' }
  foreach ($line in Get-Content -Encoding UTF8 (Join-Path $Lists 'dispositivos.txt')) {
    if ($line -match '^\s*(#|$)') { continue }
    $f = $line -split '\s*\|\s*', 5
    if ($f.Count -lt 3) { continue }
    $pattern = $f[0].Trim()
    $match = if ($pattern -like 'sistema:*') { $system -contains $pattern.Substring(8) }
             else { @($pattern -split ',' | Where-Object { $_.Trim() -and $identity -like "*$($_.Trim())*" }).Count -gt 0 }
    if ($match) {
      Info "Equipo reconocido: $($f[1]) · $($f[2])"
      if ($f.Count -gt 3 -and $f[3]) { Warn $f[3] }
      if ($f.Count -gt 4 -and $f[4].Trim()) { $script:DeviceList = $f[4].Trim() }
      return
    }
  }
}
Find-Device

function Get-Packages([string[]]$extra) {
  $set = @{}
  (Tv (@('pm', 'list', 'packages') + $extra)) -split "`r?`n" | ForEach-Object {
    if ($_ -match '^package:(.+)$') { $set[$Matches[1].Trim()] = $true }
  }
  $set
}
function Update-Packages {
  $script:Installed = Get-Packages @()
  $script:Enabled = Get-Packages @('-e')
}
Update-Packages

$ComponentRe = '^\s*([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+\s*$'
# "paquete/.Actividad" de cada app que responde a HOME
function Get-HomeComponents {
  (Tv @('cmd', 'package', 'query-activities', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')) -split "`r?`n" |
    Where-Object { $_ -match $ComponentRe } | ForEach-Object { $_.Trim() }
}
# Launchers de stock de esta TV: los conocidos más las apps del sistema que responden a HOME
function Get-HomeLaunchers {
  $system = Get-Packages @('-s')
  $found = [System.Collections.Generic.List[string]]::new()
  foreach ($p in $StockLaunchers) { if (-not $found.Contains($p)) { $found.Add($p) } }
  foreach ($c in Get-HomeComponents) {
    $p = ($c -split '/')[0]
    if ($NotLaunchers -notcontains $p -and $system.ContainsKey($p) -and -not $found.Contains($p)) { $found.Add($p) }
  }
  $found | Where-Object { $Installed.ContainsKey($_) }
}
function Get-CurrentHome {
  $h = (Tv @('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')) -split "`r?`n" |
    Where-Object { $_ -match $ComponentRe } | Select-Object -Last 1
  if ($h) { $h.Trim() } else { '' }
}

# Líneas "proteger" de las listas (apps de los operadores...); "paquete.*" protege un prefijo
$ProtectLines = @(Get-ChildItem (Join-Path $Lists '*.txt')) + @($List | ForEach-Object { Get-Item $_ }) |
  Get-Content -Encoding UTF8 | ForEach-Object { if ($_ -match '^\s*proteger\s+(\S+)') { $Matches[1] } }
function Test-Protected($pkg) {
  if ($Protected -contains $pkg) { return $true }
  foreach ($p in $ProtectLines) {
    if ($p.EndsWith('*')) { if ($pkg.StartsWith($p.TrimEnd('*'))) { return $true } }
    elseif ($p -eq $pkg) { return $true }
  }
  $false
}

function Confirm-Action($q) {
  if ($Yes -or $DryRun) { return $true }
  (Read-Host "$q [s/N]") -match '^[sSyY]$'
}

$script:BackupFile = $null
function New-Backup {
  New-Item -ItemType Directory -Force -Path $Backups | Out-Null
  $slug = ($Model.ToLower() -replace '[^a-z0-9]+', '-').Trim('-')
  if (-not $slug) { $slug = 'tv' }
  $script:BackupFile = Join-Path $Backups ("$slug-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
  if (-not $DryRun) { "# DebloatTV backup — $Model ($Manufacturer) — $(Get-Date)" | Set-Content -Encoding UTF8 $script:BackupFile }
}
# Se guarda en el PC y en la TV, para poder deshacerlo desde cualquier herramienta
function Record($op, $pkg) {
  if ($DryRun) { return }
  "$op $pkg" | Add-Content -Encoding UTF8 $script:BackupFile
  Tv @("mkdir -p /sdcard/DebloatTV && echo '$op $pkg' >> $TvBackup") | Out-Null
}

function Remove-Pkg($pkg) {
  if ($DryRun) { Ok "(simulado) $pkg"; return }
  $out = Tv @('pm', 'uninstall', '-k', '--user', '0', $pkg)
  if ($out -match 'Success') { Record 'uninstall' $pkg; Ok $pkg; return }
  $out = Tv @('pm', 'disable-user', '--user', '0', $pkg)
  if ($out -match 'disabled') { Record 'disable' $pkg; Ok "$pkg (desactivado)" }
  else { Warn "${pkg}: $out" }
}

function Get-VendorList {
  switch -Regex ($Manufacturer) {
    'sony' { 'sony.txt' }
    'tcl' { 'tcl.txt' }
    'xiaomi' { 'xiaomi.txt' }
    'hisense' { 'hisense.txt' }
    'philips|tp ?vision' { 'philips.txt' }
  }
}

function Invoke-Debloat {
  $files = @(Join-Path $Lists 'google.txt')
  $vendor = Get-VendorList
  if ($vendor) { $files += Join-Path $Lists $vendor }
  if ($script:DeviceList) { $files += Join-Path $Lists $script:DeviceList }
  $files += $List
  $max = $Ranks[$Level]
  # La app que hace de inicio nunca se quita: la TV se quedaría sin pantalla de inicio
  $homes = @(Get-HomeLaunchers) + @(Get-HomeComponents | ForEach-Object { ($_ -split '/')[0] })

  $targets = @()
  foreach ($f in $files) {
    if (-not (Test-Path $f)) { Die "no existe la lista $f" }
    foreach ($line in Get-Content -Encoding UTF8 $f) {
      if ($line -match '^\s*(#|$)') { continue }
      $lvl, $entry, $desc = ($line.Trim() -split '\s+', 3)
      if (-not $Ranks.ContainsKey($lvl) -or $Ranks[$lvl] -gt $max) { continue }
      # Un "*" final abarca todos los paquetes activos que empiezan así
      $pkgs = if ($entry.EndsWith('*')) { $Enabled.Keys | Where-Object { $_.StartsWith($entry.TrimEnd('*')) } | Sort-Object }
              elseif ($Enabled.ContainsKey($entry)) { @($entry) } else { @() }
      foreach ($pkg in $pkgs) {
        if ($targets.Pkg -contains $pkg) { continue }
        if (Test-Protected $pkg) { Warn "se ignora $pkg (protegido)"; continue }
        if ($homes -contains $pkg) { Warn "$pkg es la pantalla de inicio: cámbiala con -Launcher"; continue }
        $targets += [pscustomobject]@{ Pkg = $pkg; Desc = "${lvl}: $desc" }
      }
    }
  }

  if ($targets.Count -eq 0) { Info "Nada que quitar en el nivel '$Level'. Tu TV ya está limpia."; return }
  Info "Se quitarán $($targets.Count) apps (nivel $Level):"
  $targets | ForEach-Object { Write-Host ('  {0,-52} {1}' -f $_.Pkg, $_.Desc) }
  if (-not (Confirm-Action '¿Continuar?')) { Info 'Cancelado.'; return }

  New-Backup
  $targets | ForEach-Object { Remove-Pkg $_.Pkg }
  if (-not $DryRun) { Info 'Hecho. Para deshacerlo: .\debloat.ps1 -Restore tv' }
}

function Find-Apk {
  if ($Apk) { return $Apk }
  $candidates = @(
    (Join-Path $Here '..\dist\halo-launcher.apk'),
    (Join-Path $Here '..\launcher\app\build\outputs\apk\release\*.apk'),
    (Join-Path $Here '..\launcher\app\build\outputs\apk\debug\*.apk')
  )
  foreach ($c in $candidates) {
    $hit = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { return $hit.FullName }
  }
}

function Install-Apk($path, $label) {
  Info "Instalando $label"
  if ($DryRun) { return }
  $out = Invoke-Adb @('install', '-r', $path)
  # Sin el launcher instalado no se puede tocar el de stock: la TV se quedaría sin inicio
  if ($out -notmatch 'Success') { Die "no se pudo instalar ${label}: $(($out -split "`r?`n")[-1])" }
  Ok "$label instalado"
  Update-Packages
}

function Enable-HaloService {
  $current = Tv @('settings', 'get', 'secure', 'enabled_accessibility_services')
  if ($current -eq 'null') { $current = '' }
  if ($current -notlike "*$HaloHomeService*") {
    $value = if ($current) { "${current}:$HaloHomeService" } else { $HaloHomeService }
    Tv @('settings', 'put', 'secure', 'enabled_accessibility_services', $value) | Out-Null
  }
  Tv @('settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
  Record 'a11y' $HaloHomeService
}

function Disable-HaloService {
  $current = Tv @('settings', 'get', 'secure', 'enabled_accessibility_services')
  if ($current -notlike "*$HaloHomeService*") { return }
  $keep = ($current -split ':') | Where-Object { $_ -and $_ -ne $HaloHomeService }
  if ($keep) { Tv @('settings', 'put', 'secure', 'enabled_accessibility_services', ($keep -join ':')) | Out-Null }
  else { Tv @('settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null }
  Ok 'desactivado «Botón Inicio → Halo»'
}

# Deja $pkg como inicio. Si la TV no lo acepta (algunas TCL con Google TV ignoran
# set-home-activity y, sin su launcher, HOME cae en una pantalla de recuperación), se deshace
# todo y, para Halo, se activa su servicio de accesibilidad, que abre Halo al pulsar HOME.
function Set-Home($pkg, $label) {
  $component = Get-HomeComponents | Where-Object { $_ -like "$pkg/*" } | Select-Object -First 1
  if (-not $component -and -not $DryRun) { Die "$label no declara una pantalla de inicio" }
  $stock = @(Get-HomeLaunchers)
  Info "Se desactivará el launcher de stock: $(if ($stock) { $stock -join ' ' } else { 'ninguno' })"
  if (-not (Confirm-Action '¿Continuar?')) { Info 'Cancelado.'; return }
  New-Backup
  if ($DryRun) { $stock | ForEach-Object { Ok "(simulado) desactivar $_" }; return }

  Tv @('cmd', 'package', 'set-home-activity', $component) | Out-Null
  $changed = @()
  foreach ($p in $stock) {
    if ((Tv @('pm', 'disable-user', '--user', '0', $p)) -match 'disabled') { $changed += ,@('home', $p); Ok "desactivado $p" }
    elseif ((Tv @('pm', 'uninstall', '-k', '--user', '0', $p)) -match 'Success') {
      # Algunas TV no dejan desactivar el launcher pero sí quitarlo para el usuario
      $changed += ,@('home-uninstall', $p); Ok "quitado $p"
    }
    else { Warn "no se pudo desactivar $p" }
  }
  Tv @('cmd', 'package', 'set-home-activity', $component) | Out-Null

  $currentHome = Get-CurrentHome
  if (-not $currentHome) {
    # Android antiguo sin "resolve-activity": no se puede comprobar, así que se deja hecho
    foreach ($c in $changed) { Record $c[0] $c[1] }
    Warn 'No se pudo comprobar qué abre Inicio: pulsa Inicio en el mando para verlo.'
  } elseif ($currentHome -like "$pkg/*") {
    foreach ($c in $changed) { Record $c[0] $c[1] }
    Ok "El botón Inicio abre $label"
  } else {
    Warn "Esta TV no deja cambiar la app de inicio (Inicio abre: $currentHome)."
    foreach ($c in $changed) {
      if ($c[0] -eq 'home-uninstall') { Tv @('cmd', 'package', 'install-existing', $c[1]) | Out-Null }
      else { Tv @('pm', 'enable', $c[1]) | Out-Null }
      Ok "recuperado $($c[1])"
    }
    if ($pkg -eq $HaloPkg) { Enable-HaloService; Ok 'Activado «Botón Inicio → Halo»: al pulsar Inicio se abrirá Halo' }
    else { Warn "Activa en Ajustes → Accesibilidad el servicio de inicio de $label" }
  }
  Tv @('am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') | Out-Null
  Info 'Para volver al launcher original: .\debloat.ps1 -RestoreLauncher'
}

function Invoke-Launcher {
  if ($Apk -eq 'ltv') {
    if (-not $Installed.ContainsKey($LtvPkg)) {
      $abi = Tv @('getprop', 'ro.product.cpu.abi')
      $flavor = if ($abi -like 'arm64*') { 'arm64-v8a' } elseif ($abi -like 'armeabi*') { 'armeabi-v7a' } else { 'universal' }
      $tmp = Join-Path ([System.IO.Path]::GetTempPath()) 'ltvlauncher.apk'
      Info "Descargando LTvLauncher ($flavor) de GitHub"
      if (-not $DryRun) {
        try { Invoke-WebRequest -UseBasicParsing -Uri ($LtvUrl -f $flavor) -OutFile $tmp }
        catch { Die "no se pudo descargar LTvLauncher: $($_.Exception.Message)" }
      }
      Install-Apk $tmp 'LTvLauncher'
      Remove-Item $tmp -ErrorAction SilentlyContinue
    } else { Ok 'LTvLauncher ya está instalado' }
    Set-Home $LtvPkg 'LTvLauncher'
    return
  }

  if (-not $Installed.ContainsKey($HaloPkg)) {
    $path = Find-Apk
    if (-not $path) { Die 'no encuentro el APK de Halo. Compílalo (launcher\) o pásalo: -Launcher -Apk ruta.apk' }
    Install-Apk $path 'Halo'
  } else { Ok 'Halo ya está instalado' }
  Set-Home $HaloPkg 'Halo'
}

function Invoke-RestoreLauncher {
  if (-not $DryRun) { Disable-HaloService }
  $launchers = @($StockLaunchers) + $Setupwraith
  # También los launchers de otras marcas que se quitaron (están en la copia de la TV)
  (Tv @('cat', $TvBackup)) -split "`r?`n" | ForEach-Object {
    if ($_ -match '^(home|home-uninstall)\s+(\S+)$') { $launchers += $Matches[2] }
  }
  foreach ($p in ($launchers | Sort-Object -Unique)) {
    if (-not $Installed.ContainsKey($p)) {
      # Quitado para el usuario con el plan B de -Launcher: se recupera si existe en el sistema
      if ($DryRun -or (Tv @('cmd', 'package', 'install-existing', $p)) -notmatch 'installed') { continue }
      Ok "reinstalado $p"
    }
    if ($Enabled.ContainsKey($p)) { continue }
    if ($DryRun) { Ok "(simulado) activar $p"; continue }
    if ((Tv @('pm', 'enable', $p)) -match 'enabled') { Ok "activado $p" } else { Warn "no se pudo activar $p" }
  }
  if ($DryRun) { return }
  Tv @('am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') | Out-Null
  Info "Launcher de stock reactivado. Puedes desinstalar Halo con: adb uninstall $HaloPkg"
}

function Invoke-Restore {
  if ($Restore -eq 'tv') {
    $source = Tv @('cat', $TvBackup)
    if (-not $source -or $source -match 'No such file') { Die "la TV no tiene copia de seguridad ($TvBackup)" }
    $lines = $source -split "`r?`n"
  } else {
    if (-not (Test-Path $Restore)) { Die "no existe $Restore" }
    $lines = Get-Content -Encoding UTF8 $Restore
  }
  foreach ($line in ($lines | Sort-Object -Unique)) {
    if ($line -match '^\s*(#|$)') { continue }
    $op, $pkg = $line.Trim() -split '\s+', 2
    if ($DryRun) { Ok "(simulado) $op -> $pkg"; continue }
    switch ($op) {
      { $_ -in 'uninstall', 'home-uninstall' } {
        $out = Tv @('cmd', 'package', 'install-existing', $pkg)
        if ($out -notmatch 'installed') { $out = Tv @('pm', 'install-existing', $pkg) }
        if ($out -match 'installed') { Ok "reinstalado $pkg" } else { Warn "${pkg}: $out" }
      }
      { $_ -in 'disable', 'home' } {
        if ((Tv @('pm', 'enable', $pkg)) -match 'enabled') { Ok "activado $pkg" } else { Warn "no se pudo activar $pkg" }
      }
      'a11y' { Disable-HaloService }
    }
  }
  if ($Restore -eq 'tv' -and -not $DryRun) { Tv @('mv', $TvBackup, "$TvBackup.restaurado") | Out-Null }
  Info 'Restauración terminada.'
}

function Invoke-Scan {
  $listed = @{}
  $StockLaunchers + $Protected | ForEach-Object { $listed[$_] = $true }
  Get-ChildItem (Join-Path $Lists '*.txt') | Get-Content -Encoding UTF8 | ForEach-Object {
    if ($_ -notmatch '^\s*(#|$)' -and $_ -notmatch '\|') { $listed[($_.Trim() -split '\s+')[1]] = $true }
  }
  Info 'Apps del sistema que no están en ninguna lista (revísalas con cuidado):'
  (Tv @('pm', 'list', 'packages', '-s')) -split "`r?`n" | ForEach-Object {
    if ($_ -match '^package:(.+)$') {
      $p = $Matches[1].Trim()
      if ($listed.ContainsKey($p) -or (Test-Protected $p)) { return }
      if ($p -match '^(android$|com\.android\.|com\.google\.android\.gms|com\.google\.android\.ext\.|com\.qualcomm\.|com\.mediatek\.|com\.amlogic\.|com\.realtek\.)') { return }
      Write-Host "  $p"
    }
  } | Sort-Object
  Write-Host ''
  Info "Si quieres quitar alguna, crea un archivo con líneas 'recomendado paquete descripción' y usa -List archivo"
}

if ($Restore) { Invoke-Restore }
elseif ($RestoreLauncher) { Invoke-RestoreLauncher }
elseif ($Launcher) { Invoke-Launcher }
elseif ($Scan) { Invoke-Scan }
else { Invoke-Debloat }
