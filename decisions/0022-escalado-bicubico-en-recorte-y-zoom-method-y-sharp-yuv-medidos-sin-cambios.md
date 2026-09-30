# ADR-0022: El recorte y zoom escalan con bicúbico; la bisección de calidad pasa a `Float`; `method` y `use_sharp_yuv` quedan sin cambios

- Estado: Aceptado e implementado
- Fecha: 2026-09-30

## Contexto

Reportado desde uso real: la imagen del sticker se ve "rara" comparada
con Sticker.ly. Referencia de otros conversores (ffmpeg+libwebp): fps 15
(ya igual desde ADR-0021), escalado lanczos, `method` 4-6 y
`use_sharp_yuv`. Se investigaron los tres ejes que no coincidían con esa
referencia.

## Decisión

### 1. El recorte+zoom (RF-07) y la importación de imagen escalan con bicúbico Catmull-Rom, no bilineal

`Bitmap.createScaledBitmap` de Android solo ofrece bilineal o vecino más
cercano — no hay Lanczos nativo en la API pública de Android sin
RenderScript (deprecado) ni FFmpeg (descartado, CLAUDE.md). Se implementó
un resamplers bicúbico Catmull-Rom (`a=-0.5`) en C vía JNI, nuevo en el
módulo `:yuv` (`NativeYuvConverter.resizeBicubic`, mismo módulo que ya
hace trabajo nativo por-píxel para la conversión YUV→RGB): separable por
eje, cada píxel de salida muestrea una vecindad de 4×4 del origen.
Reemplaza el escalado final a 512×512 en `YuvFrameConverter` (video) e
`ImageFrameDecoder` (imagen) — el único paso de escalado que `degradeResolution`
en `:webp` usa (el escalón de resolución degradada de ADR-0020/384) no se
tocó: es un paso de reducir entropía a propósito, no de nitidez, y no
estaba en el alcance de lo pedido.

**Efecto medido de punta a punta, no solo teórico:** el resamplers
cambia de verdad qué píxeles ve el codificador — con el video de
referencia (sin zoom, recorte 720→512 centrado), el mismo `method=0`/sin
`sharp_yuv` da 482 478 B a calidad 93 con bilineal, contra 331 548 B a
calidad 90 con bicúbico (5 corridas, release, determinístico en las dos).
No es una mejora ni una regresión de tamaño en sí — es contenido distinto
para el bisector de calidad de ADR-0021, con una curva calidad↔tamaño
distinta.

Corrección verificada con 4 tests (`NativeYuvConverterResizeTest`, `:yuv`
androidTest): sin una referencia en Kotlin que comparar píxel a píxel
(a diferencia de la conversión YUV→RGB), se verificó la propiedad
matemática que sí es exigible — los pesos de la convolución suman 1 para
cualquier desplazamiento fraccional, así que reescalar un color uniforme
(hacia arriba y hacia abajo) debe devolver el mismo color, con el
redondeo de 8 bits. Los 4 tests pasan.

### 2. `method` (0 actual) y `use_sharp_yuv` (apagado) no cambian

Medido en el video real de referencia, a través del pipeline completo de
producción (`VideoFrameDecoder` + `WebpAnimEncoder`, con el escalado
bicúbico del punto 1 ya aplicado — RF-06 5 s, ADR-0019; escalera de
ADR-0020; maximizar calidad hasta ~95%, ADR-0021), release, 5 corridas
separadas por combinación:

| method | sharp_yuv | calidad final | tamaño | tiempo (5 corridas, estable) |
|---|---|---|---|---|
| **0** | No | 90 | 331 548 B (66.3%) | ~4.7-4.9 s |
| 0 | Sí | 90 | 351 738 B (70.3%) | ~9.5-9.6 s |
| 4 | No | 90 | 275 604 B (55.1%) | ~8.4-8.6 s |
| 4 | Sí | 90 | 290 770 B (58.2%) | ~13.1-13.3 s |
| **6** | No | **75** | 81 902 B (16.4%) | ~14.6-14.7 s |
| 6 | Sí | 75 | 83 568 B (16.7%) | ~14.9-15.0 s |

