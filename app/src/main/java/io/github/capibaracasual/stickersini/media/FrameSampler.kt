package io.github.capibaracasual.stickersini.media

/** RF-06: el sticker no puede salir de un tramo de origen más largo que esto. */
const val MAX_CLIP_DURATION_MS = 10_000L

/**
 * fps al que [FrameSampler] conserva fotogramas decodificados antes de
 * convertirlos a bitmap (YUV→RGB + escalado, el paso caro de la
 * decodificación). No decide la calidad final del sticker:
 * [io.github.capibaracasual.stickersini.webp.WebpAnimEncoder] puede seguir
 * reduciendo fotogramas por su cuenta hasta su propio piso de 5 fps
 * (ADR-0007) sobre lo que este muestreo entregue.
 *
 * ADR-0008 fijó esto en 20 sin derivarlo de nada medido; medido en
 * dispositivo real (ADR-0009) resultó en 200 fotogramas para un clip de
 * 10 s — 6.7× el máximo que `WebpAnimEncoder` había probado (30, en las
 * mediciones de ADR-0006/ADR-0007) — y el codificador agotó su tope de 20 s
 * sin encontrar ningún resultado válido. ADR-0009 reemplaza ese valor por
 * el propio piso de ADR-0007 (5 fps): no un número elegido por separado
 * para "verse fluido", sino el mismo piso que el codificador ya trata como
 * aceptable, para no pedirle más trabajo del que hay evidencia de que
 * puede hacer a tiempo.
 */
const val VIDEO_PREFILTER_TARGET_FPS = 5

/**
 * Decide, fotograma a fotograma y en el mismo orden en que `MediaCodec` los
 * entrega, cuáles se conservan para convertir a bitmap: el primero que
 * llegue en o después de cada marca de una grilla fija de `1_000_000 /
 * targetFps` microsegundos, empezando en 0. Sin lookahead (no compara contra
 * el fotograma siguiente para elegir el más cercano de los dos, solo el
 * primero que ya alcanzó la marca), pero sí sin deriva: al no reengancharse
 * al propio timestamp del fotograma conservado, mantiene la tasa promedio
 * correcta incluso cuando la tasa de origen no divide exacto a [targetFps]
 * (30 fps de origen sobre 20 fps objetivo da, en régimen, dos fotogramas
 * conservados por cada tres de origen, no un patrón fijo de saltar uno sí
 * uno no). Si el video tiene un salto real de timestamps (fotograma
 * perdido), el efecto es que los fotogramas siguientes se conservan más
 * seguido hasta ponerse al día con la grilla — comportamiento correcto de
 * un decimador a tasa fija, no un defecto. Ver ADR-0008.
 */
class FrameSampler(targetFps: Int = VIDEO_PREFILTER_TARGET_FPS) {
    private val intervalUs = 1_000_000L / targetFps
    private var nextMarkUs = 0L

    /**
     * @param presentationTimeUs marca de tiempo del fotograma decodificado,
     * en microsegundos, no decreciente entre llamadas sucesivas.
     * @return `true` si este fotograma debe convertirse a bitmap.
     */
    fun shouldKeep(presentationTimeUs: Long): Boolean {
        if (presentationTimeUs < nextMarkUs) return false
        nextMarkUs += intervalUs
        return true
    }
}
