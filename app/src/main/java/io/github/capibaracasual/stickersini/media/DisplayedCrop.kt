package io.github.capibaracasual.stickersini.media

/**
 * Convierte un recorte que el usuario eligió mientras miraba el contenido
 * ya en la orientación correcta (`ui/CropScreen.kt`: un fotograma de video
 * o una foto, ambos ya rotados según corresponda) a un [NormalizedCrop]
 * relativo al contenido *antes* de rotar — el espacio en el que
 * [YuvFrameConverter] e [ImageFrameDecoder] recortan de verdad (los dos
 * recortan antes de rotar, no después: ver sus propios KDoc).
 *
 * Sin esto, un recorte centrado seguiría dando el mismo resultado sin
 * importar la rotación (por simetría: rotar alrededor del centro no mueve
 * el centro), pero uno descentrado quedaría desplazado al revés apenas el
 * contenido tuviera una rotación de 90°/270° — el caso más común en la
 * práctica: una grabación o foto tomada en vertical.
 *
 * Las cuatro fórmulas (una por rotación posible) salen de rastrear las
 * cuatro esquinas de un rectángulo a través de una rotación horaria de
 * [rotationDegrees] grados alrededor del origen de un cuadro
 * [sourceWidth]×[sourceHeight] — el mismo sentido de rotación que
 * `Matrix.postRotate` (y por lo tanto [rotateSquareBitmap]) usa en Android.
 * Verificadas con casos de esquina conocidos en `DisplayedCropTest`, no solo
 * con la propia álgebra: es la clase de cuenta donde un error de signo no
 * rompe ningún test de tamaño ni lanza ninguna excepción, solo se nota
 * mirando un sticker mal encuadrado.
 *
 * @param displayedX, [displayedY], [displayedSize] recorte elegido, en
 * píxeles del contenido ya mostrado en pantalla (después de rotar).
 * @param sourceWidth, [sourceHeight] dimensiones reales del contenido
 * *antes* de rotar (el ancho/alto que ya lee `VideoFrameDecoder`/
 * `MediaMetadataRetriever`, no los de la vista previa rotada).
 */
internal fun displayedCropToNormalized(
    displayedX: Float,
    displayedY: Float,
    displayedSize: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    rotationDegrees: Int,
): NormalizedCrop {
    val (sourceX, sourceY) = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> displayedY to (sourceHeight - displayedSize - displayedX)
        180 -> (sourceWidth - displayedSize - displayedX) to (sourceHeight - displayedSize - displayedY)
        270 -> (sourceWidth - displayedSize - displayedY) to displayedX
        else -> displayedX to displayedY
    }

    val maxSize = minOf(sourceWidth, sourceHeight)
    val sizeFraction = (displayedSize / maxSize).coerceIn(0f, 1f)
    val xRange = sourceWidth - displayedSize
    val yRange = sourceHeight - displayedSize
    val xFraction = if (xRange > 0f) (sourceX / xRange).coerceIn(0f, 1f) else 0.5f
    val yFraction = if (yRange > 0f) (sourceY / yRange).coerceIn(0f, 1f) else 0.5f
    return NormalizedCrop(xFraction = xFraction, yFraction = yFraction, sizeFraction = sizeFraction)
}
