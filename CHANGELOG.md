# Changelog

Todos los cambios relevantes de este proyecto se documentan aquí.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
el proyecto usa [versionado semántico](https://semver.org/lang/es/).

## [Sin publicar]

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

## [0.3.0-alpha] - 2026-09-25

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
