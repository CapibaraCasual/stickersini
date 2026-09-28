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
 * sin encontrar ningún resultado válido. ADR-0009 reemplazó ese valor por
 * el piso de ADR-0007 (5 fps), conservador a propósito por falta de
 * margen medido.
 *
 * ADR-0012 subió este valor a 8, una vez que ADR-0011 (conversión YUV→RGB
 * nativa) liberó margen de tiempo real. **ADR-0018 lo sube a 10**, tras
 * sacar la fase de `minimize_size` de `WebpAnimEncoder` (ADR-0006/ADR-0018:
 * medido en contenido real, costaba 1.8×-1.9× el tiempo de encode por un
 * 3-5% de tamaño, y generaba artefactos visuales en WhatsApp) — sin esa
 * fase, 8 fps pasó a tener 64.6% de margen contra RNF-08 en el tramo de
 * 10 s, margen de sobra para subir. 10 es el fps más alto que cumple de
 * forma confiable, contando el peor caso de 5 corridas, en las tres
 * duraciones medidas (3, 5 y 10 s): 12 fps ya rompe el tramo de 10 s en
 * 5 de 5 corridas (necesita 3 codificaciones completas para 120→95
 * fotogramas, un costo que sacar `minimize_size` no toca), y 15 fps no
 * cabe en el límite de tamaño de RF-10 a duración completa, así que
 * ADR-0016 lo acorta a ~3 s en vez de cumplir a 10 s. Un solo valor, no
 * dependiente de la duración del clip: no hay razón de producto para que
 * un sticker corto se vea peor que uno largo.
 *
 * El margen del tramo de 10 s a 10 fps (4.2% peor caso) es el más ajustado
 * que haya llegado a producción hasta ahora — medido en un solo
 * dispositivo (Xiaomi Redmi Note 14): un teléfono más lento podría no
 * sostenerlo. Este valor no se toca sin repetir esta misma medición.
 */
const val VIDEO_PREFILTER_TARGET_FPS = 10

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
