# ADR-0011: Mover la conversión YUV→RGB a un módulo nativo nuevo, `:yuv`

- Estado: Aceptado e implementado — confirmado con medición (ver
  "Medición de confirmación" al final)
- Fecha: 2026-09-25

## Contexto

ADR-0008 aceptó decodificar video con `ImageReader` en `YUV_420_888` y
convertir a `Bitmap` `ARGB_8888` en CPU (Kotlin puro,
`YuvFrameConverter.yuv420CenterSquareToArgb`), dejando escrito
explícitamente: *"si la medición en dispositivo real muestra que la
conversión en CPU se come el margen de RNF-08, se abre un ADR nuevo"*. Esta
es esa medición.

Motivo inmediato: al explorar si subir el fps de prefiltro de video (5→7→10,
ver `docs/desarrollo/pruebas.md`, sección "remedición con el método de 5
corridas") se encontró que ningún valor fijo de fps sostiene con
confiabilidad el tramo de 5 000 ms de RNF-08 para todo el rango de clips que
debería cubrir. Subir el fps no es viable mientras el costo de decodificar
cada fotograma no baje — y una parte de ese costo (la conversión) nunca se
había medido por separado del resto (extraer, decodificar, esperar
`ImageReader`).

## Medición

`VideoFrameDecoder` instrumentado para acumular, sobre los fotogramas
conservados, cuánto tiempo se va en `acquireImageWithRetry` (esperar el
buffer del `ImageReader`) y cuánto en `YuvFrameConverter.toSquareBitmap` (la
conversión en sí, incluyendo el escalado/rotación que hace falta).
`VideoImportPerformanceTest` expone ambos en la traza. Mismo dispositivo y
video real de siempre (Xiaomi Redmi Note 14 `24117RN76L`, Android 14,
`Recording_20260919_191641.mp4`), fps de prefiltro sin tocar (5, el
actual), 5 corridas por duración, mediana y rango — método fijado en
`docs/desarrollo/pruebas.md`:

| Clip | Fotogramas | Decode (mediana) | Conversión (mediana) | % del decode | Espera de `ImageReader` |
|---|---|---|---|---|---|
| 3 s | 15 | 2 194 ms [2 028–2 473] | 1 187 ms [1 137–1 222] | **54.1%** | ~0 ms |
| 5 s | 25 | 3 342 ms [3 188–3 644] | 1 962 ms [1 863–2 034] | **58.7%** | ~0 ms |
| 10 s | 50 | 5 816 ms [5 711–5 913] | 3 684 ms [3 672–3 736] | **63.3%** | ~0 ms |

Dos hallazgos:

1. **La conversión es la mayoría del tiempo de decode, y esa mayoría crece
   con la duración del clip** (54% → 59% → 63%) — es la palanca correcta:
   el "resto" (extraer muestras del contenedor, encolar/desencolar en
   `MediaCodec`) es una fracción menor y decreciente, no el cuello de
   botella.
2. **Esperar el buffer del `ImageReader` no cuesta nada medible** (0-1 ms
   acumulados sobre 15-50 fotogramas). La hipótesis de que ahí se
   perdía tiempo queda descartada con este dato — no hace falta tocar esa
   parte.

La conversión actual (`YuvFrameConverter.yuv420CenterSquareToArgb`) es un
bucle Kotlin, píxel a píxel, con acceso a `ByteBuffer.get()` por canal (Y, U,
V) y aritmética entera — sin verificación de límites evitable, sin
vectorización SIMD del compilador, y con el overhead propio de `Buffer.get`
en cada iteración. Un equivalente en C, compilado por el NDK (el proyecto ya
tiene esa infraestructura funcionando para las 4 ABI, ver ADR-0002/ADR-0005),
opera sobre punteros directos sin esas capas — es razonable esperar una
mejora de varias veces, no un porcentaje marginal, para este tipo de bucle.

## Decisión

**Mover `yuv420CenterSquareToArgb` a C, vía JNI, reemplazando el bucle
Kotlin de `YuvFrameConverter`.** No a GPU/EGL: ADR-0008 ya había dejado esa
ruta como alternativa si CPU no alcanzaba, pero es la opción de más
complejidad y riesgo (contexto EGL, shaders, ciclo de vida, comportamiento
históricamente inconsistente entre fabricantes fuera de una `Activity`
visible — la razón por la que ADR-0008 la descartó sin medir en primer
lugar). El mismo criterio de este proyecto (ADR-0006, ADR-0008: no pagar
complejidad que nadie midió que haga falta) aplica acá: no hay medición que
diga que CPU nativa no alcanza, así que GPU sigue sin ser necesaria. Nativo
en C resuelve el cuello de botella medido (el overhead de la JVM sobre este
bucle) sin la complejidad de EGL.

## Consecuencias

- La firma pública de `YuvFrameConverter.toSquareBitmap` no cambia: sigue
  recibiendo un `Image` y devolviendo un `Bitmap`. Solo cambia la
  implementación interna del bucle de conversión.
- Los tests existentes (`ImageFrameDecoderTest`, la parte de
  `VideoImportPerformanceTest` que verifica el resultado) siguen siendo la
  forma de validar que el resultado no cambia de valor, solo de velocidad —
  ninguno depende de que la conversión sea Kotlin puro.
- Hace falta re-medir después de implementar (mismo método de 5 corridas):
  esta Decisión se acepta con una estimación de mejora ("varias veces", no
  un porcentaje), no con el resultado final. Solo con ese dato en mano vuelve
  a ser razonable discutir el fps de prefiltro — no antes.
- La instrumentación de `acquireImageMs`/`conversionMs` que hizo esta
  medición se deja en `VideoFrameDecoder`/`VideoImportResult`: es barata (un
  par de `System.nanoTime()` por fotograma) y esta no va a ser la última vez
  que haga falta saber dónde se va el tiempo del decode.

## Dónde vive el código nativo: módulo nuevo `:yuv`, no `:webp` ni `:app`

Dos opciones se habían considerado en la versión anterior de este ADR
(reusar el CMake de `:webp`, o meter `externalNativeBuild` dentro de
`:app`) — ambas rechazadas: la primera desvirtúa `:webp`, que debe seguir
siendo solo codificación WebP; la segunda mete configuración de NDK dentro
de `:app`, que es donde vive la UI, no infraestructura nativa.

**Decisión: un módulo nuevo, `:yuv`, con su propio CMake para las cuatro
ABI (mismo patrón que `:webp`, ver `yuv/build.gradle.kts` y
`yuv/src/main/cpp/CMakeLists.txt`), que expone la conversión YUV→RGB con
recorte al cuadrado central.** `:app` depende de `:yuv` igual que ya
depende de `:webp` — cada módulo mantiene una sola responsabilidad:

- `:webp` — codificación WebP (sin cambios).
- `:yuv` — conversión de color y recorte YUV→RGB (nuevo).
- `:app` — UI y orquestación; ya no tiene ni necesita código nativo propio.

`NativeYuvConverter.convert` (público, `:yuv`) recibe los tres planos YUV
como `ByteBuffer` directos con sus strides, más el recorte (`xOffset`,
`yOffset`, `size`), y devuelve un `Bitmap` `ARGB_8888` ya recortado —
misma forma de entrada/salida que tenía `YuvFrameConverter` en Kotlin, así
que `:app` solo cambió una llamada, no su contrato.

### Validación de correctitud: paridad píxel a píxel

La implementación en Kotlin (`YuvFrameConverter.yuv420CenterSquareToArgbKotlinReference`)
se conserva a propósito, ya no en la ruta de producción, como referencia:
un error de conversión de color no rompe ningún test de tamaño ni lanza
ninguna excepción — solo se nota mirando un sticker con los colores mal.
`YuvConversionParityTest` (`app/src/androidTest`) corre ambas
implementaciones sobre los mismos buffers YUV sintéticos (con relleno de
stride y `pixelStride=2` semiplanar, no el caso trivial de planos
empaquetados) y compara los `Bitmap` resultantes píxel a píxel
(`Bitmap.getPixels` + `assertArrayEquals`, no una tolerancia). Pasa en
dispositivo real: la salida nativa es exactamente igual a la de Kotlin,
no solo "parecida".

## Medición de confirmación

Mismo dispositivo y video real de siempre, 5 fps (sin tocar), método de 5
corridas por caso (mediana y rango, `docs/desarrollo/pruebas.md`):

| Clip | Conversión antes (Kotlin) | Conversión después (nativa) | Mejora | Total antes | Total después | Mejora |
|---|---|---|---|---|---|---|
| 3 s | 1 187 ms | 413 ms | **2.87×** | 3 370 ms | 2 795 ms | **17.1%** |
| 5 s | 1 962 ms | 559 ms | **3.51×** | 4 923 ms | 3 321 ms | **32.5%** |
| 10 s (máx.) | 3 684 ms | 1 227 ms | **3.00×** | 9 407 ms | 6 706 ms | **28.7%** |

Confirma la estimación de esta Decisión ("varias veces, no un porcentaje
marginal"). **Efecto colateral no buscado a propósito: el tramo de 5 s de
RNF-08, frágil a 5 fps con la conversión en Kotlin (4 563–5 126 ms, cruzaba
la línea de 5 000 ms sin margen confiable — ver
`docs/desarrollo/pruebas.md`), ahora cumple con margen real (3 205–3 479
ms, 30.4% de margen en el peor caso)** — arreglar el cuello de botella real
(convertir cada fotograma) resolvió también el síntoma que había disparado
la pregunta original de subir el fps, sin necesitar tocarlo.

Un hallazgo de método aparte, no de este ADR en sí: la primera corrida de
esta medición se contaminó porque el dispositivo entró en modo Doze a
mitad de la corrida (pantalla apagada) — quedó registrado en
`docs/desarrollo/pruebas.md` como parte del método de medición de tiempo,
no se repite acá.

Esta Decisión queda **Aceptada e implementada**. La pregunta de si subir el
fps de prefiltro vuelve a tener sentido explorar, con margen real de sobra
en las tres duraciones medidas — pero sin medir todavía a un fps más alto
con esta conversión nueva: queda para un ADR aparte, con ese dato en mano.
