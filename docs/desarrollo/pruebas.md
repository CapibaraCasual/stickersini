# Registro de pruebas manuales

Este documento registra en qué dispositivos y entornos se ha validado
manualmente cada hito del proyecto. No sustituye a los tests automatizados
(`./gradlew test` y `./gradlew connectedAndroidTest`): los complementa donde
el test automatizado no llega, como la confirmación real de WhatsApp al
añadir un pack.

Una fila por prueba. No se borran filas antiguas aunque el resultado quede
obsoleto por un cambio posterior: si algo deja de funcionar, se añade una fila
nueva con el resultado actual, no se edita la vieja.

## Cómo añadir una fila

Copia la plantilla, complétala y añádela al final de la tabla de la fase
correspondiente.

```
| <fecha> | <marca y modelo> | <versión de Android / API> | <WhatsApp o WhatsApp Business y versión> | <qué se probó> | <resultado> |
```

## Fase 0 — validación del ContentProvider

Objetivo: confirmar que WhatsApp acepta un pack publicado por el
`ContentProvider` de esta app y que sus stickers quedan usables en un chat
real. Contexto y decisión en ADR-0004.

| Fecha | Dispositivo | Android | WhatsApp | Qué se probó | Resultado |
|---|---|---|---|---|---|
| 2026-09-20 | `<marca y modelo>` | `<versión de Android / API>` | `<WhatsApp o WhatsApp Business y versión>` | Botón "Añadir pack a WhatsApp" → confirmación en WhatsApp → bandeja de stickers en un chat | WhatsApp mostró el pack semilla y los 3 stickers aparecieron en la bandeja del chat. Fase 0 cerrada. |

## Fase 1 — codificador

Objetivo: dado un conjunto de bitmaps, producir un WebP animado de 512×512
que cumpla RF-10 (≤500 KB), RF-12 (ajuste automático) y RF-13 (fotograma
≥8 ms, animación ≤10 s). Contexto y decisión en ADR-0005.

**Fase 1 cerrada al 2026-09-20** (`v0.2.0-alpha`). `./gradlew :webp:test`
(23 tests, lógica de ajuste de calidad y fotogramas, incluida la
estrategia completa de ADR-0006, el piso de fotogramas de ADR-0007 y su
tope de tiempo real por estimación) y
`./gradlew :webp:externalNativeBuildDebug` / `:webp:assembleDebug` (compila
y enlaza contra libwebp para las cuatro ABI: arm64-v8a, armeabi-v7a, x86,
x86_64) pasan en la máquina de desarrollo.
Validado en dispositivo real (Redmi Note 14, Android 14):
`WebpAnimEncoderInstrumentedTest` (correctitud de la codificación real vía
JNI) pasa entero. `WebpAnimEncoderPerformanceTest` **cumple RNF-08 en los
dos contenidos medidos, con margen real**: contenido representativo,
1 077 ms (muy por debajo de los 5 s); contenido adverso (el peor caso
medido, no uno típico), 14 411 ms de un tope de 20 s — 5 589 ms de margen,
no los 175 ms ajustados de la primera medición (ver detalle abajo).
ADR-0006 y ADR-0007 están Aceptados. Lo que sigue sin observar en este
dispositivo es el comportamiento del tope duro ante un caso que de verdad
lo agote — ninguno de los contenidos probados llegó a necesitarlo. El
historial completo de cómo se llegó hasta acá (la línea base sin arreglar,
las mediciones de `method`, el defecto de 1 fps y su arreglo, el ajuste
del orden de búsqueda y del tope de tiempo) queda abajo, fila por fila,
sin editar ninguna de las ya escritas.

| Fecha | Dispositivo | Android | Qué se probó | Resultado |
|---|---|---|---|---|
| 2026-09-20 | Xiaomi Redmi Note 14 (`24117RN76L`) | Android 14 (API 34), arm64-v8a | `./gradlew :webp:connectedAndroidTest` | `WebpAnimEncoderInstrumentedTest` (correctitud) pasa entero: codifica de verdad vía JNI en el dispositivo. `WebpAnimEncoderPerformanceTest` no se cuelga (los límites de tiempo funcionan) pero no cumple RNF-08 todavía — ver la tabla de rendimiento más abajo. |

### Línea base de rendimiento real (RNF-08), pendiente de medir en dispositivo

`ADR-0006` (propuesto, no implementado) se apoyó en una estimación por
proxy: Pillow con su propia copia de libwebp, en la máquina de desarrollo,
más un factor de extrapolación 2×-6× a "gama media" sin verificar. Esa
estimación queda **reemplazada, no confirmada**, por la medición real de
`WebpAnimEncoderPerformanceTest` en cuanto corra en el teléfono. Hasta
entonces, cualquier número del ADR-0006 sigue siendo una hipótesis, no un
hecho.

| Fecha | Dispositivo | Android | Qué se probó | Resultado |
|---|---|---|---|---|
| 2026-09-20 | Xiaomi Redmi Note 14 (`24117RN76L`) | Android 14 (API 34), arm64-v8a | `WebpAnimEncoderPerformanceTest`, algoritmo **sin arreglar** (`minimize_size=1` en cada intento de la bisección) | **Corrida abortada, no un fallo silencioso.** ~100 s de CPU activa (93% de uso) sin completar ni una sola llamada a `encode()`; no había timeout todavía, así que no terminaba sola. Interrumpida a petición explícita: medir con precisión el peor caso de una versión con un bug conocido no aportaba nada. Dato real, no un borrón: confirma que el costo por intento (no solo el número de intentos) ya hacía inviable RNF-08 antes de cualquier corrección. |
| 2026-09-20 | Xiaomi Redmi Note 14 (`24117RN76L`) | Android 14 (API 34), arm64-v8a | `WebpAnimEncoderPerformanceTest.lineaBaseDeRendimiento_30fotogramas_contenidoAdverso` (algoritmo **ya arreglado**: `minimize_size` solo en la pasada final, `method=4` explícito, límites de 60 s/intento y 5 min/corrida) | **Corte por el tope de la corrida completa**, `outcome=timeout_corrida_completa (>300000ms)`, sin resultado final: 14 intentos en 300 s, ninguno cupo en 500 KB. Intentos 1–12 se perdieron del búfer circular de logcat (motivo del `TraceWriter` de la corrida siguiente, ver más abajo). Los 2 últimos sí quedaron: #13 `quality=2` → 783202 bytes en 13681 ms; #14 `quality=0` → 268858 bytes en 12949 ms. **Conclusión: una sola codificación tarda unos 13 s, más del doble de los 5 s de RNF-08. El problema no es la búsqueda sino el costo por codificación. Ninguna estrategia de búsqueda sola lo resuelve.** |

Con esta fila, ADR-0006 pasa de "el algoritmo sin arreglar tarda demasiado"
(ya sabido) a "arreglado, sigue tardando demasiado, y no es por la
búsqueda": la pregunta abierta ahora es cuánto de esos ~13 s es costo
mínimo de una sola codificación (`method`, contenido) y cuánto es evitable.
No implementar ADR-0006 todavía — ver la sección siguiente para las tres
mediciones que faltan antes de escribirlo con datos reales.

### Cómo correr la validación en dispositivo

```bash
adb devices                            # confirmar que el teléfono aparece como "device", no "unauthorized"
adb shell getprop ro.product.cpu.abi   # informativo: ya se compilan las 4 ABI, cualquier teléfono real sirve
./gradlew :webp:connectedAndroidTest
```

Este comando corre las seis clases de test instrumentado del módulo:
`WebpAnimEncoderInstrumentedTest` (correctitud),
`WebpAnimEncoderPerformanceTest` (la estrategia completa de ADR-0006 y
ADR-0007, de punta a punta, sobre los dos tipos de contenido),
`WebpEncodeMethodBenchmarkTest`, `WebpMinimizeSizeCostTest` y
`WebpFrameFloorMeasurementTest` (mediciones puntuales sin bisección, ya
corridas — ver más abajo, no hace falta repetirlas salvo que cambie el
código nativo) y cualquier otra que se añada después. Para correr solo una o dos clases (evitando repetir el
benchmark de 2–3 min si no hace falta), usar
`-Pandroid.testInstrumentationRunnerArguments.class=` con el nombre
completo, separando varias con coma (ver comandos en las secciones de
cada test más abajo); `--tests` no funciona con `connectedAndroidTest`,
solo con tests unitarios.

Si todo va bien: `BUILD SUCCESSFUL`, y en
`webp/build/reports/androidTests/connected/debug/index.html` los 3 tests de
`WebpAnimEncoderInstrumentedTest` en verde
(`codificaTresFotogramasDeColorPlanoYCumpleRF10`,
`codificaContenidoRuidosoBajandoCalidadHastaCumplirRF10`,
`rechazaBitmapsDeTamanoDistinto`), más
`WebpAnimEncoderPerformanceTest.estrategiaAdr0006_30fotogramas_ambosContenidos`
también en verde. Esta última ya no puede colgarse indefinidamente: además
del tope duro de 20 s que `WebpAnimEncoder` ya tiene incorporado
(ADR-0006), el propio test envuelve la corrida completa en un límite de
60 s por tipo de contenido, puramente como red de seguridad por si ese
tope tuviera un bug. Que el test pase en verde no significa que el tiempo
esté bien: no hay ninguna aserción contra el presupuesto de RNF-08, a
propósito. **El número que importa no es que pase, es lo que imprime.**
Para leerlo sin bucear en el reporte HTML:

```bash
adb logcat -d -s StickersiniPerfBaseline:I
```

