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

Copiar dentro de `webp/src/main/cpp/third_party/libwebp/` únicamente los
módulos de libwebp que hacen falta para codificar — `src/enc/`, `src/dsp/`,
`src/utils/`, `src/webp/` (headers), `src/mux/` y `sharpyuv/` — a partir del
tag `v1.6.0`, junto con su archivo `COPYING` (BSD-3-Clause) intacto. Se deja
fuera todo lo que no se usa: `src/dec/` completo salvo lo que `mux`/`demux`
necesiten internamente, `examples/`, `imageio/`, `swig/` y los tests.

Un `README.md` dentro de esa carpeta anota el tag exacto vendorizado y la
fecha, para que actualizar sea un proceso deliberado: comparar contra el tag
nuevo, no un `git submodule update` automático.

Sobre esa copia se escribe la capa JNI propia (`webp/src/main/cpp/jni/`),
sin ningún binding de terceros de por medio.

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
  portafolio, y da control total sobre `WebPConfig` para resolver RF-12
  (bisección de calidad o `target_size` nativo de libwebp) sin depender de
  que un tercero exponga el parámetro que haga falta.
- Queda descartado adoptar `webp-android` más adelante sin revisar este ADR:
  si en el futuro publican una versión sin la dependencia de OkHttp, eso
  reabre la opción 2, pero requiere un ADR nuevo que reemplace a este, no un
  cambio silencioso.
