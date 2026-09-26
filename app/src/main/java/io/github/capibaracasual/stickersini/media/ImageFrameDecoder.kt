package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.capibaracasual.stickersini.webp.FrameTiming
import io.github.capibaracasual.stickersini.webp.WebpFrame

/** RF-10/RF-11: el fotograma que produce esta fase ya sale al tamaño exacto de un sticker. */
private const val STICKER_SIZE = 512

/**
 * Fase 2 (RF-03): decodifica una imagen o foto existente en un único
 * [WebpFrame], listo para
 * [io.github.capibaracasual.stickersini.webp.WebpAnimEncoder] con
 * `targetSizeBytes = `[io.github.capibaracasual.stickersini.webp.STATIC_WEBP_TARGET_SIZE_BYTES]
 * (RF-11: un sticker estático es una animación de un solo fotograma — ver
 * el KDoc de `WebpAnimEncoder` para el razonamiento completo, y
 * `docs/desarrollo/pruebas.md` para la medición que lo confirma).
 *
 * Mismo recorte que el video ([SquareCrop], ADR-0008): por defecto, el
 * cuadrado centrado más grande; con un [NormalizedCrop] propio (RF-07),
 * el que haya elegido el usuario en `ui/CropScreen.kt`. Se calcula sobre
 * la resolución ya reducida ([calculateInSampleSize]), no sobre la imagen
 * a su resolución original — mismo motivo que llevó a recortar antes de
 * convertir en [YuvFrameConverter]: no decodificar más píxeles de los que
 * el recorte va a conservar. Una foto de cámara puede venir a una
 * resolución mucho mayor que los 512×512 que necesita un sticker.
 */
class ImageFrameDecoder {

    /** @throws ImageDecodeException si no se puede leer o decodificar la imagen. */
    fun decode(context: Context, uri: Uri, normalizedCrop: NormalizedCrop = NormalizedCrop.CENTERED): WebpFrame {
        val rotationDegrees = readImageOrientationDegrees(context, uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri).use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageDecodeException("No se pudieron leer las dimensiones de $uri")
        }

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, STICKER_SIZE)
        val sampled = openStream(context, uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw ImageDecodeException("No se pudo decodificar $uri")

        val crop = SquareCrop.of(sampled.width, sampled.height, normalizedCrop)
        val cropped = Bitmap.createBitmap(sampled, crop.xOffset, crop.yOffset, crop.size, crop.size)
        val rotated = if (rotationDegrees % 360 != 0) rotateSquareBitmap(cropped, rotationDegrees) else cropped
        val squareBitmap = if (rotated.width == STICKER_SIZE) {
            rotated
        } else {
            Bitmap.createScaledBitmap(rotated, STICKER_SIZE, STICKER_SIZE, /* filter = */ true)
        }

        return WebpFrame(squareBitmap, FrameTiming.MIN_FRAME_DURATION_MS)
    }

    private fun openStream(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: throw ImageDecodeException("No se pudo abrir $uri")
}
