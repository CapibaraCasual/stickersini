# Stickersini

Convierte lo que ves en tu pantalla en stickers de WhatsApp, sin cuenta, sin
anuncios y sin que nada salga de tu teléfono.

<!-- ![captura](docs/img/captura.png) -->

## Qué es

Las aplicaciones que convierten video en stickers existen, pero casi todas
piden registro, suben tu contenido a un catálogo público y viven de anuncios.
Stickersini hace lo mismo sin nada de eso: graba tu pantalla, toma un video que
ya tenías, una foto o una captura, y lo convierte en un sticker animado o
estático listo para WhatsApp.

Todo el procesamiento ocurre en el dispositivo. La aplicación no tiene permiso
de internet.

## Estado

En desarrollo. Versión actual: `0.7.0-alpha`. Todavía no hay versión
publicada en Google Play.

**Rendimiento queda cerrado con esta versión.** Las Fases 0-2 (WhatsApp,
codificador, importación de video/imagen) están validadas en dispositivo
real; el fps de prefiltro de video (8, ADR-0012) es el techo real de este
pipeline, no una elección conservadora, y el decode ya corre en paralelo
con la conversión (ADR-0015). No hay ningún número de rendimiento
pendiente de decidir — lo que sigue es medir en más hardware, no cambiar
valores en el único dispositivo probado hasta ahora (ver "Qué falta").
**El siguiente foco es la interfaz**: las cuatro pantallas del recorrido
de Fase 3 funcionan pero son toscas, y falta trabajo de diseño visual, no
de funciones nuevas.

### Qué funciona ya

- **Fase 0 — integración con WhatsApp.** El `ContentProvider` (ADR-0004)
  está validado en dispositivo real: WhatsApp acepta el pack semilla y sus
  3 stickers aparecen en la bandeja de un chat.
- **Fase 1 — codificador WebP.** El módulo `:webp` (libwebp vendorizado,
  ADR-0005; estrategia de codificación en ADR-0006 y ADR-0007) está
  validado en dispositivo real, en corrección y en rendimiento: cumple
  RF-10, RF-12, RF-13 y RNF-08 con margen real medido, tanto en contenido
  representativo como en el peor caso adverso probado. Detalle completo en
  [`docs/desarrollo/pruebas.md`](docs/desarrollo/pruebas.md).
- **Fase 2 — importación de video e imagen, cerrada (RF-02, RF-03).** El
  módulo `media/` decodifica un video o una imagen/foto existente hacia
  fotogramas listos para `:webp` (ADR-0008: ruta CPU vía `ImageReader`,
  confirmada en dispositivo real, sin necesitar GPU; ADR-0009: fps de
  prefiltro derivado del piso de ADR-0007). Video e imagen comparten el
  mismo recorte al cuadrado central (`SquareCrop`) y el mismo
  `WebpAnimEncoder`: un sticker estático (RF-11) es una animación de un solo
  fotograma, no un codificador aparte (ADR-0002; medido que el contenedor de
  animación no agrega un sobrecosto relevante contra el límite de 100 KB —
  ver `docs/desarrollo/pruebas.md`). Orientación EXIF corregida y verificada
  con una prueba que sigue una marca visual a través de la rotación, no solo
  el tamaño de salida. **RNF-08 validado con una grabación de pantalla real
  en un Redmi Note 14:** un clip de 3 s (la referencia del propio requisito)
  convierte en 3 163 ms, con margen; el máximo de 10 s cae, como está
  previsto, en el segundo tramo del requisito (≤20 s). Detalle completo, con
  todas las rondas de medición, en
  [`docs/desarrollo/pruebas.md`](docs/desarrollo/pruebas.md). Alcance de
  esta fase: produce un WebP válido a partir de un video o una imagen
  existente, sin UI todavía (selección de archivo, recorte de área), sin
  guardar el resultado como sticker de un pack ni entregarlo a WhatsApp —
  eso sigue en "Qué falta".
