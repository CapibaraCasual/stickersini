# ADR-0020: Escalera de degradación con calidad y resolución primero, fps y duración como último recurso (reemplaza ADR-0016)

- Estado: Aceptado e implementado
- Fecha: 2026-09-29

## Contexto

Reportado desde uso real: convirtiendo videos largos o de mucho
movimiento, el sticker sale con menos fotogramas por segundo de lo
esperado, y a veces recortado a 3 s aunque se eligió un tramo de hasta
5 s. Reproducido y medido en `docs/desarrollo/pruebas.md`.

**Causa raíz: no es un bug de longitud.** Se confirmó en dispositivo real
que el largo del video de origen no influye en el resultado —
`VideoFrameDecoder` solo decodifica el tramo elegido, en timestamps
relativos a su propio inicio (ver KDoc de `VideoFrameDecoder`/`ClipRange`
y la comparación de `pruebas.md`: la misma ventana de 5 s da el mismo
resultado, con 0.3% de diferencia, decodificada desde un video de 37.8 s
o desde una copia recortada de 5.2 s). Lo que el reporte atribuía al
"largo" del video era en realidad la complejidad del contenido dentro del
tramo elegido: la escalera de ADR-0016 sacrifica fotogramas y duración
*antes* de agotar calidad y resolución, así que cualquier contenido algo
adverso ya se veía con menos fluidez o más corto de lo elegido, aunque
técnicamente cupiera con más calidad y el fotograma completo.

Decisión de producto pedida (modelo Sticker.ly): el sticker siempre dura
lo que el usuario eligió (hasta 5 s, RF-06/ADR-0019) y siempre sale al fps
de prefiltro completo (20, ADR-0019) mientras sea técnicamente posible.
Si no entra en 500 KB (RF-10), lo único que cede primero es calidad,
después resolución — nunca fps ni duración, hasta que ambos se agoten.

## Decisión

Invertir el orden de sacrificio de ADR-0016. Nueva escalera, de cuatro
escalones:

### 1-2. Calidad y resolución, siempre sobre el fotograma completo

Sin cambios de fondo respecto a ADR-0006/ADR-0007/ADR-0016 en los valores
(calidad 75→...→0, resolución 512→384) — el cambio es que estos dos
escalones corren **siempre sobre el número de fotogramas y la duración
que el usuario eligió**, nunca sobre un piso reducido. Antes de este ADR,
la reducción de fotogramas podía activarse antes de que calidad+resolución
se agotaran de verdad; ahora no.

### 3. Piso de fps (`MIN_FPS_FLOOR = 12`), primer último recurso

Si ni calidad ni resolución alcanzan sobre el fotograma completo, recién
acá se reduce el número de fotogramas — por estimación directa (misma
técnica de proporción de ADR-0016), nunca por debajo de una tasa fija de
12 fps sobre la duración pedida. La duración no se toca en este escalón.

Es una tasa, no una cantidad fija (a diferencia del piso de ADR-0016,
15 fotogramas fijos): el piso de ADR-0007 (5 fps fijo) se rompió
exactamente por ser una tasa aplicada sin medir sobre una duración
distinta a la probada (ver CLAUDE.md, "Errores conocidos"). Acá no aplica
el mismo riesgo: la duración de entrada a este escalón ya está acotada
por RF-06 (5 s, ADR-0019) antes de llegar, así que la tasa nunca actúa
fuera del rango medido.

### 4. Duración (mínimo 3 s), último-último recurso

Si ni siquiera el piso de fps alcanza, se acorta el clip a 3 s (mismo
valor de `MIN_DURATION_MS_BEFORE_LAST_RESORT` que fijó ADR-0016) y se
repiten los escalones 1-3 sobre ese tramo más corto. El aviso en la vista
previa (`ConvertPreviewSaveScreen`) no cambia.

### Piso absoluto de 2 fotogramas (Fase F), sin cambios

Se conserva igual que en ADR-0016: red de seguridad final, nunca medida
como necesaria, para que RF-12 nunca falle del todo.

## Medición: ¿alcanza calidad+resolución solas?

Barrido de calidad×resolución sobre el fotograma completo de producción
(99-100 fotogramas, 20 fps × hasta 5 s), sin tocar fotogramas ni
duración, con tres tipos de contenido (video real de referencia, un clip
adverso de "alto movimiento" generado por IA — el mismo que ADR-0019 ya
había descartado como no representativo, reutilizado acá justamente
*porque* es adverso — y ruido puro sintético), con y sin zoom (RF-07):

| Contenido | Mejor combinación sin zoom | Mejor combinación con zoom |
|---|---|---|
| Video largo (referencia real) | 512/q75 = 122 420 B (24%) ✅ | 512/q75 = 54 128 B (11%) ✅ |
| Alto movimiento (adverso, IA) | 384/q0 = 547 898 B (**110%**) ❌ | 384 o 512/q0 ≈ 423-437k B (85-87%) ✅ |
| Ruido puro (sintético) | 384/q0 = 394 852 B (79%) ✅ | 320/q0 = 628 786 B (**126%**) ❌ |

