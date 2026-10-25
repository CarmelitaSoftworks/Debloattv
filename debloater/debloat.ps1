<#
.SYNOPSIS
  DebloatTV — limpia una Smart TV Android/Google TV por ADB y cambia el launcher por Halo.
.EXAMPLE
  .\debloat.ps1 -Ip 192.168.1.50 -DryRun
  .\debloat.ps1 -Ip 192.168.1.50 -Level recomendado
  .\debloat.ps1 -Ip 192.168.1.50 -Launcher
  .\debloat.ps1 -Restore backups\chromecast-20260101-1200.txt
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
$HaloHome = "$HaloPkg/.MainActivity"

$StockLaunchers = @(
  'com.google.android.apps.tv.launcherx',     # Google TV
  'com.google.android.tungsten.setupwraith',  # Google TV: reabre el launcher de stock al pulsar HOME
  'com.google.android.tvlauncher',            # Android TV 8+
  'com.google.android.leanbacklauncher',      # Android TV antiguo
  'com.tcl.waterfall.overseas',               # TCL Home (Android TV)
  'com.mitv.tvhome'                           # Xiaomi PatchWall
)

$Protected = @(
  'android', 'com.android.systemui', 'com.android.settings', 'com.android.tv.settings',
  'com.android.vending', 'com.google.android.gms', 'com.google.android.gsf',
  'com.google.android.leanback.ime', 'com.google.android.inputmethod.latin',
  'com.google.android.tv.remote.service', 'com.google.android.tvsetup',
  'com.android.providers.settings', 'com.android.providers.tv', 'com.android.providers.media',
  'com.android.packageinstaller', 'com.google.android.packageinstaller',
  'com.android.shell', 'com.android.bluetooth', 'com.android.wifi.resources',
  $HaloPkg
)

$Ranks = @{ seguro = 1; recomendado = 2; agresivo = 3 }

function Info($m) { Write-Host "==> $m" -ForegroundColor White }
function Ok($m) { Write-Host "  + $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  ! $m" -ForegroundColor Yellow }
function Die($m) { Write-Host "error: $m" -ForegroundColor Red; exit 1 }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
  Die 'no se encontró adb. Instala Android platform-tools: https://developer.android.com/tools/releases/platform-tools'
}

$AdbArgs = @()
if ($Ip) {
  if ($Ip -notmatch ':') { $Ip = "${Ip}:5555" }
  Info "Conectando a $Ip (acepta el aviso de depuración en la TV si aparece)"
  adb connect $Ip | Out-Null
  $Serial = $Ip
}
if ($Serial) { $AdbArgs = @('-s', $Serial) }

# adb shell devuelve el código del comando remoto; los fallos se juzgan por la salida
function Tv([string[]]$cmd) { ((& adb @AdbArgs shell @cmd 2>&1) | Out-String).Trim() }

$state = ((& adb @AdbArgs get-state 2>&1) | Out-String).Trim()
if ($state -ne 'device') { Die 'no hay ningún dispositivo conectado. Activa la depuración por red/USB en la TV.' }

$Manufacturer = (Tv @('getprop', 'ro.product.manufacturer')).ToLower()
$Model = Tv @('getprop', 'ro.product.model')
$Sdk = Tv @('getprop', 'ro.build.version.sdk')
Info "Dispositivo: $Model ($Manufacturer, API $Sdk)"