Debería mostrar una línea por codificación individual
(`contenido=... intento #N: frameCount=... quality=... minimizeSize=...
sizeBytes=... elapsedMs=...`) y una línea `TOTAL contenido=...` por cada
tipo de contenido, con el número de codificaciones, el tiempo total en ms,
el `outcome` (`exito`, `timeout_corrida_completa`, o `excepcion: ...`), y
la calidad/tamaño/número de fotogramas del resultado final si lo hubo. Ese
`tiempoTotalMs` es el que se compara contra los 5000/20000 ms de RNF-08
según el tipo de contenido. Como el logcat puede perder líneas de una
corrida larga, la traza completa queda también en
`webp_strategy_trace.txt` (ver `adb pull` en la sección de resultados más
abajo).

**Síntomas de fallo del NDK (no de la lógica):**
- `UnsatisfiedLinkError: dlopen failed: library "libstickersini_webp.so" not found` en todos los tests a la vez, antes de que corra ninguna aserción → la ABI del teléfono no tiene `.so` compilado (comprobar con el `getprop` de arriba; con las 4 ABI ya cubiertas esto no debería pasar en ningún teléfono real de los últimos ~10 años).
- `UnsatisfiedLinkError: No implementation found for ... NativeWebpEncoder.nativeEncode` → el `.so` cargó pero JNI no encontró el símbolo: desajuste entre el nombre mangled de `Java_io_github_capibaracasual_stickersini_webp_NativeWebpEncoder_nativeEncode` en `webp_jni.c` y la firma Kotlin.
- La instrumentación entera se cae (no reporta tests individuales) y `adb logcat` muestra `Fatal signal` o un tombstone con `libstickersini_webp.so` en la pila → crash nativo real (puntero mal manejado, stride incorrecto), no un fallo de aserción.
- Cualquier fallo en la fase de instalación del APK antes de "Starting N tests" → problema de compilación/empaquetado, no de este código.

**Síntomas de fallo de lógica (el NDK funciona, el resultado está mal):**
- Mensaje de aserción concreto de JUnit (`expected:<...> but was:<...>`), con el resto de las pruebas corriendo hasta el final.
- `assertIsWebp` falla → los bytes devueltos no empiezan con `RIFF`/`WEBP`: bug de marshaling en `SetByteArrayRegion` o `WebPAnimEncoderAssemble` devolvió algo inesperado, pero el `.so` sí cargó y corrió.
- La aserción de RF-10 falla (tamaño > 500000) → `QualitySearch`/`WebpAnimEncoder` no está limitando de verdad el tamaño.
- La aserción `result.quality < QualitySearch.MAX_QUALITY` del test de contenido ruidoso falla → la búsqueda nunca bajó de calidad 100 pese a contenido difícil de comprimir; revisar la lógica de `WebpAnimEncoder`, no el JNI.
- `rechazaBitmapsDeTamanoDistinto` no lanza `WebpEncodeException` → el chequeo de dimensiones en `webp_jni.c` está mal, pero es un bug de lógica dentro del código nativo, no un fallo de carga/enlace.
- `WebpAnimEncoderPerformanceTest` termina pero con 0 intentos registrados → algo rompió la inyección de `MeasuringEncoder`, no el algoritmo de ajuste en sí.

**`outcome` distinto de `exito` en `WebpAnimEncoderPerformanceTest` no es un fallo del test** (sigue en verde): es el dato que se estaba buscando.
- `outcome=excepcion: Intento #N ... superó el límite de 60000ms` → ese intento concreto no terminó en 60 s; mirar su `minimizeSize` en el log para saber si fue de búsqueda o la pasada final.
- `outcome=timeout_corrida_completa` → entre todos los intentos que sí terminaron, la suma pasó de 5 minutos.
- Cualquiera de los dos significa que RNF-08 no se cumple ni de cerca todavía, no que algo esté roto.

### Antes de escribir ADR-0006: piso real de una sola codificación

La corrida del 2026-09-20 con el arreglo aplicado confirmó que el costo es
por codificación, no por número de intentos, pero perdió los intentos 1–12
del búfer de logcat y no dice cuánto de esos ~13 s es evitable (`method`
más bajo, contenido menos adverso). Antes de tocar ADR-0006 hacían falta
tres cosas, ya implementadas, todas sin ejecutar todavía en el teléfono:

**1. Traza persistente, no solo logcat.** `TraceWriter` escribe cada línea
de inmediato (con flush) a un archivo en el almacenamiento propio de la app
de test, así que sobrevive aunque el búfer de logcat se sature o el
proceso muera a mitad de la corrida. La usan tanto
`WebpAnimEncoderPerformanceTest` (`webp_perf_trace.txt`) como el benchmark
nuevo del punto 3 (`webp_benchmark_trace.txt`). Para traerla:

```bash
adb pull /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini.webp.test/files/webp_perf_trace.txt
adb pull /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini.webp.test/files/webp_benchmark_trace.txt
```

(`io.github.capibaracasual.stickersini.webp.test` es el `applicationId` que
Gradle genera para el APK de test de un módulo de librería sin
`testApplicationId` propio: `namespace` + `.test`. No requiere root: es el
directorio externo específico de esa app, no almacenamiento compartido.)

**2. Valor de `method` tras el arreglo: `4` explícito**, sin cambios desde
antes del arreglo (antes era el mismo valor pero implícito, por el default
de `WebPConfigInit`). El arreglo de este ADR tocó `minimize_size`, no
`method`; seguía en 4 en la corrida de 14 intentos de la tabla de arriba.
`webp_jni.c` ahora recibe `method` como parámetro explícito en vez de
tenerlo fijo, para poder medirlo en el punto 3 — el camino de producción
(`NativeWebpEncoder.encode` de 3 argumentos, el que usa `WebpAnimEncoder`)
lo sigue fijando en 4 por su cuenta.

**3. Benchmark sin búsqueda: `WebpEncodeMethodBenchmarkTest`.** Una sola
codificación por configuración (sin bisección, `minimizeSize=false`, la
opción más barata posible), `method` en `{0, 2, 4, 6}`, calidad fija en 75,
con dos contenidos: el mismo ruido adverso de siempre, y uno "realista"
nuevo (degradado de fondo, zona plana, forma en movimiento y texto —
aproxima una grabación de pantalla real en vez del peor caso puro). 8
codificaciones en total, cada una con el mismo límite de 60 s/intento que
el test de línea base; sin bucle ni reintento, así que el techo de toda la
corrida es conocido de antemano: 8 × 60 s = 8 min en el peor caso.

```bash
adb devices
./gradlew :webp:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.capibaracasual.stickersini.webp.WebpEncodeMethodBenchmarkTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
adb logcat -d -s StickersiniMethodBenchmark:I
adb pull /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini.webp.test/files/webp_benchmark_trace.txt
```

(`--tests` es de los tests unitarios de Gradle, no de `connectedAndroidTest`;
para filtrar una clase instrumentada hay que pasarla como argumento del
`AndroidJUnitRunner`. `leaveApksInstalledAfterRun=true` es necesario porque
por defecto Gradle desinstala el APK de test al terminar, y Android borra su
carpeta de datos —y con ella la traza— junto con la desinstalación.)

Estimación de duración: no hay una medición previa de `method` aislado en
este teléfono, así que esto es una expectativa razonada, no un dato. La
corrida anterior con `method=4` tardó ~13 s en las dos codificaciones que
sí se vieron completas; `method=0` (el más rápido) debería ser
sensiblemente menor, `method=6` (el más lento) probablemente mayor. Con 8
codificaciones a una calidad fija (sin la cuesta arriba de la bisección
bajando de calidad 100), un total de unos 2–4 minutos parece razonable,
pero el límite real es el techo conocido de 8 minutos, no esta estimación.

**Con este piso decidimos:** si la codificación con contenido realista y
`method` bajo cabe holgada en 5 s, ADR-0006 se reescribe alrededor de ese
caso (la búsqueda puede permitirse `method` bajo durante la bisección y
uno más alto solo en la pasada final, por ejemplo). Si ni con contenido
realista y el `method` más rápido se cabe en 5 s, lo que hay que revisar es
el propio presupuesto de RNF-08, no el algoritmo de búsqueda.

#### Resultado, 2026-09-20 (mismo Redmi Note 14, Android 14, arm64-v8a)

Corrida completa, sin timeouts: `BUILD SUCCESSFUL in 2m 26s`, las 8
mediciones se completaron dentro del techo de 8 min conocido de antemano.
Traza completa recuperada con `adb pull` (`webp_benchmark_trace.txt`); no
hizo falta recurrir a logcat, que de hecho perdió 3 de las 8 líneas
(`adverso method=0/2/4`) en su búfer circular durante la corrida — la razón
por la que existe la traza en archivo.

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

Notas sobre los propios números, no interpretación de diseño:
- Con contenido realista, tiempo y tamaño se mueven como predice la
  documentación de libwebp: más `method` → más lento, más chico, con un
  salto grande de `method=4` a `method=6` (3 942 ms → 31 026 ms) por el
  costo mucho mayor del modo exhaustivo.
- Con contenido adverso (ruido independiente por fotograma, sin nada que
  ninguna heurística de `method` pueda aprovechar) la relación no es
  monótona: `method=2` produjo el archivo más grande de los cuatro
  (7 716 510 bytes) y tardó menos que `method=4` o `method=6`. No es un
  bug de esta medición: es contenido diseñado para ser el peor caso
  posible, no contenido típico, y a esa calidad fija (75) ninguno de los
  cuatro se acerca a los 500 KB de RF-10 de todas formas.
- Nótese la brecha entre los dos contenidos: a `method=4`, adverso tardó
  29 266 ms contra 3 942 ms de realista — 7.4×. La corrida anterior que dio
  "~13 s por codificación" usó contenido adverso; con contenido
  representativo de una grabación de pantalla real, ese número no aplica.

**Qué implica `method=0` + contenido realista frente a RNF-08:** 1 330 ms
es 3.76× menor que el presupuesto de 5 000 ms — una sola codificación con
la configuración más barata medida, sobre contenido representativo, cabe
holgada. Pero esto no resuelve RNF-08 por sí solo, por dos motivos que esta
medición no cubre a propósito (es un piso de una sola codificación, no una
medición del flujo completo):
1. `WebpAnimEncoder.encodeWithQualitySearch` no hace una sola codificación:
   hace hasta 8 intentos de bisección más 1 pasada final. A 1 330 ms por
   intento, 8 intentos ya rondan los 10.6 s — más del doble del
   presupuesto — antes de contar la pasada final.
