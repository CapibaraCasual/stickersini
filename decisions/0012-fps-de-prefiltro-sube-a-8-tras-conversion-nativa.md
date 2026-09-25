# ADR-0012: El fps de prefiltro sube de 5 a 8, un solo valor para todo el rango de RF-06

- Estado: Aceptado
- Fecha: 2026-09-25

## Contexto

ADR-0009 fijó el fps de prefiltro de video en 5 (el piso de ADR-0007),
conservador a propósito por falta de margen medido en ese momento. Con
contenido real, el resultado se ve entrecortado: la observación que abrió
esta investigación fue un clip de 10 s a 292 538 bytes (58.5% del límite de
RF-10) en una sola pasada — parecía sobrar margen para subir el fps.

Una primera medición (con corridas únicas, ver
`docs/desarrollo/pruebas.md`) mostró que ese margen era de *tamaño*, no de
*tiempo*: subir el fps a 7 ya rompía el tramo de 5 000 ms de RNF-08 para
clips de hasta 5 s, y a 10 fps rompía también el tramo de 20 000 ms para
el máximo de 10 s. Investigar por qué llevó a medir, por separado, cuánto
del tiempo de decodificar un video se iba en la conversión YUV→RGB: **54
-63%**, creciendo con la duración del clip — el cuello de botella real, no
el fps en sí (ver ADR-0011). ADR-0011 movió esa conversión a un módulo
nativo nuevo (`:yuv`), 2.87×-3.51× más rápida, con paridad de píxel
verificada contra la implementación anterior en Kotlin.

Esta Decisión retoma la pregunta original del fps, ahora con ese margen de
tiempo liberado.

## Medición

Mismo dispositivo real de siempre (Xiaomi Redmi Note 14 `24117RN76L`,
Android 14, `Recording_20260919_191641.mp4`), método de 5 corridas por
caso (mediana, rango, y el peor caso del rango — el criterio pedido es que
el peor caso cumpla, no la mediana). Detalle completo, con las trazas, en
`docs/desarrollo/pruebas.md`.

| fps | Clip | Total: mediana [rango] | Peor caso vs. tramo | Tamaño | Intentos de encode |
|---|---|---|---|---|---|
| 7 | 3 s | 2 653 [2 602–2 744] | ≤5000: cumple, 45.1% margen | 117 166 B | 1 |
| 7 | 5 s | 4 016 [3 745–4 119] | ≤5000: cumple, 17.6% margen | 161 346 B | 1 |
| 7 | 10 s (máx.) | 8 111 [7 955–8 496] | ≤20000: cumple, 57.5% margen | 395 458 B | 1 |
| **8** | 3 s | 2 810 [2 625–2 947] | ≤5000: cumple, 41.1% margen | 122 308 B | 1 |
| **8** | 5 s | 4 340 [4 226–4 398] | ≤5000: cumple, **12.0% margen** | 156 378 B | 1 |
| **8** | 10 s (máx.) | 17 509 [17 293–17 589] | ≤20000: cumple, **12.1% margen** | 408 822 B | **2** |
| 9 | 3 s | 3 105 [2 963–3 231] | ≤5000: cumple, 35.4% margen | 133 916 B | 1 |
| 9 | 5 s | 4 895 [4 562–**6 436**] | ≤5000: **no cumple, 2 de 5 corridas sobre el tope** | 173 516 B | 1 |
| 9 | 10 s (máx.) | 19 384 [19 338–19 435] | ≤20000: cumple, solo 2.8% margen | 468 648 B | 2 |
| 10 | 3 s | 3 251 [2 891–3 454] | ≤5000: cumple, 30.9% margen | 152 600 B | 1 |
| 10 | 5 s | 5 045 [4 949–**5 125**] | ≤5000: **no cumple, 3 de 5 corridas sobre el tope** | 194 246 B | 1 |
| 10 | 10 s (máx.) | 21 070 [20 371–**21 572**] | ≤20000: **no cumple, 5 de 5 corridas sobre el tope** | 350 658 B | **3** |
| 15 | 3 s | 3 961 [3 868–3 988] | ≤5000: cumple, 20.2% margen | 197 444 B | 1 |
| 15 | 5 s | 6 268 [5 978–6 581] | ≤5000: **no cumple, 5 de 5 corridas sobre el tope** | 263 382 B | 1 |
| 15 | 10 s (máx.) | ~20 400 [20 189–20 706] | ≤20000: **no cumple, 5 de 5, y sin resultado válido** (`WebpEncodeException`, RF-12) | — | 2, sin éxito |

**8 fps es el valor más alto que cumple de forma confiable, contando el
peor caso, en las tres duraciones medidas.** 9 fps ya falla el tramo de
5 s (no un caso límite: 2 de 5 corridas, con un peor caso 28.7% *sobre* el
tope). 10 y 15 fps fallan en más de un tramo, y 15 fps para el clip de
10 s ni siquiera encuentra un resultado válido dentro del presupuesto —
una falla peor que "tarda de más", es "no hay sticker".

### A partir de qué fps deja de resolverse en un solo intento

El clip de 10 s (el que más fotogramas acumula a cualquier fps) pasa de 1
intento de codificación a 2 exactamente en **8 fps** (80 fotogramas): a 7
fps (70 fotogramas) todavía entra a la primera con `quality=75`. Que 8 fps
ya necesite una reducción de calidad y siga cumpliendo el tramo de 20 000 ms
con 12.1% de margen es lo que lo deja como techo viable — un fps más
(9) todavía resuelve el clip de 10 s en 2 intentos, pero ya rompe el tramo
de 5 s, que es el que primero se queda sin margen.

## Decisión

**Subir `VIDEO_PREFILTER_TARGET_FPS` de 5 a 8. Un solo valor, no
dependiente de la duración del clip.** Ya se había considerado y
descartado un prefiltro dependiente de duración (fps bajo para el tramo de
5 s, alto para el de 20 s) en la investigación que llevó a ADR-0011: no hay
razón de producto para que un sticker corto se vea con menos fluidez que
uno largo, así que un valor único, acotado por el caso más exigente (el
tramo de 5 s, que es el que se queda sin margen primero), es la elección
correcta una vez que existe un valor que cumple los dos tramos a la vez.

## Consecuencias

- `FrameSampler`/`VIDEO_PREFILTER_TARGET_FPS` (`media/FrameSampler.kt`)
  actualizado a 8, con el KDoc citando esta Decisión.
- El clip de 10 s pasa a necesitar bisección de calidad (2 intentos, no 1)
  para caber en RF-10 — más caro que antes en tiempo de encode (13.2-13.3 s
  de los 17.5 s totales), pero dentro de presupuesto con margen real
  (12.1%), no al límite.
- El margen del tramo de 5 s (12.0% en el peor caso) es más ajustado que el
  del tramo de 3 s (41.1%) o el de 10 s (12.1%, coincidentemente similar):
  es, de los tres, el que definió el techo — subir un fps más lo rompe.
  Cualquier cambio futuro que agregue costo al decode o al encode (RF-07,
  eliminar fondo de RF-08, un dispositivo más lento que el medido) debe
  volver a medir este tramo primero, no asumir que el margen se mantiene.
- No cambia nada de ADR-0011 (la conversión nativa) ni de ADR-0007 (el piso
  de 5 fps de `WebpAnimEncoder`, que sigue sin tocarse): esta Decisión
  reemplaza únicamente el valor de fps de prefiltro que fijó ADR-0009.