- **Fase 3 — en curso: recorrido de punta a punta con gestión de packs.**
  Elegir un video o una imagen → **selector de tramo (RF-06) si es video** →
  **encuadre con pellizco (RF-07)** → conversión con progreso real → vista
  previa (un solo fotograma, no animada todavía) → **elegir en qué pack
  guardarlo (RF-15)** → guardar.
  - **Gestión de packs (RF-15):** una pantalla nueva lista todos los packs
    (los dos semilla más los propios), deja crear uno con nombre y tipo
    (animado/estático, RF-18), renombrarlo, eliminarlo y quitarle un
    sticker — ninguna de esas tres últimas acciones sobre un pack semilla,
    que sigue siendo de solo lectura (ADR-0004, ADR-0014). Un pack propio
    recién creado (o que bajó de 3 stickers al quitarle uno) queda
    invisible para WhatsApp hasta volver a llegar al mínimo de RF-16, sin
    dejar de ser editable desde esta pantalla (ADR-0014) — la app avisa
    antes de eliminar un pack que ya se hubiera agregado a WhatsApp, porque
    el contrato WAStickerApps no tiene forma de retirarlo del lado de
    WhatsApp.
  - Navegación entre pasos con Navigation Compose (ADR-0013), reemplazando
    el booleano a mano que conmutaba entre las dos únicas pantallas de
    antes: necesario en cuanto el flujo de creación pasó a tener más de un
    paso propio (elegir archivo → tramo → recorte → convertir/guardar).
  - El selector de tramo (`TrimScreen`) lee la duración real del video con
    `MediaMetadataRetriever` y deja elegir cualquier ventana de hasta 10 s
    dentro de ella con un `RangeSlider`, con una miniatura del fotograma de
    inicio como vista previa.
  - El encuadre con pellizco (`CropScreen`) deja mover y redimensionar el
    cuadrado de recorte sobre una vista previa del contenido ya orientado
    correctamente, arrancando siempre centrado (mismo recorte automático
    de antes) mientras el usuario no toque nada. El recorte se guarda
    como fracciones relativas al contenido, no píxeles absolutos: la
    misma elección sigue alineada aunque la vista previa y la
    decodificación final usen resoluciones distintas.
  - `StickerConversionPipeline` reporta avance real en cada etapa
    (fotogramas decodificados, intento de codificación), no un indicador
    indeterminado.
  - Segundo pack semilla, animado (`sticker_pack_semilla_animado`): sin
    él, el primer sticker animado del usuario no tenía dónde entrar sin
    volver a chocar con el mínimo de 3 de RF-16 (ADR-0004, ADR-0010).
  - **La conversión YUV→RGB del video se movió a un módulo nativo nuevo,
    `:yuv`** (C vía JNI, ADR-0011): medida como el 54-63% del tiempo de
    decodificar un video, quedó 2.87×-3.51× más rápida, validada píxel a
    píxel contra la implementación anterior en Kotlin (que se conserva
    como referencia).
  - **El fps de prefiltro de video sube de 5 a 8** (ADR-0012, reemplaza
    ADR-0009): con el decode más barato, es el valor más alto que cumple
    de forma confiable —contando el peor caso de 5 corridas, no la
    mediana— los dos tramos de tiempo de RNF-08 en clips de 3, 5 y 10 s.
    Medido en un solo dispositivo hasta ahora, con margen ajustado (12%)
    en los clips de 5 y 10 s — ver `docs/desarrollo/pruebas.md` para por
    qué eso importa antes de sumar una segunda fila de hardware.
    **Este valor no se toca sin volver a medir**: 8 sale de la medición de
    5 corridas de ADR-0012, y 9 ya rompía el tramo de 5 s en 2 de 5 —
    subirlo a mano incumpliría RNF-08 a propósito. La única vía legítima
    para más fluidez es abaratar el decode todavía más (mismo camino que
    ya funcionó una vez: mover la conversión YUV→RGB a `:yuv`, ADR-0011),
    no cambiar el número. **Confirmado de nuevo el 2026-09-26**, barriendo
    además resolución de codificación (512/448/384/320) por si codificar
    más chico y escalar a 512 al final dejaba subir el fps, como hace
    Sticker.ly: no hay ninguna combinación con fps>8 que cumpla RNF-08 en
    ninguna resolución — el costo que domina al subir fps es decodificar
    más fotogramas, no el tamaño del archivo final, así que bajar
    resolución ataca el cuello de botella equivocado. Detalle completo del
    barrido (36 combinaciones, 180 corridas) en `docs/desarrollo/pruebas.md`.
  - **8 fps es el techo de este pipeline, no una elección conservadora
    (ADR-0015, cierra el tema).** Investigadas las dos vías que quedaban:
    decodificar a menor resolución (el dispositivo medido ignora un
    `ImageReader` más chico que el nativo, no hay ganancia posible ahí) y
    paralelizar la codificación de fotogramas (`WebPAnimEncoderAdd` es
    secuencial por diseño —cada fotograma necesita el anterior para decidir
    si sale como diferencia o como cuadro clave—, y el único parámetro de
    hilos de `libwebp` que aplica a la configuración de producción
    (`thread_level`) midió 1-13% *más lento*, nunca más rápido). Subir fps
    de verdad exigiría dejar de usar `WebPAnimEncoderAdd` y reimplementar a
    mano, con la API de más bajo nivel de `libwebpmux`, la decisión de
    cuadro-clave-vs-diferencia que hoy da gratis — con riesgo real de
    superar el límite de 500 KB de RF-10 si esa reimplementación comprime
    peor. **Descartado por ahora**, no medido: ver "Qué falta" para qué
    haría falta medir antes de intentarlo.
  - **El decode se paraleliza con la conversión del fotograma siguiente
    (ADR-0015, implementado en producción).** No sube el fps (el cuello de
    botella es el codificador, no el decode, ver arriba), pero recupera
    margen real en los tres tramos de RNF-08, sobre todo el más ajustado
    (el clip de 10 s, máximo de RF-06): el peor caso de 5 corridas pasa de
    17 589 ms (12.1% de margen, ADR-0012) a **15 319 ms (23.4% de
    margen)**; el clip de 5 s pasa de 12.0% a **30.6%**, y el de 3 s de
    41.1% a **52.9%**. Validado con el mismo método de 5 corridas de
    ADR-0012, invocaciones separadas de `am instrument` (importa: ver la
    corrección de método más abajo). Detalle completo,
    incluida una corrección de método importante (un primer intento de
    remedición dio un falso positivo de incumplimiento de RNF-08 por medir
    mal, corriendo 135 conversiones seguidas en un solo proceso, no por un
    error de la app) en `docs/desarrollo/pruebas.md` y ADR-0015.

