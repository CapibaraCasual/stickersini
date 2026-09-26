package io.github.capibaracasual.stickersini.media

/**
 * El mayor `inSampleSize` (potencia de 2) tal que el lado más chico de la
 * imagen decodificada siga siendo al menos [targetSize]: decodifica la
 * menor resolución posible sin terminar escalando hacia arriba (que
 * desenfocaría). Compartida entre [ImageFrameDecoder] (decodifica ya al
 * tamaño final de sticker) y `ui/CropScreen.kt` (decodifica a una
 * resolución pensada para la interacción de recorte, RF-07) — dos
 * consumidores del mismo cálculo con un [targetSize] distinto cada uno, no
 * dos cálculos.
 */
internal fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
    val minDimension = minOf(width, height)
    var inSampleSize = 1
    while (minDimension / (inSampleSize * 2) >= targetSize) {
        inSampleSize *= 2
    }
    return inSampleSize
}
