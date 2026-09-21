package io.github.capibaracasual.stickersini.webp

/**
 * Puente JNI hacia `webp/src/main/cpp/jni/webp_jni.c`. Una sola pasada de
 * codificación, sin ajuste: ver [SingleShotWebpEncoder].
 */
internal object NativeWebpEncoder : SingleShotWebpEncoder {

    init {
        System.loadLibrary("stickersini_webp")
    }

    /**
     * method=0: el más rápido, y el que ADR-0006 fija para producción —
     * la medición no mostró que `method` alto compre una mejora de tamaño
     * que compense su costo, ni en contenido adverso ni en representativo.
     */
    override fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean): ByteArray =
        encode(frames, quality, minimizeSize, method = 0)

    /**
     * Con `method` configurable (0=rápido .. 6=más lento y mejor). Solo para
     * medir su costo real (ver WebpEncodeMethodBenchmarkTest,
     * WebpMinimizeSizeCostTest); producción siempre pasa por el [encode] de
     * 3 argumentos, que fija method=0 (ADR-0006).
     */
    fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean, method: Int): ByteArray {
        val bitmaps = Array(frames.size) { frames[it].bitmap }
        val durationsMs = IntArray(frames.size) { frames[it].durationMs }
        return nativeEncode(bitmaps, durationsMs, quality, minimizeSize, method)
    }

    @JvmStatic
    private external fun nativeEncode(
        bitmaps: Array<android.graphics.Bitmap>,
        durationsMs: IntArray,
        quality: Int,
        minimizeSize: Boolean,
        method: Int,
    ): ByteArray
}