### Qué falta

Rendimiento cerrado (ver "Estado" arriba). **Siguiente foco: interfaz.**
Pendientes, en el orden en que probablemente importen:

1. **Trabajo de diseño visual de las cuatro pantallas** del recorrido de
   Fase 3 (`TrimScreen`, `CropScreen`, la de conversión/guardado, la de
   gestión de packs): funcionan, pero son toscas. El criterio para el
   guardado: que nunca se sienta como un trámite administrativo (el
   mecanismo de packs semilla ya lo permite —guardar es instantáneo—,
   falta que la pantalla lo transmita).
2. **Reproducir el video en `TrimScreen`**, para elegir el fragmento
   viéndolo en vez de solo por segundos.
3. **Rotar el contenido durante el encuadre en `CropScreen`**, además de
   moverlo y ampliarlo.
4. **Cola de varios archivos** (RF-25, agregado a
   `docs/desarrollo/requisitos.md`): seleccionar varios y editarlos uno
   tras otro.
5. **Los seis stickers semilla definitivos y los avisos de licencia
   (RNF-11).** Los placeholders actuales (3 estáticos, 3 animados) son
   cuadrados de color plano de prueba, no material de marca — ADR-0004 los
   hace permanentes, así que hay que reemplazarlos antes de publicar. El
   código de libwebp viaja vendorizado (ADR-0005), sin que ninguna
   herramienta automática de licencias lo detecte: hay que añadir su
   `COPYING` a la pantalla de licencias a mano.
6. **Segunda fila de dispositivo en `docs/desarrollo/pruebas.md`.** Todas
   las mediciones de rendimiento hasta ahora son de un único Xiaomi Redmi
   Note 14. El decode paralelo (ADR-0015) mejoró el margen de 8 fps de
   prefiltro en los tres tramos (12.1%→23.4% en el más ajustado, el clip
   de 10 s), pero sigue siendo el único hardware medido — un dispositivo
   bastante más lento podría no sostenerlo.

**Herramientas de medición conservadas para retomar el tema del
codificador** (no producción, no se ejecutan solas): la investigación de
si `WebPAnimEncoder` podía paralelizar fotogramas midió que no, con las
herramientas que quedaron en el repo listas para retomarlo si alguna vez
hace falta.
- `webp/src/androidTest/.../webp/WebpThreadLevelBenchmarkTest.kt`: mide
  `thread_level` de libwebp (descartado, 1-13% más lento) — punto de
  partida si se prueba otro `method` o tamaño de imagen.
