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

Estado al 2026-09-20: `./gradlew :webp:test` (21 tests, lógica de ajuste de
calidad y fotogramas, incluida la estrategia completa de ADR-0006 y el
piso de fotogramas de ADR-0007) y
`./gradlew :webp:externalNativeBuildDebug` / `:webp:assembleDebug` (compila
y enlaza contra libwebp para las cuatro ABI: arm64-v8a, armeabi-v7a, x86,
x86_64) pasan en la máquina de desarrollo.
Validado en dispositivo real el 2026-09-20 (Redmi Note 14, Android 14):
`WebpAnimEncoderInstrumentedTest` (correctitud de la codificación real vía
JNI) pasa entero. Con la estrategia de ADR-0006 y el piso de ADR-0007 ya
implementados, `WebpAnimEncoderPerformanceTest` **cumple RNF-08 en los dos
contenidos medidos**: contenido representativo, 1 226 ms (muy por debajo
de los 5 s); contenido adverso (el peor caso medido, no uno típico),
19 825 ms de un tope de 20 s — cumple, pero con solo 175 ms de margen (ver
detalle abajo). ADR-0006 y ADR-0007 están Aceptados. Lo que sigue
sin observar en este dispositivo es el comportamiento del tope duro ante
un caso que de verdad lo agote (ninguno de los contenidos probados llegó a
necesitarlo del todo — el adverso quedó muy cerca, no lo cruzó). El
historial completo de cómo se llegó hasta acá (la línea base sin arreglar,
las mediciones de `method`, el defecto de 1 fps y su arreglo) queda abajo,
fila por fila, sin editar ninguna de las ya escritas.

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
