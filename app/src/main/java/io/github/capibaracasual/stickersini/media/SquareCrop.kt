package io.github.capibaracasual.stickersini.media

import kotlin.math.roundToInt

/**
 * El recorte cuadrado que se aplica de verdad a un contenido concreto
 * (video o imagen), en píxeles absolutos contra sus dimensiones reales:
 * [width]×[height] pasa a ser un cuadrado de [size]×[size] empezando en
 * ([xOffset], [yOffset]). Antes de RF-07 este era siempre el cuadrado
 * centrado más grande posible; ahora también puede venir de la elección
 * del usuario ([NormalizedCrop], convertida a píxeles con [SquareCrop.of]
 * contra la resolución real de quien decodifica — ver su KDoc para por qué
 * el recorte no viaja ya en píxeles entre la vista previa y la
 * decodificación final).
 *
 * Un solo lugar para esta cuenta en vez de calcularla por separado en
 * [YuvFrameConverter] (fotogramas de video) y en [ImageFrameDecoder]
 * (imágenes): son dos consumidores de la misma decisión, no dos
 * decisiones — calcularla dos veces es la forma más fácil de que terminen
 * divergiendo sin que nadie se dé cuenta.
 */
internal data class SquareCrop(val size: Int, val xOffset: Int, val yOffset: Int) {
    companion object {
        /**
         * @param normalized recorte a aplicar; por defecto
         * [NormalizedCrop.CENTERED] (el cuadrado centrado más grande),
         * calculado con aritmética entera exacta para no depender del
         * redondeo de punto flotante en el caso más común. Un
         * [NormalizedCrop] distinto (RF-07) sí pasa por ese redondeo:
         * no hay un caso "exacto" equivalente para un recorte descentrado.
         */
        fun of(width: Int, height: Int, normalized: NormalizedCrop = NormalizedCrop.CENTERED): SquareCrop {
            if (normalized == NormalizedCrop.CENTERED) return centered(width, height)

            val maxSize = minOf(width, height)
            val size = (normalized.sizeFraction * maxSize).roundToInt().coerceIn(1, maxSize)
            val xOffset = (normalized.xFraction * (width - size)).roundToInt().coerceIn(0, width - size)
            val yOffset = (normalized.yFraction * (height - size)).roundToInt().coerceIn(0, height - size)
            return SquareCrop(size = size, xOffset = xOffset, yOffset = yOffset)
        }

        private fun centered(width: Int, height: Int): SquareCrop {
            val size = minOf(width, height)
            return SquareCrop(size = size, xOffset = (width - size) / 2, yOffset = (height - size) / 2)
        }
    }
}
