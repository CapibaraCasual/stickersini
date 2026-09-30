package io.github.capibaracasual.stickersini.webp

/**
 * Una sola pasada de codificación a una calidad fija, sin reintentos ni
 * ajuste: eso es responsabilidad de [WebpAnimEncoder]. Existe como interfaz,
 * en vez de llamar a [NativeWebpEncoder] directamente, para poder probar la
 * lógica de ajuste con JUnit normal, sin cargar la librería nativa.
 */
fun interface SingleShotWebpEncoder {
    /**
     * [quality] es `Float` (ADR-0022): `WebPConfig.quality` es un `float`
     * de verdad, no un entero — bisecar con esa granularidad acerca el
     * resultado final al objetivo de ocupación de
     * [WebpAnimEncoder] cuando hay un salto grande entre dos calidades
     * enteras consecutivas (medido en dispositivo real, ver
     * `docs/desarrollo/pruebas.md`: 2.5×-2.6× de salto en el video de
     * referencia, entre calidad entera 90 y 91).
     *
     * [minimizeSize] controla `WebPAnimEncoderOptions.minimize_size`: más
     * lento, prueba cada fotograma como keyframe y como diferencia contra
     * el anterior. [WebpAnimEncoder] siempre lo deja en `false` (ADR-0018,
     * cambia este punto de ADR-0006: medido en contenido real, costaba
     * 1.8×-1.9× el tiempo por un 3-5% menos de tamaño; `sticker-convert`,
     * proyecto de terceros, reporta además líneas negras en WhatsApp con
     * este mecanismo, no verificado en este dispositivo) — el parámetro sigue existiendo acá
     * para medirlo directo (`WebpMinimizeSizeCostTest`,
     * `SampledQualitySearchProbeTest` en `:app`), no para uso en producción.
     *
     * @throws WebpEncodeException si la codificación nativa falla.
     */
    fun encode(frames: List<WebpFrame>, quality: Float, minimizeSize: Boolean): ByteArray
}
