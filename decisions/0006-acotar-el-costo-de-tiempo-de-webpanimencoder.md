# ADR-0006: Codificar sin buscar cuando el contenido ya cabe

- Estado: Aceptado
- Fecha: 2026-09-20

## Contexto

La versión anterior de este ADR (ver historial de git) proponía acotar el
número de iteraciones de `QualitySearch` y separar una fase de búsqueda
"rápida" de una pasada final "lenta", con `method` y `minimize_size`
distintos en cada una. Esa propuesta partía de una estimación por proxy
(Pillow en la máquina de desarrollo, con un factor de extrapolación
2×–6× sin verificar) y nunca se implementó. Dos rondas de medición real en
dispositivo (Xiaomi Redmi Note 14, Android 14, arm64-v8a — ver
`docs/desarrollo/pruebas.md`) la invalidan:

1. Con el algoritmo de bisección ya arreglado (`minimize_size` solo en la
   pasada final, `method=4` explícito), una corrida de 30 fotogramas de
   ruido adverso hizo 14 intentos y cortó a los 5 minutos sin resultado.
   Las dos codificaciones completas que sí se vieron tardaron **~13
   segundos cada una** — más del doble del presupuesto de RNF-08 de
   entonces. El problema no era el número de intentos: era el costo de
   cada uno.
2. Un benchmark de una sola codificación por configuración (sin bisección),
   `method` en `{0, 2, 4, 6}` × contenido adverso/realista a calidad fija
   75, dio esta tabla:

   | `method` | Contenido | Tamaño (bytes) | Tiempo (ms) |
   |---|---|---|---|
   | 0 | adverso | 4 838 184 | 5 178 |
   | 2 | adverso | 7 716 510 | 10 911 |
   | 4 | adverso | 4 864 902 | 29 266 |
   | 6 | adverso | 4 870 268 | 45 768 |
   | 0 | realista | 59 672 | 1 330 |
   | 2 | realista | 42 184 | 1 898 |
   | 4 | realista | 39 150 | 3 942 |
   | 6 | realista | 37 902 | 31 026 |

   ("realista": degradado de fondo, zona plana, forma en movimiento y
   texto — aproxima una grabación de pantalla real. "adverso": ruido
   independiente por fotograma, el peor caso posible, no un caso típico.)

**El dato que cambia la conclusión: `method=0` + contenido realista da
59 672 bytes — más de 8× por debajo del límite de 500 KB de RF-10 — en
1 330 ms, sobre los 5 000 ms de RNF-08.** Con contenido representativo, la
primera codificación ya cabe. No hace falta bisección de calidad. No hace
falta `minimize_size`. El problema no era "cuántas veces se codifica": era
que el diseño anterior codificaba con bisección **siempre**, incluso
cuando una sola pasada ya bastaba. La estrategia de este ADR es distinta en
la raíz, no una optimización de la anterior: dejar de buscar por defecto,
y solo pagar el costo de búsqueda cuando la primera pasada, medida, no
alcanza.

### Costo de `minimize_size` a `method=0`, medido

Antes de decidir cuándo vale la pena pagar `minimize_size`, se midió su
costo real a `method=0`/calidad 75 (mismo dispositivo), contra las filas
`minimizeSize=false` de la tabla de arriba:

| Contenido | `minimizeSize` | Tamaño (bytes) | Tiempo (ms) |
|---|---|---|---|
| adverso | false | 4 838 184 | 5 178 |
| adverso | true | 4 838 184 | 10 360 |
| realista | false | 59 672 | 1 330 |
| realista | true | 59 610 | 2 232 |

`minimize_size` no redujo el tamaño en absoluto sobre ruido adverso (0
bytes: con fotogramas sin correlación entre sí, probar cada uno como
diferencia contra el anterior nunca gana contra codificarlo como keyframe)
y lo redujo solo 62 bytes (0.1%) sobre contenido realista, pese a que ese
contenido sí tiene partes estáticas entre fotogramas (el fondo, la
tarjeta). A cambio, costó 1.68×–2.0× más tiempo en ambos casos. Es una
opción cara para un beneficio, en estos dos contenidos, casi nulo — la
base para el umbral de la Decisión.

## Decisión

Cuatro cambios, todos en la misma dirección: no pagar ningún costo que el
contenido de entrada no exija.

