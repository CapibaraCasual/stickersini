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
     * el anterior. [WebpAnimEncoder] siempre lo deja en `false` (ADR-0018,
     * cambia este punto de ADR-0006: medido en contenido real, costaba
     * 1.8×-1.9× el tiempo por un 3-5% menos de tamaño, y además generaba
     * artefactos visuales en WhatsApp) — el parámetro sigue existiendo acá
     * para medirlo directo (`WebpMinimizeSizeCostTest`,
     * `SampledQualitySearchProbeTest` en `:app`), no para uso en producción.
     *
     * @throws WebpEncodeException si la codificación nativa falla.
     */
    fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean): ByteArray
}
