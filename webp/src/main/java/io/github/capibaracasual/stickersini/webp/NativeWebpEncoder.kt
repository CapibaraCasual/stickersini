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
        encode(frames, quality, minimizeSize, method = 0, threadLevel = 0)

    /**
     * Con `method` configurable (0=rápido .. 6=más lento y mejor). Solo para
     * medir su costo real (ver WebpEncodeMethodBenchmarkTest,
     * WebpMinimizeSizeCostTest); producción siempre pasa por el [encode] de
     * 3 argumentos, que fija method=0 (ADR-0006) y threadLevel=0.
     */
    fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean, method: Int): ByteArray =
        encode(frames, quality, minimizeSize, method, threadLevel = 0)

    /**
     * Con `threadLevel` configurable además de `method` (0=un solo hilo,
     * 1=pedirle a libwebp que use más de uno donde pueda). Solo para medir
     * si vale la pena (ver WebpThreadLevelBenchmarkTest): `WebPConfig` no
     * paraleliza fotogramas entre sí (`WebPAnimEncoderAdd` es
     * inherentemente secuencial, necesita el fotograma anterior para
     * decidir diferencia-vs-clave), solo una fase interna de un fotograma
     * — y solo cuando `method<=1`, el caso de producción (ver
     * `webp_jni.c`).
     */
    fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean, method: Int, threadLevel: Int): ByteArray {
        val bitmaps = Array(frames.size) { frames[it].bitmap }
        val durationsMs = IntArray(frames.size) { frames[it].durationMs }
        return nativeEncode(bitmaps, durationsMs, quality, minimizeSize, method, threadLevel)
    }

    @JvmStatic
    private external fun nativeEncode(
        bitmaps: Array<android.graphics.Bitmap>,
        durationsMs: IntArray,
        quality: Int,
        minimizeSize: Boolean,
        method: Int,
        threadLevel: Int,
    ): ByteArray
}

/**
 * El mismo encoder que usa [WebpAnimEncoder] por defecto (production real,
 * `method=0` fijo por ADR-0006), expuesto para poder envolverlo con un
 * `SingleShotWebpEncoder` de medición (ver `MeasuringEncoder` en
 * `WebpAnimEncoderPerformanceTest` de este módulo, o el equivalente en
 * `:app`) desde fuera de `:webp`. [NativeWebpEncoder] sigue siendo
 * `internal`: esta referencia no expone su overload de `method`
 * configurable, que ADR-0006 reserva a las mediciones de este módulo.
 */
val ProductionWebpEncoder: SingleShotWebpEncoder = NativeWebpEncoder
