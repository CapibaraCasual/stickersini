# ADR-0019: El fps de prefiltro sube a 20 y RF-06 baja a 5 s (reemplaza ADR-0018)

- Estado: Aceptado e implementado
- Fecha: 2026-09-28

## Contexto

Decisión de producto, siguiendo el modelo de Sticker.ly: priorizar la
fluidez de los stickers animados por encima de la duración del clip de
origen. Dos cambios pedidos juntos, porque uno habilita al otro:

1. **RF-06 baja de 10 s a 5 s, bloqueado en la UI** (no una sugerencia:
   `ui/TrimScreen.kt` usa `MAX_CLIP_DURATION_MS` para acotar el
   `RangeSlider`, no solo para truncar después de elegir).
2. **El fps de prefiltro sube al máximo que el nuevo tope de 5 s permita**
   sin sacrificar duración ni calidad.

Antes de fijar el fps, [ADR-0018](0018-fps-de-prefiltro-sube-a-10-sin-minimize-size.md)
había subido el valor a 10 sacando `minimize_size` de producción — pero
esa medición (como todas las anteriores desde ADR-0009) se hizo contra un
`.so` nativo de **debug**, sin saberlo. Antes de aceptar cualquier valor
nuevo, se revisó cómo compila `:webp` en cada build type.

## Hallazgo: todo lo medido hasta ahora estaba contra un `.so` sin optimizar

Inspeccionando `compile_commands.json` de cada build type (NDK
r28.2.13676358, CMake 3.22.1):

| | Debug | Release |
|---|---|---|
| Optimización | ninguna bandera `-O` (default de Clang, `-O0`) | `-O2 -DNDEBUG` |
| NEON | sí (arm64-v8a de base; armeabi-v7a con `-mfpu=neon`) | sí, igual |

NEON nunca fue el problema — ya estaba activo en los dos. La falta de
`-O2` en debug sí lo es: con el mismo contenido y `method=0` (ADR-0006),
la sola diferencia -O0→-O2 baja el costo de codificación 5.9×-6.6× (de
~2.4-3.5 s a ~0.2-0.5 s por intento). El decode casi no cambia (domina el
codec de hardware + JNI, no la parte CPU-bound de `:yuv`, que tiene el
mismo patrón de banderas).

**Esto invalida el techo medido por ADR-0018 (y probablemente por
ADR-0009/0012/0015): no estaban "mal" con los datos que tenían, midieron
en el ambiente equivocado** — mismo patrón que la corrección de método de
ADR-0015 (medir mal no es lo mismo que la app esté rota). Ver también
CLAUDE.md, "Errores conocidos".

## Medición, contra release (`assembleRelease`/`assembleReleaseAndroidTest`)

Mismo dispositivo de siempre (Xiaomi Redmi Note 14), mismo método de 5
corridas por caso, invocaciones separadas de `am instrument`, mediana y
rango, tramo de 5 s (el máximo de RF-06 ya bajado):

**Video real (grabación de pantalla, contenido de referencia de todo el proyecto):**

| fps | mediana [rango] ms | margen peor caso | tamaño | calidad | ¿acorta? |
|---|---|---|---|---|---|
| 10 | 1834 [1663–1845] | 63.1% | 194 246 B (38.8%) | 75 | No |
| 12 | 1653 [1618–1760] | 64.8% | 225 024 B (45.0%) | 75 | No |
| 15 | 1893 [1837–1920] | 61.6% | 263 382 B (52.7%) | 75 | No |
| **20** | 1868 [1757–**2246**] | 55.1% | 365 226 B (**73.0%**) | 75 | No |
| 24 | 2043 [2004–2096] | 58.1% | 431 878 B (**86.4%**) | 75 | No |

Los cinco valores cumplen tiempo con margen de sobra y calidad máxima
(75, sin bisección) sobre este contenido — la diferencia real entre ellos
es cuánto del límite de tamaño (RF-10, 500 KB) van dejando libre.

**Ruido puro (peor caso posible, mismo patrón que ADR-0006/0007/0016), tramo de 5 s:**

| fps | resultado | encode aislado |
|---|---|---|
| 15 | cae al piso fijo (15 fotogramas, quality=0, 348 300 B), acorta a 3 s | 3698 ms |
| 20 | cae al mismo piso, acorta a 3 s (347 492 B) | 4877 ms |
| 24 | cae al mismo piso, acorta a 3 s (348 840 B) | 5861 ms |

RF-12 se cumple en los tres: siempre hay un resultado válido. El tiempo
de encode aislado, sumado al decode típico (~1.3-1.4 s), queda por encima
del presupuesto estricto de 5 000 ms de RNF-08 en los tres casos — pero
RNF-08 permite explícitamente 20 000 ms para "contenido de alta
complejidad visual" sin importar la duración, y los tres quedan muy por
debajo de ese tope (peor caso ~7.3 s a 24 fps).

