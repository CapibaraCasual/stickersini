# ADR-0005: Vendorizar el código fuente de libwebp, no usarlo como submódulo ni como binding de terceros

- Estado: Aceptado
- Fecha: 2026-09-20

## Contexto

ADR-0002 ya decidió *qué* codifica los stickers: `WebPAnimEncoder` de
libwebp, invocado por JNI. Queda por decidir *de dónde sale el código* que se
compila, porque de esa elección depende cómo se clona, se compila y se
actualiza el proyecto de aquí en adelante. ADR-0002 dejó dos bindings JNI ya
publicados como referencia (`UdaraWanasinghe/webp-android`,
`b4rtaz/android-webp-encoder`), con la condición explícita de verificar su
licencia frente a RNF-10 antes de adoptar cualquiera.

Se verificaron ambos antes de escribir código, no después.

## Opciones consideradas

1. **`b4rtaz/android-webp-encoder`** — descartada. GitHub reporta
   `license: null` y no existe archivo `LICENSE` en el repositorio (404 al
   pedirlo). Sin licencia explícita rige el copyright por defecto: nadie
   puede usarlo ni redistribuirlo sin permiso expreso del autor. Queda fuera
   sin necesidad de evaluar nada más.

2. **`UdaraWanasinghe/webp-android`** (publicado en Maven Central como
   `com.aureusapps.android:webp-android`) — descartada, y da pena descartarla
   porque el diseño es bueno: MIT (compatible con GPL-3.0, RNF-10 no pone
   objeción) y su `WebPConfig` expone prácticamente el struct completo de
   libwebp (`quality`, `method`, `targetSize`, `targetPSNR`, `qmin`/`qmax`,
   etc.), incluyendo `targetSize` en bytes, que habría resuelto RF-12 casi
   gratis dejando que el propio encoder de libwebp ajuste la calidad
   internamente hasta caber en el límite.
   El motivo del descarte está en su POM publicado, no en su código: declara
   como dependencia en tiempo de ejecución `com.squareup.okhttp3:okhttp:4.12.0`
   (para poder cargar frames desde una URI `http://`, según su propio
   `README`). Eso mete un cliente HTTP dentro del APK aunque esta app nunca
   invoque esa ruta. Choca con la restricción 1 de `CLAUDE.md`: "no añadas
   ninguna dependencia que requiera conectividad" (RNF-01), y socava
   justo el argumento central del producto — que no hay forma de que salga
   nada del teléfono. Cualquiera que audite las dependencias del APK
   encontraría un cliente HTTP en una app que se anuncia como 100% local.
   Excluir el transitivo con Gradle (`exclude group: "com.squareup.okhttp3"`)
   es técnicamente posible, pero es forzar una librería a hacer algo para lo
   que no fue publicada, no una integración limpia.

3. **libwebp como submódulo de git** (`webmproject/libwebp`, tag `v1.6.0`,
   BSD-3-Clause, compatible con RNF-10) — descartada. Con un solo
   desarrollador y sin colaboradores activos, el beneficio típico de un
   submódulo (que cada quien sincronice su propia copia) no aplica. A cambio
   suma dos puntos de fallo nuevos sobre un build que ADR-0002 ya marcó como
   el más fràgil del proyecto: `git submodule update --init --recursive`
   antes de compilar, fácil de olvidar y la causa más común de "no me
   compila" al clonar un repo con submódulos, y (para cuando exista) CI
   necesitando la misma bandera. Y sigue exigiendo red la primera vez que
   alguien clona, justo lo que este proyecto evita a propósito en todo lo
   demás.

4. **`FetchContent`/`ExternalProject_Add` de CMake para bajar el tarball de
   libwebp en tiempo de configuración** — descartada por la misma razón que
   el submódulo: exige red la primera vez que se compila y añade un modo de
   fallo más ("no se pudo descargar libwebp") a un build que ya es fràgil.

5. **Vendorizar una copia recortada del código fuente de libwebp dentro del
   repositorio** — elegida.

