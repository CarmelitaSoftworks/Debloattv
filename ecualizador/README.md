# Ecualizador

App Android (Kotlin + Jetpack Compose) con ecualizador de 10 bandas para todo el teléfono, visor de
espectro en tiempo real, widget, fondo animado y skins de Winamp (clásicos, temas de Winamp 5.9 e
importación de archivos `.wsz` / `.wal`). Los skins clásicos se ven con su ventana real y el visor
dentro de su recuadro, como en Winamp.

## Compilar

```sh
export ANDROID_HOME=/ruta/al/sdk
./gradlew lintRelease testDebugUnitTest assembleRelease
```

El APK de release sale sin firmar salvo que exista `keystore.properties` (fuera de git):

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## Skins

- `WinampClassicSkins.kt`: generado por `tools/gen_skins.py` a partir de los colores extraídos con
  `tools/chrome.py` de skins de la colección «winampskins» de Internet Archive.
- `WinampModernSkins.kt`: generado por `SkinImportTest.generateModernThemes` con
  `-DwinampModernDir=<carpeta con Winamp Modern y Big Bento> -DmodernSkinsOut=<archivo>`.
- `assets/skins/<archiveId>.png`: la ventana principal (275×116) de cada skin clásico, armada con
  `WinampWindow` a partir de sus BMP (MAIN, TITLEBAR, CBUTTONS, POSBAR, VOLUME, BALANCE, SHUFREP,
  MONOSTER, PLAYPAUS, NUMBERS y TEXT). La genera `SkinImportTest.generateClassicWindows` con
  `-DclassicWszDir`, `-DwinampExeBitmaps` (recursos BITMAP de winamp.exe, para el skin base) y
  `-DwindowsOut`. Son obras de los autores de cada skin (colección «winampskins» de Internet
  Archive) y del skin base de Winamp; los .wsz originales no están en el repositorio.
- Los skins que el usuario importa guardan su ventana en los archivos de la app, en el teléfono.
- `SkinImportTest.classicImportMatchesBuiltInCatalog` con `-DclassicWszDir=<carpetas>` comprueba que
  el importador de la app da los mismos colores que el catálogo generado.
