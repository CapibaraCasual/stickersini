package io.github.capibaracasual.stickersini.media

/**
 * RF-06: el sticker no puede salir de un tramo de origen más largo que
 * esto. Bajó de 10 s a 5 s en ADR-0019 (decisión de producto, igual que
 * Sticker.ly): no es una sugerencia de UI, `ui/TrimScreen.kt` usa este
 * mismo valor para acotar el `RangeSlider` — no se puede elegir un tramo
 * más largo, no solo se trunca después.
 */
const val MAX_CLIP_DURATION_MS = 5_000L

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
 * nativa) liberó margen de tiempo real. ADR-0018 lo subió a 10 tras sacar
 * la fase de `minimize_size` de `WebpAnimEncoder` (ADR-0006/ADR-0018),
 * midiendo contra un `.so` de **debug** (sin optimizar, `-O0`) — sin
 * saberlo en ese momento. ADR-0019 corrige la medición (contra release,
 * `-O2`) y lo sube a 20, con RF-06 acotado a 5 s (decisión de producto,
 * igual que Sticker.ly).
 *
 * **ADR-0021 lo baja de 20 a 15**, sin tocar RF-06 ni la prioridad de
 * fps/duración sobre calidad (ADR-0020): con la escalera de ADR-0020, fps
 * y duración solo ceden como último recurso, así que el fps de prefiltro
 * decide cuánto margen de tamaño le queda a calidad+resolución antes de
 * necesitar ese último recurso — 20 fps deja poco margen (73.0% del
 * límite de RF-10 con el contenido de referencia, a calidad máxima); 15
 * deja bastante más (52.7%, ya medido en ADR-0019) sin perder fluidez
 * perceptible frente a 20. Ese margen es lo que habilita el paso final
 * de subir calidad hasta ~95% de RF-10 (ver KDoc de `WebpAnimEncoder`):
 * a 20 fps casi no quedaba margen para ese paso; a 15, sí.
 *
 * **Lección de método (ver también CLAUDE.md):** toda medición hasta
 * ADR-0018 se hizo contra un `.so` nativo de debug (compilado sin
 * optimizar) — el mismo contenido codifica 5.9×-6.6× más rápido contra el
 * `.so` de release (`-O2`, confirmado en `compile_commands.json`). Una
 * decisión de presupuesto de tiempo (RNF-08) medida en debug da un techo
 * mucho más bajo que el real.
 */
const val VIDEO_PREFILTER_TARGET_FPS = 15

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