## Decisión

Copiar dentro de `webp/src/main/cpp/third_party/libwebp/` el árbol completo
de `src/`, `sharpyuv/` y `cmake/` del tag `v1.6.0`, junto con
`CMakeLists.txt`, `COPYING` (BSD-3-Clause), `PATENTS` y `AUTHORS`. La
tentación inicial era copiar solo los módulos que a simple vista hacen falta
(`enc`, `dsp`, `utils`, `mux`, dejando fuera `dec`), pero el propio
`CMakeLists.txt` de libwebp arma el target `webp` a partir de objetos que
incluyen el decodificador (`$<TARGET_OBJECTS:webpdecode>`) aunque solo se
necesite codificar, así que recortar `src/dec/` a mano habría roto ese build
sin ahorrar nada relevante. Recortar a mano un árbol de código de terceros
para "ahorrar espacio" es exactamente el tipo de divergencia silenciosa que
hace imposible diferenciar limpio contra el próximo tag.

Lo que sí se deja fuera son directorios que el propio `CMakeLists.txt` de
libwebp nunca toca si las opciones de build correspondientes están en `OFF`
— se verificó leyendo ese archivo antes de decidir, no por suposición:
`examples/` e `imageio/` (compilados solo si algún `WEBP_BUILD_{CWEBP,
DWEBP, GIF2WEBP, IMG2WEBP, VWEBP, WEBPMUX, WEBPINFO, EXTRAS}` está en `ON`),
`tests/fuzzer` (solo si `WEBP_BUILD_FUZZTEST=ON`), `man/` (instalación de
páginas de manual de las herramientas de línea de comandos, que no se
compilan), `swig/` y `webp_js/` (bindings a otros lenguajes y build de
Emscripten, ninguno relevante aquí), y el `build.gradle`/`gradlew` propios
de libwebp (tiene su propio empaquetado como AAR independiente; no se usa
porque este proyecto lo integra por CMake, no consumiendo su Gradle).
Nuestro `webp/src/main/cpp/CMakeLists.txt` hace `add_subdirectory()` sobre
esa copia con esas opciones en `OFF`, y enlaza contra los targets `webp` y
`libwebpmux` (este último es el que expone `WebPAnimEncoder`, declarado en
`src/webp/mux.h` e implementado en `src/mux/anim_encode.c`).

Sobre esa copia se escribe la capa JNI propia (`webp/src/main/cpp/jni/`),
sin ningún binding de terceros de por medio.

## Procedencia del código vendorizado

| Campo | Valor |
|---|---|
| Proyecto de origen | https://github.com/webmproject/libwebp |
| Versión | v1.6.0 |
| Commit exacto | `4fa21912338357f89e4fd51cf2368325b59e9bd9` |
| Fecha de esa release | 2025-06-30 |
| Fecha de la copia a este repo | 2026-09-20 |
| Licencia | BSD-3-Clause (`COPYING`), más `PATENTS` (concesión de patentes de Google) |
| Directorios copiados | `src/`, `sharpyuv/`, `cmake/`, `CMakeLists.txt`, `COPYING`, `PATENTS`, `AUTHORS` |
| Directorios omitidos a propósito | `examples/`, `imageio/`, `tests/`, `doc/`, `man/`, `swig/`, `webp_js/`, `extras/`, y el build propio de libwebp para Autotools/iOS/Gradle (`configure.ac`, `Makefile.am`, `iosbuild.sh`, `xcframeworkbuild.sh`, `gradlew`, `build.gradle`) |

El mismo contenido de esta tabla vive también en
`webp/src/main/cpp/third_party/libwebp/PROCEDENCIA.md`, para que se pueda
ver sin salir de esa carpeta ni ir a buscar este ADR.

## Mantenimiento

Actualizar la copia vendorizada es un proceso manual y deliberado, no un
comando:

