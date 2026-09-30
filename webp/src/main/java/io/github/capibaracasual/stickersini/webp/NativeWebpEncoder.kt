package io.github.capibaracasual.stickersini.webp

import kotlin.math.roundToInt

/**
 * Puente JNI hacia `webp/src/main/cpp/jni/webp_jni.c`. Una sola pasada de
 * codificación, sin ajuste: ver [SingleShotWebpEncoder].
 */
internal object NativeWebpEncoder : SingleShotWebpEncoder {

    init {
        System.loadLibrary("stickersini_webp")
    }

    /**
     * method=0, sin sharp_yuv: los valores que ADR-0006/ADR-0022 fijan
     * para producción — la medición no mostró que `method` alto ni
     * `use_sharp_yuv` compren una mejora que compense su costo de tiempo,
     * ni en contenido adverso ni en representativo (ver
     * docs/desarrollo/pruebas.md). `quality` es `Float` (ADR-0022): ver
     * KDoc de [SingleShotWebpEncoder.encode].
     */
    override fun encode(frames: List<WebpFrame>, quality: Float, minimizeSize: Boolean): ByteArray =
        encodeFloatQuality(frames, quality, minimizeSize, method = 0, threadLevel = 0, useSharpYuv = false)

    /**
     * Con `method` configurable (0=rápido .. 6=más lento y mejor), calidad
     * entera. Solo para medir su costo real a calidad fija (ver
     * WebpEncodeMethodBenchmarkTest, WebpMinimizeSizeCostTest) — producción
     * siempre pasa por el [encode] de 3 argumentos (`Float`, ADR-0022), que
     * fija method=0 (ADR-0006) y threadLevel=0.
     */
    fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean, method: Int): ByteArray =
        encode(frames, quality, minimizeSize, method, threadLevel = 0, useSharpYuv = false)

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
    fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean, method: Int, threadLevel: Int): ByteArray =
        encode(frames, quality, minimizeSize, method, threadLevel, useSharpYuv = false)

    /**
     * Con `useSharpYuv` configurable además de `method`/`threadLevel`
     * (ADR-0022): RGB→YUV420 más nítido en bordes de alto contraste
     * (texto, UI), a costa de tiempo de codificación — ver
     * `WebpSharpYuvBenchmarkTest` y `docs/desarrollo/pruebas.md`.
     */
    fun encode(
        frames: List<WebpFrame>,
        quality: Int,
        minimizeSize: Boolean,
        method: Int,
        threadLevel: Int,
        useSharpYuv: Boolean,
    ): ByteArray {
        val bitmaps = Array(frames.size) { frames[it].bitmap }
        val durationsMs = IntArray(frames.size) { frames[it].durationMs }
        return nativeEncode(bitmaps, durationsMs, quality, minimizeSize, method, threadLevel, useSharpYuv)
    }

    @JvmStatic
    private external fun nativeEncode(
        bitmaps: Array<android.graphics.Bitmap>,
        durationsMs: IntArray,
        quality: Int,
        minimizeSize: Boolean,
        method: Int,
        threadLevel: Int,
        useSharpYuv: Boolean,
    ): ByteArray

    /**
     * Con `quality` fraccionaria (ADR-0022, investigación): `WebPConfig.quality`
     * es un `float` de verdad — esto expone esa granularidad en vez de
     * truncarla a entero, para ver si acerca la bisección al ~95% de
     * ocupación cuando hay un salto grande entre dos calidades enteras
     * consecutivas (ver `QualityCurveProbeTest`/`docs/desarrollo/pruebas.md`).
     * No es la vía de producción: [encode] (entero) sigue siendo esa.
     */
    fun encodeFloatQuality(
        frames: List<WebpFrame>,
        quality: Float,
        minimizeSize: Boolean,
        method: Int,
        threadLevel: Int,
        useSharpYuv: Boolean,
    ): ByteArray {
        val bitmaps = Array(frames.size) { frames[it].bitmap }
        val durationsMs = IntArray(frames.size) { frames[it].durationMs }
        return nativeEncodeFloat(bitmaps, durationsMs, quality, minimizeSize, method, threadLevel, useSharpYuv)
    }

    @JvmStatic
    private external fun nativeEncodeFloat(
        bitmaps: Array<android.graphics.Bitmap>,
        durationsMs: IntArray,
        quality: Float,
        minimizeSize: Boolean,
        method: Int,
        threadLevel: Int,
        useSharpYuv: Boolean,
    ): ByteArray

    /**
     * Con el filtro de bloques configurable (ADR-0022, investigación):
     * bloques visibles reportados en contenido adverso real a calidad muy
     * baja — ver KDoc de `nativeEncodeTuned` en `webp_jni.c`.
     * `minimizeSize=false`, `threadLevel=0`, `useSharpYuv=false` fijos
     * (sin relación con lo que se investiga acá, ya medidos aparte).
     */
    fun encodeTuned(
        frames: List<WebpFrame>,
        quality: Float,
        method: Int,
        filterStrength: Int,
        autofilter: Boolean,
        filterSharpness: Int,
        snsStrength: Int,
    ): ByteArray {
        val bitmaps = Array(frames.size) { frames[it].bitmap }
        val durationsMs = IntArray(frames.size) { frames[it].durationMs }
        return nativeEncodeTuned(bitmaps, durationsMs, quality, method, filterStrength, autofilter, filterSharpness, snsStrength)
    }

    @JvmStatic
    private external fun nativeEncodeTuned(
        bitmaps: Array<android.graphics.Bitmap>,
        durationsMs: IntArray,
        quality: Float,
        method: Int,
        filterStrength: Int,
        autofilter: Boolean,
        filterSharpness: Int,
        snsStrength: Int,
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

/**
 * Codificador con `method`/`useSharpYuv` configurables (ADR-0022), para
 * medir su costo real sobre contenido decodificado fuera de este módulo
 * (ver `LengthZoomLadderProbeTest`/`MethodSharpYuvSweepTest` en `:app`).
 * Mismo motivo que [ProductionWebpEncoder]: [NativeWebpEncoder] sigue
 * siendo `internal`, esto expone una referencia puntual en vez de bajarle
 * la visibilidad a la clase completa. `threadLevel` no se expone: ya
 * fijado en 0 por ADR-0006/`WebpThreadLevelBenchmarkTest`, sin relación
 * con lo que mide ADR-0022.
 */
fun measuringWebpEncoder(method: Int, useSharpYuv: Boolean): SingleShotWebpEncoder =
    SingleShotWebpEncoder { frames, quality, minimizeSize ->
        // Redondea a entero a propósito: este codificador compara method/
        // sharp_yuv a calidad entera (ADR-0022, investigación), la
        // granularidad fraccionaria no es lo que mide.
        NativeWebpEncoder.encode(frames, quality.roundToInt(), minimizeSize, method, threadLevel = 0, useSharpYuv)
    }

/**
 * Como [measuringWebpEncoder], pero con `quality` fraccionaria (ADR-0022,
 * investigación) — no hay una interfaz pública análoga a
 * [SingleShotWebpEncoder] para calidad `Float` porque no es una vía de
 * producción, solo de esta medición puntual.
 */
fun measuringWebpEncoderFloat(method: Int, useSharpYuv: Boolean): (List<WebpFrame>, Float, Boolean) -> ByteArray =
    { frames, quality, minimizeSize ->
        NativeWebpEncoder.encodeFloatQuality(frames, quality, minimizeSize, method, threadLevel = 0, useSharpYuv)
    }

/**
 * Como [measuringWebpEncoderFloat], pero con el filtro de bloques
 * configurable (ADR-0022, investigación) — sin `minimizeSize` porque
 * `encodeTuned` ya lo fija en `false` (ver su KDoc).
 */
fun measuringWebpEncoderTuned(
    method: Int,
    filterStrength: Int,
    autofilter: Boolean,
    filterSharpness: Int,
    snsStrength: Int,
): (List<WebpFrame>, Float) -> ByteArray =
    { frames, quality ->
        NativeWebpEncoder.encodeTuned(frames, quality, method, filterStrength, autofilter, filterSharpness, snsStrength)
    }
