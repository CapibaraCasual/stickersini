# ADR-0006: Acotar el costo de tiempo de WebpAnimEncoder frente a RNF-08

- Estado: Propuesto (no implementado; ver nota al final)
- Fecha: 2026-09-20

## Contexto

`QualitySearch` (ver [ADR-0005](0005-libwebp-vendorizado-no-submodulo-ni-binding.md))
busca por bisección la mayor calidad que quepa en 500 KB, y `WebpAnimEncoder`
recodifica la animación **entera** en cada intento: no hay una forma barata
de "probar" una calidad sin pagar el costo completo de codificación.

### Cuántas pasadas hace en el peor caso, medido, no estimado

Simulé la lógica exacta de `QualitySearch.next()` contra las 102
calidades-umbral posibles (0..100, y "ninguna calidad cabe"). El peor caso
real es **8 codificaciones completas de la animación** por cada nivel de
número de fotogramas (ocurre cuando la calidad que realmente cabe es muy
baja, 0 o 1 — ver `docs/desarrollo/pruebas.md` para el script). Y no
termina ahí: si esas 8 no bastan, `WebpAnimEncoder.encode()` combina
fotogramas de dos en dos (`FrameTiming.halve`) y **repite la búsqueda
completa** en el conjunto reducido. Con un número de fotogramas inicial
alto, esto puede encadenar varias rondas de hasta 8 codificaciones cada una.

### Cuánto cuesta cada pasada, medido por proxy, no en el dispositivo real

