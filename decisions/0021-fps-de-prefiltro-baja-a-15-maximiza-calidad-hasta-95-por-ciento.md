# ADR-0021: El fps de prefiltro baja de 20 a 15 (reemplaza ADR-0019 solo en ese valor) y se agrega el escalón de maximizar calidad hasta ~95% de RF-10

- Estado: Aceptado e implementado
- Fecha: 2026-09-30

## Contexto

Decisión de producto, siguiente a [ADR-0020](0020-escalera-de-degradacion-calidad-resolucion-primero-fps-y-duracion-ultimo-recurso.md)
(fps y duración tienen prioridad sobre calidad y resolución: solo ceden
como último recurso). Con esa prioridad ya fijada, el fps de prefiltro de
20 (ADR-0019) deja poco margen de tamaño para lo que calidad+resolución
pueden hacer sobre el fotograma completo: el video de referencia ya usaba
73.0% del límite de RF-10 a calidad máxima (75, medido en ADR-0019). Bajar
el fps a 15 libera ese margen — 52.7% con el mismo contenido, ya medido en
ADR-0019 — sin tocar RF-06 (5 s) ni la prioridad de ADR-0020.

Con ese margen liberado, decisión de producto adicional: no conformarse
con la primera calidad que entra en el límite (lo que ADR-0006 llamaba
"mínimo trabajo necesario") — usar el margen disponible para subir la
calidad hasta ~95% de RF-10, dando el mejor resultado visual que el
tamaño disponible permita.

## Decisión

1. **`VIDEO_PREFILTER_TARGET_FPS` baja de 20 a 15.** RF-06 (5 s) y la
   prioridad de fps/duración de ADR-0020 no cambian.
2. **Nuevo escalón, dentro de los escalones 1-2 de ADR-0020 (calidad y
   resolución): una vez que una calidad cabe, se sigue bisecando hacia
   arriba hasta que el resultado use al menos el 95% del límite de
   tamaño** (`TARGET_UPPER_OCCUPANCY_FRACTION = 0.95`), no solo hasta que
   quepa. Reemplaza el criterio anterior (`UPWARD_SEARCH_MAX_OCCUPANCY_FRACTION
   = 0.5`, de ADR-0016/ADR-0020): aquel paraba de buscar apenas se
   superaba el 50% de ocupación, un criterio conservador de tiempo que
   dejaba calidad sin usar en contenido liviano.

## Medición (release, 5 corridas separadas, `am instrument`)

**Video real de referencia**, mismo tramo de siempre (5 s, sin zoom):

| | Antes (20 fps) | Después (15 fps + escalón de calidad) |
|---|---|---|
| Fotogramas | 99 | 74 |
| Calidad | 75 | **93** |
| Tamaño | 122 420 B (24.5%) | **482 478 B (96.5%)** |
| Peor tiempo de 5 corridas | ~1 900 ms | 4 505 ms (77.5% de margen contra 20 000 ms) |

Determinístico en las 5 corridas después del cambio. El tiempo sube
porque ahora se bisecta hacia arriba en vez de parar en la primera
calidad que entra, pero queda muy por debajo de RNF-08.

**Contenido adverso, sin pedirlo explícitamente pero necesario antes de
aceptar un cambio de fps** (CLAUDE.md: un parámetro que cambia el volumen
de trabajo se revisa contra el peor caso, no solo el caso de referencia):
alto movimiento (el clip adverso de IA de ADR-0019/ADR-0020) ahora entra
en el fotograma completo sin necesitar el piso de fps ni acortar duración
(antes sí hacía falta) — **mejora, no regresión**. Ruido puro con zoom
(peor caso sintético) sigue necesitando el piso de fps, con resultado
prácticamente igual y más rápido. Detalle completo, con todas las tablas,
en `docs/desarrollo/pruebas.md`.

Sticker del video real generado antes/después para comparación visual
directa (empujado a `/sdcard/Download/` del dispositivo de prueba):
`sticker_video_real_ANTES_ADR0019.webp` (122 420 B, calidad 75) vs.
`sticker_video_real_DESPUES_ADR0021.webp` (482 478 B, calidad 93).

## Consecuencias

- `FrameSampler.kt`: `VIDEO_PREFILTER_TARGET_FPS` 20→15, KDoc actualizado.
- `WebpAnimEncoder.kt`: `UPWARD_SEARCH_MAX_OCCUPANCY_FRACTION` (0.5) se
  reemplaza por `TARGET_UPPER_OCCUPANCY_FRACTION` (0.95). `bisectQuality`
  se unifica en una sola función parametrizada por la calidad de la
  primera sonda (`FIRST_QUALITY` en el escalón de 512, `MIN_QUALITY` en
  el de 384) — mismo comportamiento de salto al piso de ADR-0020 cuando la
  sonda alta falla, ahora además sigue subiendo tras encontrar un ajuste
  hasta cruzar el 95% de ocupación, en vez de solo hasta el escalón de
  384.
- `MIN_FPS_FLOOR` (12) y `MIN_DURATION_MS_BEFORE_LAST_RESORT` (3000) no
  cambian: siguen siendo el último y último-último recurso, ahora
  activándose con menos frecuencia (el caso de alto movimiento ya no los
  necesita).
- `WebpAnimEncoderTest.kt`: 2 de 22 tests reescritos para reflejar el
  escalón nuevo (dejan de asumir que la búsqueda para en la primera
  calidad que entra). Sigue verde.
- No cambia RF-06, RF-10, RF-11, RF-12 ni RF-13.
- Todas las mediciones son de un solo dispositivo (Xiaomi Redmi Note 14)
  — sigue pendiente la segunda fila de hardware del README.
- **Sigue pendiente, no cerrado por este ADR:** validar con un clip real
  de alto movimiento (tipo TikTok), no el generado por IA — mismo
  pendiente que dejaron ADR-0019 y ADR-0020.
