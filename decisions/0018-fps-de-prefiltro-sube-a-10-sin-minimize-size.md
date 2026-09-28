# ADR-0018: El fps de prefiltro sube de 8 a 10, sin `minimize_size` en producción (reemplaza ADR-0012, cambia parte de ADR-0006)

- Estado: Aceptado e implementado — Superseded por [ADR-0019](0019-fps-sube-a-20-rf-06-baja-a-5s.md) (la medición de este ADR se hizo contra un `.so` de debug sin optimizar; remedida contra release, sube a 20 fps con RF-06 acotado a 5 s)
- Fecha: 2026-09-28

## Contexto

Pedido de producto: priorizar la fluidez de los stickers animados, meta
12-15 fps de prefiltro (hoy 8, ADR-0012). Dos palancas a investigar antes
de implementar nada: decodificar por GPU (`Surface`+GLES, la Opción 1 que
[ADR-0008](0008-decodificacion-de-video-a-fotogramas.md) dejó sin medir —
no la descartó por medición, la difirió por complejidad) y bajar `method`
de libwebp.

Antes de escribir el spike de GLES (la parte más cara y riesgosa de
construir), se midió el pipeline de producción sin ningún cambio a fps
variable, para localizar el cuello de botella real. Detalle completo,
con todas las trazas y tablas, en `docs/desarrollo/pruebas.md` ("Fase 3 —
fluidez 12-15 fps").

## Qué se descartó, y por qué

**GPU en decode: descartada sin escribir el spike.** El decode+conversión
es plano (~2.0-2.3 s) sin importar el fps de muestreo — `MediaCodec`
decodifica todos los fotogramas de origen igual, sea cual sea el
prefiltro (las dependencias P/B no permiten saltarse el decode); la
conversión paralela (ADR-0015) ya absorbe barato lo que cambia con el
fps. GPU ahorraría como mucho esos ~2 s, insuficiente tanto para que 12
fps entre en el tope de 20 s de RNF-08 como para que 15 fps no necesite
acortar el clip (ese es un problema de tamaño, 500 KB de RF-10, no de
tiempo). No corresponde abrir un ADR nuevo para GPU: sigue exactamente
donde la dejó ADR-0008, sin medición que la justifique.

**`method`: sin margen, ya en su piso.** Fijado en `0` por ADR-0006,
declarado en código (`NativeWebpEncoder.kt`). No hay "bajarlo" más.

**Prototipo de búsqueda de calidad por muestreo: descartado, medido más
lento.** Bisecar sobre 1 de cada 4 fotogramas y verificar con una
codificación completa salió más lento que el enfoque actual en las tres
configuraciones probadas (8/12/15 fps) — la muestra rompe la redundancia
temporal entre fotogramas consecutivos que el codificador de diferencias
de WebP aprovecha, así que comprime peor de lo que la proporción predice
y termina necesitando bisección completa de todos modos, más una
verificación extra. La variante de tramo contiguo (en vez de muestreo
disperso) se descartó sin medir: no ataca el problema de fondo, que es
que el número de intentos de bisección ya es bajo (1-3).

## Qué sí funcionó: sacar `minimize_size`

Medido en contenido real (no el sintético de ADR-0006): `minimize_size`
cuesta 1.8×-1.9× el tiempo de la codificación que ya cupo, por un 3-5% de
ahorro de tamaño — confirma en contenido real lo que ADR-0006 ya midió
como marginal (≤0.1%) en otro contenido, ahora con un costo relativo
todavía peor. Motivo adicional, de corrección y no de rendimiento: el
mecanismo de `minimize_size` (decide por fotograma si sale como keyframe
o como diferencia) generó artefactos visuales (líneas negras) en WhatsApp
en la experiencia previa del equipo con este mecanismo.

**Cambia el punto de [ADR-0006](0006-acotar-el-costo-de-tiempo-de-webpanimencoder.md)
que reservaba `minimize_size` para cuando el resultado ya válido quedaba
cerca del límite de RF-10: esa fase de cierre se elimina de
`WebpAnimEncoder`.** `NativeWebpEncoder`/`SingleShotWebpEncoder` conservan
el parámetro `minimizeSize` — sigue disponible para medición directa
(`WebpMinimizeSizeCostTest`, `SampledQualitySearchProbeTest`), solo deja
de usarse en la orquestación de producción.

## Medición: el fps de prefiltro con `minimize_size` ya afuera

Mismo dispositivo de siempre (Xiaomi Redmi Note 14, `24117RN76L`), mismo
video real, método de 5 corridas por caso, invocaciones separadas de
`am instrument`, mediana y rango, el peor caso decide.

**Tramo de 10 s (el más exigente, RF-06 al máximo):**

| fps | Total: mediana [rango] | ¿Cumple 20 000 ms? | Tamaño | Calidad | Fotogramas | ¿Acortó? |
|---|---|---|---|---|---|---|
| 8 | 6757 [6677–7080] | Sí, 64.6% margen | 422 508 B | 75 | 80/80 | No |
| **10** | **18482** [18447–**19164**] | **Sí, 5/5, 4.2% margen** | 350 658 B | 37 | 96/100 | No |
| 12 | 20839 [20829–**21046**] | **No, 5/5 sobre el tope** | 394 736 B | 37 | 95/120 | No |
| 15 | 18757 [18589–19301] | Sí, 5/5 | 197 444 B | 75 | 45/150 | **Sí, a ~3.1 s** |

**10 fps es el único de los cuatro valores medidos que cumple el tiempo
sin acortar el clip.** 12 fps sigue rompiendo RNF-08 incluso sin
`minimize_size` (necesita 3 codificaciones completas a 120→95 fotogramas,
no es un problema que sacar `minimize_size` resuelva). 15 fps sigue
cayendo en el escalón de acortar duración de ADR-0016 porque no cabe en
500 KB a la duración completa — un problema de tamaño, no de tiempo.

**Validado también en los tramos de 3 s y 5 s** (ADR-0012 encontró que el
tramo de 5 s podía fallar antes que el de 10 s, así que no alcanza con
medir solo el tramo completo):

| Tramo | Total: mediana [rango] | Presupuesto | Margen peor caso | Calidad |
|---|---|---|---|---|
| 3 s | 2421 [2399–2569] | 5000 | 48.6% | 75 |
| 5 s | 3710 [3649–3725] | 5000 | 25.5% | 75 |

Ambos con margen amplio y sin bisección. El tramo de 10 s es el único
ajustado de los tres.

## Decisión

**Subir `VIDEO_PREFILTER_TARGET_FPS` de 8 a 10. Sacar la fase de
`minimize_size` de `WebpAnimEncoder`.** Un solo valor de fps, no
dependiente de la duración del clip — mismo criterio que ADR-0012: no hay
razón de producto para que un sticker corto se vea con menos fluidez que
uno largo.

No se sube a 12 ni 15: ambos violan RNF-08 o RF-06 de formas que sacar
`minimize_size` no corrige (ver medición arriba). Subir más allá de 10
necesitaría atacar el costo de `WebpAnimEncoder` en sí — el número de
codificaciones completas que hacen falta cuando la calidad fija no
alcanza — no otro cambio de decode ni de parámetro de libwebp.

## Consecuencias

- `FrameSampler`/`VIDEO_PREFILTER_TARGET_FPS` (`media/FrameSampler.kt`)
  sube a 10, con el KDoc citando esta Decisión.
- `WebpAnimEncoder.kt` pierde el parámetro `closeToLimitFraction` y la
  fase de cierre que llamaba a `singleShotEncoder.encode(..., minimizeSize
  = true)`. `SingleShotWebpEncoder`/`NativeWebpEncoder` no cambian su
  contrato: `minimizeSize` sigue siendo un parámetro válido, solo que
  `WebpAnimEncoder` ya nunca lo pasa en `true`.
- **El margen del tramo de 10 s (4.2%, peor caso 19164 ms contra 20000 ms)
  es el más ajustado que haya llegado a producción hasta ahora** — más
  parecido en espíritu al 2.8%-12.1% que ya aceptó ADR-0012 para el clip
  largo, pero menor que cualquier valor vigente hasta este ADR. Medido en
  un solo dispositivo: un teléfono más lento que el Redmi Note 14 podría
  no sostenerlo. Cualquier cambio futuro que agregue costo al decode o al
  encode (RF-07, eliminar fondo de RF-08, un dispositivo más lento) debe
  volver a medir este tramo primero, no asumir que el margen se mantiene
  — igual advertencia que dejó ADR-0012, ahora con menos margen de sobra.
- No cambia nada de ADR-0011 (conversión nativa), ADR-0007/ADR-0016 (el
  piso de fotogramas y la escalera de degradación) ni ADR-0015 (decode
  paralelo): esta Decisión reemplaza el valor de fps de ADR-0012 y quita
  únicamente la fase de `minimize_size` que fijó ADR-0006.
- Pendiente, no perseguido en esta investigación: bajar de verdad el
  costo de `WebpAnimEncoder` cuando la calidad fija no alcanza (menos
  intentos de bisección, una estimación inicial mejor, o paralelizar la
  exploración de candidatos) — es lo único que abriría margen para subir
  el fps más allá de 10. El prototipo de búsqueda por muestreo de esta
  misma investigación no sirvió (ver `docs/desarrollo/pruebas.md`), pero
  no cierra la pregunta, solo descarta esa implementación puntual.