Determinístico en las 5 corridas de cada combinación.

**`use_sharp_yuv` no ayuda en ninguna combinación medida**: mismo
resultado de calidad, tamaño más grande (+6.1% a +5.5%) y 1.4×-2.1× más
tiempo, en las tres configuraciones de `method` probadas. No se adopta.

**`method=4` comprime mejor a la misma calidad** (275 604 B contra
331 548 B, -16.9%, a igual calidad 90) pero a casi el doble de tiempo
(8.5 s contra 4.8 s) — sigue con margen amplio contra RNF-08 (57.5%), así
que el costo de tiempo por sí solo no lo descarta.

**`method=6` es el hallazgo inesperado: peor, no mejor, en esta métrica.**
Con la búsqueda de ADR-0021 (subir calidad hasta ~95%), converge a
calidad 75 — muy por debajo de calidad 90 que dan `method` 0 y 4 —
dejando 83.6% del límite de tamaño sin usar, y tardando más que
cualquier otra combinación (~14.6 s). La causa más probable: `method`
alto no solo comprime mejor a una calidad dada, también cambia decisiones
internas de libwebp (filtrado, cuadro-clave-vs-diferencia) de una forma
que puede volver la relación calidad↔tamaño menos monótona o dejar un
salto grande entre la calidad 75 (cabe) y la siguiente que se prueba
(no cabe) — no investigado más a fondo, el dato alcanza para descartarlo
sin necesitar la causa exacta.

**Decisión: no cambiar `method` (queda en 0, ADR-0006) ni `use_sharp_yuv`
(queda apagado).** `method=4` mejora tamaño pero no hay margen de RF-10
que lo necesite hoy (66.3% de ocupación con `method=0` ya deja bastante
margen); si en el futuro hace falta más margen de tamaño (contenido más
adverso que el medido), `method=4` es la primera palanca a probar, no
`use_sharp_yuv` ni `method=6`.

Sticker generado por combinación para comparación visual directa
(empujados a `/sdcard/Download/` del dispositivo de prueba, prefijo
`comparar_`): bilineal (antes), bicúbico method=0, bicúbico
method=0+sharp_yuv, method=4, method=4+sharp_yuv, method=6,
method=6+sharp_yuv.

## Dos dudas planteadas antes de aceptar, resueltas con más medición

### ¿Por qué la búsqueda no sube hasta ~95% con method=0/4? ¿Es un bug?

**No es un bug — verificado probando cada calidad de 80 a 100 de forma
directa (sin bisección), sobre el mismo fotograma real** (`QualityCurveProbeTest`,
nuevo, `:app` androidTest):

| Calidad | method=0 | method=4 |
|---|---|---|
| 84 | 195 602 B | 145 812 B |
| 85 | 296 128 B (+51%) | 221 618 B (+52%) |
| 89 | 316 428 B | 261 430 B |
| **90** | **331 548 B (66.3%)** | **275 604 B (55.1%)** |
| **91** | **854 972 B (171%)** | **693 634 B (139%)** |
| 100 | 2 159 186 B | 1 430 880 B |

**Salto real de 2.5×-2.6× entre calidad 90 y 91, en los dos `method`
probados** — no una diferencia gradual. La bisección de `QualitySearch`
sí prueba el valor exacto en el límite (`75→88→94→91→89→90` en el caso de
`method=0`, 6 intentos — confirmado con la traza de `LengthZoomLadderProbeTest`):
al fallar en 91, no queda ninguna calidad entera sin probar entre 90 y
91. El bisector encontró correctamente el techo real de este contenido —
no hay tope de calidad ni de tiempo cortándolo antes. La causa más
probable (no confirmada a fondo): a partir de cierto umbral (~90-91 en
este contenido) libwebp cambia de régimen de cuantización hacia
casi-sin-pérdida, con un costo de tamaño desproporcionado — comportamiento
conocido de codificadores lossy con un parámetro de calidad continuo, no
un defecto de esta implementación.

