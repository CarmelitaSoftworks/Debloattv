# DebloatTV + Halo Launcher

Limpia tu Smart TV con **Google TV / Android TV** y cambia el launcher de Google, lleno de anuncios y recomendaciones, por **Halo**: un launcher ligero (≈65 KB, sin dependencias) inspirado en Apple TV. También puedes elegir **LTvLauncher**, un launcher de código abierto de la comunidad.

![Halo, pantalla de inicio](docs/halo-inicio.png)

El diseño está en Figma: [Halo – rediseño TV](https://www.figma.com/design/cOhkyFGCndvYC2di07C1EX) (pantalla de inicio, Limpiar TV, selector de launcher y componentes).

- **Halo** se instala en la TV y **también limpia la TV desde la propia TV**: no hace falta teléfono ni PC.
- **App para el teléfono** (`phone/`): hace lo mismo por WiFi desde el móvil.
- **Debloater** (`debloater/`): quita las apps preinstaladas por niveles de riesgo, detecta la marca del televisor y guarda un backup de todo lo que toca para poder deshacerlo.
- **Halo Launcher** (`launcher/`): cuadrícula limpia de apps en 16:9, foco con elevación y brillo, nombre bajo la app enfocada, lavado de color de fondo que sigue a la app, reloj, reordenar con "tembleque" y ocultar apps. Sin anuncios, sin recomendaciones, sin conexión a internet.

Todo es **reversible**: nada se borra de la partición del sistema. Las apps se quitan solo para el usuario (`pm uninstall --user 0`) y los launchers se desactivan (`pm disable-user`), así que un restablecimiento de fábrica también lo devuelve todo a como estaba.

---

## Opción A: solo con la TV (recomendada, p. ej. TCL)

Solo necesitas instalar **`dist/halo-launcher.apk`** en la TV. Formas de hacerlo sin PC:

- **Memoria USB:** copia el APK a un pendrive, conéctalo a la TV y ábrelo con el gestor de archivos (en TCL: *Gestor de archivos* o *Media Center*). Si no tienes gestor, instala *File Commander* o *X-plore* desde Play Store.
- **Desde el teléfono:** con la app *Send Files to TV* (Play Store en el móvil y en la TV).
- **Con la app Downloader** (Play Store de la TV), escribiendo la URL del APK.

La primera vez Android pedirá permitir **instalar apps desconocidas** para la app con la que lo abras: acéptalo.

Después, en Halo: ve a la última tarjeta (**⚙ Ajustes**) → **Limpiar TV**.

![Limpiar TV](docs/halo-limpiar-tv.png)

Halo se conecta al ADB de la propia TV. Basta con activar **Depuración USB** (no hace falta cable ni *Depuración inalámbrica*): Halo prueba `127.0.0.1:5555` y, si no responde, la IP WiFi/Ethernet de la propia TV, igual que haría un móvil.

- **Si funciona (modo automático):** la TV mostrará *¿Permitir depuración?*; marca **Permitir siempre** y acepta con el mando. Luego eliges el nivel, pulsas **Quitar N apps** y **Elegir launcher de inicio** (Halo o LTvLauncher). Todo se deshace con **Restaurar todo lo quitado** y **Volver al launcher original**.
  Para que funcione: Ajustes → Sistema → Información → pulsa 7 veces *Compilación*, y en *Opciones para desarrolladores* activa **Depuración USB** (y **Depuración por red** si aparece).
- **Si tu TV no lo permite (modo manual):** Halo te muestra las apps basura que tiene tu TV; pulsa cada una para abrir sus ajustes y elige **Desactivar**. Y activa **Botón Inicio → Halo**: un servicio de accesibilidad que, cuando el botón HOME abre el launcher de TCL/Google, abre Halo encima. Así Halo queda como inicio sin ADB.

> Si el interruptor de *Halo: botón Inicio* aparece bloqueado (Android 13+ lo hace con las apps instaladas desde un archivo), ve a Ajustes → Apps → Halo, pulsa ⋮ / Más opciones → **Permitir ajustes restringidos** y vuelve a intentarlo.
>
> Si usas *Botón Inicio → Halo* y luego quieres volver al launcher original, desactiva el servicio en Ajustes → Accesibilidad.

![Elegir launcher](docs/halo-elegir-launcher.png)

### Launcher alternativo: LTvLauncher

[LTvLauncher](https://github.com/leanbitlab-org/LtvLauncher) es un launcher de código abierto (GPL-3.0) para Android TV, derivado de FLauncher y mantenido activamente: fondos, categorías, widgets, sin anuncios. Pesa unos 10 MB. Si lo eliges, la app descarga la última versión desde sus releases de GitHub (la variante para el procesador de tu TV), la instala y la deja como inicio. Halo sigue instalado para abrir **Limpiar TV** desde la lista de apps.

## Opción B: desde el teléfono

`dist/debloattv-telefono.apk` es una app Android (75 KB) que habla directamente con la TV por WiFi. Lleva dentro las listas de paquetes y el launcher Halo.

1. **En la TV:** Ajustes → Sistema → Información → pulsa OK 7 veces sobre *Compilación* hasta que salga "Ya eres desarrollador".
2. **En la TV:** Ajustes → Sistema → Opciones para desarrolladores → activa **Depuración USB** (y **Depuración por red** si existe).
   No hace falta la opción *Depuración inalámbrica* de Android 11+: la app se conecta igual que `adb connect IP:5555`. En la mayoría de televisores, con *Depuración USB* activada ya se acepta la conexión por la red.
3. **En el teléfono:** instala `debloattv-telefono.apk` (permite "instalar apps desconocidas" cuando te lo pida) y conéctalo a la misma WiFi que la TV.
4. Abre DebloatTV → **Buscar TV** → **Conectar**. En la TV saldrá *¿Permitir depuración?*: marca **Permitir siempre** y acepta.
5. Elige el nivel, revisa la lista, pulsa **Quitar seleccionadas** y luego **Instalar Halo**.

Para deshacer, en la misma app: **Restaurar todo** y **Volver al original**.

> **Si "Buscar TV" no la encuentra:** tu TV no abre el puerto 5555 solo con *Depuración USB*. Prueba a activar *Depuración por red* si tu modelo la tiene. Si no, conecta la TV una vez a un PC por USB y ejecuta `adb tcpip 5555`; desde ese momento la app funciona por WiFi hasta que reinicies la TV.

## Opción C: desde un PC con los scripts

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
| Limpiar TV, ajustes del sistema, botón Inicio | Última tarjeta (engranaje) |

## 4. Compilar Halo tú mismo (opcional)

Requiere JDK 17+ y el Android SDK (o Android Studio).

```bash
cd launcher
./gradlew assembleRelease
# APK en launcher/app/build/outputs/apk/release/app-release.apk
```

GitHub Actions compila los dos APK en cada cambio (pestaña *Actions → Compilar APKs → Artifacts*).

La app del teléfono se compila igual desde `phone/` (`./gradlew assembleRelease`) y empaqueta `dist/halo-launcher.apk` y `debloater/lists/`.

El APK release está firmado con la clave de depuración para que se pueda instalar directamente. Si compilas tu propia versión con otra clave, desinstala antes la de `dist/` (`adb uninstall com.carmelita.halo`), porque Android no deja actualizar una app firmada con una clave distinta.

## Estructura

```
debloater/
  debloat.sh       Linux / macOS
  debloat.ps1      Windows (PowerShell)
  debloat.bat      Atajo para Windows
  lists/*.txt      Listas de paquetes por marca y nivel
launcher/          Código fuente de Halo (Java, sin dependencias), incluida la pantalla Limpiar TV
shared/            Cliente ADB y reglas de limpieza compartidos por Halo y la app del teléfono
phone/             App DebloatTV para el teléfono (cliente ADB por WiFi en Java, sin dependencias)
dist/              APKs listos para instalar
```

## Aviso

Úsalo bajo tu responsabilidad. Los nombres de paquete cambian entre modelos y versiones de firmware: ejecuta siempre primero con `-n` / `-DryRun` y revisa la lista. Si algo deja de funcionar, usa `--restore` con el backup correspondiente.