## Punto sin validar: contenido real de alto movimiento

Se pidió medir también un clip real de mucho movimiento (tipo TikTok).
Dos intentos fallaron por motivos distintos, documentados para que quede
explícito qué falta, no para que se asuma resuelto:

1. Un video generado por IA usado como sustituto resultó patológicamente
   adverso — incluso a 10 fps (el valor ya vigente) se reducía al piso de
   15 fotogramas para caber en 500 KB. No es representativo de un TikTok
   real (probablemente porque un video generado por IA cambia toda la
   imagen cuadro a cuadro, sin fondo estático que el codificador de
   diferencias pueda aprovechar) — se descartó como dato.
2. El clip real que motivó el pedido resultó ser un ítem de Google Fotos
   sin copia local accesible por `adb`, y no se pudo recuperar en esta
   ronda.

**Por eso se eligió 20 fps y no 24**, a pesar de que 24 también cumple
contra el contenido sí medido: a 24 fps el video real de referencia ya
ocupa 86.4% del límite de tamaño, contra 73.0% a 20 fps — con un tipo de
contenido real de alto movimiento todavía sin validar, 20 deja más
colchón. Queda pendiente repetir esta medición con ese contenido antes de
considerar subir a 24.

## Decisión

- **`VIDEO_PREFILTER_TARGET_FPS` sube de 10 a 20.**
- **`MAX_CLIP_DURATION_MS` (RF-06) baja de 10 000 a 5 000 ms**, bloqueado
  en `TrimScreen`.
- **La escalera de degradación de ADR-0016 no cambia de estructura.**
  `MIN_FRAME_COUNT_FLOOR` (15) y `DEGRADED_RESOLUTION` (384) no dependen
  de la duración del clip ni del fps de prefiltro. `MIN_DURATION_MS_BEFORE_LAST_RESORT`
  (3000) sigue siendo menor que el nuevo máximo (5000), así que "acortar
  a 3 s" sigue siendo un escalón real, confirmado con el caso de ruido
  puro arriba. `HARD_TIME_LIMIT_MS` (20000) se mantiene: RNF-08 sigue
  permitiendo ese presupuesto para contenido de alta complejidad
  independientemente de la duración, y el peor caso medido lo usa con
  margen de sobra (~7.3 s de 20 s).

## Lección de método (sumada a CLAUDE.md)

Medir un presupuesto de tiempo (RNF-08 o cualquier otro) contra un `.so`
nativo de **debug** da un techo mucho más bajo que el real: la sola
diferencia de optimización (`-O0` vs `-O2`) cambió el resultado de esta
decisión en 5.9×-6.6×. Toda medición futura que decida un valor contra un
presupuesto de RNF-08 debe compilarse y correrse con `assembleRelease`
(o el `androidTest` equivalente, vía `testBuildType`), no con el build de
debug por defecto.

## Cómo instalar el build de release para probar en un teléfono

`release` ahora firma con la key de debug (sin keystore de producción
todavía — ver README, `applicationId` pendiente), así que se instala
igual que cualquier build de desarrollo:

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

No usar esta firma para publicar — es un atajo para poder probar el
binario optimizado (`-O2`, `RelWithDebInfo`) fuera de este equipo, no una
firma de distribución.

## Consecuencias

- `FrameSampler.kt` (`VIDEO_PREFILTER_TARGET_FPS` y `MAX_CLIP_DURATION_MS`)
  actualizado, con el KDoc citando esta Decisión.
- `docs/desarrollo/requisitos.md` (RF-06) actualizado a 5 s.
- `ClipRangeTest` tenía tres casos con la duración de 10 s escrita a mano
  en la aserción (no solo vía el símbolo `MAX_CLIP_DURATION_MS`):
  corregidos a 5 s.
- `app/build.gradle.kts`: `buildTypes.release` firma con
  `signingConfigs.debug` de forma permanente (antes no tenía firma
  propia, así que `assembleRelease` producía un APK sin firmar,
  instalable solo tras firmarlo a mano).
- Pendiente explícito, no cerrado por este ADR: validar con un clip real
  de alto movimiento (tipo TikTok) antes de considerar subir de 20 a 24 —
  ver "Punto sin validar" arriba.
- No cambia nada de ADR-0006 (`method=0`, ya sin `minimize_size` desde
  ADR-0018), ADR-0007/ADR-0016 (piso de fotogramas y escalera), ADR-0011
  (conversión YUV→RGB nativa) ni ADR-0015 (decode paralelo): esta
  Decisión reemplaza el valor de fps de ADR-0018 y el máximo de RF-06,
  nada más.
