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
`WebpAnimEncoderInstrumentedTest` (codificación real vía JNI) corrió una vez
sin completarse por una corrida de rendimiento aparte que se dejó sin
terminar (ver tabla de rendimiento más abajo); `WebpAnimEncoderPerformanceTest`
ya tiene el arreglo de `minimize_size`/`method` aplicado y límites de
tiempo, pendiente de correr en el teléfono para tener la medición real.
Pendiente de correr `./gradlew :webp:connectedAndroidTest` completo en un
teléfono real antes de dar la Fase 1 por probada.

| Fecha | Dispositivo | Android | Qué se probó | Resultado |
|---|---|---|---|---|
| `<fecha>` | `<marca y modelo>` | `<versión de Android / API>` | `./gradlew :webp:connectedAndroidTest` | `<pendiente>` |

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
| `<fecha>` | `<marca y modelo>` | `<versión de Android / API>` | `WebpAnimEncoderPerformanceTest.lineaBaseDeRendimiento_30fotogramas_contenidoAdverso` (algoritmo **ya arreglado**: `minimize_size` solo en la pasada final, `method` explícito, límites de 60 s/intento y 5 min/corrida) | `<pendiente: intentos, ms por intento, tiempo total, outcome, ¿cumple los 5 s de RNF-08?>` |

No hay ADR-0006 con alcance definitivo hasta que la segunda fila tenga
datos reales. La primera fila ya no es la pregunta abierta: confirmó que el
diseño sin arreglar no servía, sin necesidad de terminar la medición.

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