1. **`method=0` fijo, siempre.** Ya no hay una fase "rápida" y otra
   "lenta" con `method` distinto: la tabla de arriba muestra que
   `method=0` es, en los dos contenidos medidos, el más barato en tiempo y
   no el peor en tamaño de forma consistente (con ruido adverso ni
   siquiera hay una relación monótona entre `method` y tamaño — ver
   `docs/desarrollo/pruebas.md`). `NativeWebpEncoder.encode` (el punto de
   entrada de 3 argumentos que usa producción) pasa a fijar `method=0` en
   vez de `4`.
2. **Una sola pasada a calidad fija (75) primero, sin bisección.** Si el
   resultado cabe en RF-10, se entrega tal cual. 75 se conserva como punto
   de partida precisamente porque es la calidad ya medida en la tabla de
   arriba: cabe de sobra en contenido representativo (59 672 de 500 000
   bytes), así que no hace falta recalibrarla sin repetir la medición.
3. **Si no cabe, reducir fotogramas antes que calidad, por estimación
   directa, no por tanteo.** La proporción entre el tamaño obtenido y el
   límite (`objetivo / obtenido`) estima directamente cuántos fotogramas
   hacen falta (`fotogramas_actuales × proporción`, con un piso de 2). Es
   una sola reducción, no una bisección de fotogramas: el tamaño de un
   WebP animado sin `minimize_size` escala aproximadamente lineal con el
   número de fotogramas (cada uno se codifica de forma independiente como
   keyframe), así que una estimación lineal es razonable sin necesitar
   varias rondas para converger. Solo si, tras esa única reducción,
   todavía no cabe, se pasa a bisecar calidad (reutilizando
   `QualitySearch` sin cambios, sembrada con el resultado ya conocido de
   la calidad 75 en el conjunto reducido).
4. **`minimize_size` solo si el resultado ya válido queda cerca del
   límite — umbral: 80% de RF-10 (400 000 bytes).** Justificación con la
   medición de la sección anterior: el beneficio medido fue nulo (adverso)
   o marginal (0.1% en realista) a un costo de 1.7×–2× el tiempo. No es un
   umbral que capture un beneficio grande — los datos no muestran uno—,
   es un umbral que evita pagar ese costo en el caso común (contenido que
   cabe con margen amplio, como el realista de la tabla, a solo 12% del
   límite) reservándolo para cuando el margen es más estrecho y una
   reducción, aunque pequeña, tiene más chance de importar. Con `method=0`
   el costo absoluto de intentarlo es bajo en cualquier caso (2.2 s en
   realista, incluso 10.4 s en el adverso de 30 fotogramas sin reducir),
   así que un umbral relativamente generoso (80%, no 99%) no arriesga
   RNF-08 de forma apreciable incluso si dispara más seguido de lo
   estrictamente necesario.
5. **Tope duro de 20 segundos para toda la llamada.** Ver RNF-08
   reescrito (rehecho junto con este ADR, en `docs/desarrollo/requisitos.md`):
   distingue contenido representativo (≤5 s) de contenido de alta
   complejidad visual (≤20 s, con progreso visible, aceptando un resultado
   con menos fotogramas). El encoder no puede saber de antemano en cuál de
   los dos casos está: intenta terminar rápido, y si no puede, entrega el
   mejor resultado válido encontrado hasta el momento en que se cumplan
   los 20 s, en vez de seguir intentando sin límite. Como ya se estableció
   en la ronda anterior de este trabajo, una llamada JNI bloqueada no se
   puede interrumpir de verdad desde Kotlin: el tope se comprueba *entre*
   codificaciones, no dentro de una. Si la codificación en curso cuando se
   cumple el tope es en sí misma más larga que el margen restante, esa
   codificación corre hasta terminar de todas formas — el tope acota
   cuántas codificaciones más se empiezan, no cuánto puede tardar la
   última que ya estaba en curso.

### Por qué no las opciones de la versión anterior de este ADR

- *Límite de iteraciones de bisección con degradación aceptable*: sigue
  sin bastar por sí sola — un límite de N intentos sobre un diseño que
  siempre busca no evita el costo de buscar cuando no hace falta. Se
  vuelve innecesaria: con la Decisión de este ADR, el caso común no llega
  a iterar ni una vez.
