# ADR-0009: El fps de prefiltro de Fase 2 se deriva del piso del codificador (ADR-0007), no se elige por separado

- Estado: Aceptado
- Fecha: 2026-09-24

## Contexto

ADR-0008 fijó el prefiltro de fotogramas de la Fase 2 en 20 fps,
justificándolo como "acota el costo de convertir, no decide la calidad
final" y apoyándose en que el resultado representativo de ADR-0006 medía
59 672 de 500 000 bytes (11.9%) — "hay margen de sobra". Ese margen era
real, pero se midió con **30 fotogramas**, el máximo que cualquier
medición de ADR-0006 o ADR-0007 usó jamás, siempre sobre el caso de
referencia de 3 s. Nadie calculó qué significa 20 fps sobre el tope real de
un clip de esta fase: **10 s (RF-06)**. La cuenta es `20 × 10 = 200`
fotogramas — 6.7 veces el máximo jamás ejercitado, no una extrapolación
menor.

Medido en dispositivo real el 2026-09-24
(`docs/desarrollo/pruebas.md`, Xiaomi Redmi Note 14, grabación de pantalla
real de 37.7 s recortada a los 10 s de RF-06):

- El **decode solo** (10 s de origen → 200 fotogramas convertidos) tardó
  **31 823 ms** — ya por sí solo 6.4× el tope de 20 000 ms del segundo tramo
  de RNF-08, sin que el codificador llegara a correr.
- El **codificador agotó su tope de 20 s sin encontrar ningún resultado
  válido**: `WebpAnimEncoder.encode()` terminó en
  `WebpEncodeException` (RF-12) — no un resultado degradado, ningún
  resultado.

La falla no es una casualidad de este video en particular: es la
consecuencia directa de pedirle al codificador casi 7 veces más trabajo
del que cualquier medición previa (ADR-0006, ADR-0007) validó que puede
hacer a tiempo. El error de diseño de ADR-0008 fue elegir un fps de
prefiltro por un criterio de "se ve fluido", sin calcular cuántos
fotogramas produce ese criterio sobre el tope real de duración de esta
misma fase, y sin verificar esa cantidad contra ninguna medición existente
del codificador.

## Decisión

**El fps de prefiltro se deriva del piso de ADR-0007 (5 fps), no se fija
por un criterio de fluidez elegido aparte.** `VIDEO_PREFILTER_TARGET_FPS`
pasa de 20 a **5**. Para el tope de 10 s de RF-06, eso son **50
fotogramas**.

Razones para anclarlo ahí y no en otro punto del rango 5-10 fps que
ADR-0007 dejaría razonable:

1. **Es el valor que ADR-0007 ya trata como aceptable, no uno nuevo.** El
   piso de 5 fps de ADR-0007 existe precisamente para marcar el límite por
   debajo del cual el resultado deja de verse como animación. Pedirle al
   prefiltro exactamente ese número significa que el prefiltro nunca le
   entrega al codificador más fotogramas de los que el propio codificador
   ya considera el mínimo aceptable — no hay una "calidad de movimiento"
   nueva que inventar ni validar por separado.
