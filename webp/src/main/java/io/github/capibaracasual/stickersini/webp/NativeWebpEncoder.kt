package io.github.capibaracasual.stickersini.webp

/**
 * Puente JNI hacia `webp/src/main/cpp/jni/webp_jni.c`. Una sola pasada de
 * codificación, sin ajuste: ver [SingleShotWebpEncoder].
 */
internal object NativeWebpEncoder : SingleShotWebpEncoder {

    init {
        System.loadLibrary("stickersini_webp")
    }

    /** method=4: punto medio documentado por libwebp, el único que usa producción. */
    override fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean): ByteArray =
        encode(frames, quality, minimizeSize, method = 4)

    /**
     * Con `method` configurable (0=rápido .. 6=más lento y mejor). Solo para
     * medir su costo real (ver WebpEncodeMethodBenchmarkTest); producción
     * siempre pasa por el [encode] de 3 argumentos, que fija method=4.
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