$Installed = @{}
(Tv @('pm', 'list', 'packages')) -split "`r?`n" | ForEach-Object {
  if ($_ -match '^package:(.+)$') { $Installed[$Matches[1].Trim()] = $true }
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
function Record($op, $pkg) { if (-not $DryRun) { "$op $pkg" | Add-Content -Encoding UTF8 $script:BackupFile } }

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
  $files += $List
  $max = $Ranks[$Level]

  $targets = @()
  foreach ($f in $files) {
    if (-not (Test-Path $f)) { Die "no existe la lista $f" }
    foreach ($line in Get-Content -Encoding UTF8 $f) {
      if ($line -match '^\s*(#|$)') { continue }
      $lvl, $pkg, $desc = ($line.Trim() -split '\s+', 3)
      if (-not $Ranks.ContainsKey($lvl) -or $Ranks[$lvl] -gt $max) { continue }
      if (-not $Installed.ContainsKey($pkg)) { continue }
      if ($Protected -contains $pkg) { Warn "se ignora $pkg (protegido)"; continue }
      $targets += [pscustomobject]@{ Pkg = $pkg; Desc = "${lvl}: $desc" }
    }
  }

  if ($targets.Count -eq 0) { Info "Nada que quitar en el nivel '$Level'. Tu TV ya está limpia."; return }
  Info "Se quitarán $($targets.Count) apps (nivel $Level):"
  $targets | ForEach-Object { Write-Host ('  {0,-52} {1}' -f $_.Pkg, $_.Desc) }
  if (-not (Confirm-Action '¿Continuar?')) { Info 'Cancelado.'; return }

  New-Backup
  $targets | ForEach-Object { Remove-Pkg $_.Pkg }
  if (-not $DryRun) { Info "Hecho. Para deshacerlo: .\debloat.ps1 -Restore $script:BackupFile" }
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

function Invoke-Launcher {
  if (-not $Installed.ContainsKey($HaloPkg)) {
    $path = Find-Apk
    if (-not $path) { Die 'no encuentro el APK de Halo. Compílalo (launcher\) o pásalo: -Launcher -Apk ruta.apk' }
    Info "Instalando $(Split-Path $path -Leaf)"
    if (-not $DryRun) { & adb @AdbArgs install -r $path | Select-Object -Last 1 }
  } else { Ok 'Halo ya está instalado' }

  $stock = $StockLaunchers | Where-Object { $Installed.ContainsKey($_) }
  Info "Se desactivará el launcher de stock: $(if ($stock) { $stock -join ' ' } else { 'ninguno' })"
  if (-not (Confirm-Action '¿Continuar?')) { Info 'Cancelado.'; return }

  New-Backup
  if (-not $DryRun) { Tv @('cmd', 'package', 'set-home-activity', $HaloHome) | Out-Null }
  foreach ($p in $stock) {
    if ($DryRun) { Ok "(simulado) desactivar $p"; continue }
    if ((Tv @('pm', 'disable-user', '--user', '0', $p)) -match 'disabled') { Record 'disable' $p; Ok "desactivado $p" }
    else { Warn "no se pudo desactivar $p" }
  }
  if ($DryRun) { return }
  Tv @('am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') | Out-Null
  Info 'Listo. Pulsa HOME en el mando: debería abrirse Halo.'
  Info 'Para volver al launcher original: .\debloat.ps1 -RestoreLauncher'
}

function Invoke-RestoreLauncher {
  foreach ($p in $StockLaunchers | Where-Object { $Installed.ContainsKey($_) }) {
    if ($DryRun) { Ok "(simulado) activar $p"; continue }
    if ((Tv @('pm', 'enable', $p)) -match 'enabled') { Ok "activado $p" } else { Warn "no se pudo activar $p" }
  }
  if ($DryRun) { return }
  Tv @('am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') | Out-Null
  Info "Launcher de stock reactivado. Puedes desinstalar Halo con: adb uninstall $HaloPkg"
}

function Invoke-Restore {
  if (-not (Test-Path $Restore)) { Die "no existe $Restore" }
  foreach ($line in Get-Content -Encoding UTF8 $Restore) {
    if ($line -match '^\s*(#|$)') { continue }
    $op, $pkg = $line.Trim() -split '\s+', 2
    if ($DryRun) { Ok "(simulado) $op -> $pkg"; continue }
    switch ($op) {
      'uninstall' {
        $out = Tv @('cmd', 'package', 'install-existing', $pkg)
        if ($out -notmatch 'installed') { $out = Tv @('pm', 'install-existing', $pkg) }
        if ($out -match 'installed') { Ok "reinstalado $pkg" } else { Warn "${pkg}: $out" }
      }
      'disable' {
        if ((Tv @('pm', 'enable', $pkg)) -match 'enabled') { Ok "activado $pkg" } else { Warn "no se pudo activar $pkg" }
      }
    }
  }
  Info 'Restauración terminada.'
}

function Invoke-Scan {
  $listed = @{}
  $StockLaunchers | ForEach-Object { $listed[$_] = $true }
  Get-ChildItem (Join-Path $Lists '*.txt') | Get-Content -Encoding UTF8 | ForEach-Object {
    if ($_ -notmatch '^\s*(#|$)') { $listed[($_.Trim() -split '\s+')[1]] = $true }
  }
  Info 'Apps del sistema que no están en ninguna lista (revísalas con cuidado):'
  (Tv @('pm', 'list', 'packages', '-s')) -split "`r?`n" | ForEach-Object {
    if ($_ -match '^package:(.+)$') {
      $p = $Matches[1].Trim()
      if ($listed.ContainsKey($p)) { return }
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
