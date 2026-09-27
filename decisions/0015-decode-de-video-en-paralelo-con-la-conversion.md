# ADR-0015: Decodificar y convertir video en paralelo

- Estado: Aceptado
- Fecha: 2026-09-26

## Contexto

ADR-0012 fijó el fps de prefiltro de video en 8 con un margen ajustado
(12% en los clips de 5 y 10 s, el más apretado de los tres tramos de
RNF-08). Investigar si ese margen podía ampliarse llevó a medir dónde se
va el tiempo hoy: el cuello de botella real, para subir fps, es
`WebpAnimEncoder` (la codificación en sí), no el decode — un video de 10 s
a 8 fps gasta 13-22 s de encode contra 2-5 s de decode. Esa investigación
del encoder se cierra por separado (ver `docs/desarrollo/pruebas.md`,
sección "¿paraleliza `WebPAnimEncoder`..."): ninguna vía encontrada sirve,
así que el fps de prefiltro no sube y el techo de ADR-0012 queda firme.

Pero antes de llegar a esa conclusión se investigaron dos vías para
abaratar el decode en sí — no para subir fps (el decode nunca fue el
cuello de botella para eso), sino porque un decode más barato deja más
margen en el tramo más ajustado de RNF-08 de cara a un dispositivo más
lento que el único medido hasta ahora (un Xiaomi Redmi Note 14):

1. **Pedirle a `MediaCodec`/`ImageReader` menos píxeles desde el decode
   mismo** (`ImageReader` más chico que el nativo del video). **Descartada:**
   este dispositivo ignora por completo el tamaño pedido — entrega
   siempre el nativo (720×1600), sin excepción y sin ninguna ganancia de
   tiempo. No es un costo a optimizar, es una función que el hardware no
   expone (confirmado también contra el propio comportamiento documentado
   de `MediaCodec`, no solo contra este dispositivo).
2. **Decodificar y convertir en paralelo** (superponer la conversión
   YUV→RGB nativa, `:yuv`, ADR-0011, de un fotograma con el decode del
   siguiente, en vez de esperarla). **Funciona**: 1.75×-2.75× más rápido
   que el bucle secuencial en decode+conversión aislado, medido con
   `ParallelDecodeConversionProbeTest` (`app/src/androidTest`).

Una primera remedición de la tubería completa con esta vía (decode
paralelo + `WebpAnimEncoder` real) pareció mostrar que el propio 8 fps de
ADR-0012 ya no cumplía RNF-08 en el clip de 10 s — alarma seria, un
requisito roto en producción. Investigado a fondo, resultó ser un
**artefacto del banco de pruebas**, no un problema de la app: esa
remedición corrió 135 conversiones seguidas dentro de un mismo proceso de
`am instrument` (9 celdas × 3 configuraciones × 5 corridas, sin pausa),
mientras que ADR-0012 se había medido con invocaciones separadas. Aislado
con 5 invocaciones separadas del decoder de producción sin tocar
(`VideoImportPerformanceTest`) y 5 más del prototipo paralelo en
aislamiento (una corrida por invocación): ambos dieron rangos tensos pero
estables, sin ningún indicio de la dispersión de 13.6-24.3 s que mostró la
corrida contaminada. **RNF-08 nunca estuvo roto** — ver
`docs/desarrollo/pruebas.md`, sección "Corrección del hallazgo colateral",
para el detalle completo de esta investigación metodológica.

## Medición

Mismo dispositivo y video real de siempre. Comparación decode+conversión
aislado (sin `WebpAnimEncoder`), secuencial contra paralelo
(`hilos=3`/`capacidad=5`, la configuración que mejor o igual resultado dio
en las 9 celdas probadas):

| fps | Clip | Secuencial (mediana) | Paralelo (mediana) | Mejora |
|---|---|---|---|---|
| 8 | 3 s | 1920 ms | 1095 ms | 1.75× |
| 8 | 10 s | 3429 ms | 1959 ms | 1.75× |
| 12 | 10 s | 4760 ms | 1947 ms | 2.44× |
| 15 | 10 s | 5630 ms | 2049 ms | **2.75×** |