**En 2 de 6 combinaciones, ninguna calidad×resolución entra en 500 KB** —
confirma que el escalón 3 (piso de fps) no es un caso teórico, hace falta
de verdad para que RF-12 se siga cumpliendo. Detalle completo,
metodología y harnesses en `docs/desarrollo/pruebas.md`.

## Corrección de diseño encontrada al medir: bisecar desde arriba sobre el fotograma completo rompe RNF-08

La primera implementación bisecaba calidad de 75 hacia abajo (como
ADR-0006/0007 siempre hicieron) en los escalones 1-2. Medido en
dispositivo real sobre el caso "alto movimiento sin zoom" (el que no cabe
en ninguna calidad×resolución): **34.36 s hasta encontrar un resultado
válido**, muy por encima del tope de 20 s de RNF-08 — con un tope real de
20 000 ms, esta implementación lanzaba `WebpEncodeException` para un
contenido que ADR-0016 sí resolvía. Una regresión de RF-12, no solo de
rendimiento.

**Causa:** confirmar que ninguna calidad alcanza a una resolución, bisecando
desde arriba, cuesta hasta 7 codificaciones — y sobre el fotograma
completo (hasta 100 fotogramas) cada una tarda 1-3 s con contenido
adverso real. Confirmar "512 no alcanza" y "384 tampoco" antes de llegar
al escalón de fps agotaba el presupuesto completo.

**Corrección:** aplicar, también en los escalones 1-2, el mismo principio
que `QualitySearch` ya usaba solo en el piso de ADR-0007 — sembrar la
bisección por la calidad mínima (0) en vez de bisecar desde arriba. Un
solo intento a calidad 0 confirma si una resolución tiene alguna chance;
si no la tiene, se descarta en 1 intento en vez de 7. Si sí cabe, recién
ahí se bisecta hacia arriba buscando algo mejor (con el mismo límite de
margen real que ya existía). Remedido tras la corrección, 5 corridas
separadas, release:

| Caso | peor totalMs/encodeMs | margen contra 20 000 ms | resultado |
|---|---|---|---|
| Alto movimiento sin zoom | 12 901 | 35.5% | 61 fotogramas, q=0, acorta ~1.9 s |
| Ruido puro con zoom | 8 874 | 55.6% | 70 fotogramas, q=0, **sin acortar** |

Determinístico entre las 5 corridas en ambos casos. El caso de ruido con
zoom termina mejor que con ADR-0016 (que lo cortaba a 3 s con 15
fotogramas a calidad 6): acá el piso de fps solo alcanza, conservando los
5 s completos.

## Consecuencias

- `WebpAnimEncoder.kt` (`:webp`) reescrito: `MIN_FRAME_COUNT_FLOOR` (15,
  cantidad fija) se reemplaza por `MIN_FPS_FLOOR` (12, tasa) +
  `minFrameCountForFpsFloor(durationMs)`. `MIN_DURATION_MS_BEFORE_LAST_RESORT`
  (3000) no cambia de valor, pero pasa de ser el escalón 3 a ser el
  escalón 4 (último-último recurso). `DEGRADED_RESOLUTION` (384) y
  `ABSOLUTE_MIN_FRAME_COUNT` (2, Fase F) no cambian.
- La bisección de calidad de los escalones 1-2 ahora siempre siembra por
  el piso (`atFloor = true`) tras el primer intento a `FIRST_QUALITY`, no
  solo cuando ya se está en un piso de fotogramas reducido. Esto no
  cambia qué calidad final se encuentra (la garantía de `QualitySearch`
  no depende de por dónde se arranca la bisección), solo cuántos
  intentos hacen falta para confirmar que una resolución no alcanza.
- `WebpAnimEncoderTest.kt` reescrito: los casos que dependían del piso
  fijo de 15 fotogramas o del orden viejo (fotogramas/duración antes de
  agotar calidad+resolución) se rediseñaron para el nuevo orden. Sigue
  verde (22 tests).
- Nuevos harnesses de medición conservados en el repo, mismo espíritu que
  los anteriores: `LengthZoomLadderProbeTest` y
  `QualityResolutionOnlySweepTest` (`:app` androidTest).
- RF-12 y RF-06 no cambian de texto — la garantía sigue siendo "siempre
  entrega un sticker", ahora con un orden de sacrificio distinto.
- **Pendiente, no cerrado por este ADR:** validar con un clip real de
  alto movimiento (tipo TikTok), no el generado por IA — el mismo pendiente
  que dejó ADR-0019 sin resolver. El contenido adverso usado acá (el
  mismo clip de IA de ADR-0019) sirve para confirmar que el último
  recurso funciona y cumple RNF-08, pero no reemplaza esa medición
  pendiente.
- Todas las mediciones son de un solo dispositivo (Xiaomi Redmi Note 14)
  — sigue pendiente la segunda fila de hardware del README.