No hay dispositivo disponible en este entorno (ver la sección "Preparar
la validación en dispositivo" de la respuesta que acompaña a este ADR), así
que no hay forma de medir el costo real de una pasada de
`WebPAnimEncoderAdd` en un teléfono de gama media todavía. En su lugar,
bench-marqué en la máquina de desarrollo la codificación WebP con Pillow
(que también usa libwebp, aunque su propia copia vendorizada y sin
`WebPAnimEncoderOptions.minimize_size`, que es exclusivo del encoder de
animación) sobre una imagen de 512×512 con contenido ruidoso — el caso
realista más caro, mucho más parecido a una captura de pantalla o video
real que a un color plano:

| calidad | method | ms/fotograma (escritorio) |
|---|---|---|
| 100 | 0 (rápido) | 35.3 |
| 100 | 4 (por defecto de libwebp, el que usa hoy `webp_jni.c`) | 72.2 |
| 100 | 6 (máximo esfuerzo) | 134.0 |

`config.method` nunca se fija en `webp_jni.c`: queda en el valor por
defecto de `WebPConfigInit`, que es 4 (verificado en
`third_party/libwebp/src/enc/config_enc.c:37`). Y
`WebPAnimEncoderOptions.minimize_size` está en 1 desde el primer commit del
módulo, con este comentario mío: "RF-10 es un límite duro, no un objetivo
aproximado". El propio header de libwebp lo confirma y además revela que el
costo es aún mayor de lo que mide esta tabla:

```c
// third_party/libwebp/src/webp/mux.h:436
int minimize_size;    // If true, minimize the output size (slow). Implicitly
                       // disables key-frame insertion.
```

`minimize_size` prueba cada fotograma como keyframe **y** como diferencia
contra el anterior, y se queda con el que pese menos — es decir, un costo
extra por fotograma que ni siquiera aparece en el benchmark de Pillow
(que codifica imágenes sueltas, no animaciones). El número real en el
encoder de animación de este proyecto es más alto que 72.2 ms, no más
bajo.

Traduciendo a un teléfono de gama media con un multiplicador conservador de
2×-6× sobre la cifra de escritorio (rango típico citado para código con
carga de CPU/DSP intensiva entre un core de escritorio moderno y un SoC
móvil de gama media; sin medición real todavía, este rango es una hipótesis
a confirmar en el dispositivo):

| Escenario | ms/fotograma estimados en gama media |
|---|---|
| Búsqueda actual (`method=4`, `minimize_size=1`, sin acotar) | 144–432+ |
| Con `method=0` en la fase de búsqueda (propuesta) | 70–212 |

### Por qué esto no cumple RNF-08 tal como está

RNF-08: "la conversión de 3 segundos de video a sticker animado debe
completarse en menos de 5 segundos en un dispositivo de gama media." La
tasa de fotogramas de un sticker no está decidida todavía (es del editor,
Fase 2, fuera de alcance de esta fase), así que uso dos supuestos
razonables para ilustrar la magnitud del problema, no como cifra definitiva:

- **30 fotogramas** (3 s a 10 fps).
- Peor caso actual: 8 codificaciones completas × 30 fotogramas × 144–432
  ms/fotograma = **34.6–103.7 segundos**. Entre 7× y 21× el presupuesto de
  RNF-08, y eso sin contar que podría hacer falta más de una ronda de
  reducción de fotogramas.
- Incluso **una sola** codificación completa a la calidad final
  (`method=4`, `minimize_size=1`) de 30 fotogramas de contenido complejo:
  30 × 144–432 ms = **4.3–13.0 segundos**. En el extremo pesimista del
  rango, ni una sola pasada cabe en el presupuesto — el problema no es solo
  cuántas veces se repite la codificación, es cuánto cuesta cada una.

Esto cambia la conclusión respecto a lo que se me pidió evaluar: acotar
solo el número de iteraciones de la bisección **no basta**. El costo por
intento, dominado por `minimize_size=1` aplicado a cada intento de la
búsqueda en vez de solo al resultado final, pesa al menos tanto como el
número de intentos.

## Opciones consideradas

1. **Límite de iteraciones con degradación aceptable.** Cortar la
   bisección a un máximo fijo de pasos (propuesto: 4, tras una primera
   sonda) y aceptar la mejor calidad encontrada hasta ese punto en vez de
   converger al óptimo exacto. Acota el número de intentos por nivel de
   fotogramas de 8 a 5 (una sonda + 4 refinamientos). Por sí sola, según la
   sección anterior, no basta: el costo por intento sigue siendo el mismo.

2. **Primera estimación a partir del tamaño de los bitmaps de entrada.**
   Se consideró y se descarta como estimador principal: el tamaño crudo
   (ancho × alto × fotogramas) no predice el tamaño comprimido sin saber
   nada del contenido — un fotograma de color plano y uno ruidoso del mismo
   tamaño crudo pueden diferir 50× en bytes comprimidos (se ve en la propia
   tabla de arriba: color plano a calidad 100 pesa 542 bytes, ruidoso a la
   misma calidad pesa 291996 bytes). Un heurístico así podría arrancar la
   búsqueda muy lejos del valor real y no ahorrar nada. En su lugar, uso una
   sonda real y barata como estimador (ver Decisión): más cara que un
   cálculo aritmético, pero fiable porque codifica de verdad.

3. **Reducción de fotogramas antes que de calidad.** Antes de gastar
   ninguna pasada de bisección de calidad, comprobar con una sonda barata
   si el número de fotogramas actual es viable en absoluto. Si ni la
   calidad mínima cabe, reducir fotogramas de inmediato en vez de agotar la
   búsqueda de calidad primero para descubrir al final que no había forma
   de que cupiera. Ataca el número de *rondas* desperdiciadas, no el costo
   de cada intento.

4. **Separar la codificación de "búsqueda" de la codificación "final"**
   (no estaba en las tres opciones planteadas, pero es la que más pesa
   según la medición): usar `method` bajo (0–2) y `minimize_size=0`
   durante toda la bisección, y pagar el costo de `method=4` +
   `minimize_size=1` una sola vez, al final, sobre la calidad ya elegida.
   El tamaño medido durante la búsqueda con `method` bajo no es idéntico al
   que daría el ajuste fino final, así que la calidad elegida por la
   búsqueda rápida se re-valida con una codificación final a los ajustes
   lentos antes de devolverla; si esa validación final no cupiera (raro,
   pero posible), se prueba una calidad menos antes de rendirse.

## Decisión (propuesta)

Combinar las cuatro, no eligiendo una sola: se atacan costos distintos y
no son excluyentes.

1. Antes de cualquier bisección, sondear calidad 0 con los ajustes
   **rápidos** (`method` bajo, `minimize_size=0`). Si ni así cabe, pasar
   directamente a `FrameTiming.halve` sin gastar el resto del presupuesto
   de bisección (opción 3, usando la sonda de la opción 2 en vez de un
   estimador aritmético).
2. Si la sonda cabe, bisecar con los ajustes rápidos, acotado a un máximo
   de 4 refinamientos adicionales (opción 1): 5 intentos rápidos como
   mucho por nivel de fotogramas, no 8.
3. Con la calidad ganadora de la búsqueda rápida, una única codificación
   final con los ajustes lentos actuales (`method=4`, `minimize_size=1`)
   para producir los bytes que de verdad se entregan (opción 4). Si esa
   pasada final no cupiera pese a que la búsqueda rápida sí, bajar la
   calidad en un paso más y reintentar la validación final (acotado
   también, no un bucle sin límite).

Esto cambia el contrato de `SingleShotWebpEncoder`: pasa a necesitar un
modo (`búsqueda` vs. `final`) además de `frames`/`quality`, y
`webp_jni.c` necesita exponer `method` además de `quality`. Es un cambio de
diseño real, no un ajuste de constantes — por eso este ADR, y por eso no
está implementado todavía.

## Consecuencias

- Acota el peor caso a 5 codificaciones rápidas + 1 codificación final por
  nivel de fotogramas, en vez de 8 codificaciones lentas. Con los números
  de esta tabla, eso baja el peor caso de un solo nivel de ~35–104 s a
  aproximadamente 5×30×(70–212 ms) + 30×(144–432 ms) ≈ **14.8–44.8
  segundos**. Sigue muy por encima de RNF-08 en todo el rango estimado —
  la mejora es real (entre 2.3× y 2.4×) pero no basta por sí sola para
  cumplir el presupuesto de 5 segundos sin datos reales del dispositivo.
- La calidad final entregada puede ser ligeramente peor que el óptimo
  matemático que encontraría una bisección sin acotar: costo aceptado a
  cambio de un tiempo predecible, tal como pedía la opción 1.
- Introduce una codificación "de más" (la validación final) que no existía
  antes. En el caso común (pocos fotogramas, contenido simple) esto es
  barato; en el caso adversarial es la pasada más cara de todas, pero solo
  ocurre una vez.
- Los números de esta sección son un proxy de escritorio con Pillow más una
  extrapolación 2×-6× sin verificar, no una medición en un teléfono real.
  Antes de dar esto por resuelto hace falta instrumentar
  `WebpAnimEncoderInstrumentedTest` (o un test nuevo) con tiempos reales de
  pared en el dispositivo del punto 3 de la petición original, y recalibrar
  el límite de iteraciones y la elección de `method` de búsqueda con esos
  números, no con esta estimación.
- Si tras medir en el dispositivo real el peor caso sigue sin caber en el
  presupuesto de RNF-08, las palancas que quedan (no evaluadas aquí por
  quedar fuera de lo pedido) son: paralelizar la codificación de
  fotogramas con `WebPConfig.thread_level`, o replantear qué tasa de
  fotogramas ofrece el editor para los casos más exigentes.

## Nota sobre el estado de este ADR

Se escribe como **propuesta**, no como decisión aceptada e implementada:
la petición que lo origina pide explícitamente no implementar nada nuevo
todavía. Falta confirmación antes de tocar `WebpAnimEncoder.kt`,
`QualitySearch.kt`, `SingleShotWebpEncoder.kt`, `NativeWebpEncoder.kt` y
`webp_jni.c`.
