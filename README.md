# DebloatTV + Halo Launcher

Limpia tu Smart TV con **Google TV / Android TV** y cambia el launcher de Google, lleno de anuncios y recomendaciones, por **Halo**: un launcher ligero (≈26 KB, sin dependencias) inspirado en Apple TV.

- **Debloater** (`debloater/`): quita las apps preinstaladas por niveles de riesgo, detecta la marca del televisor y guarda un backup de todo lo que toca para poder deshacerlo.
- **Halo Launcher** (`launcher/`): cuadrícula limpia de apps en 16:9, foco con elevación y brillo, nombre bajo la app enfocada, lavado de color de fondo que sigue a la app, reloj, reordenar con "tembleque" y ocultar apps. Sin anuncios, sin recomendaciones, sin conexión a internet.

Todo es **reversible**: nada se borra de la partición del sistema. Las apps se quitan solo para el usuario (`pm uninstall --user 0`) y los launchers se desactivan (`pm disable-user`), así que un restablecimiento de fábrica también lo devuelve todo a como estaba.

---

## 1. Preparar la TV

1. **Ajustes → Sistema → Información → Compilación de Android TV**: pulsa OK 7 veces hasta que salga "Ya eres desarrollador".
2. **Ajustes → Sistema → Opciones para desarrolladores**: activa **Depuración USB** (y **Depuración por red/inalámbrica** si aparece).
3. Apunta la IP de la TV: **Ajustes → Red e Internet → (tu red)**.
4. Instala [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) en tu PC para tener `adb`.

La primera vez que te conectes, la TV pedirá permiso: marca *Permitir siempre* y acepta.

## 2. Debloat

Linux / macOS:

```bash
cd debloater
./debloat.sh -i 192.168.1.50 -n          # simulación: muestra qué se quitaría
./debloat.sh -i 192.168.1.50             # nivel "recomendado"
```

Windows (doble clic en `debloat.bat` o desde una terminal):

```powershell
cd debloater
.\debloat.bat -Ip 192.168.1.50 -DryRun
.\debloat.bat -Ip 192.168.1.50 -Level recomendado
```

### Niveles

| Nivel | Qué quita | Riesgo |
|---|---|---|
| `seguro` | Telemetría, informes de errores, Play Juegos, YouTube Music, impresión, demos de tienda | Ninguno |
| `recomendado` *(por defecto)* | Lo anterior + tienda de películas, recomendaciones/anuncios del home, modo ambiente, promos del fabricante | Bajo |
| `agresivo` | Lo anterior + Asistente de Google, Chromecast integrado, Live Channels, TTS y apps de streaming preinstaladas | **Pierdes la voz y el cast** |

Hay listas para **Google** (siempre se aplica), **Sony, TCL, Xiaomi, Hisense y Philips** (se elige sola según la marca). Están en `debloater/lists/` y se pueden editar.

Paquetes críticos como Play Store, Play Services, el teclado o los ajustes están protegidos y no se tocan nunca, aunque los pongas en una lista.

### Revisar tu TV a mano

```bash
./debloat.sh -i 192.168.1.50 --scan
```

Muestra las apps del sistema que no aparecen en ninguna lista. Si quieres quitar alguna, crea un archivo `mis-apps.txt`:

```
recomendado  com.fabricante.appbasura   Descripción
```

y ejecuta `./debloat.sh -i 192.168.1.50 --list mis-apps.txt`.

### Deshacer

Cada ejecución guarda un backup en `debloater/backups/`. Para restaurarlo:

```bash
./debloat.sh -i 192.168.1.50 --restore backups/chromecast-20261025-1430.txt
```

```powershell
.\debloat.bat -Ip 192.168.1.50 -Restore backups\chromecast-20261025-1430.txt
```

## 3. Cambiar el launcher por Halo

```bash
./debloat.sh -i 192.168.1.50 --launcher
```

```powershell
.\debloat.bat -Ip 192.168.1.50 -Launcher
```

Instala `dist/halo-launcher.apk`, lo marca como pantalla de inicio y desactiva el launcher de stock (Google TV, Android TV, PatchWall de Xiaomi). Pulsa **HOME** en el mando y aparecerá Halo.

Para volver al launcher original:

```bash
./debloat.sh -i 192.168.1.50 --restore-launcher
adb uninstall com.carmelita.halo      # opcional
```

> En Google TV el script también desactiva `com.google.android.tungsten.setupwraith`, que es lo que reabre el launcher de Google al pulsar HOME. Si alguna vez necesitas el asistente de configuración inicial de Google, reactívalo con `--restore-launcher`.

### Usar Halo

| Acción | Mando |
|---|---|
| Abrir app | OK |
| Opciones (mover, ocultar, info, desinstalar) | Mantener OK, o botón ☰ Menú |
| Mover app | Opciones → Mover, flechas para colocarla y OK para soltarla |
| Ver apps ocultas | Opciones → Ver apps ocultas |
| Volver arriba | Atrás o HOME |
| Ajustes del sistema | Última tarjeta (engranaje) |

## 4. Compilar Halo tú mismo (opcional)

Requiere JDK 17+ y el Android SDK (o Android Studio).

```bash
cd launcher
./gradlew assembleRelease
# APK en launcher/app/build/outputs/apk/release/app-release.apk
```

GitHub Actions lo compila automáticamente en cada cambio en `launcher/` (pestaña *Actions → Compilar Halo Launcher → Artifacts*).

El APK release está firmado con la clave de depuración para que se pueda instalar directamente. Si compilas tu propia versión con otra clave, desinstala antes la de `dist/` (`adb uninstall com.carmelita.halo`), porque Android no deja actualizar una app firmada con una clave distinta.

## Estructura

```
debloater/
  debloat.sh       Linux / macOS
  debloat.ps1      Windows (PowerShell)
  debloat.bat      Atajo para Windows
  lists/*.txt      Listas de paquetes por marca y nivel
launcher/          Código fuente de Halo (Java, sin dependencias)
dist/              APK de Halo listo para instalar
```

## Aviso

Úsalo bajo tu responsabilidad. Los nombres de paquete cambian entre modelos y versiones de firmware: ejecuta siempre primero con `-n` / `-DryRun` y revisa la lista. Si algo deja de funcionar, usa `--restore` con el backup correspondiente.
