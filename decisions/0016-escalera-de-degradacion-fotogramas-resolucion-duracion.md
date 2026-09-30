# ADR-0016: Escalera de degradación con piso de fotogramas fijo, resolución y duración como escalones

- Estado: Aceptado e implementado — Superseded por [ADR-0020](0020-escalera-de-degradacion-calidad-resolucion-primero-fps-y-duracion-ultimo-recurso.md) (decisión de producto: fps y duración pasan a ser el último recurso, no un escalón intermedio — el piso fijo de fotogramas se reemplaza por un piso de fps)
- Fecha: 2026-09-27

## Contexto

Reportado desde uso real: convertir el tramo máximo de RF-06 (10 s) con
contenido adverso lanza `WebpEncodeException` (RF-12) en vez de entregar
un sticker. Reproducido y medido en `docs/desarrollo/pruebas.md`, Fase 3
("RF-12 falla en clips de 10 s").

**Causa raíz:** el piso de ADR-0007 está definido como **5 fps**, no como
una cantidad de fotogramas, y solo se validó para el caso de referencia de
3 s (15 fotogramas). Para 10 s da 50 fotogramas — el barrido de este ADR
midió que **50, 30 y 24 fotogramas no caben en 500 KB a 512×512 en
ninguna calidad, ni siquiera calidad 0** (235%, 141% y 112% del límite
respectivamente). Con la escalera actual (fotogramas + calidad, sin
resolución) no existe ninguna salida válida por encima de 15
fotogramas: el fallo no es un caso límite raro, es el resultado
garantizado para cualquier duración cuyo piso supere 15.

Agravante encontrado en la reproducción: el único intento de la Fase 2
(50 fotogramas, calidad 75) tardó 8254 ms; sumado a la Fase 1, agotó el
presupuesto de 20 s de RNF-08 **antes de que la Fase 3 llegara a probar
calidad 0** — la única configuración con alguna chance nunca se intentó.

Además, la resolución de codificación (512/384/320) está medida desde el
2026-09-26 (README, "Tema abierto") pero nunca se conectó a la
degradación. Este ADR la conecta con datos: bajar de 512 a 384 hace caber
exactamente los tres frameCount que fallaban (50, 30, 24) — ver la tabla
completa en `pruebas.md`.

## Decisión

Reemplazar el piso único de "5 fps" por una escalera de cuatro escalones,
en este orden de sacrificio — **calidad primero, resolución segundo,
duración tercero, fluidez (fotogramas) al final, como red de seguridad
absoluta**:

### 1. El piso deja de ser una tasa; pasa a ser una cantidad fija de fotogramas

`MIN_FRAMES_PER_SECOND = 5` más `ceil(duración × 5)` se reemplaza por un
piso constante, **no derivado de la duración del clip**. El valor
propuesto es **15**: es el único número con validación de punta a punta
(ADR-0007, confirmado de nuevo en el barrido de este ADR: 15
fotogramas/512/calidad 0 = 348 426 bytes, 70% del límite, con margen
consistente en las dos mediciones).

Por sí solo, este cambio ya resuelve el fallo de 10 s (15 fotogramas a
512/calidad 0 cabe) — pero **reintroduce el defecto que ADR-0007
corrigió** si se aplica ingenuamente sobre los 10 s completos: 15
fotogramas repartidos en 10 s son 1.5 fps, el mismo tipo de problema que
30 fotogramas terminando en 3 (1 fps) motivó ADR-0007 en primer lugar.
Por eso este piso fijo no reemplaza la fluidez objetivo de 5 fps — la
preserva combinándose con el escalón 3 (duración).

### 2. La resolución de codificación es un escalón nuevo, antes de tocar fotogramas

