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

**No uses FFmpeg.** Está descartado por peso y licencia (ADR-0002).

Otras elecciones razonables mientras no haya ADR en contra: Room para
persistencia, Hilt o inyección manual para dependencias, Coroutines + Flow para
concurrencia, `targetSdk` 36 y `minSdk` 26 (RNF-04, RNF-05).

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

---

## Cuando tengas dudas

Pregunta antes de improvisar en estos casos: licencia de una dependencia nueva,
cambio de cualquier elección del stack fijado, o cualquier cosa que toque las
restricciones innegociables. En lo demás, avanza y explica lo que hiciste.