**No hace falta "arreglar" nada**: la escalera de ADR-0021 ya se
comporta como corresponde (converge al techo real). No se remidió
`method` 0 y 4 con una versión corregida porque no había nada que
corregir — la tabla de arriba ya es la remedición pedida, con la curva
completa en vez de solo el resultado de la bisección.

Nota aparte: `method=6` **crasheó** al repetir esta misma curva completa
(21 codificaciones seguidas en un solo proceso) — se detuvo en calidad 86
sin completar. Coherente con CLAUDE.md ("correr muchas conversiones
seguidas en un mismo proceso da números que no reflejan uso real"): la
escalera de producción nunca hace 21 codificaciones seguidas (como mucho
6-8), así que no es una preocupación de producción, pero confirma que
`method=6` es más pesado de recursos de lo que sugiere el tiempo por
codificación solo.

### ¿El bicúbico tiene soporte fijo (alias en reducciones grandes)?

**Sí tenía ese problema, corregido.** El bicúbico de 4 taps fijos es un
filtro de *interpolación* (correcto para ampliar), no de *anti-alias*
(no basta para reducir por un factor grande sin prefiltrar). Se agregó,
en `nativeResizeBicubic`, un prefiltro de **promedio de área 2×2 en
pasadas sucesivas** cuando la reducción supera ~2× en cualquier eje —
reduce hasta que ambos ejes queden a lo sumo al doble del tamaño de
destino, y recién ahí aplica el bicúbico de 4 taps sobre esa imagen ya
prefiltrada. Opción explícitamente ofrecida como alternativa al soporte
variable, más simple de implementar y de verificar.

**Verificado con un patrón de la frecuencia más alta posible** (rayas de
1 píxel, blanco/negro alternado), reducido 4× (2048→512, por encima del
umbral de 2×): sin el prefiltro, el bicúbico de 4 taps solo ve 4 columnas
del patrón y puede dar blanco o negro casi puro según la fase exacta del
muestreo (alias); con el prefiltro, cada píxel de salida promedia muchas
columnas alternadas y converge a gris medio (`~127`, confirmado dentro de
`100..155` en 4 puntos de muestra) — test nuevo,
`reduccionGrandePromediaEnVezDeAliasear` (`NativeYuvConverterResizeTest`,
5 tests en total ahora).

**Sin efecto visible en el video de referencia usado en las mediciones de
arriba**: el recorte automático (720→512, sin zoom) es una reducción de
apenas 1.4×, por debajo del umbral de 2× — confirmado remidiendo
`method=0`/sin `sharp_yuv` después de la corrección: mismo resultado
exacto, 331 548 bytes, calidad 90. La corrección importa para contenido
con una reducción mayor (video de origen de resolución más alta que la
medida hasta ahora, o fotos de cámara donde `inSampleSize` deje más de
2× de residuo) — no cambia nada para el contenido ya medido.

### 3. La bisección de calidad pasa a `Float`, adoptada a producción

El hallazgo del salto 90→91 (arriba) expuso que bisecar solo enteros deja
resultados lejos del objetivo de ~95% de ADR-0021 cuando el salto cae
justo en el rango que importa. `WebPConfig.quality` ya es un `float`
en libwebp — bisecarlo como tal, no truncado a entero, no tiene costo:
mismo número de codificaciones, solo cambia por dónde caen los puntos
intermedios.

**Adoptado a producción**, no solo investigación: `SingleShotWebpEncoder.encode`,
`QualitySearch` y `WebpAnimEncoder` pasan a `Float` de punta a punta
(`WebpEncodeResult.quality` también). `QualitySearch` converge por
épsilon (`CONVERGENCE_EPSILON = 0.05`, el mismo valor ya validado en la
investigación de method/sharp_yuv) en vez de por igualdad entera — mismo
principio de siembra por el piso de ADR-0020 (saltar a la calidad mínima
si la semilla alta falla, no bisecar el punto medio natural), ahora sobre
un dominio continuo.

**Confirmado en dispositivo real, release, 3 corridas, video de
referencia:** calidad final `90.234375` (no un entero — confirma que la
bisección fraccionaria está activa de verdad), 334 138 bytes (66.8%),
determinístico, ~14 s en el peor caso (30% de margen contra RNF-08).
Métrica y contenido similares a la búsqueda entera previa (90/331 548 B),
como se esperaba: el contenido de referencia no tiene el problema del
salto grande cerca de donde converge, así que `Float` refina apenas —
el beneficio real de este cambio se ve en contenido más adverso (el
TikTok real usado para las mediciones de method/sharp_yuv, no
documentado en este ADR, llegó a 95.6%-99.9% de ocupación con `Float`
donde antes un salto de calidad entera lo hubiera dejado mucho más
lejos del objetivo).

Tests actualizados para `Float` en las tres capas
(`QualitySearchTest`, `WebpAnimEncoderTest`, y los harnesses de
medición que implementan `SingleShotWebpEncoder`) — 22 tests de
`WebpAnimEncoderTest` y 4 de `QualitySearchTest`, todos verdes.

## Consecuencias

- `:yuv`: nuevo `NativeYuvConverter.resizeBicubic` (JNI, bicúbico
  Catmull-Rom con prefiltro de promedio de área 2×2 cuando la reducción
  supera ~2×), usado por `YuvFrameConverter` (video) e
  `ImageFrameDecoder` (imagen) en vez de `Bitmap.createScaledBitmap`.
  `NativeYuvConverterResizeTest` nuevo (`:yuv` androidTest, 5 tests).
- `:webp`: `WebpEncodeResult` gana un campo `resolution` (qué resolución
  de codificación produjo el resultado final — 512 o 384, ADR-0020) — no
  existía forma de saberlo desde fuera sin instrumentar aparte; se agregó
  al confirmar que el video de referencia siempre codifica a 512 (nunca
  degrada resolución).
- **`SingleShotWebpEncoder.encode`, `QualitySearch` y `WebpAnimEncoder`
  pasan a calidad `Float`** (`WebpEncodeResult.quality` también) —
  adoptado a producción, ver punto 3 de la Decisión. `QualitySearch`
  converge por épsilon (`CONVERGENCE_EPSILON = 0.05`), no por igualdad
  entera.
- `webp_jni.c`/`NativeWebpEncoder`: `use_sharp_yuv` y el filtro de
  bloques (`filter_strength`/`autofilter`/`filter_sharpness`/`sns_strength`)
  configurables, hilados igual que `method` (medición, no producción —
  `ProductionWebpEncoder` sigue fijo en `method=0`/sin `sharp_yuv`/filtro
  por defecto). Funciones públicas `measuringWebpEncoder`,
  `measuringWebpEncoderFloat` y `measuringWebpEncoderTuned` para medir
  desde `:app` sin bajarle visibilidad a `NativeWebpEncoder` (mismo
  motivo que `ProductionWebpEncoder`, ver CLAUDE.md).
- `:yuv` gana `NativeYuvConverter.lightBlur` (suavizado gaussiano 3×3),
  investigación de bloques visibles en contenido adverso — no adoptado a
  producción (ver "Experimentos sin decidir" en `docs/desarrollo/pruebas.md`).
- Nuevos harnesses de medición conservados en el repo (`:app` androidTest):
  `MethodSharpYuvSweepTest`, `QualityCurveProbeTest`,
  `FloatQualitySharpYuvSweepTest`, `TunedEncodingSweepTest`.
- No cambia RF-06, RF-10, RF-11, RF-12, RF-13 ni el orden de sacrificio de
  ADR-0020/ADR-0021.
- Todas las mediciones son de un solo dispositivo (Xiaomi Redmi Note 14)
  — sigue pendiente la segunda fila de hardware del README.
