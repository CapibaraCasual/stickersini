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

En desarrollo. Versión actual: `0.2.0-alpha`. Todavía no hay versión
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
- **Fase 2 — importación de video e imagen con `MediaCodec`.** Siguiente
  fase: decodificar video existente y fotos/capturas como fuente de
  fotogramas para el encoder, además de la captura de pantalla en vivo.
- **Al llegar a la Fase 2:** volver a medir el codificador con grabaciones
  de pantalla reales, no el contenido sintético de
  `docs/desarrollo/pruebas.md`, y revisar con esos datos si el umbral del
  50% para seguir bisecando calidad hacia arriba en el piso de fotogramas
  (ADR-0007) sigue siendo razonable, y si `minimize_size` aporta algo real
  fuera del contenido sintético — si no, quitarlo.
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
