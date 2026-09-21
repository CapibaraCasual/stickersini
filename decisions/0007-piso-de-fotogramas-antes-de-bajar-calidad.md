# ADR-0007: Piso de fotogramas antes de bajar calidad

- Estado: Aceptado — reemplaza parcialmente a ADR-0006
- Fecha: 2026-09-20

## Contexto

La medición de la estrategia completa de [ADR-0006](0006-acotar-el-costo-de-tiempo-de-webpanimencoder.md)
en dispositivo (Redmi Note 14) dio, para el contenido adverso, un
resultado que sí cumple RF-10 y RNF-08 pero es un defecto real: 3 de 30
fotogramas. A 100 ms por fotograma eso es 1 fotograma por segundo — deja
de ser una animación, aunque numéricamente "quepa".

La causa es el punto 3 de la Decisión de ADR-0006: la reducción de
fotogramas por proporción no tiene más piso que
`MIN_FRAMES_AFTER_REDUCTION = 2`, un valor elegido para no dividir por
cero, no para preservar movimiento. Cuando la proporción estimada da un
número bajo, ADR-0006 lo acepta sin cuestionarlo y nunca llega a
considerar bajar la calidad en su lugar.

## Medición (Redmi Note 14, Android 14, arm64-v8a — contenido adverso, `method=0`, sin `minimize_size`, 512×512, 3 s de referencia = 30 fotogramas a 100 ms)

`WebpFrameFloorMeasurementTest`, una sola pasada por configuración:

| Fotogramas | Calidad | Tamaño (bytes) | Tiempo (ms) | ¿Cabe en 500 KB? |
|---|---|---|---|---|
| 30 | 75 | 4 838 184 | 5 178 | No |
| 30 | 50 | 4 125 890 | 6 720 | No |
| 30 | 25 | 3 266 680 | 5 576 | No |
| 30 | 0 | 703 138 | 4 844 | **No** (40% sobre el límite) |
| 15 | 75 | 2 417 928 | 3 993 | No |
| 10 | 75 | 1 611 694 | 3 358 | No |
| 3 | 75 | 483 522 | 483 | Sí (medido en la corrida de estrategia de ADR-0006) |

### Qué dice la medición

- **Bajar calidad sola, incluso al mínimo (0), no alcanza a 30
  fotogramas.** 703 138 bytes siguen siendo un 40% más que el límite de
  500 KB. Para este contenido (ruido independiente por fotograma, sin
  ninguna redundancia que explotar), no existe ninguna calidad que haga
  caber 30 fotogramas — el problema no es solo el número de intentos ni
  el costo por intento, es que la combinación fotogramas×calidad no tiene
  solución en esa esquina del espacio.
- **El tamaño escala de forma casi perfectamente lineal con el número de
  fotogramas, a calidad fija.** A calidad 75: 30 fotogramas → 161 273
  bytes/fotograma; 15 fotogramas → 161 195 bytes/fotograma; 10 fotogramas
  → 161 169 bytes/fotograma; 3 fotogramas → 161 174 bytes/fotograma.
  Consistente a menos de 0.1% de variación entre las cuatro mediciones —
  para este contenido, cada fotograma cuesta lo mismo sin importar
  cuántos más haya alrededor (esperable: sin redundancia temporal que
  aprovechar, cada uno se codifica de forma esencialmente independiente).
  Esta linealidad es la que justifica seguir usando una estimación por
  proporción (ADR-0006) en vez de tantear.
- **Bajar calidad de 75 a 0 sí tiene un efecto grande cuando se aplica a
  fondo:** a 30 fotogramas, -85% de tamaño (4 838 184 → 703 138). No
  alcanza por sí solo a 30 fotogramas, pero combinado con la linealidad
  de arriba, sí debería alcanzar a un número de fotogramas menor:
  aplicando la misma proporción de reducción (0.1453×) a los 15
  fotogramas de calidad 75 (2 417 928 bytes) da una estimación de
  ~351 300 bytes — 30% por debajo del límite. Es una extrapolación, no
  una medición directa a 15 fotogramas y calidad 0 (fuera del alcance de
  las cinco configuraciones pedidas para este ADR); la corrida de la
  estrategia completa en `docs/desarrollo/pruebas.md` la confirma o la
  desmiente con un número real.

## Decisión

**Piso de 5 fotogramas por segundo**, redondeado hacia arriba:
`ceil(duración_total_ms / 1000 × 5)`, con un mínimo absoluto de 2
fotogramas (el mismo `MIN_FRAMES_AFTER_REDUCTION` de ADR-0006, para clips
de menos de 0.4 s donde 5 fps daría menos de 2). Para el caso de
referencia de RF-13 (3 s), el piso es **15 fotogramas**.

5 fps no es un óptimo medido — nadie pidió encontrar el máximo de
fotogramas posible, y no se midió esa frontera exacta (ver nota en la
sección anterior sobre la extrapolación). Es un valor propuesto,
consistente con las mediciones: por debajo de él (10, 3 fotogramas) el
resultado deja de leerse como animación de forma evidente; en él, la
combinación con calidad baja tiene margen razonable según la
extrapolación de arriba, sin necesitar tantear valores intermedios.

**La Fase 2 de ADR-0006 (reducción de fotogramas por proporción) nunca
baja del piso.** Si, con el número de fotogramas ya fijado en el piso
(o en el original, si el original ya estaba en el piso o por debajo), la
calidad de partida (75) sigue sin caber, se bisecta calidad —
reutilizando `QualitySearch` sin ningún cambio, exactamente como ya hacía
la Fase 3 de ADR-0006. Lo único que cambia es *sobre qué número de
fotogramas* corre esa bisección: antes, el que diera la proporción sin
límite (pudiendo llegar a 2); ahora, nunca menos que el piso.

