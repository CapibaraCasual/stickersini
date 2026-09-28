# Instalación

Compilar y probar Stickersini desde el código fuente. No hay versión
publicada en Google Play todavía (ver README, "Estado").

## Requisitos

- Android Studio (o el SDK/NDK de línea de comandos equivalente) con el
  NDK y CMake instalados — `:webp` y `:yuv` compilan código nativo.
- Un dispositivo o emulador Android 8.0 (API 26) o superior.
- Sin conexión a internet más allá de la que Gradle necesita para bajar
  dependencias la primera vez: la app en sí no la requiere (RNF-01).

## Compilar e instalar (debug)

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Este es el build normal de desarrollo. El código nativo de `:webp`
compila **sin optimizar** (`-O0`): suficiente para probar la app, pero
mucho más lento que lo que verá un usuario real — no sirve para medir
nada contra un presupuesto de tiempo (RNF-08 y similares). Ver
ADR-0019 y CLAUDE.md, "Errores conocidos".

## Compilar e instalar el build de release, para probar el binario real

`release` firma con la key de debug (no hay keystore de producción
todavía — ver README, `applicationId` pendiente), así que se instala
exactamente igual que el de debug, sin pasos extra:

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Esto compila `:webp`/`:yuv` con `-O2` (5.9×-6.6× más rápido en la
codificación WebP que el build de debug, medido en ADR-0019) — es el
binario que de verdad se parece a lo que llegaría a producción. Usar este
build, no el de debug, para juzgar fluidez, tiempos de conversión o
cualquier sensación de "se siente lento".

**No es una firma de distribución.** No usar `app-release.apk` para
publicar en Google Play ni para compartir fuera de pruebas propias.