- `app/src/androidTest/.../media/ReducedResolutionDecodeFeasibilityTest.kt`:
  si un `ImageReader` más chico que el nativo baja el costo de decode
  (este dispositivo lo ignora; otro podría no hacerlo).
- `app/src/androidTest/.../media/ParallelDecodeConversionProbeTest.kt` y
  `ParallelVideoFrameDecoder.kt`: comparan configuraciones de
  hilos/capacidad del decode paralelo contra las fijas de producción
  (ADR-0015) — el punto de partida si se cambia de dispositivo o si el
  decode vuelve a importar tras optimizar el encoder.
- `app/src/androidTest/.../media/ParallelDecoderIsolationTest.kt`: el
  patrón correcto (una corrida por invocación de `am instrument`) para
  medir un número absoluto contra un presupuesto de RNF-08, después de que
  la variante anterior de este archivo (barrer muchas celdas en un solo
  proceso) diera un falso positivo — ver `docs/desarrollo/pruebas.md` y
  ADR-0015.
- **Si se quiere perseguir más fps de todos modos (descartado por ahora,
  ADR-0015):** la única vía que queda es dejar de usar
  `WebPAnimEncoderAdd`, codificar cada fotograma por separado con
  `WebPEncode` (en paralelo de verdad, sin la limitación de que cada
  llamada necesite la anterior) y rearmar el WebP animado a mano con
  `WebPMuxPushFrame`. Antes de tocar código de producción hace falta medir
  dos cosas que hoy no se sabe: cuánto tamaño gana hoy el diffing
  automático de `WebPAnimEncoderAdd` frente a codificar cada fotograma como
  cuadro clave independiente (el peor caso si no se reimplementa esa
  lógica), y si migrar a la API de más bajo nivel de `libwebpmux` compila y
  funciona de verdad en el `:webp` vendorizado de este proyecto.

**Tema abierto, pendiente de mirar, no de medir:** el barrido del
2026-09-26 mostró que, a los mismos 8 fps, bajar la resolución de
codificación de 512 a 384 evita un segundo intento de bisección del
codificador en el clip de 10 s y casi duplica su margen (12.6% → 55.9%) —
a costa de nitidez, porque el resultado final sale de escalar una imagen
más chica a 512×512, no de codificar 512 nativo. Se generaron tres
stickers de la misma escena (actual 512@8fps, candidatas 384@8fps y
320@8fps) con `ComparisonStickerGeneratorTest`, dejados en el teléfono
(`/sdcard/Download/stickersini_comparacion/`). Decisión pendiente de
mirarlos: si la pérdida de nitidez es aceptable, va en un ADR nuevo que
ajuste la resolución de codificación (no el fps, que ADR-0012 ya cerró).

- **Abrir en GitHub** (no bloquea el desarrollo, sí la publicación o el
  seguimiento del trabajo):
  - Issues de los puntos 5 y 6 de arriba (stickers semilla, licencias) y de
    los temas de rendimiento sin cerrar (WebPMux, resolución de
    codificación).
  - Historias de usuario del trabajo pendiente (Fase 3 en adelante), para
    rastrearlo fuera de este README.

## Instalación

Próximamente en Google Play. Mientras tanto, compilar desde el código fuente
siguiendo [la guía de instalación](docs/desarrollo/instalacion.md).

## Documentación

- [Guía de usuario](docs/usuario/) — cómo usar la aplicación
- [Documentación técnica](docs/desarrollo/) — requisitos, arquitectura, pruebas
- [Decisiones arquitectónicas](decisions/) — por qué el proyecto es como es

## Stack

- **Kotlin + Jetpack Compose** — aplicación y interfaz
- **MediaProjection** — captura de pantalla
- **MediaCodec** — decodificación del video de origen
- **C vía JNI/NDK (módulo `:yuv`)** — conversión de color YUV→RGB
- **libwebp (`WebPAnimEncoder`) vía JNI/NDK** — codificación de los stickers
- **ContentProvider** — entrega de los packs a WhatsApp

## Licencia

GPL-3.0 — ver [LICENSE](LICENSE).

El nombre **Stickersini**, el logotipo y el ícono no están cubiertos por la
licencia: ver [TRADEMARK.md](TRADEMARK.md).

Este proyecto no está afiliado a WhatsApp LLC ni a Meta Platforms, Inc.
