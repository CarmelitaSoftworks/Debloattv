# Ecualizador

App Android (Kotlin + Jetpack Compose) con ecualizador de 10 bandas para todo el teléfono, visor de
espectro en tiempo real, widget, fondo animado y skins de Winamp (clásicos, temas de Winamp 5.9 e
importación de archivos `.wsz` / `.wal`, de los que solo se guardan los colores).

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
- `SkinImportTest.classicImportMatchesBuiltInCatalog` con `-DclassicWszDir=<carpetas>` comprueba que
  el importador de la app da los mismos colores que el catálogo generado.