Cuando la calidad sola (75→50→25→0, sin cambios de ADR-0006) no alcanza a
la duración y fotogramas ya decididos, el siguiente escalón es bajar la
resolución de codificación — **512 → 384**, no una escalera de varios
pasos. El barrido midió que 384 alcanza en los tres frameCount que
necesitaban bajar resolución (50, 30, 24); 320 nunca resolvió un caso
adicional que 384 no resolviera, y a calidad 75/50/25 320 midió **más
pesado** que 384 (no monotónico, sin explicar — ver `pruebas.md`): no hay
lugar para un segundo escalón de resolución con los datos actuales.

Se sacrifica resolución antes que fotogramas porque, medido, cuesta menos
percibido (una imagen algo menos nítida) que perder movimiento (menos
fotogramas) o perder contenido (menos duración) — y porque además abarata
el tiempo de codificación (dato colateral del barrido: -16% en el caso
medido), ayudando también a RNF-08.

### 3. Acortar la duración es el tercer escalón, antes de bajar fotogramas por debajo del piso

Si calidad + resolución (384) sobre el número de fotogramas que da 5 fps
× duración completa todavía no caben, el siguiente escalón no es reducir
más fotogramas sobre la misma duración — es **acortar la duración
considerada**, recalculando fotogramas a 5 fps sobre la duración más
corta. El barrido lo confirma indirectamente: 24 fotogramas (5 fps × 4.8
s) a 384/calidad 0 tienen 44-56% de margen, muy por encima del 8-11% que
da forzar 50 fotogramas (5 fps × 10 s) sobre las mismas calidad y
resolución. Acortar cuesta menos, medido, que estirar el piso.

**Implica un cambio de comportamiento frente a RF-06:** hoy RF-06 dice
que el usuario puede elegir hasta 10 s, y se asume que el sticker final
cubre todo ese tramo. Con este escalón, el sticker final puede terminar
siendo más corto que el tramo elegido, si el contenido lo exige. Esto
necesita decisión de producto, no solo de ingeniería — ver "Abierto" más
abajo.

### 4. Piso absoluto de 2 fotogramas como red de seguridad final — nunca sin sticker

