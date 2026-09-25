# ADR-0008: Decodificar video a fotogramas con ImageReader y conversión en CPU, seleccionando por muestreo uniforme tras el decode

- Estado: Aceptado — Superseded parcialmente por [ADR-0009](0009-fps-de-prefiltro-derivado-del-piso-del-codificador.md) (el valor de fps de prefiltro)
- Fecha: 2026-09-24

## Contexto

La Fase 2 (RF-02) necesita convertir un video existente en un
`List<WebpFrame>` — bitmaps `ARGB_8888` de 512×512, todos del mismo tamaño —
para entregárselo a `WebpAnimEncoder` sin tocar `:webp` (ver
`docs/desarrollo/arquitectura.md`). ADR-0002 ya decidió usar `MediaCodec`
para decodificar; lo que falta decidir es cómo se obtienen bitmaps
utilizables desde su salida, y qué fotogramas de un video que normalmente
viene a 30 fps (o más) se conservan, dado que un sticker no necesita —ni
puede usar, por RF-10— tantos.

Esta decisión no corre en un presupuesto de tiempo aparte: RNF-08 mide "la
conversión" completa de 3 s de contenido, no solo la llamada a
`WebpAnimEncoder.encode`. Todo lo que decida este ADR compite por el mismo
margen que ADR-0006 y ADR-0007 ya midieron ajustado en el peor caso medido
hasta ahora (contenido sintético): 14 411 ms de un tope de 20 000 ms para el
caso adverso, 1 077 ms de un tope de 5 000 ms para el caso representativo
(`docs/desarrollo/pruebas.md`). Ninguna de esas mediciones incluye todavía
el costo de decodificar y seleccionar fotogramas de un video real — es
exactamente lo que esta fase debe medir a continuación (ver "Aceptado sin
medición previa" más abajo).

## Opciones consideradas — ruta de decodificación

1. **`MediaCodec` → `Surface` respaldada por `SurfaceTexture`/EGL, escalado y
   recorte a 512×512 mediante un shader GLES sobre un FBO, lectura con
   `glReadPixels` a un `Bitmap`.**
   - A favor: el sampler externo OES hace la conversión YUV→RGB de forma
     automática y el escalado/recorte corre en GPU, barato en CPU por
     fotograma. Es además reutilizable a futuro para RF-07 (recorte de área)
     y RF-08 (eliminar fondo), que también son operaciones de imagen
     candidatas a GPU.
   - En contra: exige EGL/GLES a mano (contexto, shader, FBO, hilo dedicado,
     ciclo de vida) — mucha más superficie de bugs para un proyecto de un
     solo desarrollador, con comportamiento históricamente inconsistente
     entre fabricantes en `SurfaceTexture`/EGL fuera de una `Activity`
     visible. Nada de esto está medido: no hay dato de que la conversión en
     CPU sea el cuello de botella que justifique pagar esta complejidad.
2. **`MediaCodec` → `Surface` de un `ImageReader` configurado en
   `YUV_420_888`, conversión YUV→RGB y escalado/recorte a 512×512 en CPU
   (Kotlin puro).**
   - A favor: código directo, sin EGL ni shaders, testeable con JUnit igual
     que el resto del proyecto (`FrameTiming`, `QualitySearch`). El
     decodificador sigue siendo hardware — usar `ImageReader` no fuerza
     software decoding, solo cambia el formato de salida de la superficie.
     Es la opción que permite medir en dispositivo real cuanto antes, que es
     el objetivo declarado de esta fase.
   - En contra: el costo de convertir y escalar recae en la CPU, fotograma a
     fotograma; no hay medición todavía de si compite con el margen ya
     ajustado que dejaron ADR-0006/ADR-0007.
3. **`MediaMetadataRetriever.getFrameAtTime` por cada fotograma que se
   quiera conservar** — descartada sin medir: pensada para extraer
   fotogramas sueltos (una miniatura, un fotograma de vista previa), no una
   secuencia. Cada llamada reinicia la decodificación desde el keyframe
   anterior más cercano, así que el costo escala mal con el número de
   fotogramas que pide un sticker (varios por segundo, varios segundos).

## Decisión

**Opción 2: `ImageReader` en `YUV_420_888` + conversión y escalado en CPU.**

Mismo criterio que ya usó ADR-0006: no pagar complejidad (EGL/GLES) que
nadie midió que haga falta. Si la medición en dispositivo real —con una
grabación de pantalla real, no contenido sintético— muestra que la
conversión en CPU se come el margen de RNF-08, se abre un ADR nuevo que
reemplace este con la ruta GPU (CLAUDE.md: un ADR aceptado no se edita, se
reemplaza). No se seguirá esa ruta por adelantado sin ese dato.

### Selección de fotogramas

El decodificador entrega fotogramas a la tasa original del video (30 fps o
más); se decodifica cada uno que entrega `MediaCodec` — las dependencias
entre fotogramas P/B no permiten saltarse el decode sin arriesgar
corrección—, pero solo se convierte a `Bitmap` (el costo caro: YUV→RGB más
escalado) el subconjunto que se conserva.

La selección es un **muestreo uniforme sobre el timestamp**, con un fps
objetivo de **20**. Nótese qué decide este número y qué no: acota el costo
de *convertir* fotogramas a bitmap (YUV→RGB + escalado, el paso caro de la
Opción 2), no decide la calidad final del sticker — eso sigue siendo
trabajo exclusivo de `WebpAnimEncoder`, que puede bajar tanto como haga
falta hasta su propio piso de 5 fps (ADR-0007) si el conjunto no cabe en
RF-10. Subirlo no arriesga RF-10 ni RNF-08 por sí solo: solo cambia cuántos
fotogramas le llegan al encoder para que decida.

Los 10 fps de una versión anterior de este ADR no eran una medición: era el
valor usado como referencia en las pruebas sintéticas de Fase 1 (100
ms/fotograma), elegido para esas pruebas, no para este prefiltro. Con
contenido representativo, el resultado final midió 59 672 bytes de un
límite de 500 000 (11.9%, `docs/desarrollo/pruebas.md`): hay margen de
sobra para conservar más movimiento sin que RF-10 lo resienta, así que fijar
el prefiltro en 10 fps por inercia lo hubiese limitado por debajo de lo que
el propio codificador ya demostró que tolera. 20 es el extremo superior del
rango considerado (15-20 fps), preferido sobre el inferior porque esta fase
no tiene todavía ninguna medición de cuánto cuesta convertir un fotograma
real (a diferencia del codificador, que sí la tiene): partir del extremo que
preserva más fluidez, dentro de un rango ya acotado a propósito para no
convertir cada fotograma de un origen a 30/60 fps, deja que sea la medición
en dispositivo real —el objetivo declarado de esta fase— la que diga si hay
que bajarlo, en vez de adivinar un valor más conservador sin datos.

Para el rango de entrada (ver más abajo, acotado a 10 s), se conserva el
primer fotograma decodificado que llegue en o después de cada marca de una
grilla fija de `1000 / 20` ms desde el inicio del tramo, y se descartan los
demás sin conversión. No es una búsqueda del fotograma más cercano por
distancia (no hay lookahead: decidir eso exigiría comparar contra el
fotograma siguiente antes de resolver el actual), pero al no reengancharse
al timestamp del fotograma ya conservado, la tasa promedio resultante es
correcta incluso cuando la tasa de origen no divide exacto al fps objetivo
— verificado en `FrameSamplerTest`: 90 fotogramas de origen a 30 fps dan
exactamente 60 conservados a 20 fps objetivo, no una aproximación.

Este muestreo es un prefiltro razonable, no la reducción adaptativa final:
`WebpAnimEncoder` ya sabe reducir más fotogramas si ese conjunto no cabe en
RF-10 (ADR-0006, fase 2 de su Decisión) y ya respeta el piso de 5 fps
(ADR-0007). La selección de esta fase no necesita ser óptima ni adaptativa
— solo evitar pasarle al encoder muchos más fotogramas de los que un
sticker puede llegar a usar (30 fps de origen sobre 10 s serían 300
fotogramas convertidos y descartados en su mayoría, un costo de conversión
que no tiene sentido pagar).

### Tramo de entrada: inicio y duración, con tope de 10 s

RF-06 limita el sticker a 10 s de origen. La Fase 2 no incluye todavía la UI
de recorte que le permitiría al usuario elegir ese tramo (ver
`docs/desarrollo/arquitectura.md`, paso 2), pero el decodificador no debe
asumir por eso que el tramo siempre arranca en el segundo 0: esa UI, cuando
exista, solo necesita poder pasarle un `startMs` propio sin que el
decodificador cambie.

**Decisión: `decode` recibe `startMs` y `durationMs`, con `durationMs`
recortado a 10 s (RF-06) e informado si eso ocurre — sin UI todavía, los
valores por defecto (`startMs = 0`, `durationMs = 10 000`) mantienen el
comportamiento sin cambios.** El decodificador se posiciona en el keyframe
anterior o igual a `startMs` (`MediaExtractor.seekTo` con
`SEEK_TO_PREVIOUS_SYNC`, la única forma de arrancar un decodificador de
video: no puede empezar desde un fotograma P/B suelto) y decodifica —sin
conservar— los fotogramas entre ese keyframe y `startMs`, igual que ya
descarta sin convertir los que el muestreo de fotogramas no elige. Deja de
alimentar `MediaCodec` en cuanto el timestamp de origen supera el fin del
tramo — no decodifica de más para descartarlo después. El resultado de la
decodificación expone si el tramo procesado quedó más corto que lo pedido
(por el tope de 10 s, o porque el video no llegaba), para que la capa que
llame (todavía sin UI en esta fase) pueda mostrárselo al usuario cuando
exista una pantalla para hacerlo.

Esto sigue siendo la misma Decisión, no una nueva: sortear el rango es
"cómo se decodifica", no una ruta ni un fps distintos. Cuando exista la UI
de recorte (RF-06), lo único que cambia es quién elige `startMs` — la API
ya lo acepta.

## Consecuencias

- Todo el trabajo de conversión y selección ocurre en `media/`, antes de
  llamar a `WebpAnimEncoder`, que no cambia: sigue recibiendo
  `List<WebpFrame>` con bitmaps ya en 512×512 `ARGB_8888`.
- Descartar fotogramas después del decode pero antes de la conversión ahorra
  el costo caro (CPU: YUV→RGB + escalado) sin ahorrar el costo del decode en
  sí, que `MediaCodec` ya paga decodifique o no se use el resultado. Si el
  decode en sí (sin contar conversión) resulta ser el cuello de botella, la
  única palanca disponible sin cambiar de ruta es decodificar menos rango de
  video, no menos fotogramas dentro del rango ya elegido.
- **Pendiente explícito, es el objetivo declarado de la Fase 2:** medir en
  un dispositivo real cuánto tarda decode + selección + conversión de una
  grabación de pantalla real, sumarlo al tiempo ya medido del encoder
  (`docs/desarrollo/pruebas.md`), y confirmar si el total sigue dentro de
  RNF-08. Esa medición confirma si esta Decisión se sostiene tal cual, si
  conviene ajustar el fps de prefiltro, o si hace falta un ADR nuevo que la
  reemplace con la ruta GPU de la Opción 1.
- La misma medición es la que puede invalidar o confirmar el umbral del 50%
  de ADR-0007 y la utilidad de `minimize_size` de ADR-0006 fuera de
  contenido sintético (ver "Qué falta" en el README): una grabación de
  pantalla real tiene redundancia temporal entre fotogramas que el ruido
  adverso sintético, a propósito, no tiene.
- Un tramo pedido más largo que 10 s, o que se pasa del final real del
  video, se procesa igual pero recortado; el resto nunca se decodifica. La
  Fase 2 no muestra ese aviso al usuario (no hay pantalla todavía) pero el
  dato para hacerlo ya queda expuesto en el resultado de la decodificación.
  Este cálculo (`ClipRange`) es puro y está cubierto por `ClipRangeTest`,
  sin necesitar dispositivo real.

## Aceptado sin medición previa

A diferencia de ADR-0006 y ADR-0007, que se aceptaron después de medir en
dispositivo real, este ADR se acepta antes de esa medición: es la única
forma de escribir el código que la Fase 2 necesita para poder medir. El fps
de prefiltro (20) y, en menor medida, la ruta de decodificación (Opción 2)
son los dos valores más expuestos a quedar invalidados por esa medición —
ver "Pendiente explícito" arriba. Si eso ocurre, no se edita este documento:
se escribe uno nuevo que lo reemplace, con el dato real en mano.