## Consecuencias

- El caso adverso ya no puede degradarse por debajo del piso de fps. A
  cambio, puede necesitar bajar la calidad de forma más agresiva (hasta
  0) para caber: un sticker de 15 fotogramas a calidad muy baja en vez de
  uno de 3 fotogramas a calidad 75. Es un cambio de qué se sacrifica, no
  una garantía de mejor resultado visual — más movimiento, cada
  fotograma más comprimido. Ver la medición de la corrida completa en
  `docs/desarrollo/pruebas.md` para el resultado real, no estimado.
- **Si ni el piso ni calidad 0 alcanzan a caber, esta Decisión no añade
  una segunda reducción de fotogramas por debajo del piso como último
  recurso.** Se consideró y se descarta: diluiría el propósito mismo de
  tener un piso. El comportamiento en ese caso es el mismo que ya definía
  ADR-0006: el tope duro de 20 s entrega el mejor resultado válido
  encontrado hasta ese momento, o RF-12 si no encontró ninguno. Ninguna
  medición de este ADR observó ese caso (el piso de 15 fotogramas, según
  la extrapolación, sí debería caber para el peor contenido medido hasta
  ahora), pero tampoco está descartado para contenido más adverso que el
  probado.
- No cambia nada más de ADR-0006: `method=0` fijo, primera pasada a
  calidad 75 sin bisección, `minimize_size` solo sobre el 80% del
  límite, tope duro de 20 s. Este ADR reemplaza únicamente el punto 3
  (reducción de fotogramas) de su Decisión.

## Medición de confirmación (corrida completa de la estrategia implementada)

`WebpAnimEncoderPerformanceTest`, mismo dispositivo, tras implementar esta
Decisión: contenido adverso, 30 fotogramas de entrada → **15 fotogramas,
calidad 0, 348 516 bytes, 19 825 ms**. Confirma la extrapolación de la
sección de Medición (~351 300 bytes estimados) casi exacta — el modelo
lineal por fotograma se sostuvo. El piso de 15 fotogramas sí cupo, tal
como se esperaba.

Lo que la extrapolación no anticipó es el **tiempo**: la bisección de
calidad tuvo que bajar hasta el mínimo absoluto (0) para caber — ninguna
calidad entre 1 y 74 cupo en 500 KB a 15 fotogramas (quality=1 dio 680 120
bytes, todavía sobre el límite) — así que corrió los 8 intentos completos
de `QualitySearch` en vez de cortar antes, a ~2 s reales por intento.
19 825 ms de 20 000 ms de tope: **cumple RNF-08 (segundo tramo, ≤20 s) por
apenas 175 ms de margen.** No es un margen cómodo. El detalle completo,
con la traza intento por intento, está en `docs/desarrollo/pruebas.md`.

Esto no invalida la Decisión — el piso de 15 fotogramas sigue siendo
alcanzable para el peor contenido medido, y RNF-08 se cumple — pero deja
un margen de tiempo mucho más ajustado de lo que sugería la extrapolación
de tamaño, específicamente para el caso extremo de ruido puro. Si en el
futuro aparece contenido real (no sintético) todavía más adverso que este
ruido, o un dispositivo más lento que el Redmi Note 14, este margen de
175 ms es el primer lugar donde revisar antes de asumir que RNF-08 sigue
cumpliéndose.

## Actualización: orden de búsqueda en el piso (calidad mínima primero)

Los 175 ms de margen de arriba eran, en la práctica, más frágiles de lo
que parecían: la bisección de calidad, sembrada desde arriba (75), llegaba
a `quality=0` — la única que cabía — como su *último* intento, no el
primero. En un dispositivo más lento que el Redmi Note 14, el tope de 20 s
podía cumplirse antes de llegar a probarla, y entonces `bestBytes` seguía
en `null`: RF-12, sin sticker, en vez del resultado degradado pero válido
que sí existía.

Implementación (sin ADR nuevo — es una decisión dentro de lo que ya
definía este ADR, no una decisión estructural distinta): al llegar a la
bisección de calidad ya en el piso de fotogramas, `WebpAnimEncoder`
siembra la búsqueda con `QualitySearch.MIN_QUALITY` (0) en vez del punto
medio habitual. Si no cabe, se sabe en una sola codificación que no hay
solución a ese número de fotogramas. Si cabe, queda de inmediato como
resultado válido garantizado, y el tiempo que quede se usa para bisecar
hacia arriba buscando algo mejor — nunca a costa de perder la garantía ya
conseguida. Razonamiento completo en el KDoc de `QualitySearch`.

**Medición de confirmación** (mismo dispositivo, mismo contenido adverso):
`quality=0` queda banqueado como resultado válido tras el tercer intento
(fase 1 + fase 2 + la propia prueba de 0), a los **~11.1 s** — mucho antes
del tope de 20 s. La corrida completa sigue tardando un total similar
(**21 825 ms**, incluso un poco más que los 19 825 ms de antes: la
búsqueda ahora sigue explorando hacia arriba buscando algo mejor que 0
después de encontrarlo, y en este contenido nada mejor existe, así que ese
tiempo extra no encuentra nada — pero tampoco pierde la garantía ya
conseguida) y de hecho **supera el tope de 20 s por ~1.8 s**, porque la
última codificación en curso cuando se cumple el tope corre hasta
terminar (comportamiento documentado, no nuevo). Es decir: el tiempo total
de la corrida no mejoró, y el tope de tiempo formal se pasó en este caso
concreto — lo que cambió, que era el objetivo pedido, es que un corte a
mitad de camino (en cualquier punto después de los ~11.1 s) ya no deja al
usuario sin resultado. Detalle intento por intento en
`docs/desarrollo/pruebas.md`.
