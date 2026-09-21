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

Estado al 2026-09-20: `./gradlew :webp:test` (19 tests, lógica de ajuste de
calidad y fotogramas, incluidos los 2 que verifican el patrón
`minimizeSize` false-durante-búsqueda/true-en-la-final) y
`./gradlew :webp:externalNativeBuildDebug` / `:webp:assembleDebug` (compila
y enlaza contra libwebp para las cuatro ABI: arm64-v8a, armeabi-v7a, x86,
x86_64) pasan en la máquina de desarrollo.
Validado en dispositivo real el 2026-09-20 (Redmi Note 14, Android 14):
`WebpAnimEncoderInstrumentedTest` (correctitud de la codificación real vía
JNI) pasa entero. `WebpAnimEncoderPerformanceTest`, con el arreglo de
`minimize_size`/`method` ya aplicado, corrió sin colgarse (los límites de
tiempo funcionaron) pero **no cumple RNF-08**: cortó a los 5 minutos sin
producir un resultado, y las dos codificaciones que sí se vieron completas
tardaron ~13 s cada una — más del doble del presupuesto de 5 s (ver tabla
de rendimiento más abajo). ADR-0006 sigue sin implementarse: antes hacen
falta las tres mediciones adicionales de la sección "Antes de escribir
ADR-0006" (traza persistente, `method` confirmado, benchmark sin búsqueda
por `method` y tipo de contenido), pendientes de correr en el teléfono.

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

Este comando corre las tres clases de test instrumentado del módulo:
`WebpAnimEncoderInstrumentedTest` (correctitud), `WebpAnimEncoderPerformanceTest`
(línea base de rendimiento) y cualquier otra que se añada después.

Si todo va bien: `BUILD SUCCESSFUL`, y en
`webp/build/reports/androidTests/connected/debug/index.html` los 3 tests de
`WebpAnimEncoderInstrumentedTest` en verde
(`codificaTresFotogramasDeColorPlanoYCumpleRF10`,
`codificaContenidoRuidosoBajandoCalidadHastaCumplirRF10`,
`rechazaBitmapsDeTamanoDistinto`), más
`WebpAnimEncoderPerformanceTest.lineaBaseDeRendimiento_30fotogramas_contenidoAdverso`
también en verde. Esta última ya no puede colgarse indefinidamente: tiene
límite de 60 s por intento individual y 5 min para toda la corrida — si algo
se descontrola, el test termina solo, marca `outcome` como timeout y
reporta los intentos que sí alcanzó a medir, en vez de quedarse corriendo
hasta que alguien la mate a mano (como pasó en la corrida anterior, ver la
tabla arriba). Que el test pase en verde no significa que el tiempo esté
bien: no hay todavía ninguna aserción contra el presupuesto de RNF-08,
a propósito. **El número que importa no es que pase, es lo que imprime.**
Para leerlo sin bucear en el reporte HTML:

```bash
adb logcat -d -s StickersiniPerfBaseline:I
```

Debería mostrar una línea por intento de codificación
(`intento #N: quality=... minimizeSize=... sizeBytes=... elapsedMs=...`) y
una línea `TOTAL` con el número de intentos, el tiempo total en ms, el
`outcome` (`exito`, `timeout_corrida_completa`, o `excepcion: ...`), y la
calidad/tamaño/número de fotogramas del resultado final si lo hubo. Ese
`tiempoTotalMs` es el que se compara contra los 5000 ms de RNF-08. A
diferencia de la corrida anterior, esta versión ya tiene aplicado el
arreglo de `minimize_size`/`method` (ver el commit `fix(webp):` más
reciente): la búsqueda usa `minimizeSize=false` en cada intento y solo la
última pasada usa `minimizeSize=true`.

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
./gradlew :webp:connectedAndroidTest --tests "*.WebpEncodeMethodBenchmarkTest"
adb logcat -d -s StickersiniMethodBenchmark:I
adb pull /storage/emulated/0/Android/data/io.github.capibaracasual.stickersini.webp.test/files/webp_benchmark_trace.txt
```

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
