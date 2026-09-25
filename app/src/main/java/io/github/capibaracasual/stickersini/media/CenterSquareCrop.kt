package io.github.capibaracasual.stickersini.media

/**
 * El cuadrado centrado más grande dentro de un rectángulo de
 * [width]×[height]: la regla que decide qué parte de una imagen o de un
 * fotograma de video ve el usuario mientras no exista el recorte de área
 * que elige el usuario (RF-07). Un solo lugar para esta regla en vez de
 * calcularla por separado en [YuvFrameConverter] (fotogramas de video) y
 * en [ImageFrameDecoder] (imágenes): son dos consumidores de la misma
 * decisión, no dos decisiones — calcularla dos veces es la forma más fácil
 * de que terminen divergiendo sin que nadie se dé cuenta.
 */
internal data class CenterSquareCrop(val size: Int, val xOffset: Int, val yOffset: Int) {
    companion object {
        fun of(width: Int, height: Int): CenterSquareCrop {
            val size = minOf(width, height)
            return CenterSquareCrop(size = size, xOffset = (width - size) / 2, yOffset = (height - size) / 2)
        }
    }
}