- *`method` bajo en la búsqueda, alto en la pasada final*: la tabla de
  `method` × contenido no respalda que `method` alto valga su costo — en
  ninguno de los dos contenidos medidos `method=6` dio una mejora de
  tamaño que compense 6×–9× más tiempo que `method=0`. Se descarta tener
  dos valores de `method`: uno solo, fijo, es más simple y los datos no
  piden lo contrario.

## Consecuencias

- Contenido representativo (la mayoría de las capturas y grabaciones
  reales, por lo que muestra el caso "realista" medido): una sola
  codificación, sin bisección, sin `minimize_size`. Del orden de 1.3
  segundos medidos, muy por debajo de los 5 s de RNF-08.
- Contenido adversarial (ruido puro, sin redundancia entre fotogramas):
  sigue sin garantía de caber en 5 s — para eso existe ahora el segundo
  tramo de RNF-08 (≤20 s, con degradación aceptada) y el tope duro de esta
  Decisión. No se afirma que 20 s sea suficiente en todos los casos: eso
  es lo que mide la corrida de validación pendiente (ver más abajo).
- `SingleShotWebpEncoder` no cambia de forma (`frames`, `quality`,
  `minimizeSize`): `method` queda fijo dentro de `NativeWebpEncoder`, no
  se añade como parámetro de la interfaz de producción. Solo
  `NativeWebpEncoder` expone un `method` configurable, y únicamente para
  los tests de medición (`WebpEncodeMethodBenchmarkTest`,
  `WebpMinimizeSizeCostTest`).
- `FrameTiming.halve` (combinar fotogramas de dos en dos) se reemplaza por
  `FrameTiming.reduceTo` (reducir a un número de fotogramas estimado
  directamente): ya no hace falta reducir en pasos de potencia de 2 cuando
  la estimación da un número exacto de entrada.
- El umbral del 80% para `minimize_size` (punto 4) se apoya en solo dos
  mediciones de contenido (una adversa, una representativa sintética): no
  es una curva de beneficio marginal real. Si en el futuro aparece
  contenido con más redundancia temporal genuina entre fotogramas (una
  grabación de pantalla real, no la aproximación sintética de este ADR)
  donde `minimize_size` sí ahorre una fracción significativa de tamaño,
  este umbral debería revisarse con esa medición, no mantenerse por
  inercia.

## Medición final, en dispositivo (Redmi Note 14, Android 14, arm64-v8a)

Implementado en `WebpAnimEncoder.kt`, `FrameTiming.kt`,
`NativeWebpEncoder.kt`. Corrida completa de
`WebpAnimEncoderPerformanceTest.estrategiaAdr0006_30fotogramas_ambosContenidos`
(el orquestador real, no configuraciones sueltas) sobre los dos tipos de
contenido de 30 fotogramas — detalle en `docs/desarrollo/pruebas.md`,
sección "Estrategia de ADR-0006, medida en dispositivo":

| Contenido | Codificaciones | Tiempo total | Resultado |
|---|---|---|---|
| adverso | 3 | 6 223 ms | quality=75, 3/30 fotogramas, 483 522 bytes |
| realista | 1 | 1 292 ms | quality=75, 30/30 fotogramas, 59 672 bytes |

Contenido representativo: 1 292 ms contra el presupuesto de 5 000 ms de
RNF-08 — cumple con margen amplio, una sola pasada, sin bisección ni
`minimize_size`, tal como predecía la Decisión. Contenido adverso (el peor
caso posible, no uno típico): 6 223 ms contra el presupuesto de 20 000 ms
del segundo tramo de RNF-08 reescrito — cumple con margen amplio también,
sin necesitar el tope duro de tiempo (`outcome=exito` en ambas corridas,
ninguna lo agotó). El caso adverso se resolvió reduciendo a 3 fotogramas
(estimado por proporción en un solo paso) más una pasada de
`minimize_size` que no cambió el tamaño (0 bytes, consistente con la
medición de la sección anterior) pero tampoco costó caro (796 ms).

Con esta medición, la estrategia queda confirmada para los dos contenidos
evaluados y este ADR pasa a **Aceptado**. Queda sin observar en este
dispositivo el comportamiento del tope duro de 20 s ante un caso que
realmente lo agote (ninguno de los dos contenidos probados llegó ni
cerca) — no bloquea la aceptación, porque el tope es una red de seguridad
para ese escenario no cubierto, no el mecanismo que resuelve los casos
medidos.