2. **Es la extrapolación más chica disponible sobre la única escala
   medida.** 50 fotogramas son 1.67× los 30 ya probados; 100 (el otro
   extremo del rango de 5-10 fps) serían 3.3×; 200 (el valor de ADR-0008)
   eran 6.7×. Sin una medición que diga cuánto tolera el codificador por
   encima de 30, el criterio de este proyecto (ver ADR-0006: "no pagar
   costo que nadie midió que haga falta") pide arrancar por el extremo más
   conservador, no por el punto medio.
3. **Encaja con la rama ya optimizada para el piso.** Cuando
   `WebpAnimEncoder` llega a su piso de fotogramas con la calidad de
   partida (75) sin caber, prueba `quality=0` primero, no en bisección
   normal (arreglo posterior de ADR-0007, ver
   `docs/desarrollo/pruebas.md`) — precisamente para garantizar un
   resultado válido cuanto antes en el caso más adverso. Si el prefiltro ya
   entrega exactamente el piso (50 fotogramas), la fase 2 de
   `WebpAnimEncoder` (reducción de fotogramas por proporción) no tiene
   nada que reducir (`currentFrames.size > frameFloor` es falso) y el
   codificador entra directo a esa rama optimizada si la primera pasada no
   alcanza — un intento menos que gastar en encontrar una reducción de
   fotogramas que de todas formas iba a terminar en el mismo piso.

**Explícitamente se descarta elegir el número por "qué tan fluido se ve"
independiente del codificador** (el criterio original de ADR-0008): sin una
medición del techo real de `WebpAnimEncoder`, cualquier valor por encima
del piso de ADR-0007 es una apuesta sin respaldo, la misma apuesta que ya
falló una vez.

Esta Decisión es **provisional hasta la medición de confirmación** con el
mismo video real (ver más abajo): si 50 fotogramas también agota el tope de
20 s, el problema no es este número — es que el techo real del codificador
está por debajo incluso del piso de ADR-0007, un hallazgo mucho más serio
que exigiría revisar el propio presupuesto de tiempo de `WebpAnimEncoder`
o su estrategia, no solo el prefiltro.

## Consecuencias

- La fase 2 de la Decisión de ADR-0006 (reducción de fotogramas por
  proporción) queda efectivamente sin uso para cualquier clip importado de
  video de hasta 10 s, porque la entrada ya llega en el piso. No es un
  problema funcional (el código ya contempla `currentFrames.size >
  frameFloor` como guarda), pero es una observación de arquitectura: ese
  camino de código queda ejercitado solo por captura de pantalla en vivo u
  otros orígenes que todavía no pasan por este prefiltro.
- La fluidez de movimiento de un sticker importado de video baja de "hasta
  20 fps" (nunca entregable en la práctica, según esta medición) a un 5 fps
  garantizado — una reducción de calidad real y visible frente a la
  intención original de ADR-0008, pero es el único valor con evidencia de
  que podría entrar en el presupuesto de RNF-08.
- Si la medición de confirmación con 50 fotogramas también falla, la
  conclusión no es "bajar más el fps": es que el techo del codificador está
  por debajo de lo que ADR-0007 ya definió como piso aceptable, y hace
  falta revisar el presupuesto de tiempo o la estrategia de
  `WebpAnimEncoder` en sí, no seguir ajustando este número.
- No cambia nada de la ruta de decodificación (CPU vía `ImageReader`) ni
  del tope de duración de entrada de ADR-0008: esta Decisión reemplaza
  únicamente el valor de fps de prefiltro de esa Decisión.

## Medición de confirmación

Mismo dispositivo (Redmi Note 14) y mismo video real
(`Recording_20260919_191641.mp4`, 37.687 s, 720×1600) que expuso el
problema, ahora con `VIDEO_PREFILTER_TARGET_FPS=5` y con
`WebpAnimEncoder` instrumentado por intento (`ProductionWebpEncoder` +
`MeasuringEncoder`, ver `docs/desarrollo/pruebas.md` para la traza
completa):

| | Antes (20 fps, ADR-0008) | Después (5 fps, esta Decisión) |
|---|---|---|
| Fotogramas para el codificador | 200 | **50** |
| Tiempo de decode | 31 823 ms | **9 681 ms** |
| Codificaciones (intentos) | — (ninguna completó) | **1** |
| Resultado del encoder | `WebpEncodeException` (RF-12), sin resultado | **quality=75, 292 538 bytes, en un solo intento** |
| Tiempo de encode | — | 3 185 ms |
| Tiempo total | — (nunca terminó) | **12 866 ms** |
| RF-10 (≤500 000 bytes) | No aplica (sin resultado) | **Cumple**, con margen (58.5% del límite) |
| RNF-08 | No cumple (ninguno de los dos tramos) | **Cumple el tramo de 20 000 ms** (alta complejidad visual). **No cumple el de 5 000 ms** (contenido representativo). |

**Confirmado: 50 fotogramas sí entra en el presupuesto de RNF-08 (segundo
tramo), y lo hace con margen amplio del lado del codificador** — una sola
pasada a calidad 75, sin bisección, 3 185 ms de un tope de 20 000 ms
(15.9%). El techo del codificador para este contenido real está muy por
encima de 50 fotogramas; el costo que sigue sin caber en el tramo de 5 s de
RNF-08 es el de decodificar y convertir, no el de codificar. Esta Decisión
queda **Aceptada**: el problema original (agotar el tope sin resultado)
está resuelto, aunque el objetivo más estricto de RNF-08 (5 s, contenido
representativo) sigue sin cumplirse — ver el análisis de la relación entre
fotogramas convertidos y tiempo de decode en `docs/desarrollo/pruebas.md`,
que apunta al costo de conversión YUV→RGB por fotograma (no al recorrido
fijo de `MediaCodec`) como el próximo punto a optimizar, no a este número de
fps.
