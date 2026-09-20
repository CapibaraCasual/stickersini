# ADR-0002: Codificar los stickers con libwebp vía JNI, no con FFmpeg

- Estado: Aceptado
- Fecha: 2026-09-19

## Contexto

La aplicación necesita producir archivos WebP animados de 512×512 píxeles que
pesen 500 KB o menos (RF-10), y WebP estáticos de 100 KB o menos (RF-11). El
material de origen es video MP4, imágenes o fotogramas capturados de la
pantalla.

El framework de Android puede *decodificar* WebP animado desde `ImageDecoder`,
pero no ofrece ninguna forma de *codificarlo*. `Bitmap.compress()` solo produce
WebP estático. Hace falta una biblioteca externa.

Restricciones: proyecto de un solo desarrollador, tamaño del instalador
relevante para la adopción (RNF-07), y necesidad de control fino sobre calidad
y tasa de fotogramas para cumplir el límite de 500 KB sin ensayo y error del
usuario.

## Opciones consideradas

1. **FFmpeg mediante `ffmpeg_kit_flutter`** — descartada: el proyecto fue
   retirado oficialmente en enero de 2025 y sus binarios precompilados se
   eliminaron de Maven Central en abril del mismo año. Está marcado como
   discontinuado.
2. **FFmpeg mediante `ffmpeg_kit_flutter_new`** (fork mantenido) — descartada:
   funciona, pero añade decenas de megabytes por ABI para usar una fracción
   mínima de su capacidad. Arrastra además el debate de patentes de códecs que
   motivó el retiro del proyecto original.
3. **Solo `MediaCodec` + `MediaMuxer` del framework** — descartada: sirve para
   decodificar el video de origen, pero no puede producir WebP animado. No
   resuelve el problema principal.
4. **libwebp (`WebPAnimEncoder`) mediante JNI** — elegida.

## Decisión

Usar `MediaCodec` para decodificar el video de origen a fotogramas, y
`WebPAnimEncoder` de libwebp, invocado por JNI, para codificar el sticker.

libwebp es la implementación de referencia del formato, pesa unos cientos de
kilobytes, está bajo licencia BSD y expone directamente los parámetros de
calidad y duración de fotograma que hacen falta para ajustarse al límite de
tamaño de forma automática (RF-12).

## Consecuencias

- El instalador queda dos órdenes de magnitud más ligero que con FFmpeg.
- Se gana control directo sobre el compromiso calidad/tamaño, que es el
  requisito duro del formato.
- Se asume complejidad de compilación: hay que integrar el NDK, compilar
  libwebp por ABI y escribir la capa JNI. Es la parte más frágil del build.
- La aplicación queda limitada a los formatos de entrada que `MediaCodec`
  soporte en el dispositivo. Contenedores exóticos no se podrán importar.
- Existen bindings JNI de libwebp ya publicados (`UdaraWanasinghe/webp-android`,
  `b4rtaz/android-webp-encoder`) que sirven de referencia o de punto de partida;
  si se adopta alguno, verificar antes su licencia frente a RNF-10.
- La licencia BSD de libwebp es compatible con GPL-3.0 (ver ADR-0003).
