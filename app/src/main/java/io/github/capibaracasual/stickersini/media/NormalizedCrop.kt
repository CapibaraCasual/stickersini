package io.github.capibaracasual.stickersini.media

/**
 * Recorte cuadrado elegido por el usuario (RF-07), como fracciones
 * relativas al contenido de origen en vez de píxeles absolutos: el mismo
 * [NormalizedCrop] sirve tanto para la vista previa de `ui/CropScreen.kt`
 * (que decodifica a una resolución pensada para la interacción) como para
 * la decodificación final (`:yuv` para video vía [YuvFrameConverter],
 * `BitmapFactory` con su propio `inSampleSize` para imagen vía
 * [ImageFrameDecoder]) — cada una lo convierte a píxeles absolutos con
 * [SquareCrop.of] contra su propia resolución real, así que un cambio de
 * resolución entre la vista previa y la decodificación final no desalinea
 * el recorte.
 *
 * @param xFraction posición horizontal del recorte dentro del rango
 * disponible (`0` = pegado a la izquierda, `1` = pegado a la derecha, `0.5`
 * = centrado), no una posición absoluta.
 * @param yFraction igual que [xFraction], en vertical.
 * @param sizeFraction lado del cuadrado de recorte, como fracción del lado
 * más chico del contenido (`1` = el cuadrado centrado más grande posible,
 * el mismo que daba el recorte automático antes de RF-07).
 */
data class NormalizedCrop(
    val xFraction: Float,
    val yFraction: Float,
    val sizeFraction: Float,
) {
    companion object {
        /** Recorte automático al centro (comportamiento previo a RF-07, y el que se mantiene mientras el usuario no toque nada). */
        val CENTERED = NormalizedCrop(xFraction = 0.5f, yFraction = 0.5f, sizeFraction = 1f)
    }
}
