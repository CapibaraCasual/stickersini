# CLAUDE.md

Contexto permanente del proyecto. Léelo antes de cualquier tarea y respétalo
aunque la instrucción puntual no lo repita.

---

## Qué es este proyecto

Aplicación Android que convierte contenido visual del propio dispositivo
(captura de pantalla en vivo, video existente, imagen, foto de cámara) en
stickers de WhatsApp, animados o estáticos. Todo el procesamiento ocurre en el
dispositivo.

Es un proyecto de código abierto y gratuito, mantenido por una sola persona, con
doble objetivo: ser un producto usable y ser una pieza de portafolio técnico.

Los requisitos numerados están en `docs/desarrollo/requisitos.md`. Cíta sus IDs
(RF-xx, RNF-xx) cuando implementes algo que los cumpla.

---

## Estado actual (retomar acá)

Versión: `v0.11.0-alpha` (2026-09-28). Detalle completo en README,
sección "Estado" y "Qué falta" — esto es solo el punteo para orientarse
sin abrirlo primero. En orden:

1. **Los seis stickers semilla definitivos** — bloquea la publicación
   (ADR-0004 los hace permanentes).
2. **Validar 20 fps (ADR-0019) contra un clip real de alto movimiento**
   (tipo TikTok) antes de pensar en subir a 24 — dos intentos previos
   fallaron, ver README.
3. **Antes de publicar: firmar `release` con una key propia**, no la de
   debug (ADR-0019 la dejó así a propósito, para medir el binario
   optimizado sin keystore de producción).
4. Reproducir el video en `TrimScreen`.
5. Rotar el contenido en `CropScreen`.
6. Cola de varios archivos (RF-25).
7. Lint pendiente: `NonObservableLocale` en `StickerScreenChrome.kt:193`.
8. Test intermitente en `:webp`, sin diagnosticar.
9. Investigar cómo Sticker.ly permite packs de 1 sticker cuando WhatsApp
   exige un mínimo de 3 (RF-16) — ¿relleno automático por detrás?
10. Segunda fila de dispositivo en `docs/desarrollo/pruebas.md` (todo lo
    medido hasta ahora es de un solo Xiaomi Redmi Note 14).

---

## Restricciones innegociables

Estas no se discuten en una tarea suelta. Si una instrucción las contradice,
detente y dilo antes de implementar.

1. **Sin red.** La aplicación no declara `INTERNET` en el manifiesto. No añadas
   ninguna dependencia que requiera conectividad (RNF-01).
2. **Sin anuncios ni telemetría.** Nada de AdMob, Firebase Analytics, Crashlytics
   ni SDK equivalentes (RNF-02).
3. **Sin cuenta de usuario.** No hay login, no hay perfil, no hay nube.
4. **GPL-3.0.** Toda dependencia nueva debe ser compatible. Si no estás seguro
   de la licencia de una biblioteca, pregunta antes de añadirla (RNF-10).
5. **Límites duros de WhatsApp.** 512×512 exactos; animado ≤500 KB; estático
   ≤100 KB; fotograma ≥8 ms; animación ≤10 s; pack de 3 a 30 stickers; ícono de
   bandeja 96×96. Estos números no son objetivos aproximados, son validación.

---

## Stack fijado

Decidido y documentado. No lo cambies sin escribir un ADR nuevo.

| Pieza | Elección | ADR |
|---|---|---|
| Lenguaje y UI | Kotlin + Jetpack Compose, nativo | ADR-0001 |
| Codificación WebP | libwebp (`WebPAnimEncoder`) vía JNI/NDK | ADR-0002 |
| Decodificación de video | `MediaCodec` del framework | ADR-0002 |
| Licencia | GPL-3.0 | ADR-0003 |
| Entrega a WhatsApp | `ContentProvider` WAStickerApps + pack semilla | ADR-0004 |
| Conversión YUV→RGB | C vía JNI/NDK, módulo `:yuv` propio (no `:webp` ni `:app`) | ADR-0011 |

**No uses FFmpeg.** Está descartado por peso y licencia (ADR-0002).

Otras elecciones razonables mientras no haya ADR en contra: Hilt o inyección
manual para dependencias, Coroutines + Flow para concurrencia, `targetSdk` 36
y `minSdk` 26 (RNF-04, RNF-05). Para persistencia de packs de usuario, no
Room: ver ADR-0010 (JSON + archivos en almacenamiento interno, misma forma
que ya usa el pack semilla).

---

## Estructura del proyecto