Si ni calidad, ni resolución (384), ni acortar duración hasta el mínimo
del punto 6 alcanzan, el último recurso es el mismo mínimo absoluto que
ya existía (`MIN_FRAMES_AFTER_REDUCTION = 2`) a calidad 0 y 384: un
sticker corto y poco fluido, pero un sticker. **RF-12 pasa de "ajustar
automáticamente e informar si no es posible" a "ajustar automáticamente
y siempre entregar algo"** — una garantía más fuerte que la que el
requisito pide literalmente hoy (RF-12 ya contempla el fallo: "informando
al usuario si no es posible"). Ninguna medición de este ADR encontró un
caso que llegue a necesitar este último escalón — el peor contenido
probado (ruido puro) ya resuelve en el escalón 2 o 3.

### 5. Corrección de diseño: estimar antes de intentar, no solo reducir por proporción

Encontrado en la propia reproducción del fallo, no en el diseño original
de la Decisión: la Fase 3 (bisección de calidad, que en el piso prueba
calidad 0 primero) **nunca llegó a correr** en el caso de 10 s — el único
intento de la Fase 2 (50 fotogramas, calidad 75) tardó 8254 ms y, sumado
a la Fase 1, agotó el presupuesto de 20 s antes de que pudiera intentarse
la única configuración con alguna chance. El problema no era solo el
piso: era que ese intento ya era descartable *antes* de gastarlo — la
misma proporción que estima cuántos fotogramas hacen falta (`ratio =
target/tamaño medido`) también sabe, sin codificar nada, si el resultado
va a quedar clampeado contra el piso (`estimación cruda < piso`): cuando
pasa eso, ninguna calidad alta va a alcanzar tampoco, así que **no se
repite el intento de calidad 75 sobre el número de fotogramas ya en el
piso** — se entra directo a probar calidad 0 (que la Fase 3 ya hacía
primero en ese caso). Mismo principio aplicado consistentemente en los
escalones 2 y 3: al bajar a resolución 384 ya sabiendo que ni calidad 0
alcanzó a 512, no se vuelve a probar calidad 75 (medido: 384 nunca pesa
menos del 70% de 512 a la misma calidad — no alcanza para cerrar una
diferencia de cientos de por ciento); se entra directo por calidad 0 ahí
también.

### 6. Duración mínima antes del último recurso: 3 s

**El acortamiento de duración se avisa, pero no antes de convertir —
recién en la vista previa** (`ConvertPreviewSaveScreen`): hasta intentar
codificar no se sabe si hizo falta acortar, así que no hay nada que
avisar antes. Una línea dice a cuánto quedó y por qué ("Se acortó a 4.8 s
(de 8 s elegidos) para entrar en el límite de tamaño de WhatsApp").

El mínimo de duración antes de sacrificar fluidez por debajo del piso
(escalón 4) es **3 s** — no un número nuevo: `MIN_FRAME_COUNT_FLOOR` (15)
fotogramas a los 5 fps de ADR-0007 son exactamente 3 s, así que este
mínimo es una consecuencia directa del punto 1, no una elección
adicional. Es también el único punto con validación de punta a punta
(ADR-0007) y coincide con lo que las implementaciones equivalentes
revisadas usan por defecto — no baja de ahí porque por debajo ya no
alcanza a leerse como animación con fluidez aceptable.

### 7. RF-06 no cambia: el máximo elegible sigue en 10 s

Diez segundos funciona bien con contenido simple (la mayoría), y bajar el
máximo penalizaría a quien no necesita acortar. En cambio, `TrimScreen`
agrega una **sugerencia, no un límite**: si el tramo elegido supera 3 s,
una línea sugiere que un tramo más corto suele dar mejor calidad — sin
impedir elegir hasta 10 s.

## Consecuencias

- **RF-12 se cumple con una garantía más fuerte** que su texto literal
  ("informando al usuario si no es posible" → siempre entrega un
  resultado). No se tocó el texto de `requisitos.md`: la escalera nueva
  hace que el caso de fallo ya no ocurra en la práctica, no cambia lo que
  el requisito promete por escrito. Si en el futuro aparece contenido más
  adverso que el probado y el escalón 4 se activa alguna vez, ahí sí
  valdría la pena revisar el texto de RF-12.
- **RF-06 no cambia.** `TrimScreen` suma una sugerencia de tramo corto,
  no un límite nuevo.
- Recortar la duración toma un prefijo contiguo desde el principio del
  tramo ya elegido (`FrameTiming.trimToDuration`) — la opción más simple,
  no medida frente a recortar del final o del centro. Posible ajuste
  futuro si hiciera falta.
- Confirma, con datos nuevos, algo que ADR-0007 ya sospechaba: el
  problema de fondo es que el contenido adverso (ruido puro, sin
  redundancia temporal ni espacial) es un caso límite genuino, no un
  defecto de implementación — la escalera nueva le da más salidas
  válidas, no elimina la posibilidad de necesitar todas ellas.
- Todas las mediciones son de un solo dispositivo (Redmi Note 14) —
  sigue pendiente la segunda fila de hardware del README.
- Implementado en `WebpAnimEncoder.kt` (`:webp`), con
  `FrameTiming.trimToDuration` nuevo en el mismo módulo. Pruebas
  unitarias actualizadas y ampliadas en `WebpAnimEncoderTest.kt`
  (`:webp`, JVM puro, con `resolutionDegrader` inyectable porque
  `Bitmap.createScaledBitmap` no funciona fuera de un runtime Android
  real). **Confirmado en dispositivo real** (Redmi Note 14) con el caso
  que fallaba (10 s, contenido adverso — ya no falla, y queda acortado a
  3 s en vez de estirado en los 10 s) y con el caso de referencia de
  ADR-0007 (3 s — mismo resultado de siempre, 348516 bytes, ahora en 2
  codificaciones en vez de 8 gracias al punto 5). Ver
  `docs/desarrollo/pruebas.md`, "Confirmación en dispositivo real".