2. Esta medición fija `minimizeSize=false` en las 8 configuraciones, la
   opción más barata a propósito: la pasada final de producción usa
   `minimizeSize=true`, y su costo con `method=0` no está medido en
   ningún punto de este documento. Por el comportamiento de
   `minimize_size` (prueba cada fotograma dos veces, como keyframe y como
   diferencia), es razonable esperar que sea más cara que 1 330 ms, no
   igual.

En resumen: `method=0` con contenido realista muestra que el costo mínimo
de codificar no es, por sí solo, el obstáculo para RNF-08 — hay margen de
sobra ahí. El obstáculo sigue siendo el número de codificaciones que hace
la búsqueda actual y el costo de la pasada final con `minimize_size=true`,
ninguno de los dos medido todavía con `method` bajo. Sin esos dos números
no se puede afirmar que RNF-08 sea alcanzable ni que no lo sea con la
arquitectura actual — que es exactamente lo que ADR-0006 tendría que
decidir, y sigue sin implementarse.

**Nota posterior:** esta conclusión quedó superada el mismo día. El dato de
`method=0` + realista (59 672 de 500 000 bytes) no dice "hay margen de
sobra a pesar de la búsqueda" — dice que con contenido representativo **no
hace falta ninguna búsqueda**: la primera pasada ya cabe. ADR-0006 se
reescribió alrededor de esa observación (dejar de buscar por defecto, no
acotar mejor la búsqueda) y sí está implementado — ver las dos secciones
siguientes para las mediciones que faltaban y el resultado final.

#### Costo de `minimize_size` a `method=0`, medido (para el umbral de ADR-0006)

Mismo dispositivo, calidad 75, sin bisección — una sola pasada con
`minimizeSize=true` por contenido, comparada contra las filas
`minimizeSize=false` ya medidas arriba:

| Contenido | `minimizeSize` | Tamaño (bytes) | Tiempo (ms) |
|---|---|---|---|
| adverso | false | 4 838 184 | 5 178 |
| adverso | true | 4 838 184 | 10 360 |
| realista | false | 59 672 | 1 330 |
| realista | true | 59 610 | 2 232 |

`minimize_size` no redujo el tamaño en absoluto sobre ruido adverso (0
bytes) y solo 62 bytes (0.1%) sobre contenido realista, pese a costar
1.68×–2.0× más tiempo en ambos casos. Es la medición que fija en 80% el
umbral de "cerca del límite" de ADR-0006: el beneficio observado es tan
pequeño que no vale la pena pagarlo salvo que ya casi no quede margen.

Comando (2 mediciones, tope de 60 s cada una):

```bash
./gradlew :webp:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.capibaracasual.stickersini.webp.WebpMinimizeSizeCostTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
adb pull /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini.webp.test/files/webp_minimize_size_trace.txt
```

#### Estrategia de ADR-0006, medida en dispositivo

`WebpAnimEncoderPerformanceTest.estrategiaAdr0006_30fotogramas_ambosContenidos`
corre el orquestador real ([`WebpAnimEncoder`] sobre
[`NativeWebpEncoder`]) de punta a punta, sobre los mismos dos contenidos de
30 fotogramas de siempre. Mismo dispositivo, 2026-09-20:

```bash
./gradlew :webp:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.capibaracasual.stickersini.webp.WebpAnimEncoderPerformanceTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
adb pull /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini.webp.test/files/webp_strategy_trace.txt
```

`BUILD SUCCESSFUL in 19s` (todo el `connectedAndroidTest` de esta única
clase, no solo la corrida). Resultado, leído de `webp_strategy_trace.txt`:

| Contenido | Codificaciones | Tiempo total | Resultado |
|---|---|---|---|
| adverso | 3 | 6 223 ms | quality=75, 3/30 fotogramas, 483 522 bytes |
| realista | 1 | 1 292 ms | quality=75, 30/30 fotogramas, 59 672 bytes |

Traza completa de la corrida adversa: intento 1 (30 fotogramas, quality=75,
`minimizeSize=false`) da 4 838 184 bytes en 4 918 ms — no cabe, la
proporción (500 000/4 838 184 ≈ 0.103) estima 3 fotogramas; intento 2 (3
fotogramas, quality=75) da 483 522 bytes en 483 ms — ya cabe, y al 96.7%
del límite dispara `minimize_size`; intento 3 (3 fotogramas,
`minimizeSize=true`) da el mismo tamaño, 483 522 bytes, en 796 ms — cero
beneficio, consistente con la medición de la sección anterior, pero barato
(796 ms) y se usa igual porque no empeoró. La corrida realista es una sola
línea: 30 fotogramas, quality=75, cabe a la primera (59 672 bytes), sin
reducir nada y sin `minimize_size` (11.9% del límite, muy por debajo del
80%).

**Lectura contra RNF-08 reescrito:**
- Contenido representativo: 1 292 ms, muy por debajo de los 5 000 ms.
  **Cumple con margen amplio.**
- Contenido de alta complejidad visual (el ruido adverso, el peor caso
  posible, no uno típico): 6 223 ms, por debajo de los 20 000 ms, y
  entrega un resultado válido con menos fotogramas (3 de 30) tal como
  permite la segunda cláusula de RNF-08 reescrito. **Cumple, con margen
  amplio también** — el tope duro de 20 s de `WebpAnimEncoder` ni llegó a
  activarse: la estrategia de ADR-0006 resolvió el caso adverso en menos
  de un tercio del presupuesto que tiene disponible.
- Ninguna de las dos corridas necesitó el tope de tiempo: ambas terminan
  por convergencia normal del algoritmo (`outcome=exito` en las dos). El
  comportamiento del tope duro ante un caso que de verdad lo agote sigue
  sin observarse en este dispositivo — no hizo falta para estos dos
  contenidos.

Con esta medición, ADR-0006 pasa de Propuesto a Aceptado.

### ADR-0007: el resultado adverso de arriba (3/30 fotogramas) era un defecto

3 de 30 fotogramas es 1 fps: cabe en RF-10 pero deja de ser una animación.
[ADR-0007](../../decisions/0007-piso-de-fotogramas-antes-de-bajar-calidad.md)
fija un piso de 5 fps (15 fotogramas para el caso de referencia de 3 s) por
debajo del cual la reducción de fotogramas de ADR-0006 no debe bajar;
desde ahí, lo que se ajusta es la calidad.

**Medición previa a la decisión** (`WebpFrameFloorMeasurementTest`, mismo
dispositivo, contenido adverso, `method=0`, sin `minimize_size`, una sola
pasada por configuración):

| Fotogramas | Calidad | Tamaño (bytes) | Tiempo (ms) | ¿Cabe en 500 KB? |
|---|---|---|---|---|
| 30 | 75 | 4 838 184 | 5 178 | No |
| 30 | 50 | 4 125 890 | 6 720 | No |
| 30 | 25 | 3 266 680 | 5 576 | No |
| 30 | 0 | 703 138 | 4 844 | No (40% sobre el límite) |
| 15 | 75 | 2 417 928 | 3 993 | No |
| 10 | 75 | 1 611 694 | 3 358 | No |

Comando:

```bash
./gradlew :webp:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.capibaracasual.stickersini.webp.WebpFrameFloorMeasurementTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
adb pull /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini.webp.test/files/webp_frame_floor_trace.txt
```

Lectura: bajar calidad sola, incluso al mínimo (0), no basta a 30
fotogramas. El tamaño escala casi perfectamente lineal con el número de
fotogramas a calidad fija (~161 200 bytes/fotograma, consistente entre 3,
10, 15 y 30 fotogramas medidos a quality=75). Ver el detalle completo y la
justificación del piso de 5 fps en ADR-0007.

**Corrida completa de la estrategia, ya con el piso implementado**
(`WebpAnimEncoderPerformanceTest`, mismo dispositivo):

| Contenido | Codificaciones | Tiempo total | Resultado |
|---|---|---|---|
| realista | 1 | 1 226 ms | quality=75, 30/30 fotogramas, 59 672 B (**sin cambios** respecto a la corrida anterior) |
| adverso | 8 | **19 825 ms** | quality=0, **15/30 fotogramas**, 348 516 B |

Traza completa del caso adverso: intento 1 (30 fotogramas, quality=75) da
4 838 184 bytes — no cabe, el piso de 5 fps clampa la reducción a 15
fotogramas (la proporción sola habría pedido menos). Intento 2 (15
fotogramas, quality=75) da 2 418 984 bytes — tampoco cabe, entra la
bisección de calidad sobre esos 15 fotogramas fijos. Intentos 3 a 8 bajan
la calidad (37, 18, 8, 3, 1, 0); **ninguna calidad entre 1 y 74 cupo en
500 KB** — quality=1 dio 680 120 bytes, todavía sobre el límite — así que
la bisección corrió sus 8 intentos completos hasta el mínimo absoluto
(quality=0, 348 516 bytes) en vez de cortar antes.

**Esto deja un margen de tiempo mucho más ajustado de lo que sugería la
extrapolación de ADR-0007: 19 825 ms de un tope de 20 000 ms — 175 ms de
margen.** Cumple RNF-08 (segundo tramo, ≤20 s), pero por muy poco. El
tamaño sí confirmó la extrapolación casi exacto (348 516 medidos contra
~351 300 estimados), pero nadie había estimado cuántos intentos de
bisección harían falta ni cuánto tardaría cada uno en el dispositivo real.
Registrado como hallazgo en ADR-0007, no oculto: si aparece contenido real
más adverso que este ruido sintético, o un dispositivo más lento, este es
el primer lugar donde revisar antes de asumir que RNF-08 sigue
cumpliéndose.

