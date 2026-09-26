package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.media.ExifInterface
import android.net.Uri

/**
 * Corrección EXIF: solo distingue rotaciones de 90°, no espejado (poco
 * frecuente fuera de fotos de cámara frontal sin corregir). Compartida
 * entre [ImageFrameDecoder] (decodificación final) y `ui/CropScreen.kt`
 * (RF-07, vista previa de recorte): las dos necesitan la misma rotación
 * para que lo que el usuario ve al elegir el recorte sea lo que termina en
 * el sticker.
 *
 * @throws ImageDecodeException si no se puede abrir [uri].
 */
internal fun readImageOrientationDegrees(context: Context, uri: Uri): Int {
    val stream = context.contentResolver.openInputStream(uri) ?: throw ImageDecodeException("No se pudo abrir $uri")
    val exif = stream.use { ExifInterface(it) }
    return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