```
stickersini/
├── app/                    módulo principal
│   └── src/main/java/<paquete>/
│       ├── ui/             pantallas Compose, navegación, tema
│       ├── capture/        MediaProjection, servicio en primer plano, burbuja
│       ├── media/          MediaCodec, extracción de fotogramas
│       ├── stickers/       modelo de dominio, packs, persistencia
│       └── provider/       StickerContentProvider (contrato WAStickerApps)
│
├── webp/                   módulo NDK: libwebp + capa JNI (CMake)
├── yuv/                    módulo NDK: conversión YUV→RGB + recorte (CMake, ADR-0011)
│
├── docs/
│   ├── usuario/            guías para quien usa la app
│   └── desarrollo/         requisitos, arquitectura, instalación, pruebas
│
└── decisions/              ADR
```

`applicationId`: pendiente de definir. Usa `io.github.USUARIO.stickersini` como
marcador y avisa cuando toque fijarlo.

---

## Reglas de trabajo

**Commits.** Pequeños y enfocados. Uno por cambio lógico. Mensaje descriptivo
en español. Si hay un issue asociado, enlázalo (`Closes #12`). No agrupes un
arreglo de bug con una refactorización.

**ADR.** Si durante una tarea tomas una decisión estructural, difícil de
revertir, o que alguien podría cuestionar después, propón un ADR nuevo en
`decisions/` con la numeración correlativa siguiente. Escríbelo el mismo día.
Nunca edites un ADR ya aceptado: si cambia la decisión, se escribe uno nuevo que
lo reemplaza y se enlazan los dos.

**CHANGELOG.** Toda función visible al usuario, corrección o cambio de
comportamiento va a `[Sin publicar]` en `CHANGELOG.md`.

**Documentación.** Documenta fronteras, no líneas. Qué módulos existen y cómo
fluye un dato de punta a punta va en `docs/desarrollo/arquitectura.md`. Las
firmas y el comportamiento de un método van en KDoc dentro del código, nunca en
un `.md`.

**Pruebas.** Tests unitarios para la lógica de packs, validación de límites y
cálculo de parámetros del encoder. Tests instrumentados para el
`ContentProvider`. No pidas tests de UI exhaustivos: no valen el mantenimiento
en este proyecto.

**Mediciones de rendimiento.** Toda decisión sobre un parámetro que afecta
tiempo, tamaño o cantidad de trabajo (fps, calidad, número de fotogramas,
tiempos límite) se toma después de medir en dispositivo real, nunca antes —
un valor que "suena razonable" puede estar fuera de cualquier escala ya
probada (ver "Errores conocidos" más abajo). El registro de esas mediciones
vive en `docs/desarrollo/pruebas.md`: ahí van las trazas completas, las
tablas de antes-y-después y el razonamiento numérico. Un ADR cita el
resultado medido, no lo reemplaza ni lo repite completo.

**Método: 5 corridas por caso, invocaciones separadas de `am instrument`,
el peor caso decide (no la mediana).** Un número que se va a documentar
como "cumple" o "no cumple" un presupuesto (RNF-08 y similares) se mide
así, no con un bucle de varias corridas dentro del mismo proceso: eso
falsea el resultado (ver "Errores conocidos"). Un valor ya medido y fijado
(como `VIDEO_PREFILTER_TARGET_FPS`) no se toca —ni para subirlo ni para
"confirmarlo"— sin repetir esta misma medición; no alcanza con razonar
que debería seguir cumpliendo.

---

## Cómo compilar

```bash
./gradlew assembleDebug          # compilar
./gradlew test                   # tests unitarios
./gradlew connectedAndroidTest   # tests instrumentados (requiere dispositivo)
./gradlew lint                   # análisis estático
```

---

## Errores conocidos que no debes repetir

- **No intentes enviar stickers con `ACTION_SEND`.** No funciona: llegan como
  imagen normal. La única vía es el `ContentProvider` (ADR-0004).
- **No caches el token de `MediaProjection`.** Solo sirve para una sesión y
  requiere consentimiento del usuario cada vez.
- **No llames a `getMediaProjection()` desde el servicio** en Android 15+.
  Requiere una Activity visible.
- **No intentes eludir `FLAG_SECURE`** de otras aplicaciones (RNF-03). Es motivo
  de rechazo en Google Play.
- **No mezcles stickers animados y estáticos** en un mismo pack (RF-18).
- **No elijas un parámetro que multiplica el volumen de trabajo (fps de
  muestreo, número de fotogramas, etc.) sin calcular qué significa en el
  caso límite real** (el tope de duración de RF-06, el peor contenido)
  contra la escala que ya se midió. El prefiltro de fotogramas de la
  importación de video se fijó en 20 fps por sonar razonable; sobre el tope
  de 10 s de RF-06 son 200 fotogramas, 6.7× lo único medido hasta entonces
  (30, en las pruebas de `:webp`), y agotó el codificador sin encontrar
  ningún resultado válido (ADR-0009, `docs/desarrollo/pruebas.md`).