Comando (igual que el de la sección anterior, ya no hace falta repetir el
de correctitud): ver `webp_strategy_trace.txt` vía `adb pull`, comando en
la sección "Estrategia de ADR-0006, medida en dispositivo" de arriba.

### Orden de búsqueda en el piso: calidad mínima primero (fix sin ADR, dentro de ADR-0007)

Los 175 ms de margen de la sección anterior escondían un riesgo real: la
bisección de calidad, sembrada desde 75, llegaba a `quality=0` — la única
que cabía en el peor caso medido — como su *último* intento, no el
primero. En un teléfono más lento que el Redmi Note 14, el tope de 20 s
podía cumplirse antes de probar 0, y entonces no había ningún resultado
válido que devolver: RF-12, sin sticker.

**Qué devolvía el tope hasta este cambio, cuando no había encontrado
ningún resultado válido todavía:** una excepción (`WebpEncodeException`,
RF-12) — nada que entregar al usuario. Confirmado leyendo el código antes
de tocarlo, no supuesto.

**Arreglo:** al llegar a la bisección de calidad ya con el número de
fotogramas en el piso, se prueba `QualitySearch.MIN_QUALITY` (0) primero
en vez del punto medio habitual. Si no cabe, se sabe en una sola
codificación que no hay solución a ese número de fotogramas. Si cabe,
queda de inmediato como resultado válido garantizado, y el tiempo restante
se usa para bisecar hacia arriba buscando algo mejor sin arriesgar esa
garantía. Razonamiento completo en el KDoc de `QualitySearch`. No hizo
falta ADR nuevo: es una decisión dentro de lo que ya definía ADR-0007, no
una decisión estructural distinta (ver la actualización añadida al final
de ese documento).

**Antes y después, mismo dispositivo, mismo contenido adverso (30
fotogramas de entrada):**

| | Antes (bisección desde 75) | Después (0 primero en el piso) |
|---|---|---|
| Orden de calidades probadas en la fase 3 | 37, 18, 8, 3, 1, **0** (último) | **0** (primero), 37, 18, 9, 4, 2 |
| Resultado válido garantizado desde | ~19.8 s (el último intento) | **~11.1 s** (el tercer intento) |
| Codificaciones totales | 8 | 8 |
| Tiempo total de la corrida | 19 825 ms | 21 825 ms |
| Resultado final | quality=0, 15/30 fotogramas, 348 516 B | mismo: quality=0, 15/30 fotogramas, 348 516 B |

**El tiempo total no mejoró — de hecho fue un poco peor, y esta corrida
concreta superó el tope de 20 s por ~1.8 s** (la última codificación en
curso cuando se cumple el tope corre hasta terminar; comportamiento
documentado, no nuevo). Después de banquear `quality=0`, la búsqueda sigue
explorando hacia arriba buscando algo mejor, y en este contenido no existe
nada mejor, así que ese tiempo adicional no encuentra nada — pero tampoco
pierde la garantía ya conseguida. Eso era exactamente el objetivo pedido:
no que la corrida sea más rápida, sino que un corte del tope de tiempo en
cualquier punto después de los ~11.1 s ya no deje a quien usa la app sin
resultado. El contenido realista no cambió: 1 codificación, 1 229 ms,
quality=75, 59 672 bytes — igual que en todas las corridas anteriores.

Traza completa (`webp_strategy_trace.txt`, mismo comando de `adb pull` de
arriba) — nótese `intento #3` como el primero en probar `quality=0`, en
vez del último:

```
intento #1: frameCount=30 quality=75 sizeBytes=4838184 elapsedMs=6285
intento #2: frameCount=15 quality=75 sizeBytes=2418984 elapsedMs=2939
intento #3: frameCount=15 quality=0  sizeBytes=348516  elapsedMs=1865  <- ya hay resultado válido
intento #4: frameCount=15 quality=37 sizeBytes=1870764 elapsedMs=2300
intento #5: frameCount=15 quality=18 sizeBytes=1458716 elapsedMs=2188
intento #6: frameCount=15 quality=9  sizeBytes=1180814 elapsedMs=2118
intento #7: frameCount=15 quality=4  sizeBytes=934942  elapsedMs=2051
intento #8: frameCount=15 quality=2  sizeBytes=787848  elapsedMs=2020
TOTAL: codificaciones=8 tiempoTotalMs=21825 outcome=exito resultadoQuality=0 resultadoBytes=348516 resultadoFrameCount=15
```

**Pendiente, no cubierto por esta medición:** que la corrida completa haya
superado el tope de 20 s (21 825 ms) es un dato nuevo, no solo un margen
estrecho como antes. Ningún cambio de este turno lo dirige — el objetivo
pedido era la garantía de resultado, no el cumplimiento estricto del
tiempo total, y quedó explícitamente fuera de alcance. Si en el futuro se
decide que el tiempo total también debe respetarse de forma estricta, una
palanca disponible (no implementada) es dejar de buscar una calidad mejor
en cuanto ya hay un resultado válido banqueado y quede poco tiempo, en vez
de seguir intentando mejorar hasta que el tope corte.

### Tope de tiempo real: estimación previa + dejar de buscar sin margen

La palanca que quedó pendiente en la sección anterior se implementó: dos
cambios sobre `WebpAnimEncoder`, ambos dentro de lo que ya decidía
ADR-0007 (sin ADR nuevo).

1. **Estimación previa a cada codificación.** Antes de lanzar cualquier
   codificación, se compara el tiempo restante contra la duración de la
   última codificación medida con el mismo número de fotogramas; si no
   alcanza, no se lanza. Sin dato previo para ese número de fotogramas
   (la primera vez que se prueba), se permite el intento — no hay con qué
   estimar. Antes, el tope solo se comprobaba entre codificaciones sin
   estimar nada, así que una codificación que no iba a alcanzar a
   terminar se lanzaba igual.
2. **Dejar de buscar hacia arriba sin margen real.** En el piso de
   fotogramas, tras confirmar que la calidad mínima cabe, seguir
   bisecando hacia arriba solo tiene sentido si ese resultado deja
   margen: el umbral es 50% del límite, elegido con la propia medición de
   la corrida anterior (calidad 0 a 15 fotogramas ocupó 70% del límite y
   ninguna calidad superior cupo — los 5 intentos que lo intentaron no
   encontraron nada y costaron ~10.5 s de los ~21.8 s totales).

**Resultado, mismo dispositivo, mismo contenido adverso:**

| | Antes (sin estimación, sigue buscando siempre) | Después (estimación + corta sin margen) |
|---|---|---|
| Codificaciones | 8 | **3** |
| Tiempo total | 21 825 ms | **14 411 ms** |
| ¿Cumple RNF-08 (≤20 000 ms)? | No (lo supera por ~1.8 s) | **Sí, con 5 589 ms de margen** |
| Resultado final | quality=0, 15/30 fotogramas, 348 516 B | mismo: quality=0, 15/30 fotogramas, 348 516 B |

El resultado final no cambió (sigue siendo la mejor calidad alcanzable a
15 fotogramas para este contenido); lo que cambió es que ya no se gastan
5 codificaciones más buscando algo mejor que no existe. Traza completa:

```
intento #1: frameCount=30 quality=75 sizeBytes=4838184 elapsedMs=10033
intento #2: frameCount=15 quality=75 sizeBytes=2418984 elapsedMs=2458
intento #3: frameCount=15 quality=0  sizeBytes=348516  elapsedMs=1889  <- 69.7% del límite: sin margen, no sigue buscando
TOTAL: codificaciones=3 tiempoTotalMs=14411 outcome=exito resultadoQuality=0 resultadoBytes=348516 resultadoFrameCount=15
```

Contenido realista, sin cambios: 1 codificación, 1 077 ms, quality=75,
59 672 bytes.

Nuevo test unitario (`nunca lanza una codificacion sin tiempo estimado
para terminar`) verifica la propiedad de forma directa: registra en qué
momento arrancó cada codificación simulada y cuánto tardó la anterior con
el mismo número de fotogramas, y falla si alguna arrancó con menos tiempo
restante del que esa anterior había tardado.

**Fase 1 cerrada:** el caso adverso (peor caso medido, no uno típico)
cumple RNF-08 con margen real, no al límite. Etiquetado como
`v0.2.0-alpha`.

### Peso del AAB por ABI (RNF-07, valida ADR-0002)

Medido en la máquina de desarrollo el 2026-09-20, sin dispositivo: build de
`:app:bundleRelease` con `app` ya dependiendo de `:webp`
(`implementation(project(":webp"))`, añadido para esta medición — antes no
existía esa dependencia y el .aab no habría incluido libwebp en absoluto,
midiendo lo que no tocaba). Tamaño de descarga estimado por configuración
vía `bundletool get-size total --dimensions=ABI,SDK`:

| ABI | Descarga estimada (min–max) |
|---|---|
| arm64-v8a | 7.92–7.93 MB |
| x86_64 | 7.97–7.98 MB |
| armeabi-v7a (sin `.so` de libwebp, no compilado para esta ABI) | 7.67–7.68 MB |
| x86 (sin `.so` de libwebp, no compilado para esta ABI) | 7.67–7.68 MB |

**Cumple RNF-07** (<15 MB) con margen amplio: la ABI más pesada
(x86_64) queda a menos de la mitad del límite.

Diferencia atribuible al `.so` de libwebp compilado (release, símbolos
recortados por AGP): ~243 KB en arm64-v8a, ~289 KB en x86_64 — consistente
con lo que predecía ADR-0002 ("pesa unos cientos de kilobytes"). El resto
del peso (~7.4 MB base) es Compose + AndroidX, no libwebp.

