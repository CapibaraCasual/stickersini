# Changelog

Todos los cambios relevantes de este proyecto se documentan aquí.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
el proyecto usa [versionado semántico](https://semver.org/lang/es/).

## [Sin publicar]

### Añadido
- Selector de tramo de video (RF-06): antes de convertir, el usuario elige
  con un `RangeSlider` de Material 3 qué ventana de hasta 10 s del video
  origen se usa, con una miniatura del fotograma de inicio como vista
  previa (`TrimScreen`, lee la duración real con `MediaMetadataRetriever`).
  `StickerConversionPipeline.convert` recibe ese tramo (`startMs`,
  `durationMs`) en vez de asumir siempre el segundo 0.

### Cambiado
- Navegación entre pantallas migrada a Navigation Compose (ADR-0013),
  reemplazando el booleano a mano que conmutaba entre pantallas: prepara
  el terreno para que el flujo de creación sume pasos propios (tramo,
  recorte de área) sin acumular más booleanos/enums manuales.

## [0.5.0-alpha] - 2026-09-25

Primer recorrido de punta a punta de la Fase 3 (elegir archivo → vista
previa → guardar), con dos hallazgos de rendimiento medidos en dispositivo
real que reemplazan una decisión previa: la conversión YUV→RGB pasa a un
módulo nativo nuevo (ADR-0011) y el fps de prefiltro sube de 5 a 8
(ADR-0012, reemplaza ADR-0009).

### Cambiado
- La conversión YUV→RGB del pipeline de video pasa de Kotlin puro a un
  módulo nativo nuevo, `:yuv` (C vía JNI/NDK, cuatro ABI, ADR-0011):
  medido que era el 54-63% del tiempo de decodificar un video, y esa
  fracción crecía con la duración del clip. La versión en Kotlin se
  conserva como referencia (`YuvFrameConverter.yuv420CenterSquareToArgbKotlinReference`),
  validada píxel a píxel contra la nativa por `YuvConversionParityTest`.
  Medido en dispositivo real (Redmi Note 14): la conversión queda
  2.87×-3.51× más rápida y el tiempo total de decode+encode baja 17-33%
  según la duración del clip, sin cambiar el resultado. Efecto colateral:
  el tramo de 5 s de RNF-08, que ya no tenía margen confiable a 5 fps
  (hallazgo de esta misma sesión de medición, ver `docs/desarrollo/pruebas.md`),
  vuelve a cumplirse con margen real.
- El fps de prefiltro de video sube de 5 a 8 (ADR-0012, reemplaza el valor
  de ADR-0009): con el decode más barato (ver punto anterior), 8 es el
  valor más alto que cumple de forma confiable —contando el peor caso de 5
  corridas, no la mediana— los dos tramos de RNF-08 en clips de 3, 5 y
  10 s. Un solo valor, no dependiente de la duración del clip: 9 fps ya
  rompe el tramo de 5 s en 2 de 5 corridas.

### Añadido
- Fase 3 (en curso): primer recorrido de punta a punta, elegir un video o
  una imagen → conversión con progreso real → vista previa → guardar. Con
  recorte automático al centro y sin selector de tramo todavía (RF-06/RF-07
  quedan para después de este recorrido mínimo).
  - `StickerConversionPipeline` junta `VideoFrameDecoder`/`ImageFrameDecoder`
    con `WebpAnimEncoder` en una sola llamada, reportando `ConversionStage`
    real en cada paso (RNF-08): fotogramas decodificados sobre el total
    estimado durante el decode de video, e intento actual sobre fracción del
    presupuesto de tiempo gastado durante el encode — no un indicador
    indeterminado.
  - Segundo pack semilla, animado (`sticker_pack_semilla_animado`, 3
    placeholders descartables como los del pack semilla estático): sin él,
    el primer sticker animado que genere el usuario no tendría dónde entrar
    sin volver a chocar con el mínimo de 3 de RF-16 que ADR-0004 ya había
    resuelto para el caso estático. Ver ADR-0004 (ya preveía "al menos dos
    packs por defecto" por RF-18) y ADR-0010.
  - `StickerPackRepository` (ADR-0010) combina la definición base de cada
    pack semilla (`assets/`, sin cambios) con los stickers que el usuario
    agrega después (`UserPackStickerRepository`, JSON + archivos en
    almacenamiento interno, sin Room). Todo sticker nuevo se agrega a uno de
    los dos packs semilla según sea animado o estático — no a un pack propio
    nuevo, hasta que exista una UI de packs (RF-15).
  - `StickerContentProvider.openAssetFile` sirve tanto los assets originales
    como los archivos nuevos en almacenamiento interno; `image_data_version`
    ahora refleja la cantidad real de stickers del pack (RF-22 mínimo: que
    WhatsApp note que un pack ya añadido cambió, en vez de servir su copia
    cacheada).

Validado en dispositivo real (Xiaomi Redmi Note 14, Android 14): con 8 fps
de prefiltro, los clips de 3, 5 y 10 s cumplen RNF-08 con el peor caso de 5
corridas, aunque el margen en los clips de 5 y 10 s es ajustado (12%) —
medido en un solo dispositivo hasta ahora; ver
`docs/desarrollo/pruebas.md` para por qué eso importa antes de sumar una
segunda fila de hardware a las pruebas.

## [0.4.0-alpha] - 2026-09-24

Cierra la Fase 2 completa: los dos orígenes de contenido que le tocaban
(RF-02 video, RF-03 imagen) están implementados y medidos en dispositivo
real, compartiendo el mismo recorte y el mismo codificador.

### Añadido
- RF-03: `ImageFrameDecoder` decodifica una imagen o foto existente en un
  único `WebpFrame`, para `WebpAnimEncoder(targetSizeBytes =
  STATIC_WEBP_TARGET_SIZE_BYTES)` (RF-11: un sticker estático es una
  animación de un solo fotograma, no un codificador aparte — ADR-0002,
  medido que el contenedor de animación no agrega sobrecosto relevante
  contra el límite de 100 KB). Decodifica con `BitmapFactory` +
  `inSampleSize` (sin decodificar más resolución que la necesaria para el
  recorte) y corrige la orientación con el EXIF del framework
  (`android.media.ExifInterface`, sin dependencia nueva).
- `CenterSquareCrop`: el cálculo del cuadrado central, compartido entre
  `YuvFrameConverter` (video) e `ImageFrameDecoder` (imagen) — antes vivía
  solo dentro de `YuvFrameConverter`.

Validado en dispositivo real (Xiaomi Redmi Note 14, Android 14): sobrecosto
del contenedor de animación frente a un WebP estático real, despreciable
(máximo 1.36% del límite de RF-11); decodificar y codificar una imagen,
131 ms totales, 1 268 bytes; orientación EXIF corregida y verificada contra
las cuatro rotaciones posibles. Detalle completo en
`docs/desarrollo/pruebas.md`.

## [0.3.0-alpha] - 2026-09-24

Cierra la Fase 2 de importación de video: el pipeline decodifica un video
existente y produce un WebP animado válido de punta a punta, y RNF-08 queda
validado con una grabación de pantalla real, no solo con contenido
sintético.

### Añadido
- Fase 2 (RF-02): módulo `media/` que decodifica un video existente con
  `MediaCodec` hacia un `ImageReader` en `YUV_420_888`, convierte a RGB en
  CPU y produce una lista de fotogramas lista para `WebpAnimEncoder`
  (ADR-0008 — ruta CPU, confirmada en dispositivo real, sin necesitar GPU).
  Prefiltro de fotogramas por muestreo uniforme a 5 fps antes de convertir
  (ADR-0009 — derivado del piso de ADR-0007, no elegido aparte; medido en
  dispositivo real con 20 fps primero, agotaba el codificador sin
  resultado). `VideoFrameDecoder.decode` acepta un tramo (`startMs`,
  `durationMs`) con tope de 10 s (RF-06, `ClipRange`): un tramo más largo, o
  que se pasa del final del video, se procesa solo hasta donde alcanza, sin
  decodificar de más; se posiciona en el keyframe anterior al inicio pedido
  y descarta lo previo sin convertirlo. `YuvFrameConverter` recorta al
  cuadrado central directo sobre los planos YUV de origen, sin convertir
  primero el fotograma completo (medido: -55% de píxeles convertidos, -37%
  de tiempo de decode en un clip de 10 s).
- `docs/desarrollo/arquitectura.md`: primer documento de fronteras, con el
  flujo completo de un fotograma desde el video de origen hasta el WebP
  codificado.
- `VideoImportPerformanceTest` (instrumentado, `:app`): mide en dispositivo
  real decodificación + codificación de una grabación de pantalla real
  provista por quien corre el test. Instrumentado con el mismo
  `MeasuringEncoder` que `WebpAnimEncoderPerformanceTest` en `:webp` (vía el
  nuevo `ProductionWebpEncoder`, público), y mide clips de 2, 3, 5 y 10 s,
  no solo el máximo. Ver `docs/desarrollo/pruebas.md`.

### Cambiado
- RNF-08 (`docs/desarrollo/requisitos.md`, versión 1.1) precisa que el
  tramo de 5 s aplica a clips de **hasta 5 s** de contenido representativo,
  y el de 20 s a clips **más largos** o de alta complejidad visual. No
  cambia lo exigido: deja escrita la distinción por duración que la
  medición de esta fase confirma que el requisito ya hacía.

### Corregido
- `VideoImportResult.truncated` no detectaba un video más largo que el
  tramo procesado cuando ese tramo coincidía con lo pedido (el caso común,
  con los valores por defecto): comparaba el tramo pedido contra su propio
  tope en vez de contra la duración real del video. Encontrado con una
  grabación de pantalla real de 37.7 s recortada a los 10 s de RF-06.

Validado en dispositivo real (Xiaomi Redmi Note 14, Android 14) con una
grabación de pantalla real de 37.7 s (720×1600), no contenido sintético:
clip de 3 s (la referencia del propio RNF-08), 3 163 ms, cumple el tramo de
5 000 ms con margen; clip de 10 s (el máximo de RF-06), 9 395 ms, cae en el
segundo tramo (≤20 000 ms) tal como el requisito prevé para el caso más
exigente. Detalle completo, con las tres rondas de medición, en
`docs/desarrollo/pruebas.md`.

## [0.2.0-alpha] - 2026-09-20

Cierra la Fase 1: el codificador WebP funciona de punta a punta, medido en
dispositivo real con contenido representativo y adverso.

### Añadido
- Módulo `:webp`: libwebp v1.6.0 vendorizado (ADR-0005) y capa JNI propia
  que codifica bitmaps a WebP animado.
- `WebpAnimEncoder`: codifica gastando el mínimo trabajo que el contenido de
  entrada exija (ADR-0006, ADR-0007) — una sola pasada a calidad fija si el
  contenido representativo ya cabe; si no, reduce fotogramas por estimación
  directa sin bajar de un piso de 5 fps, y bisecta calidad como último
  recurso, probando primero la calidad mínima al llegar al piso y siguiendo
  hacia arriba solo si deja margen real. `minimize_size` se reserva para
  cuando el resultado ya válido queda cerca del límite. Cumple RF-10
  (≤500 KB), RF-12 (ajuste automático) y RF-13 (tiempos de fotograma), con
  un tope de tiempo que estima la duración de cada codificación antes de
  lanzarla, según RNF-08.

Validado en dispositivo real (Xiaomi Redmi Note 14, Android 14): contenido
representativo, ~1.1 s; contenido adverso (ruido puro, el peor caso
medido), ~14.4 s — ambos dentro de los presupuestos de RNF-08 (≤5 s / ≤20 s
según complejidad del contenido). Detalle completo de las mediciones en
`docs/desarrollo/pruebas.md`.

## [0.1.0-alpha] - 2026-09-20

Cierra la Fase 0: valida que WhatsApp acepta un pack publicado por esta app.
Sin editor, sin captura de pantalla y sin encoder todavía.

### Añadido
- Estructura inicial del repositorio, requisitos y primeras decisiones
  arquitectónicas (ADR-0001 a ADR-0004).
- Proyecto Gradle del módulo `app` (Kotlin + Compose, compileSdk/targetSdk 36,
  minSdk 26), applicationId `io.github.capibaracasual.stickersini`.
- Modelo de dominio de packs y stickers, con validación del mínimo/máximo de
  stickers por pack (RF-16) y de la prohibición de mezclar animados y
  estáticos (RF-18).
- `StickerContentProvider` conforme al contrato WAStickerApps (RF-19), con
  pack semilla de 3 stickers de prueba precargado (RF-17).
- Pantalla para añadir el pack semilla a WhatsApp mediante el intent de
  confirmación (RF-20), con detección de si WhatsApp está instalado (RF-21).
- Registro de pruebas manuales (`docs/desarrollo/pruebas.md`) y texto
  completo de la licencia (`LICENSE`, GPL-3.0).

### Corregido
- RF-14: el ícono de bandeja es PNG, no un formato libre; se descubrió al
  implementar el `ContentProvider`.

Validado en dispositivo real: WhatsApp muestra el pack semilla y sus 3
stickers aparecen en la bandeja de un chat.

<!--
Categorías disponibles: Añadido, Cambiado, Obsoleto, Eliminado, Corregido,
Seguridad.

Al publicar una versión, mover lo de [Sin publicar] a una sección nueva:

## [0.1.0] - AAAA-MM-DD
-->