- **Al recortar o reducir contenido visual (video, imagen), hacelo antes de
  la operación cara (conversión de color, decodificación a resolución
  completa), no después.** Medido dos veces con la misma ganancia: recortar
  al cuadrado central antes de convertir YUV→RGB bajó el tiempo de
  decodificación de video un 37%; decodificar una imagen con `inSampleSize`
  antes de recortar evita decodificar resolución que se va a descartar.
- **`internal` en un módulo Gradle no es visible desde otro módulo aunque
  dependa de él con `implementation()`.** Para instrumentar o medir algo de
  `:webp` desde un test de `:app` (o viceversa), exponé una referencia
  pública puntual (ver `ProductionWebpEncoder` en `NativeWebpEncoder.kt`) en
  vez de bajarle la visibilidad a la clase original.
- **Verificá la fecha real (`date` en la terminal) antes de escribirla en un
  ADR, el CHANGELOG o `pruebas.md`.** Ya se escribió mal una vez (un día
  adelantada) por asumirla en vez de comprobarla.
- **`adb` desde Git Bash reescribe un path remoto que empieza con `/`**
  (como `/sdcard/...`) a un path de Windows, rompiendo `adb push`/`pull`.
  Anteponer una barra extra (`//sdcard/...`) evita esa conversión.
- **Correr muchas conversiones seguidas dentro de un mismo proceso de
  `am instrument` (un bucle de varias celdas/corridas en un solo test) da
  números que no reflejan un uso real de la app, y puede fabricar un
  "incumplimiento" que no existe.** Pasó midiendo si adoptar el decode en
  paralelo mejoraba el margen de RNF-08: una corrida de 135 conversiones
  encadenadas en un proceso mostró que el fps de producción (8, ADR-0012)
  rompía el tramo de 10 s (peor caso 23-24 s contra el tope de 20 s) — 5
  invocaciones *separadas* de `am instrument`, con el mismo decoder, dieron
  un rango tenso pero estable, muy por debajo del tope. La causa exacta no
  se identificó (se sospecha de hilos que no terminan entre corridas), pero
  el síntoma desaparece por completo sin encadenar corridas en un proceso.
  Un número que se vaya a documentar como cumplido o incumplido se mide con
  invocaciones separadas, no con un bucle (ver ADR-0015,
  `docs/desarrollo/pruebas.md`).
- **Un piso definido como tasa (por segundo, por unidad de tiempo) se
  rompe en cuanto cambia la duración que multiplica esa tasa — definilo
  como una cantidad fija si lo que necesitás garantizar es un mínimo
  absoluto.** El piso de fotogramas de ADR-0007 era "5 fps", validado
  solo para el caso de 3 s (15 fotogramas); para el máximo de RF-06
  (10 s) daba 50, y ninguna calidad los hacía caber — RF-12 fallaba de
  forma garantizada, no como caso límite. ADR-0016 lo reemplazó por una
  cantidad fija (15) más un escalón de duración aparte para no perder la
  fluidez que la tasa pretendía garantizar (`docs/desarrollo/pruebas.md`).
- **Antes de gastar una codificación (o cualquier operación cara) para
  confirmar si una configuración cabe, revisá si ya podés estimarlo por
  proporción con un dato que ya mediste.** La reproducción del fallo de
  RF-12 de arriba encontró que la Fase de bisección de calidad nunca
  llegó a correr: un intento que la propia proporción (tamaño medido ×
  relación con el límite) ya indicaba inviable (1512% del límite) se
  gastó igual, agotando el presupuesto de tiempo antes de llegar al único
  intento con alguna chance. Estimar primero no es prematuro: la misma
  matemática que ya se usa para decidir cuántos fotogramas reducir sirve
  para decidir si vale la pena intentar del todo (ADR-0016).
- **Una decisión de presupuesto de tiempo (RNF-08 o cualquiera) medida
  contra un `.so` nativo de debug da un techo mucho más bajo que el
  real.** ADR-0009/0012/0015/0018 fijaron el fps de prefiltro de video
  midiendo siempre contra el build de debug por defecto, sin saberlo:
  `:webp` compila sin ninguna bandera `-O` en debug (el default de Clang,
  `-O0`) contra `-O2 -DNDEBUG` en release — con el mismo contenido, la
  codificación WebP salió 5.9×-6.6× más lenta en debug. No estaban "mal"
  con los datos que tenían, midieron en el ambiente equivocado (ADR-0019
  remidió contra release y subió el fps de 10 a 20). Toda medición futura
  que decida un valor contra un presupuesto de tiempo debe compilarse y
  correrse con `assembleRelease`/`testBuildType = "release"`, no con el
  build de debug por defecto.

---

## Cuando tengas dudas

Pregunta antes de improvisar en estos casos: licencia de una dependencia nueva,
cambio de cualquier elección del stack fijado, o cualquier cosa que toque las
restricciones innegociables. En lo demás, avanza y explica lo que hiciste.