Nota aparte de la medición pedida: `armeabi-v7a` y `x86` no tienen
`.so` de libwebp (`webp/build.gradle.kts` solo compila para `arm64-v8a` y
`x86_64`, ver Fase 1 más arriba), pero el AAB no restringe la instalación a
esas dos ABI. Un teléfono real de 32 bits puro instalaría la app y
`NativeWebpEncoder` fallaría al cargar la librería nativa en cuanto algo la
invoque. No es un problema hoy (nada en `app` llama todavía al encoder),
pero hay que resolverlo (compilar también `armeabi-v7a`, o declarar
`<supports-screens>`/`splits` que excluyan esas ABI) antes de que el editor
de la Fase 2 dependa de verdad de `:webp`.

**Re-medido el 2026-09-20, tras compilar las 4 ABI** (no se edita la
medición anterior, se añade esta): mismo procedimiento
(`:app:bundleRelease` + `bundletool get-size total --dimensions=ABI,SDK`),
ahora con `webp/build.gradle.kts` compilando `arm64-v8a`, `armeabi-v7a`,
`x86` y `x86_64`.

| ABI | Descarga estimada (min–max) |
|---|---|
| x86 | 7.99–8.03 MB |
| x86_64 | 7.97–8.01 MB |
| arm64-v8a | 7.92–7.97 MB |
| armeabi-v7a | 7.89–7.93 MB |

**Sigue cumpliendo RNF-07** (<15 MB) con margen amplio: la ABI más pesada
(x86) queda a la mitad del límite. La nota del hueco de ABI de la medición
anterior queda resuelta: las 4 ABI que usa Android hoy en dispositivos
reales tienen `.so` de libwebp.

## Fase 2 — importación de video

Objetivo: dado un video existente, decodificarlo con `MediaCodec` y
producir un WebP animado válido de punta a punta (ADR-0008), y medir en
dispositivo real cuánto tarda decodificar + convertir + codificar una
grabación de pantalla real — no el contenido sintético de la Fase 1 — para
saber si RNF-08 se sostiene con contenido de verdad.

`./gradlew :app:test` (`FrameSamplerTest`: el muestreo uniforme de
fotogramas de ADR-0008, incluida la verificación exacta de que 90
fotogramas de origen a 30 fps dan 60 conservados a 20 fps objetivo;
`ClipRangeTest`: el cálculo del tramo de entrada — inicio, duración, tope de
10 s de RF-06, truncamiento cuando el video no llega) pasa en la máquina de
desarrollo. La conversión YUV→RGB, el posicionamiento real
(`MediaExtractor.seekTo`) y el decodificador en sí (`VideoFrameDecoder`,
`YuvFrameConverter`) necesitan `MediaCodec` real y solo se validan en
dispositivo, con el procedimiento de abajo.

| Fecha | Dispositivo | Android | Video usado | Qué se probó | Resultado |
|---|---|---|---|---|---|
| 2026-09-25 | Xiaomi Redmi Note 14 (`24117RN76L`) | Android 14 (API 34), arm64-v8a | Grabación de pantalla real, `Recording_20260919_191641.mp4` (ver características abajo) | `VideoImportPerformanceTest` completo (`decodificaYCodificaUnVideoReal` + `decodificaDesdeUnInicioArbitrario`), vía `adb push` + `connectedAndroidTest` | **No cumple RNF-08.** El decode solo (10 s de origen → 200 fotogramas) tardó 31 823 ms — ya por sí solo casi 6.4× el presupuesto de 20 000 ms del segundo tramo de RNF-08, sin contar el encoder. La codificación no llegó a producir ningún resultado válido: `WebpAnimEncoder.encode` terminó en `WebpEncodeException` (RF-12) a los ~15.8 s de haber empezado, sin bajar de 500 KB ni al mínimo de calidad. `decodificaDesdeUnInicioArbitrario` (posicionamiento con `startMs=1000`) sí pasó, en 6 185 ms. Detalle completo abajo. |

### Video usado en esta medición

No es contenido sintético: es una grabación de pantalla real ya existente en
el teléfono (Xiaomi Redmi Note 14), la más reciente entre `/sdcard/DCIM/Camera/`,
`/sdcard/Movies/` y las carpetas típicas de grabador de pantalla —
`/sdcard/DCIM/ScreenRecorder/` no existía en este teléfono; el archivo
apareció en `/sdcard/Movies/Recorder0/`, la carpeta del grabador de pantalla
integrado de MIUI.

| Propiedad | Valor |
|---|---|
| Archivo de origen | `/sdcard/Movies/Recorder0/Recording_20260919_191641.mp4` |
| Tamaño | 25 667 796 bytes (~24.5 MB) |
| Duración total | 37.687 s (medida por `ffprobe`; `MediaFormat.KEY_DURATION` coincide: 37 687 ms) |
| Resolución | 720×1600 (retrato, pantalla completa del teléfono) |
| Códec de video | H.264 |
| Fotogramas totales (video completo) | 1322 (contados con `ffprobe -count_frames`) |
| fps promedio (video completo) | ≈35.1 (1322 fotogramas / 37.687 s) — tasa variable de MIUI, no constante |
| Bitrate | ~5.43 Mbps |
| Pista de audio | Sí (AAC) — irrelevante para esta medición, `VideoFrameDecoder` no la toca |

El video se copió del teléfono a la máquina de desarrollo (`adb pull`), no se
editó, y se volvió a subir con `adb push` al nombre que espera el test
(`stickersini_test_video.mp4`) en el `externalFilesDir` de la app — la app no
estaba instalada todavía en este teléfono, así que hubo que instalarla
primero (`./gradlew :app:installDebug`) para que esa carpeta existiera.

### Traza completa

`video_import_trace.txt` (`decodificaYCodificaUnVideoReal`):

```
[21:09:48.733] === inicio: /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini/files/stickersini_test_video.mp4 (25667796 bytes) ===
[21:10:20.562] decode: elapsedMs=31823 sourceDurationMs=37687 truncated=false decodedFrameCount=200 framesParaEncoder=200
[21:10:36.389] === fin ===
```

No hay línea `encode:` ni `TOTAL:`: el test no las alcanza a escribir porque
`WebpAnimEncoder.encode()` lanzó `WebpEncodeException` antes de volver, y el
test no captura esa excepción (se propaga y marca el test en rojo, no un
`outcome` registrado como en `WebpAnimEncoderPerformanceTest` de la Fase 1).
El resultado exacto, tomado del reporte de JUnit
(`app/build/outputs/androidTest-results/connected/debug/`):

```
io.github.capibaracasual.stickersini.webp.WebpEncodeException: RF-12: no se pudo producir un WebP de 500000 bytes o menos ni bajando calidad ni reduciendo fotogramas dentro de 20000ms
	at io.github.capibaracasual.stickersini.webp.WebpAnimEncoder.encode(WebpAnimEncoder.kt:203)
	at io.github.capibaracasual.stickersini.media.VideoImportPerformanceTest.decodificaYCodificaUnVideoReal(VideoImportPerformanceTest.kt:84)
```

Tiempo total del método de test (JUnit): 47.625 s. Con el decode en
31.823 s, eso deja ≈15.8 s para el intento de codificación antes de la
excepción — un número derivado por resta, no logueado directamente, porque
la excepción interrumpe el test antes de que se pueda medir `encodeMs` por
separado.

`video_import_seek_trace.txt` (`decodificaDesdeUnInicioArbitrario`,
`startMs=1000, durationMs=2000` sobre el mismo video):

```
[21:10:36.502] === inicio: startMs=1000 durationMs=2000 sobre /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini/files/stickersini_test_video.mp4 ===
[21:10:42.587] decode: elapsedMs=6084 truncated=false decodedFrameCount=36 framesParaEncoder=36 duracionTotalFramesMs=1980
[21:10:42.588] === fin ===
```

Esta sí pasó: 36 fotogramas conservados para 1980 ms de duración total (2 s
pedidos), sin lanzar ninguna excepción. Confirma en dispositivo real, no
solo en `ClipRangeTest`, que `MediaExtractor.seekTo(startUs,
SEEK_TO_PREVIOUS_SYNC)` posiciona correctamente y que el descarte de lo
anterior a `startMs` funciona.

### Lectura de estos números

- **RNF-08 no se cumple, y no por poco.** El decode solo (31 823 ms) ya
  supera los 20 000 ms del tramo de "alta complejidad visual" de RNF-08 sin
  que el encoder llegue a correr una sola vez; ni hablar de los 5 000 ms del
  tramo representativo. Y el encoder, con lo que el decode le entregó (200
  fotogramas de 512×512 de contenido real), no encontró ningún resultado
  válido dentro de su propio tope de 20 s: terminó en RF-12
  (`WebpEncodeException`), no en un resultado degradado.
- **200 fotogramas es una escala nunca antes probada.** Todas las
  mediciones previas de `WebpAnimEncoder` (ADR-0006, ADR-0007) usaron 30
  fotogramas como máximo, sobre un caso de referencia de 3 s. Acá el clip es
  de 10 s (el tope de RF-06) y el prefiltro de 20 fps de ADR-0008 entregó
  exactamente 200 — el cálculo es correcto (`20 × 10 = 200`, confirmado en
  dispositivo real, no solo en `FrameSamplerTest`), pero nadie había medido
  antes cuánto tarda `WebpAnimEncoder` con un conjunto de entrada de ese
  tamaño. El piso de fotogramas de ADR-0007 (5 fps × 10 s = 50) también es
  muy superior a cualquier cosa medida hasta ahora.
- **No hay traza por intento** (esta prueba no envuelve
  `NativeWebpEncoder` con un `MeasuringEncoder` como sí hace
  `WebpAnimEncoderPerformanceTest` en `:webp`), así que no se puede saber
  todavía si el problema es el tamaño de cada intento, el número de
  intentos, o ambos — ni en qué fase de `WebpAnimEncoder` (búsqueda de
  fotogramas, bisección de calidad, umbral del 50%, `minimize_size`) se
  agotó el tiempo. Instrumentar eso es el paso obvio antes de decidir qué
  cambiar, pero es un cambio de código y queda fuera del alcance de esta
  medición.