Validación de la tubería completa (decode paralelo, ya implementado en
`VideoFrameDecoder`, + `WebpAnimEncoder` real) a los 8 fps de producción,
método de 5 corridas de ADR-0012, **invocaciones separadas** de
`am instrument` (la disciplina de método que la corrida contaminada no
siguió):

| Clip | Total: mediana [rango] | Presupuesto | Margen peor caso | Margen peor caso, ADR-0012 |
|---|---|---|---|---|
| 3 s | 2317 [2282–2355] | 5000 ms | **52.9%** | 41.1% |
| 5 s | 3429 [3257–3471] | 5000 ms | **30.6%** | 12.0% |
| 10 s (máx.) | 15251 [15210–15319] | 20000 ms | **23.4%** | 12.1% |

Las tres duraciones cumplen con margen real, mejorado en los tres tramos
frente a ADR-0012 — el tramo de 5 s y el de 10 s, los que menos margen
tenían, son los que más ganan.

## Decisión

**`VideoFrameDecoder` decodifica y convierte en paralelo.** La conversión
de cada fotograma (`YuvFrameConverter`) se manda a un `ExecutorService` de
`CONVERTER_THREADS=3` hilos apenas `ImageReader` lo entrega, en vez de
esperarla antes de seguir decodificando; un semáforo
(`IMAGE_READER_CAPACITY - 1 = 4` permisos) frena el bucle de decode si la
conversión se atrasa, para no pedirle a `ImageReader` más buffers de los
que puede retener (`IMAGE_READER_CAPACITY=5`, antes 2). Valores fijos, no
configurables desde fuera: no hay ningún llamador que necesite variarlos,
y son los que midió esta investigación como mejor opción.

**El fps de prefiltro no cambia (sigue en 8, ADR-0012).** Esta Decisión no
lo toca: el decode nunca fue el cuello de botella para subirlo, el
codificador sí, y esa vía se investigó y se cerró por separado sin
encontrar ninguna mejora viable (ver `docs/desarrollo/pruebas.md`). Lo que
esta Decisión cambia es cuánto margen queda contra RNF-08 al fps que ya
había, no el fps en sí.

## Consecuencias

- `VideoFrameDecoder.decode` mantiene la misma firma pública; el único
  cambio observable es la implementación interna y el tiempo total.
- `VideoImportResult` pierde los campos `acquireImageMs`/`conversionMs`:
  con la conversión superpuesta a propósito al decode del siguiente
  fotograma, ya no hay un desglose que aislar — medir cada uno por
  separado dejó de tener sentido. `VideoImportPerformanceTest` se ajustó
  para no citarlos.
- El progreso reportado por `onFrameDecoded` (RNF-08: avance real) ahora se
  dispara desde el hilo que termina cada conversión, no desde el hilo del
  bucle principal — sigue siendo un fotograma a la vez, solo que el hilo
  que lo entrega ya no es siempre el mismo. `ConvertPreviewSaveScreen` ya
  llamaba a este callback desde `Dispatchers.Default` (no el hilo
  principal), así que no cambia ningún supuesto de hilo existente.
- `ParallelVideoFrameDecoder` (`app/src/androidTest`) queda en el repo como
  herramienta de medición para comparar *otras* configuraciones de
  hilos/capacidad contra la adoptada, no como código duplicado de
  producción a mantener en paralelo — si se vuelve a tocar este número,
  ese es el prototipo para remedirlo antes de cambiar las constantes de
  `VideoFrameDecoder`.
- **Lección de método, tan importante como el resultado:** medir un
  parámetro cerca de su presupuesto (RNF-08) corriendo muchas repeticiones
  dentro de un mismo proceso de `am instrument` puede introducir
  dispersión que no está en la app — invocaciones separadas, como hizo
  ADR-0012 originalmente y como se repitió acá para validar, es el método
  correcto para un número que se va a documentar como cumplido o
  incumplido.
