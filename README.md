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

En desarrollo. Todavía no hay versión publicada.

- **TODO (bloqueante de publicación):** los 3 stickers del pack semilla son
  cuadrados de color plano generados para probar el `ContentProvider`, no
  material de marca. Según ADR-0004 el pack semilla es permanente y viaja con
  la app, así que hay que reemplazarlos por diseños definitivos antes de
  publicar. Pendiente de abrir como issue.

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