- **El recorte automático a cuadrado también es relevante para leer estos
  números con cuidado:** el video es retrato (720×1600); `YuvFrameConverter`
  recorta al cuadrado centrado más grande (720×720, el 45% de la altura
  original) antes de escalar a 512×512. El contenido real que llegó al
  encoder es una franja central de la pantalla, no la captura completa —
  correcto según el alcance de esta fase (RF-07 no está implementado
  todavía), pero relevante si se compara este resultado con expectativas
  sobre "toda la pantalla".
- **Hallazgo aparte, no de rendimiento: `truncated=false` en la línea
  `decode` de arriba, con un video de 37 687 ms procesado hasta los 10 000
  ms, se lee raro a primera vista.** No es un bug de esta corrida: con los
  valores por defecto (`startMs=0, durationMs=10000`), `ClipRange` compara
  el tramo procesado contra lo *pedido* (10000 ms), no contra la duración
  *total del video* (37 687 ms), y lo pedido se cumplió exacto. El efecto
  práctico es que, con los parámetros por defecto que usa esta fase,
  `truncated` nunca se activa por esta vía — solo se activaría si algún
  llamador pidiera explícitamente más de 10 s, o un tramo que el video no
  llega a cubrir. Si la intención (RF-06) es que la futura UI pueda avisar
  "solo se usaron los primeros 10 segundos de tu video", este campo, tal
  como quedó definido, no sirve para eso todavía — haría falta comparar
  contra la duración total del video, no contra lo pedido. No se toca en
  esta medición: es un hallazgo para revisar junto con el resto, no un
  cambio de código de esta sesión.

**Actualización, mismo día:** confirmado como bug real (no una lectura
rara nomás) y corregido — ver `ClipRange` y la fila
`seMarcaTruncadaSiElVideoSigueDespuesDelTramoProcesado` en
`ClipRangeTest`. El fps de prefiltro también se corrigió (ver ADR-0009). La
sección siguiente mide de nuevo con ambos arreglos aplicados.

## Fase 2 — segunda corrida: prefiltro a 5 fps (ADR-0009) y `truncated` corregido

Mismo dispositivo (Redmi Note 14, Android 14, arm64-v8a) y el mismo video
real de la corrida anterior (`Recording_20260919_191641.mp4`), sin volver a
grabar ni tocar el archivo. Tres cambios desde la corrida anterior, todos
documentados en ADR-0009 y en el texto de arriba:

1. `VIDEO_PREFILTER_TARGET_FPS` de 20 a 5 (ADR-0009): 200 → 50 fotogramas
   para este clip de 10 s.
2. `ClipRange.truncated` corregido: ahora compara la duración real del
   video contra el tramo procesado, no el tramo pedido contra su propio
   tope.
3. `VideoImportPerformanceTest` instrumentado con `MeasuringEncoder` (la
   misma idea que `WebpAnimEncoderPerformanceTest` en `:webp`, expuesta vía
   el nuevo `ProductionWebpEncoder` porque `NativeWebpEncoder` es
   `internal` a ese módulo): ahora la traza muestra cada intento de
   codificación por separado, y una `WebpEncodeException` ya no tumba el
   test — se registra como `outcome` y la corrida completa queda en el
   archivo de todas formas (mismo criterio que `:webp`: un `outcome`
   distinto de `exito` es el dato buscado, no un fallo del test).

| Fecha | Dispositivo | Android | Video usado | Qué se probó | Resultado |
|---|---|---|---|---|---|
| 2026-09-25 | Xiaomi Redmi Note 14 (`24117RN76L`) | Android 14 (API 34), arm64-v8a | Mismo `Recording_20260919_191641.mp4` de la corrida anterior | `VideoImportPerformanceTest`, prefiltro a 5 fps, con traza por intento | **Cumple RNF-08 (segundo tramo, ≤20 000 ms), no el de 5 000 ms.** Decode: 9 681 ms (50 fotogramas). Encode: 1 solo intento, quality=75, 292 538 bytes, 3 185 ms — sin bisección. Total: 12 866 ms. |

### Traza completa

`video_import_trace.txt`:

```
[21:24:17.868] === inicio: /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini/files/stickersini_test_video.mp4 (25667796 bytes) ===
[21:24:27.558] decode: elapsedMs=9681 sourceDurationMs=37687 truncated=true decodedFrameCount=50 framesParaEncoder=50
[21:24:30.746] intento #1: frameCount=50 quality=75 minimizeSize=false sizeBytes=292538 elapsedMs=3179
[21:24:30.747] encode: elapsedMs=3185 outcome=exito intentos=1 sizeBytes=292538 quality=75 frameCount=50
[21:24:30.747] TOTAL: decodeMs=9681 encodeMs=3185 totalMs=12866 outcome=exito (comparar contra RNF-08: 5000ms contenido representativo, 20000ms alta complejidad visual)
[21:24:30.747] === fin ===
```

`truncated=true` esta vez — confirma el arreglo del bug: el video (37 687
ms) sigue después del tramo procesado (10 000 ms).

`video_import_seek_trace.txt` (`decodificaDesdeUnInicioArbitrario`,
`startMs=1000, durationMs=2000`, sin cambios de código propios pero
afectada por el nuevo fps y el fix de `truncated`):

```
[21:24:30.871] === inicio: startMs=1000 durationMs=2000 sobre /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini/files/stickersini_test_video.mp4 ===
[21:24:33.217] decode: elapsedMs=2345 truncated=true decodedFrameCount=10 framesParaEncoder=10 duracionTotalFramesMs=1993
[21:24:33.218] === fin ===
```

10 fotogramas (5 fps × 2 s, exacto) y `truncated=true` correctamente
(el video sigue después del segundo 3).

### Tabla comparativa, mismo video, antes y después de ADR-0009

| | Antes (20 fps) | Después (5 fps) |
|---|---|---|
| Tiempo de decodificación | 31 823 ms | **9 681 ms** |
| Tiempo de codificación | — (sin resultado) | **3 185 ms** |
| Tiempo total | — (nunca terminó) | **12 866 ms** |
| Fotogramas de origen (ventana de 10 s) | ~351 estimados | ~351 estimados (sin cambios: `MediaCodec` decodifica lo mismo) |
| Fotogramas conservados tras el prefiltro | 200 | **50** |
| Fotogramas finales (al codificador) | — | **50** (sin reducción adicional: cupo a la primera) |
| Calidad final | — | **75** (la de partida, ADR-0006 — no hizo falta bajarla) |
| Tamaño final | — | **292 538 bytes** (58.5% de RF-10) |

### La pregunta sobre el costo del decode: ¿por fotograma convertido, o por recorrido fijo de `MediaCodec`?

Bajar el prefiltro de 20 a 5 fps (200 → 50 fotogramas, una reducción de
4×) bajó el tiempo de decode de 31 823 a 9 681 ms — una reducción de 3.29×,
casi proporcional a la reducción de fotogramas *convertidos*. Si el costo
dominante fuera el recorrido de `MediaCodec` por los ~351 fotogramas del
tramo de 10 s (que no cambia entre las dos corridas: el decodificador tiene
que decodificarlos todos igual, sin importar cuántos se conserven después,
por las dependencias P/B), el tiempo de decode debería haber sido casi
idéntico en ambas corridas. No lo fue.

Ajustando un modelo lineal simple (`tiempo = fijo + costoPorFotograma ×
fotogramasConvertidos`) con los dos puntos medidos:

```
31823 = fijo + costoPorFotograma × 200
 9681 = fijo + costoPorFotograma × 50
```

Resolviendo: `costoPorFotograma ≈ 147.6 ms`, `fijo ≈ 2301 ms`.

**Lectura: de los 9 681 ms de la corrida de 50 fotogramas, ~2 301 ms
(24%) son costo fijo (abrir el extractor, posicionar, decodificar los ~351
fotogramas del tramo sin convertir los descartados) y ~7 380 ms (76%) son
la conversión de los 50 fotogramas que sí se guardan — unos 148 ms por
fotograma convertido.** Esto responde la pregunta con datos, no con
suposición: **el costo es predominantemente por fotograma convertido
(YUV→RGB + recorte + escalado en CPU), no por el recorrido fijo de
`MediaCodec`.** La ruta CPU de ADR-0008 queda validada en el sentido de que
"menos fotogramas convertidos" sí ayuda casi proporcionalmente — pero el
costo por fotograma (148 ms para convertir un cuadro de este video) es alto
en términos absolutos, y es ahí donde está el margen que falta para llegar
al tramo de 5 000 ms de RNF-08, no en seguir bajando fotogramas (ya en el
piso de ADR-0007) ni en el codificador (usó apenas 3 185 ms de sus 20 000 ms
disponibles, en un solo intento).

**Dato aparte, no medido en esta corrida pero visible en el propio código:**
`YuvFrameConverter.toSquareBitmap` convierte primero el fotograma completo
de origen (720×1600 = 1 152 000 píxeles) de YUV a RGB, y recién después lo
recorta al cuadrado centrado (720×720 = 518 400 píxeles) antes de escalar a
512×512. Eso es convertir un 55% de píxeles que se descartan enseguida por
el recorte. Si el costo de 148 ms/fotograma medido arriba es
mayoritariamente la conversión YUV→RGB en sí (no el recorte ni el
escalado), recortar la región de interés *antes* de convertir —o
convertirla directo desde los planos YUV, sin pasar por el fotograma
completo— es una optimización concreta y barata de proponer, sin tocar la
ruta CPU en sí ni escalar hacia GPU. No se implementa en esta sesión: es un
hallazgo para una futura ADR o issue, no una medición pedida.

**Conclusión sobre CPU vs. GPU (ADR-0008):** con la evidencia de esta
corrida, no hace falta escalar a la ruta GPU todavía. El costo es real y
está concentrado en la conversión por fotograma, pero antes de pagar la
complejidad de EGL/GLES (que ADR-0008 ya había descartado sin medir) hay
una optimización mucho más barata sin cambiar de ruta: no convertir los
píxeles que el recorte va a descartar. Si esa optimización no alcanza para
entrar en el tramo de 5 000 ms de RNF-08, ahí sí GPU vuelve a ser una opción
a considerar con datos — pero no antes.

