package io.github.capibaracasual.stickersini.webp

/**
 * Una sola pasada de codificación a una calidad fija, sin reintentos ni
 * ajuste: eso es responsabilidad de [WebpAnimEncoder]. Existe como interfaz,
 * en vez de llamar a [NativeWebpEncoder] directamente, para poder probar la
 * lógica de ajuste con JUnit normal, sin cargar la librería nativa.
 */
fun interface SingleShotWebpEncoder {
    /** @throws WebpEncodeException si la codificación nativa falla. */
    fun encode(frames: List<WebpFrame>, quality: Int): ByteArray
}
