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

En desarrollo. Versión actual: `0.5.0-alpha`. Todavía no hay versión
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
- **Fase 3 — en curso: primer recorrido de punta a punta.** Elegir un
  video o una imagen → **selector de tramo (RF-06) si es video** →
  conversión con progreso real → vista previa (un solo fotograma, no
  animada todavía) → guardar. Con recorte de área automático al centro
  todavía (RF-07 sigue en "Qué falta"). El resultado se guarda siempre en
  uno de los dos packs semilla (estático o animado según corresponda,
  ADR-0010) — no hay todavía un pack propio con nombre (RF-15).
  - Navegación entre pasos con Navigation Compose (ADR-0013), reemplazando
    el booleano a mano que conmutaba entre las dos únicas pantallas de
    antes: necesario en cuanto el flujo de creación pasó a tener más de un
    paso propio (elegir archivo → tramo → convertir/guardar).
  - El selector de tramo (`TrimScreen`) lee la duración real del video con
    `MediaMetadataRetriever` y deja elegir cualquier ventana de hasta 10 s
    dentro de ella con un `RangeSlider`, con una miniatura del fotograma de
    inicio como vista previa.
  - `StickerConversionPipeline` reporta avance real en cada etapa
    (fotogramas decodificados, intento de codificación), no un indicador
    indeterminado.
  - Segundo pack semilla, animado (`sticker_pack_semilla_animado`): sin
    él, el primer sticker animado del usuario no tenía dónde entrar sin
    volver a chocar con el mínimo de 3 de RF-16 (ADR-0004, ADR-0010).
  - **La conversión YUV→RGB del video se movió a un módulo nativo nuevo,
    `:yuv`** (C vía JNI, ADR-0011): medida como el 54-63% del tiempo de
    decodificar un video, quedó 2.87×-3.51× más rápida, validada píxel a
    píxel contra la implementación anterior en Kotlin (que se conserva
    como referencia).
  - **El fps de prefiltro de video sube de 5 a 8** (ADR-0012, reemplaza
    ADR-0009): con el decode más barato, es el valor más alto que cumple
    de forma confiable —contando el peor caso de 5 corridas, no la
    mediana— los dos tramos de tiempo de RNF-08 en clips de 3, 5 y 10 s.
    Medido en un solo dispositivo hasta ahora, con margen ajustado (12%)
    en los clips de 5 y 10 s — ver `docs/desarrollo/pruebas.md` para por
    qué eso importa antes de sumar una segunda fila de hardware.

### Qué falta

- **Fase 3 — el resto de la interfaz**, sobre el recorrido mínimo que ya
  funciona:
  - Recorte de área con pellizco de dos dedos (RF-07): hoy `CenterSquareCrop`
    siempre recorta al cuadrado centrado más grande, sin ninguna elección de
    por medio. Cuando exista este recorte, tiene que reemplazar esa regla
    fija en los dos consumidores que la comparten (`YuvFrameConverter`,
    `ImageFrameDecoder`), no solo en uno.
  - Flujo pensado: elegir video → elegir tramo de 10 s (RF-06) → encuadrar
    con pellizco (RF-07) → vista previa → guardar.
  - Gestión de stickers y packs (RF-15, RF-16): ver los stickers ya
    creados, crear packs propios con nombre, renombrarlos, eliminar
    stickers. Hoy no existe ninguna pantalla para esto.
  - **Trabajo de diseño de la interfaz, no solo de funciones.** Lo que hay
    hoy es funcional pero tosco. El criterio para el guardado: que nunca se
    sienta como un trámite administrativo (el mecanismo de packs semilla ya
    lo permite —guardar es instantáneo—, falta que la pantalla lo transmita).
- **Segunda fila de dispositivo en `docs/desarrollo/pruebas.md`.** Todas
  las mediciones de rendimiento hasta ahora son de un único Xiaomi Redmi
  Note 14; el margen que deja 8 fps de prefiltro (ADR-0012) es ajustado
  (12%) en dos de los tres casos medidos, y podría no sostenerse en un
  dispositivo más lento.
- **Abrir en GitHub** (no bloquea el desarrollo, sí la publicación o el
  seguimiento del trabajo):
  - Issue de los stickers semilla: los placeholders actuales (3 estáticos,
    3 animados) son cuadrados de color plano de prueba, no material de
    marca; según ADR-0004 los packs semilla son permanentes y viajan con
    la app, así que hay que reemplazarlos antes de publicar.
  - Issue de los avisos de licencia (RNF-11): el código de libwebp viaja
    vendorizado (ADR-0005), así que ninguna herramienta automática de
    generación de licencias lo detecta; hay que añadir el `COPYING` de
    libwebp a la pantalla de licencias a mano.
  - Historias de usuario del trabajo pendiente (Fase 3 en adelante), para
    rastrearlo fuera de este README.

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
- **C vía JNI/NDK (módulo `:yuv`)** — conversión de color YUV→RGB
- **libwebp (`WebPAnimEncoder`) vía JNI/NDK** — codificación de los stickers
- **ContentProvider** — entrega de los packs a WhatsApp

## Licencia

GPL-3.0 — ver [LICENSE](LICENSE).

El nombre **Stickersini**, el logotipo y el ícono no están cubiertos por la
licencia: ver [TRADEMARK.md](TRADEMARK.md).

Este proyecto no está afiliado a WhatsApp LLC ni a Meta Platforms, Inc.
