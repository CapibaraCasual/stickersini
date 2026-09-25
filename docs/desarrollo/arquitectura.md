# Arquitectura

Documento de fronteras: qué módulos existen, qué responsabilidad tiene cada
uno, y cómo fluye un dato de punta a punta. Las firmas y el comportamiento
interno de una clase concreta viven en su KDoc, no acá.

## Módulos

- **`:app`** — Compose UI (`ui/`), captura de pantalla en vivo (`capture/`,
  RF-01), decodificación de video e imagen importados (`media/`, RF-02/RF-03,
  Fase 2 en adelante), modelo de dominio y persistencia de packs
  (`stickers/`), entrega a WhatsApp (`provider/`).
- **`:webp`** — libwebp vendorizado (ADR-0005) + capa JNI. Su único contrato
  de entrada es `List<WebpFrame>`: cada `WebpFrame` es un `Bitmap` `ARGB_8888`
  del mismo tamaño que el resto del conjunto, más una duración en
  milisegundos. `WebpAnimEncoder` lo dice explícito en su propio KDoc: *"No
  decide de dónde salen los fotogramas ni cuántos hay: eso es
  responsabilidad de quien llame"*. Este contrato es la frontera que
  cualquier fase nueva debe respetar sin tocar `:webp`.
- **`:yuv`** ([ADR-0011](../../decisions/0011-conversion-yuv-a-rgb-en-capa-nativa.md))
  — conversión YUV→RGB con recorte al cuadrado central, en C vía JNI. Su
  contrato de entrada son los tres planos de un `YUV_420_888` (`ByteBuffer`
  directos, con sus strides) más un recorte (`xOffset`, `yOffset`, `size`);
  devuelve un `Bitmap` `ARGB_8888` ya recortado. No conoce `:webp` ni
  `WebpFrame`: es un módulo de conversión de color, no de codificación.
  `:app` es el único que lo llama, desde `media/YuvFrameConverter`, que
  conserva la implementación equivalente en Kotlin como referencia para
  `YuvConversionParityTest` (paridad píxel a píxel), no como ruta de
  producción.

## Flujo: importar un video existente hasta un WebP animado (Fase 2, RF-02)

1. **Selección del archivo** (`ui/`): el usuario elige un video del
   almacenamiento mediante Storage Access Framework.
2. **Recorte temporal** (`ui/` + `media/`, RF-06): el usuario elige un tramo
   de hasta 10 s. La UI de recorte queda fuera del alcance de la Fase 2; el
   decodificador sí debe aceptar de entrada un rango `[startMs, endMs]`.
3. **Decodificación y selección de fotogramas** (`media/`, ADR-0002,
   [ADR-0008](../../decisions/0008-decodificacion-de-video-a-fotogramas.md)):
   `MediaCodec` decodifica el video en un tramo `[startMs, startMs +
   durationMs]`, con `durationMs` recortado a 10 s (RF-06). Sin UI de
   recorte todavía, esta fase siempre pide `startMs = 0`, pero el
   decodificador ya acepta cualquier inicio: se posiciona en el keyframe
   anterior o igual a `startMs` y descarta, sin convertir, lo decodificado
   antes de ese punto. La salida va hacia la superficie de un `ImageReader`
   en `YUV_420_888`. El decodificador entrega fotogramas a la tasa original
   del video (30 fps o más); un muestreo uniforme sobre el timestamp decide
   cuáles se conservan ([ADR-0009](../../decisions/0009-fps-de-prefiltro-derivado-del-piso-del-codificador.md)
   fija el fps de prefiltro en 5, no en el 20 original de ADR-0008). Solo
   los fotogramas conservados se convierten de YUV a RGB (`:yuv`, C vía
   JNI, [ADR-0011](../../decisions/0011-conversion-yuv-a-rgb-en-capa-nativa.md))
   y se escalan/recortan a 512×512; los descartados no pagan ese costo,
   aunque el decode en sí ya ocurrió (las dependencias entre fotogramas P/B
   no permiten saltárselo).
4. **Recorte de área** (RF-07) y **eliminación de fondo** (RF-08) — fuera del
   alcance de la Fase 2, no implementados todavía. Cuando existan, se
   insertan entre la conversión del paso 3 y el envoltorio del paso 5, sin
   cambiar el contrato de salida.
5. **Bitmap → `WebpFrame`**: cada bitmap ya en 512×512 `ARGB_8888` se
   envuelve en un `WebpFrame(bitmap, durationMs)`, con `durationMs` derivado
   del muestreo del paso 3 (RF-13: ≥8 ms por fotograma).
6. **Codificación** (`:webp`, ADR-0006, ADR-0007):
   `WebpAnimEncoder.encode(frames)` recibe la lista completa y decide por su
   cuenta calidad y reducción adicional de fotogramas si hace falta (RF-12).
   No distingue si los fotogramas vinieron de un video, una captura de
   pantalla en vivo o una foto: es el mismo punto de entrada para los cuatro
   orígenes de RF-01 a RF-04.
7. **Salida de la Fase 2**: un `WebpEncodeResult` con bytes ya validados
   contra RF-10 (o una excepción según RF-12 si no hay solución). Guardarlo
   como sticker de un pack (`stickers/`), mostrar una vista previa (RF-09) o
   entregarlo a WhatsApp (`provider/`) queda fuera del alcance de esta fase.

## Por qué esta frontera importa

- Los cuatro orígenes de contenido (RF-01 a RF-04) convergen todos en el
  mismo `List<WebpFrame>` antes de tocar `:webp`. Ninguno necesita, ni debe,
  conocer la estrategia de ajuste de calidad/fotogramas de ADR-0006/ADR-0007:
  esa lógica vive una sola vez.
- El costo de decodificar, seleccionar y convertir un video (paso 3) compite
  por el mismo presupuesto de RNF-08 que ya mide
  [`docs/desarrollo/pruebas.md`](pruebas.md) para la codificación sola: RNF-08
  habla de "la conversión" completa de 3 s de contenido, no solo de la
  llamada a `WebpAnimEncoder`. Medir ese costo con una grabación de pantalla
  real —no el contenido sintético usado hasta ahora— es el objetivo
  declarado de la Fase 2. Hasta esa medición, cualquier número de tiempo del
  paso 3 es una expectativa, no un hecho, igual que le pasó al codificador
  antes de su primera medición en dispositivo real.
