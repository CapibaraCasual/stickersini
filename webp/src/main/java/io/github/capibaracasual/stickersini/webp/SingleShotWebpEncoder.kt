package io.github.capibaracasual.stickersini.webp

/**
 * Una sola pasada de codificación a una calidad fija, sin reintentos ni
 * ajuste: eso es responsabilidad de [WebpAnimEncoder]. Existe como interfaz,
 * en vez de llamar a [NativeWebpEncoder] directamente, para poder probar la
 * lógica de ajuste con JUnit normal, sin cargar la librería nativa.
 */
fun interface SingleShotWebpEncoder {
    /**
     * [minimizeSize] controla `WebPAnimEncoderOptions.minimize_size`: más
     * lento, prueba cada fotograma como keyframe y como diferencia contra
     * el anterior. [WebpAnimEncoder] lo deja en `false` durante la búsqueda
     * de calidad y en `true` solo en la pasada final — quien implemente
     * esta interfaz no decide eso, solo lo respeta.
     *
     * @throws WebpEncodeException si la codificación nativa falla.
     */
    fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean): ByteArray
}