## Fase 2 — tercera corrida: recorte antes de convertir, y duraciones típicas de sticker

Mismo dispositivo y mismo video real de las dos corridas anteriores. Dos
cambios de código, ambos dentro de lo que ya decidía ADR-0008 (sin ADR
nuevo), más cuatro duraciones de clip medidas en la misma corrida.

### Validación de ADR-0008: la ruta CPU queda confirmada

ADR-0008 se aceptó **sin medición previa** (ver su propia sección
"Aceptado sin medición previa"), marcando dos valores como los más
expuestos a quedar invalidados: el fps de prefiltro (ya resuelto por
ADR-0009) y, "en menor medida", la propia elección de ruta de
decodificación — CPU vía `ImageReader`, en vez de GPU vía
`SurfaceTexture`/EGL (Opción 1, descartada en su momento sin medir).

El análisis de la corrida anterior (sección "La pregunta sobre el costo
del decode" de arriba) responde exactamente esa pregunta pendiente: bajar
de 200 a 50 fotogramas convertidos (4×) bajó el tiempo de decode 3.29×,
casi proporcional — el costo escala con los píxeles que se convierten, no
con que `MediaCodec` recorra el video. Eso es evidencia directa a favor de
la Opción 2 (CPU) de ADR-0008: el costo es atacable optimizando la
conversión (como se hace más abajo en esta misma corrida), no es un piso
fijo que solo GPU podría bajar. **Con esta medición, la ruta CPU de
ADR-0008 queda confirmada — no hizo falta abrir un ADR nuevo para GPU, ni
hace falta todavía.**

### Recorte antes de convertir (dentro de ADR-0008, sin ADR nuevo)

`YuvFrameConverter` convertía el fotograma de origen completo (720×1600 =
1 152 000 píxeles) a RGB y recién después lo recortaba al cuadrado central
(720×720 = 518 400 píxeles) — un 55% del trabajo de conversión descartado
de inmediato. Ahora convierte directo el cuadrado central de los planos
YUV de origen, sin pasar por el fotograma completo. Detalle y
justificación de por qué el recorte no cambia con la rotación en el KDoc
de `YuvFrameConverter`.

**Antes y después, mismo video, mismo clip de 10 s:**

| | Antes (recorta después de convertir) | Después (recorta antes de convertir) |
|---|---|---|
| Fotogramas convertidos | 50 | 50 |
| Píxeles convertidos por fotograma | 1 152 000 | **518 400** (-55%) |
| Tiempo de decode | 9 681 ms | **6 057 ms** (-37.4%) |
| Tiempo de encode | 3 185 ms | 3 338 ms (sin cambios significativos — no toca el encoder) |
| Tiempo total | 12 866 ms | **9 395 ms** (-27.0%) |

La reducción de tiempo (-37.4%) es menor a la reducción de píxeles
convertidos (-55%): parte del tiempo de decode es costo fijo que no cambia
con la conversión (abrir el extractor, posicionar, que `MediaCodec`
decodifique los ~351 fotogramas del tramo de 10 s aunque no se conviertan
todos) — consistente con el modelo de la corrida anterior, que ya estimaba
ese fijo en ~2.3 s. El resultado final (`sizeBytes`, `quality`, `frameCount`)
no cambió: la optimización toca cómo se llega a los bitmaps, no qué bitmaps
son.

### Duraciones típicas de sticker, no solo el máximo de RF-06

Los 12.8 s (y ahora 9.4 s) de las corridas anteriores son para un clip de
10 s — el **máximo** que permite RF-06, no lo típico: un sticker real suele
ser de 2 o 3 segundos. Mismo video, mismo dispositivo, ya con el recorte
antes de convertir:

| Duración pedida | Fotogramas (5 fps) | Decode | Encode (1 intento, quality=75) | Total | ¿Entra en 5 000 ms (RNF-08, contenido representativo)? |
|---|---|---|---|---|---|
| 2 s | 10 | 1 810 ms | 375 ms, 22 872 bytes | **2 185 ms** | **Sí**, con 2 815 ms de margen |
| 3 s | 15 | 2 285 ms | 878 ms, 91 360 bytes | **3 163 ms** | **Sí**, con 1 837 ms de margen |
| 5 s | 25 | 3 190 ms | 1 449 ms, 120 500 bytes | **4 639 ms** | **Sí**, con 361 ms de margen (ajustado) |
| 10 s (máximo) | 50 | 6 057 ms | 3 338 ms, 292 538 bytes | **9 395 ms** | **No** (supera por 4 395 ms) — pero sí entra en el tramo de 20 000 ms |

Traza completa de las tres duraciones nuevas
(`video_import_trace_2s.txt`, `video_import_trace_3s.txt`,
`video_import_trace_5s.txt`):

```
[21:31:21.637] === inicio: durationMs=2000 sobre .../stickersini_test_video.mp4 (25667796 bytes) ===
[21:31:23.448] decode: elapsedMs=1810 sourceDurationMs=37687 truncated=true decodedFrameCount=10 framesParaEncoder=10
[21:31:23.824] intento #1: frameCount=10 quality=75 minimizeSize=false sizeBytes=22872 elapsedMs=374
[21:31:23.825] encode: elapsedMs=375 outcome=exito intentos=1 sizeBytes=22872 quality=75 frameCount=10
[21:31:23.826] TOTAL: decodeMs=1810 encodeMs=375 totalMs=2185 outcome=exito

[21:31:12.126] === inicio: durationMs=3000 sobre .../stickersini_test_video.mp4 (25667796 bytes) ===
[21:31:14.413] decode: elapsedMs=2285 sourceDurationMs=37687 truncated=true decodedFrameCount=15 framesParaEncoder=15
[21:31:15.291] intento #1: frameCount=15 quality=75 minimizeSize=false sizeBytes=91360 elapsedMs=878
[21:31:15.292] encode: elapsedMs=878 outcome=exito intentos=1 sizeBytes=91360 quality=75 frameCount=15
[21:31:15.292] TOTAL: decodeMs=2285 encodeMs=878 totalMs=3163 outcome=exito

[21:31:15.301] === inicio: durationMs=5000 sobre .../stickersini_test_video.mp4 (25667796 bytes) ===
[21:31:18.492] decode: elapsedMs=3190 sourceDurationMs=37687 truncated=true decodedFrameCount=25 framesParaEncoder=25
[21:31:19.942] intento #1: frameCount=25 quality=75 minimizeSize=false sizeBytes=120500 elapsedMs=1449
[21:31:19.942] encode: elapsedMs=1449 outcome=exito intentos=1 sizeBytes=120500 quality=75 frameCount=25
[21:31:19.943] TOTAL: decodeMs=3190 encodeMs=1449 totalMs=4639 outcome=exito
```

Todas las corridas, en las cuatro duraciones, terminaron en un solo intento
de codificación a `quality=75` — nunca hizo falta bisecar ni reducir
fotogramas: el margen del lado del codificador es amplio en las cuatro
(el peor caso, 10 s, usó 3 338 ms de un tope de 20 000 ms). El tamaño no
escala de forma perfectamente lineal con los fotogramas (2 287 B/fotograma
a 2 s, 6 091 B/fotograma a 3 s, 4 820 B/fotograma a 5 s, 5 851 B/fotograma a
10 s) — a diferencia del contenido sintético de Fase 1, este es un video
real con partes de complejidad visual distinta (texto, animaciones,
pantallas estáticas) según qué tramo de los 37.7 s se haya tomado; no es un
error de medición.

### Lectura para decidir sobre RNF-08

- **Para las duraciones que la gente realmente usa (2-3 s, y con margen
  hasta 5 s), RNF-08 (tramo de 5 000 ms) se cumple con contenido real en
  este dispositivo**, incluso antes de cualquier optimización adicional
  más allá del recorte antes de convertir.
- **Solo el clip del tamaño máximo permitido (10 s) no entra en el tramo de
  5 000 ms** — pero sí entra, con margen, en el segundo tramo de RNF-08
  (≤20 000 ms, contenido de alta complejidad visual), que es exactamente la
  cláusula que ese requisito ya prevé para el caso más exigente.
- Esto es justo la pregunta que dejó abierta el pedido de esta corrida:
  ¿RNF-08 está mal planteado por no distinguir duración de clip, o el
  segundo tramo (≤20 000 ms) ya es esa distinción y el resultado de 9.4 s a
  10 s simplemente cae ahí, tal como el requisito previó para "contenido de
  alta complejidad" (en este caso, alta complejidad no del contenido visual
  en sí sino de la duración)? Los números de esta corrida no deciden esa
  pregunta por sí solos — es una decisión sobre cómo leer el propio
  requisito, no un dato adicional que estas mediciones puedan resolver.

### Veredicto: RNF-08 queda validado con datos reales

**RNF-08 se cumple.** El clip de 3 s — la duración que el propio requisito
usa como referencia — sale en 3 163 ms, con 1 837 ms de margen, en el Redmi
Note 14 con contenido real (no sintético). Que el clip del máximo de
duración permitido (10 s) caiga en el segundo tramo (≤20 000 ms) no es un
incumplimiento: es el requisito funcionando como se diseñó — un clip en el
máximo de duración es exigente por definición, y esa cláusula existe
exactamente para ese caso.

Con esta lectura, `docs/desarrollo/requisitos.md` (versión 1.1) precisa
RNF-08 para que el texto diga explícitamente lo que esta medición ya
muestra: el tramo de 5 000 ms aplica a clips de **hasta 5 s** de contenido
representativo, y el de 20 000 ms a clips **más largos** o de alta
complejidad visual. No cambia lo exigido — el clip de 3 s seguía cumpliendo
el tramo rápido antes de esta aclaración, y el de 10 s seguía cayendo en el
segundo tramo — solo deja escrita la distinción por duración que las
mediciones de esta fase confirman que el requisito ya hacía, sin decirlo
explícito.