1. Revisar el `ChangeLog` de libwebp entre el commit vendorizado y el tag
   nuevo. Prestar atención especial a avisos de seguridad: libwebp ha tenido
   CVEs explotables antes (el más conocido, CVE-2023-4863, un desbordamiento
   de búfer en el decodificador VP8L explotado activamente). Si el motivo de
   la actualización es un CVE, no esperar al próximo ciclo de trabajo:
   tratarlo con la misma urgencia que cualquier vulnerabilidad de una
   dependencia declarada.
2. Descargar el `src/`, `sharpyuv/` y `cmake/` del tag nuevo y reemplazar por
   completo los de `webp/src/main/cpp/third_party/libwebp/` (no aplicar un
   diff a mano archivo por archivo: reemplazar el árbol entero evita quedarse
   con una mezcla de dos versiones).
3. Actualizar `PROCEDENCIA.md` y esta tabla con el tag, commit y fecha
   nuevos.
4. Recompilar (`./gradlew :webp:externalNativeBuildDebug` o equivalente) y
   correr los tests instrumentados de codificación antes de dar la
   actualización por buena: un cambio de versión de libwebp puede alterar
   sutilmente el tamaño de salida a igual `quality`, lo que afecta
   directamente al ajuste de RF-12.
5. Commit propio para la actualización, sin mezclar con otros cambios.

## Consecuencias

- `git clone` seguido de `./gradlew assembleDebug` compila sin pasos
  manuales adicionales ni acceso a red más allá del que Gradle ya necesita.
  Eso importa doblemente aquí: es un proyecto de portafolio que otras
  personas van a clonar para evaluarlo.
- Actualizar libwebp a una versión nueva es trabajo manual (traer el tag
  nuevo, revisar el diff, volver a copiar los módulos necesarios), no un
  comando. Es aceptable: libwebp es una librería madura con cambios poco
  frecuentes, y este proyecto no necesita ir a la última versión el mismo
  día que sale.
- El repositorio crece unos megabytes de código C vendorizado. No afecta al
  tamaño del AAB (RNF-07): el `.so` compilado pesa lo mismo salga el código
  fuente de un submódulo, de una descarga en tiempo de build o de una copia
  vendorizada.
- La capa JNI la escribe este proyecto, no una librería externa. Es más
  trabajo que adoptar `webp-android`, pero es la parte de "trabajo específico
  de Android" que ADR-0001 ya identificó como el punto central del
  portafolio, y da control total sobre `WebPConfig`.
- **RF-12 queda como implementación propia, sin atajos.** El campo nativo
  `target_size` de `WebPConfig` sí está disponible ahora que se controla el
  struct completo, pero opera por fotograma, no sobre el archivo animado
  final: cada llamada a `WebPAnimEncoderAdd` codifica un fotograma
  individual, y el límite de 500 KB de RF-10 es sobre el contenedor completo
  ya ensamblado (cabeceras ANMF, chunk `ANIM`, todos los fotogramas juntos).
  Repartir 500 KB entre fotogramas a priori (por ejemplo, 500 KB / número de
  fotogramas) no es fiable: la cabecera del contenedor y el peso de cada
  fotograma no son uniformes. Por eso el ajuste automático de RF-12 —
  codificar, medir el tamaño del archivo ya ensamblado, y volver a codificar
  a otra calidad si no cabe — hay que escribirlo a mano en este proyecto,
  iterando calidad y, si hace falta, también tasa de fotogramas. Esto es un
  costo asumido de descartar `webp-android` (que tampoco resolvía esto solo
  con `targetSize`, por la misma razón, pero no se llegó a comprobar porque
  se descartó antes por la dependencia de OkHttp), no una omisión: se deja
  constancia aquí para que quede explícito en el diseño del módulo `webp`, no
  como sorpresa durante la implementación.
- Queda descartado adoptar `webp-android` más adelante sin revisar este ADR:
  si en el futuro publican una versión sin la dependencia de OkHttp, eso
  reabre la opción 2, pero requiere un ADR nuevo que reemplace a este, no un
  cambio silencioso.
