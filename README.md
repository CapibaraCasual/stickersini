# Stickersini

Convierte lo que ves en tu pantalla en stickers de WhatsApp, sin cuenta, sin
anuncios y sin que nada salga de tu teléfono.

<!-- ![captura](docs/img/captura.png) -->

## Qué es

Las aplicaciones que convierten video en stickers existen, pero casi todas
piden registro, suben tu contenido a un catálogo público y viven de anuncios.
Stickersini hace lo mismo sin nada de eso: graba tu pantalla, toma un video que
ya tenías, una foto o una captura, y lo convierte en un sticker animado o
estático listo para WhatsApp.

Todo el procesamiento ocurre en el dispositivo. La aplicación no tiene permiso
de internet.

## Estado

En desarrollo. Versión actual: `0.4.0-alpha`. Todavía no hay versión
publicada en Google Play.

### Qué funciona ya

- **Fase 0 — integración con WhatsApp.** El `ContentProvider` (ADR-0004)
  está validado en dispositivo real: WhatsApp acepta el pack semilla y sus
  3 stickers aparecen en la bandeja de un chat.
- **Fase 1 — codificador WebP.** El módulo `:webp` (libwebp vendorizado,
  ADR-0005; estrategia de codificación en ADR-0006 y ADR-0007) está
  validado en dispositivo real, en corrección y en rendimiento: cumple
  RF-10, RF-12, RF-13 y RNF-08 con margen real medido, tanto en contenido
  representativo como en el peor caso adverso probado. Detalle completo en
  [`docs/desarrollo/pruebas.md`](docs/desarrollo/pruebas.md).
- **Fase 2 — importación de video e imagen, cerrada (RF-02, RF-03).** El
  módulo `media/` decodifica un video o una imagen/foto existente hacia
  fotogramas listos para `:webp` (ADR-0008: ruta CPU vía `ImageReader`,
  confirmada en dispositivo real, sin necesitar GPU; ADR-0009: fps de
  prefiltro derivado del piso de ADR-0007). Video e imagen comparten el
  mismo recorte al cuadrado central (`CenterSquareCrop`) y el mismo
  `WebpAnimEncoder`: un sticker estático (RF-11) es una animación de un solo
  fotograma, no un codificador aparte (ADR-0002; medido que el contenedor de
  animación no agrega un sobrecosto relevante contra el límite de 100 KB —
  ver `docs/desarrollo/pruebas.md`). Orientación EXIF corregida y verificada
  con una prueba que sigue una marca visual a través de la rotación, no solo
  el tamaño de salida. **RNF-08 validado con una grabación de pantalla real
  en un Redmi Note 14:** un clip de 3 s (la referencia del propio requisito)
  convierte en 3 163 ms, con margen; el máximo de 10 s cae, como está
  previsto, en el segundo tramo del requisito (≤20 s). Detalle completo, con
  todas las rondas de medición, en
  [`docs/desarrollo/pruebas.md`](docs/desarrollo/pruebas.md). Alcance de
  esta fase: produce un WebP válido a partir de un video o una imagen
  existente, sin UI todavía (selección de archivo, recorte de área), sin
  guardar el resultado como sticker de un pack ni entregarlo a WhatsApp —
  eso sigue en "Qué falta".

### Qué falta

- **Abrir en GitHub** (no bloquea el desarrollo, sí la publicación o el
  seguimiento del trabajo):
  - Issue de los stickers semilla: los 3 actuales son cuadrados de color
    plano de prueba, no material de marca; según ADR-0004 el pack semilla
    es permanente y viaja con la app, así que hay que reemplazarlos antes
    de publicar.
  - Issue de los avisos de licencia (RNF-11): el código de libwebp viaja
    vendorizado (ADR-0005), así que ninguna herramienta automática de
    generación de licencias lo detecta; hay que añadir el `COPYING` de
    libwebp a la pantalla de licencias a mano.
  - Historias de usuario del trabajo pendiente (Fase 2 en adelante), para
    rastrearlo fuera de este README.
- **UI de la Fase 2** (selección de archivo, recorte temporal RF-06,
  recorte de área RF-07, vista previa RF-09): siguiente trabajo de código,
  ahora que los dos orígenes de contenido de esta fase (video e imagen)
  están implementados y medidos.
- **Al implementar la UI: el indicador de progreso de RNF-08 debe mostrarse
  siempre, no solo para clips largos o de alta complejidad visual.** Medido
  en el Redmi Note 14: un clip de 5 s cumple el tramo rápido (≤5 s) con solo
  361 ms de margen; en un dispositivo más lento ese mismo clip podría
  superarlo, y ahí el progreso es lo que sostiene la experiencia, no un
  detalle solo del caso lento. Ver `docs/desarrollo/pruebas.md`.
- **Al llegar a los packs del usuario:** falta un ADR sobre dónde se
  almacenan los packs que arma el usuario (no el semilla) y cómo se
  sirven al `ContentProvider`.

## Instalación

Próximamente en Google Play. Mientras tanto, compilar desde el código fuente
siguiendo [la guía de instalación](docs/desarrollo/instalacion.md).

## Documentación

- [Guía de usuario](docs/usuario/) — cómo usar la aplicación
- [Documentación técnica](docs/desarrollo/) — requisitos, arquitectura, pruebas
- [Decisiones arquitectónicas](decisions/) — por qué el proyecto es como es

## Stack

- **Kotlin + Jetpack Compose** — aplicación y interfaz
- **MediaProjection** — captura de pantalla
- **MediaCodec** — decodificación del video de origen
- **libwebp (`WebPAnimEncoder`) vía JNI/NDK** — codificación de los stickers
- **ContentProvider** — entrega de los packs a WhatsApp

## Licencia

GPL-3.0 — ver [LICENSE](LICENSE).

El nombre **Stickersini**, el logotipo y el ícono no están cubiertos por la
licencia: ver [TRADEMARK.md](TRADEMARK.md).

Este proyecto no está afiliado a WhatsApp LLC ni a Meta Platforms, Inc.
