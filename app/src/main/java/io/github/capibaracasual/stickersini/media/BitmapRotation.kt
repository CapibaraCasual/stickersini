package io.github.capibaracasual.stickersini.media

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Rota un bitmap ya recortado a cuadrado (ver [SquareCrop]) múltiplos
 * de 90°. Compartida entre [YuvFrameConverter] (fotogramas de video) e
 * [ImageFrameDecoder] (imágenes): ambos recortan antes de rotar — con el
 * recorte automático (centrado), un cuadrado centrado en la imagen final,
 * rotada alrededor de su propio centro, corresponde siempre al mismo
 * cuadrado centrado en la imagen de origen (rotar alrededor del centro no
 * mueve el centro), así que los dos necesitan la misma rotación final
 * sobre un cuadrado ya chico, no sobre la imagen completa.
 */
internal fun rotateSquareBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, /* filter = */ true)
}