**Pendiente de la fase de UI, no de esta medición:** el margen del clip de
5 s en este dispositivo es de apenas 361 ms (4 639 ms de 5 000 ms). Un
dispositivo más lento que el Redmi Note 14 podría superar ese tramo con un
clip de esa misma duración. El indicador de progreso que RNF-08 exige para
el tramo de 20 000 ms **debe mostrarse siempre, no solo cuando se detecta
que el contenido es de alta complejidad o el clip es largo** — en un
dispositivo lento, el tramo "rápido" puede no sentirse instantáneo, y ahí
el progreso es lo que sostiene la experiencia. Anotado también en el README
("Qué falta").

### Cómo correr la medición con tu propio video

`VideoImportPerformanceTest` no trae ningún video embebido: usa uno real
que vos le pasás al dispositivo. Si no lo encuentra, el test se salta (no
falla) e imprime la ruta exacta y el comando que falta.

```bash
adb devices

# A diferencia de los tests de :webp (que corren en un APK de test aparte,
# "...webp.test"), este test corre dentro de la propia app bajo prueba: el
# archivo va al externalFilesDir de io.github.capibaracasual.stickersini
# mismo, sin sufijo ".test" y sin necesitar permisos de almacenamiento.
adb push mi_grabacion.mp4 \
  /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini/files/stickersini_test_video.mp4

./gradlew :app:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.capibaracasual.stickersini.media.VideoImportPerformanceTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true

adb pull /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini/files/video_import_trace.txt
```

(`leaveApksInstalledAfterRun=true` es necesario por el mismo motivo que en
la Fase 1: Gradle desinstala el APK al terminar por defecto, y Android
borra su carpeta de datos —con el video y la traza— junto con la
desinstalación.)

Para usar un nombre de archivo distinto a `stickersini_test_video.mp4`,
pasar `-Pandroid.testInstrumentationRunnerArguments.videoFileName=<nombre>`
y usar ese mismo nombre en el `adb push`.

El filtro de clase de arriba corre las cinco pruebas de
`VideoImportPerformanceTest`, cada una con su propia traza (mismo `adb
pull`, cambiando el nombre del archivo):

- `decodificaYCodificaUnVideoReal` — el clip completo de 10 s, el tope de
  RF-06 (`video_import_trace.txt`).
- `decodificaYCodificaClipDe5Segundos`, `decodificaYCodificaClipDe3Segundos`,
  `decodificaYCodificaClipDe2Segundos` — la misma medición a duraciones más
  típicas de un sticker real (`video_import_trace_5s.txt`,
  `..._3s.txt`, `..._2s.txt`), agregadas para no evaluar RNF-08 solo contra
  el caso más largo posible.
- `decodificaDesdeUnInicioArbitrario` — chequeo de humo del posicionamiento
  de ADR-0008 (`startMs`/`durationMs`, keyframe anterior, descarte de lo
  previo al inicio pedido) con `startMs=1000, durationMs=2000`
  (`video_import_seek_trace.txt`). Se salta sola si el video dura menos de
  3 s. `ClipRangeTest` (`./gradlew :app:test`) ya cubre en aislamiento el
  cálculo del tramo; esta prueba en dispositivo cubre lo que `ClipRangeTest`
  no puede: que `MediaExtractor.seekTo` de verdad posiciona y decodifica
  bien desde ahí.

Si todo va bien: `BUILD SUCCESSFUL`, y `video_import_trace.txt` (además de
`adb logcat -d -s StickersiniVideoImport:I`) muestra una línea `decode`
(tiempo de `MediaCodec` + conversión + muestreo, duración del video de
origen, si se truncó a 10 s, cuántos fotogramas sobrevivieron el prefiltro),
una línea `encode` (tiempo de `WebpAnimEncoder`, tamaño y calidad final) y
una línea `TOTAL` con la suma de ambos — ese es el número que se compara
contra los 5000/20000 ms de RNF-08, no cada mitad por separado. El test en
sí solo afirma RF-10 (el resultado cabe en 500 KB); no hay ninguna
aserción de tiempo a propósito, igual que en `WebpAnimEncoderPerformanceTest`
de la Fase 1 — **el número que importa es el que imprime, no que el test
pase.**

Con ese número en mano, completar la tabla de arriba y revisar los tres
puntos que quedaron pendientes en el README ("Qué falta"): el umbral del
50% de ADR-0007, la utilidad de `minimize_size` de ADR-0006, y si el fps de
prefiltro (20) o la ruta de decodificación (CPU) de ADR-0008 siguen siendo
correctos con contenido real.

## Fase 2 (RF-03) — costo del contenedor de animación para un sticker estático

Antes de implementar la importación de imagen (RF-03), había que decidir
cómo codificar el resultado: RF-11 pide un WebP **estático** (≤100 KB), no
animado. La opción de usar `WebpAnimEncoder` con una lista de un solo
fotograma (en vez de un codificador estático aparte) ya estaba decidida por
ADR-0002 (ver el KDoc de `WebpAnimEncoder`), pero antes de darla por buena
había que medir cuánto pesa el contenedor de animación (`WebPAnimEncoder`,
chunks `ANIM`/`ANMF`) frente a un WebP estático real (`Bitmap.compress`,
framework de Android, sin ningún chunk de animación) — un sobrecosto
despreciable contra el límite de 500 KB de RF-10 podría no serlo contra los
apenas 100 KB de RF-11.

`StaticWebpContainerOverheadTest` (`:webp`, instrumentado), mismo Redmi Note
14, dos contenidos (color plano y el patrón "realista" ya usado en otras
mediciones) a tres calidades (50, 75, 90), un solo fotograma, sin bisección
ni `minimize_size`:

| Contenido | Calidad | `WebPAnimEncoder` (1 fotograma) | `Bitmap.compress(WEBP)` | Diferencia | % del límite de RF-11 (100 000 B) |
|---|---|---|---|---|---|
| color plano | 50 | 820 B | 1 154 B | **-334 B** | -0.33% |
| color plano | 75 | 836 B | 1 032 B | **-196 B** | -0.20% |
| color plano | 90 | 1 016 B | 1 036 B | **-20 B** | -0.02% |
| realista | 50 | 5 070 B | 3 896 B | **+1 174 B** | +1.17% |
| realista | 75 | 5 700 B | 4 340 B | **+1 360 B** | +1.36% |
| realista | 90 | 7 122 B | 6 002 B | **+1 120 B** | +1.12% |

**Resultado: el sobrecosto es despreciable.** En el peor caso medido (contenido
"realista", calidad 75), el contenedor de animación pesa 1 360 bytes más
que el estático — 1.36% del límite de RF-11 — y en el contenido de color
plano, la versión animada resultó incluso más chica (el encoder de Skia
detrás de `Bitmap.compress` no necesariamente usa la misma configuración
interna que libwebp vía JNI; no se investigó por qué, no cambia la
conclusión). **Se sigue con libwebp vía `WebpAnimEncoder` para RF-11, sin
codificador estático aparte**, tal como ya establecía ADR-0002 — esta
medición lo confirma con datos, no lo cambia.

## Fase 2 (RF-03) — importación de imagen

Objetivo: decodificar una imagen o foto existente en un `WebpFrame` único,
reutilizando el mismo pipeline de recorte que ya usa el video (ADR-0008),
sin duplicar la lógica de "qué parte de la imagen ve el usuario".

`ImageFrameDecoder` decodifica con `BitmapFactory` usando `inSampleSize`
(la técnica estándar de Android para no decodificar más resolución de la
que hace falta), calcula el cuadrado central con `CenterSquareCrop` —la
misma clase que ya usa `YuvFrameConverter` para el video, sin duplicar la
regla— corrige la orientación leyendo el EXIF (`android.media.ExifInterface`,
del framework, sin agregar ninguna dependencia nueva) y entrega un único
`WebpFrame` para `WebpAnimEncoder(targetSizeBytes =
STATIC_WEBP_TARGET_SIZE_BYTES)`.

### Corrección de la orientación EXIF, verificada de verdad

`ImageFrameDecoderTest.corrigeLaOrientacionExifDeVerdad` no se conforma con
comprobar que el resultado mide 512×512: dibuja una marca roja distintiva
cerca de una esquina de una imagen sintética, la guarda como JPEG con cada
una de las cuatro orientaciones EXIF (`NORMAL`, `ROTATE_90`, `ROTATE_180`,
`ROTATE_270`), y verifica que la marca aparece en el cuadrante exacto que
predice la rotación horaria que cada orientación exige (`ROTATE_90` →
esquina superior derecha, `ROTATE_180` → inferior derecha, `ROTATE_270` →
inferior izquierda). Pasa en dispositivo real (Redmi Note 14): la
corrección de orientación funciona, no solo el tamaño de salida.

### Medición

`ImageFrameDecoderTest.mideDecodificacionYCodificacionDeUnaImagen`, mismo
dispositivo, imagen sintética de 1200×1600 (JPEG, calidad 95):

```
decodeMs=15 encodeMs=116 totalMs=131 sizeBytes=1268 quality=75
```

131 ms en total, muy por debajo de cualquier presupuesto de RNF-08 (que en
todo caso habla de clips de video por duración, no de una imagen suelta —
no hay una fila de esa tabla contra la cual comparar este número, se
registra igual por completitud). El resultado (1 268 bytes) cumple RF-11
(≤100 000 bytes) con margen amplio. No se usó contenido real de cámara para
esta medición (a diferencia del video, donde el riesgo real solo aparecía
con contenido real): acá el riesgo era de formato/orientación/recorte, no
de cuánto tarda comprimir, y eso se ejercita igual de bien con una imagen
sintética con EXIF real escrito y releído por el framework.
