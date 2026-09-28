package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap

/** RF-10: un WebP animado no debe pesar más de 500 KB. */
const val ANIMATED_WEBP_TARGET_SIZE_BYTES = 500_000

/**
 * RF-11: un WebP estático no debe pesar más de 100 KB. Un sticker estático
 * es una animación de un solo fotograma: se codifica con el mismo
 * [WebpAnimEncoder], pasando `targetSizeBytes = STATIC_WEBP_TARGET_SIZE_BYTES`
 * y una lista de un elemento, en vez de con un codificador aparte. Ver el
 * KDoc de [WebpAnimEncoder] para el razonamiento completo.
 */
const val STATIC_WEBP_TARGET_SIZE_BYTES = 100_000

/**
 * Calidad de la primera pasada, sin bisección: medida en ADR-0006 como
 * suficiente para contenido representativo (59 672 de 500 000 bytes). No
 * es un valor a ajustar sin repetir esa medición.
 */
private const val FIRST_QUALITY = 75

/**
 * Piso de fotogramas (ADR-0016, reemplaza el piso de "5 fps" de ADR-0007):
 * una cantidad FIJA, no derivada de la duración del clip. Es el único
 * valor con validación de punta a punta — el caso de referencia de
 * ADR-0007 (3 s de RF-13, a 5 fps) y confirmado de nuevo en el barrido de
 * ADR-0016 (`docs/desarrollo/pruebas.md`): 15 fotogramas a 512×512/calidad
 * 0 caben con ~30% de margen.
 *
 * El piso de ADR-0007 escalaba con la duración
 * (`ceil(duración/1000×5)`): para 10 s daba 50 fotogramas, y ninguna
 * calidad hacía caber esa cantidad, ni siquiera 0 — la causa raíz del
 * fallo de RF-12 en clips largos que este ADR corrige. Reemplazarlo por
 * una cantidad fija solo (sin más cambios) resolvería el fallo pero
 * dejaría fotogramas fijos estirados sobre cualquier duración (15
 * fotogramas en 10 s son 1.5 fps, el mismo tipo de defecto que ADR-0007
 * corrigió para 3 s) — por eso este piso se combina con
 * [MIN_DURATION_MS_BEFORE_LAST_RESORT]: la fluidez de 5 fps (ADR-0007) se
 * preserva acortando la duración considerada antes de necesitar estirar
 * este piso sobre una duración más larga.
 */
private const val MIN_FRAME_COUNT_FLOOR = 15

/** Mínimo absoluto de última instancia (ADR-0016, Fase F): garantiza que siempre haya un resultado, aunque sea muy poco fluido. */
private const val ABSOLUTE_MIN_FRAME_COUNT = 2

/**
 * Duración mínima a la que ADR-0016 acorta un clip antes de sacrificar
 * fluidez por debajo de [MIN_FRAME_COUNT_FLOOR]: no baja de acá porque
 * [MIN_FRAME_COUNT_FLOOR] fotogramas a 5 fps (ADR-0007) son exactamente
 * 3 s — el mismo número que ya valida ADR-0007 de punta a punta, no uno
 * nuevo sin medir.
 */
private const val MIN_DURATION_MS_BEFORE_LAST_RESORT = 3_000

/** Resolución de codificación de producción: 512×512 exactos (RF-10/RF-11). */
private const val PRODUCTION_RESOLUTION = 512

/**
 * Único escalón de resolución degradada (ADR-0016). El barrido de
 * `docs/desarrollo/pruebas.md` midió que 320 no resuelve ningún caso que
 * 384 no resolviera ya, y que a calidad 75/50/25 320 pesa *más* que 384
 * (no monotónico, sin explicar) — por eso la escalera tiene un solo
 * escalón de resolución, no dos.
 */
private const val DEGRADED_RESOLUTION = 384

/**
 * RNF-08 (contenido de alta complejidad visual): tope dentro del cual esta
 * clase debe entregar el mejor resultado válido que encuentre, aunque no
 * sea óptimo. Una llamada JNI bloqueada no se puede interrumpir de verdad
 * desde Kotlin: no alcanza con comprobar el tope solo entre codificaciones,
 * porque para cuando una codificación termina ya es tarde para no haberla
 * empezado. Por eso, antes de lanzar cada una, [encode] estima su duración
 * a partir de la última medida con el mismo número de fotogramas y no la
 * lanza si el tiempo restante no alcanza.
 */
private const val HARD_TIME_LIMIT_MS = 20_000L

/**
 * En el piso de fotogramas, tras asegurar que la calidad mínima cabe, solo
 * tiene sentido seguir bisecando hacia arriba si ese resultado deja margen
 * real. Medido en dispositivo: calidad 0 a 15 fotogramas ocupó el 70% del
 * límite (348 516 de 500 000 bytes) y ninguna calidad superior cupo — los
 * intentos que lo intentaron (5 codificaciones más) no encontraron nada
 * mejor. No hay medición de dónde exactamente deja de valer la pena entre
 * 50% y 70%, así que se elige 50%, por debajo del único dato medido, como
 * margen conservador en vez de arriesgar seguir gastando tiempo cerca del
 * punto ya confirmado inútil.
 */
private const val UPWARD_SEARCH_MAX_OCCUPANCY_FRACTION = 0.5

/**
 * Escala [frames] a [resolution]×[resolution] y de vuelta a su tamaño
 * original antes de codificar (ADR-0016): el WebP final sigue midiendo lo
 * mismo que [frames] ya traía (512×512 en producción — RF-10/RF-11 son
 * exactos, no aproximados), lo que cambia es cuánta entropía real tiene la
 * imagen que se escala hacia arriba, y eso es lo que compra tamaño (medido
 * en `docs/desarrollo/pruebas.md`, mismo patrón que
 * `ComparisonStickerGeneratorTest`).
 */
private fun degradeResolution(frames: List<WebpFrame>, resolution: Int): List<WebpFrame> {
    if (frames.isEmpty()) return frames
    val originalSize = frames.first().bitmap.width
    if (originalSize == resolution) return frames
    return frames.map { frame ->
        val downscaled = Bitmap.createScaledBitmap(frame.bitmap, resolution, resolution, /* filter = */ true)
        val upscaled = Bitmap.createScaledBitmap(downscaled, originalSize, originalSize, /* filter = */ true)
        frame.copy(bitmap = upscaled)
    }
}

/**
 * Codifica una animación WebP, gastando el mínimo trabajo que el contenido
 * de entrada exija (ADR-0006), en una escalera de sacrificio de cuatro
 * escalones (ADR-0016, reemplaza el piso único de ADR-0007):
 *
 * 1. **Calidad** (75→50→25→0, sin cambios de ADR-0006/ADR-0007): una sola
 *    pasada a calidad fija primero; si no cabe, se reduce el número de
 *    fotogramas por estimación directa (nunca por debajo del piso fijo de
 *    [MIN_FRAME_COUNT_FLOOR]) y se bisecta calidad sobre ese número.
 * 2. **Resolución de codificación** ([DEGRADED_RESOLUTION], un solo
 *    escalón): si ni la calidad más baja alcanza a 512, se repite la
 *    bisección de calidad sobre los mismos fotogramas escalando a 384
 *    antes de codificar.
 * 3. **Duración**: si tampoco alcanza, se acorta el clip a
 *    [MIN_DURATION_MS_BEFORE_LAST_RESORT] (preservando los 5 fps de
 *    ADR-0007 sobre esa duración más corta, en vez de estirar el piso de
 *    fotogramas sobre la duración completa) y se repiten los escalones 1-2
 *    sobre ese tramo más corto.
 * 4. **Fluidez, último recurso**: si ni acortar la duración alcanza
 *    (ninguna medición encontró este caso todavía), se reduce el número de
 *    fotogramas por debajo del piso, hasta [ABSOLUTE_MIN_FRAME_COUNT], para
 *    garantizar que siempre haya un resultado (RF-12).
 *
 * No usa `minimize_size` (ADR-0018, cambia este punto de ADR-0006): medido
 * en contenido real, cuesta 1.8×-1.9× el tiempo de la codificación que ya
 * cupo por solo un 3-5% menos de tamaño — no vale la pena ese costo, y
 * aparte afecta la decisión de cuadro-clave-vs-diferencia por fotograma de
 * una forma que en la práctica generó artefactos visuales en WhatsApp. Un
 * tope duro de tiempo (RNF-08) acota cuánto
 * puede tardar el caso adverso, estimando la duración de cada codificación
 * antes de lanzarla en vez de solo comprobar el reloj después — y, dentro
 * de un mismo escalón, **estimando por proporción si un intento tiene
 * alguna chance antes de gastarlo**: la reproducción del fallo de RF-12 en
 * clips de 10 s encontró que la Fase de calidad, en el piso, nunca llegó a
 * correr porque un intento que la propia proporción ya indicaba
 * inviable (50 fotogramas a calidad 75, 1512% del límite) agotó el
 * presupuesto completo — ver `docs/desarrollo/pruebas.md` y ADR-0016.
 *
 * No decide de dónde salen los fotogramas ni cuántos hay: eso es
 * responsabilidad de quien llame (editor, captura de pantalla), fuera del
 * alcance de esta fase.
 *
 * **También codifica stickers estáticos (RF-11), no solo animados (RF-10):
 * un sticker estático es una animación de un solo fotograma**, codificada
 * con esta misma clase pasando `frames` de tamaño 1 y `targetSizeBytes =
 * [STATIC_WEBP_TARGET_SIZE_BYTES]`, en vez de con un codificador separado
 * (ADR-0002 ya fija libwebp como la única vía de codificación, no
 * `Bitmap.compress`). La razón de no tener dos codificadores es doble:
 * primero, con dos rutas de codificación habría dos comportamientos
 * distintos frente al límite de tamaño — esta clase ya resuelve ese ajuste
 * una sola vez, y duplicarlo para el caso estático es duplicar una fuente
 * de bugs, no ahorrar trabajo. Segundo, RF-12 ("el sistema debe ajustar
 * automáticamente calidad y fps hasta cumplir RF-10 y RF-11") describe un
 * solo comportamiento para ambos formatos.
 *
 * [singleShotEncoder] es sustituible en los tests para probar el ajuste sin
 * tocar la librería nativa; en la app real es [NativeWebpEncoder].
 * [resolutionDegrader] es sustituible por la misma razón: `degradeResolution`
 * usa `Bitmap.createScaledBitmap`, que no funciona fuera de un runtime
 * Android real (los tests JVM puros de este módulo usan bitmaps
 * simulados). [hardTimeLimitMs] es un parámetro, no una constante fija,
 * para poder probarlo en JUnit sin esperar segundos reales ni depender del
 * valor de producción exacto.
 */
class WebpAnimEncoder(
    private val singleShotEncoder: SingleShotWebpEncoder = NativeWebpEncoder,
    private val targetSizeBytes: Int = ANIMATED_WEBP_TARGET_SIZE_BYTES,
    private val hardTimeLimitMs: Long = HARD_TIME_LIMIT_MS,
    private val resolutionDegrader: (List<WebpFrame>, Int) -> List<WebpFrame> = ::degradeResolution,
) {

    /**
     * @param onAttempt se llama antes de cada codificación real, con cuánto
     * del presupuesto de [hardTimeLimitMs] ya se gastó — ver
     * [EncodeAttemptProgress]. El número total de intentos no se puede
     * anticipar (depende del contenido), pero
     * la fracción de tiempo gastado sí es información real para mostrar
     * avance, no un indicador indeterminado (RNF-08).
     * @throws WebpEncodeException si [frames] no cumple RF-13, o si no se
     * encontró ningún resultado dentro de [targetSizeBytes] antes de que el
     * tiempo restante dejara de alcanzar para otra codificación (RF-12).
     */
    fun encode(frames: List<WebpFrame>, onAttempt: (EncodeAttemptProgress) -> Unit = {}): WebpEncodeResult {
        if (frames.isEmpty()) {
            throw WebpEncodeException("Se necesita al menos 1 fotograma")
        }
        FrameTiming.validate(frames.map { it.durationMs })

        val requestedDurationMs = frames.sumOf { it.durationMs }.toLong()

        val startNanos = System.nanoTime()
        val deadlineNanos = startNanos + hardTimeLimitMs * 1_000_000L
        var bestBytes: ByteArray? = null
        var bestQuality: Int? = null
        var bestFrames: List<WebpFrame>? = null
        var attemptNumber = 0

        // Del último intento, haya cupido o no: a diferencia de bestBytes/
        // bestQuality/bestFrames (que solo se actualizan cuando el
        // resultado cabe), esto existe para poder describir, si al final
        // no hay ningún resultado válido, con qué se quedó más cerca (RF-12).
        var lastAttemptBytes: ByteArray? = null
        var lastAttemptQuality: Int? = null
        var lastAttemptFrameCount: Int? = null

        // Última duración medida (ms) por número de fotogramas: el mejor
        // estimador disponible para la próxima codificación con ese mismo
        // número, sin necesitar codificar de más solo para medir. Se
        // comparte entre resoluciones (no hay una entrada separada por
        // resolución): medido que la resolución degradada no tarda más que
        // la de producción, así que usar esta estimación para ambas es
        // conservador, nunca optimista.
        val lastDurationMsByFrameCount = mutableMapOf<Int, Long>()

        fun remainingMs() = (deadlineNanos - System.nanoTime()) / 1_000_000L

        fun estimatedTimeAllows(frameCount: Int): Boolean {
            val remaining = remainingMs()
            if (remaining <= 0) return false
            val lastDuration = lastDurationMsByFrameCount[frameCount] ?: return true
            return remaining >= lastDuration
        }

        fun attempt(candidateFrames: List<WebpFrame>, quality: Int, resolution: Int): ByteArray {
            attemptNumber++
            onAttempt(EncodeAttemptProgress(attemptNumber, (System.nanoTime() - startNanos) / 1_000_000L, hardTimeLimitMs))
            val encodeFrames = if (resolution == PRODUCTION_RESOLUTION) candidateFrames else resolutionDegrader(candidateFrames, resolution)
            val start = System.nanoTime()
            val bytes = singleShotEncoder.encode(encodeFrames, quality, minimizeSize = false)
            lastDurationMsByFrameCount[candidateFrames.size] = (System.nanoTime() - start) / 1_000_000L
            lastAttemptBytes = bytes
            lastAttemptQuality = quality
            lastAttemptFrameCount = candidateFrames.size
            if (bytes.size <= targetSizeBytes) {
                bestBytes = bytes
                bestQuality = quality
                bestFrames = candidateFrames
            }
            return bytes
        }

        /**
         * Bisección de calidad (ADR-0006/ADR-0007, sin cambios de fondo)
         * sobre [candidateFrames] a [resolution]. Si [seedBytes] no es
         * null, es el resultado ya medido de [FIRST_QUALITY] a esta misma
         * combinación (evita repetirlo). Si [atFloor], arranca por la
         * calidad mínima en vez de por el medio (ver KDoc de
         * [QualitySearch]) y no sigue hacia arriba sin margen real
         * ([UPWARD_SEARCH_MAX_OCCUPANCY_FRACTION]).
         */
        fun bisectQuality(candidateFrames: List<WebpFrame>, resolution: Int, atFloor: Boolean, seedBytes: ByteArray?) {
            if (bestBytes != null) return
            val search = QualitySearch(targetSizeBytes)
            var quality: Int?
            if (atFloor) {
                quality = if (estimatedTimeAllows(candidateFrames.size)) {
                    val bytes = attempt(candidateFrames, QualitySearch.MIN_QUALITY, resolution)
                    val fitsWithHeadroom = bytes.size <= targetSizeBytes * UPWARD_SEARCH_MAX_OCCUPANCY_FRACTION
                    val next = search.next(QualitySearch.MIN_QUALITY, bytes.size)
                    if (bytes.size <= targetSizeBytes && !fitsWithHeadroom) null else next
                } else {
                    null
                }
            } else {
                val bytes = seedBytes ?: attempt(candidateFrames, FIRST_QUALITY, resolution)
                quality = if (bytes.size <= targetSizeBytes) null else search.next(FIRST_QUALITY, bytes.size)
            }
            while (quality != null && estimatedTimeAllows(candidateFrames.size)) {
                val bytes = attempt(candidateFrames, quality, resolution)
                quality = search.next(quality, bytes.size)
            }
        }

        /**
         * Escalones 1-2 (calidad + resolución) sobre [candidateFrames]. Si
         * [allowShorten] y la proporción de la Fase 2 clampa contra el piso
         * fijo (indicio de que ni la calidad más baja sostendría los 5 fps
         * de ADR-0007 sobre la duración de [candidateFrames]) mientras esa
         * duración todavía supera [MIN_DURATION_MS_BEFORE_LAST_RESORT], no
         * se acepta el piso estirado sobre esa duración larga (fluidez peor
         * que 5 fps real) — se acorta la duración primero (escalón 3,
         * ADR-0016: duración se sacrifica antes que fluidez) y se repiten
         * los escalones 1-2 sobre ese tramo más corto. La misma redirección
         * aplica si, sin haber clampado, esta pasada agota calidad y
         * resolución sin encontrar nada en absoluto — la reacción, no solo
         * la anticipación, también prefiere acortar antes de rendirse a la
         * duración actual. No hace nada si ya hay un resultado válido de un
         * escalón anterior (ADR-0006: mínimo trabajo necesario).
         *
         * @return `true` si encontró un resultado válido (en [bestBytes]).
         */
        fun runQualityAndResolutionLadder(candidateFrames: List<WebpFrame>, allowShorten: Boolean): Boolean {
            if (bestBytes != null) return true
            if (!estimatedTimeAllows(candidateFrames.size)) return false

            fun shorten(): Boolean {
                val trimmedIndices = FrameTiming.trimToDuration(frames.map { it.durationMs }, MIN_DURATION_MS_BEFORE_LAST_RESORT)
                val trimmedFrames = trimmedIndices.map { frames[it] }
                return runQualityAndResolutionLadder(trimmedFrames, allowShorten = false)
            }

            var working = candidateFrames
            var bytes = attempt(working, FIRST_QUALITY, PRODUCTION_RESOLUTION)

            if (bytes.size > targetSizeBytes && working.size > MIN_FRAME_COUNT_FLOOR) {
                val ratio = targetSizeBytes.toDouble() / bytes.size
                val rawEstimate = (working.size * ratio).toInt()
                val totalDurationMs = working.sumOf { it.durationMs }
                if (rawEstimate < MIN_FRAME_COUNT_FLOOR && allowShorten && totalDurationMs > MIN_DURATION_MS_BEFORE_LAST_RESORT) {
                    return shorten()
                }
                val estimatedCount = rawEstimate.coerceIn(MIN_FRAME_COUNT_FLOOR, working.size - 1)
                if (estimatedTimeAllows(estimatedCount)) {
                    val reduced = FrameTiming.reduceTo(working.map { it.durationMs }, estimatedCount)
                    working = reduced.map { (originalIndex, duration) -> working[originalIndex].copy(durationMs = duration) }
                    bytes = if (rawEstimate >= MIN_FRAME_COUNT_FLOOR) {
                        attempt(working, FIRST_QUALITY, PRODUCTION_RESOLUTION)
                    } else {
                        // ADR-0016 (corrección de diseño): la propia
                        // proporción ya dice que ni el piso alcanzaría a
                        // calidad 75 — llegamos acá porque no se pudo
                        // acortar más la duración (ya está en el mínimo, o
                        // ya se acortó una vez), no porque haya alguna
                        // chance de que este intento cambie algo. No
                        // malgastarlo: `bytes` se deja con el valor de más
                        // arriba (a otro frameCount, sigue sin caber), que
                        // bisectQuality no usa como semilla en la rama
                        // atFloor de todas formas.
                        bytes
                    }
                }
            }

            if (bestBytes != null) return true
            val atFloor = working.size <= MIN_FRAME_COUNT_FLOOR
            bisectQuality(working, PRODUCTION_RESOLUTION, atFloor, seedBytes = if (atFloor) null else bytes)

            if (bestBytes != null) return true
            if (estimatedTimeAllows(working.size)) {
                // Escalón de resolución (ADR-0016): a esta altura ya se
                // sabe, por el escalón de calidad recién hecho, que ni la
                // calidad mínima alcanzó a 512 — probar calidad 75 de nuevo
                // a 384 repetiría un intento con la misma conclusión casi
                // segura (medido: 384 pesa ~70-75% de 512 a la misma
                // calidad, nunca alcanza a cerrar una diferencia de
                // cientos de por ciento). Se entra directo por calidad
                // mínima.
                bisectQuality(working, DEGRADED_RESOLUTION, atFloor = true, seedBytes = null)
            }

            if (bestBytes != null) return true
            if (allowShorten && candidateFrames.sumOf { it.durationMs } > MIN_DURATION_MS_BEFORE_LAST_RESORT) {
                return shorten()
            }
            return false
        }

        runQualityAndResolutionLadder(frames, allowShorten = true)

        // Fase F, red de seguridad final (ADR-0016): ninguna medición
        // encontró un caso que llegue hasta acá — el peor contenido
        // probado ya resuelve en un escalón anterior — pero RF-12 exige
        // que siempre haya una salida. Un solo intento al mínimo absoluto,
        // a la calidad y resolución más baratas medidas, sobre el tramo ya
        // acortado: no hay datos para justificar una búsqueda más fina en
        // esta zona.
        if (bestBytes == null) {
            val trimmedIndices = FrameTiming.trimToDuration(frames.map { it.durationMs }, MIN_DURATION_MS_BEFORE_LAST_RESORT)
            val trimmedFrames = trimmedIndices.map { frames[it] }
            if (estimatedTimeAllows(ABSOLUTE_MIN_FRAME_COUNT)) {
                val lastResortCount = ABSOLUTE_MIN_FRAME_COUNT.coerceAtMost(trimmedFrames.size)
                val reduced = FrameTiming.reduceTo(trimmedFrames.map { it.durationMs }, lastResortCount)
                val lastResortFrames = reduced.map { (originalIndex, duration) -> trimmedFrames[originalIndex].copy(durationMs = duration) }
                attempt(lastResortFrames, QualitySearch.MIN_QUALITY, DEGRADED_RESOLUTION)
            }
        }

        val finalBytes = bestBytes
            ?: run {
                val lastSize = lastAttemptBytes?.size
                val overBy = lastSize?.minus(targetSizeBytes)
                val overByText = if (overBy != null && overBy > 0) {
                    val overByPercent = overBy * 100.0 / targetSizeBytes
                    " ($overBy bytes por encima del límite, %.1f%% de exceso)".format(java.util.Locale.US, overByPercent)
                } else {
                    ""
                }
                throw WebpEncodeException(
                    "RF-12: no se pudo producir un WebP de $targetSizeBytes bytes o menos " +
                        "para un clip de ${requestedDurationMs}ms (${frames.size} fotogramas de entrada, " +
                        "piso de fotogramas=$MIN_FRAME_COUNT_FLOOR por ADR-0016). Último intento (#$attemptNumber, " +
                        "de $attemptNumber en total): frameCount=$lastAttemptFrameCount " +
                        "quality=$lastAttemptQuality sizeBytes=$lastSize$overByText, dentro de " +
                        "${hardTimeLimitMs}ms de presupuesto.",
                )
            }
        val finalQuality = checkNotNull(bestQuality)
        val finalFrames = checkNotNull(bestFrames)

        return WebpEncodeResult(
            bytes = finalBytes,
            quality = finalQuality,
            frameCount = finalFrames.size,
            frameDurationsMs = finalFrames.map { it.durationMs },
            requestedDurationMs = requestedDurationMs,
        )
    }
}

data class WebpEncodeResult(
    val bytes: ByteArray,
    val quality: Int,
    val frameCount: Int,
    val frameDurationsMs: List<Int>,
    /**
     * Duración total de los fotogramas de entrada, antes de cualquier
     * acortamiento (ADR-0016). Comparar contra la suma de
     * [frameDurationsMs] (la duración final, ya codificada) es lo que le
     * permite a la interfaz avisar cuánto se acortó — ver
     * `ConvertPreviewSaveScreen`.
     */
    val requestedDurationMs: Long,
) {
    /** Cuánto se acortó la animación frente a lo pedido; `0` si no se acortó (ADR-0016). */
    val shortenedByMs: Long
        get() = (requestedDurationMs - frameDurationsMs.sumOf { it.toLong() }).coerceAtLeast(0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebpEncodeResult) return false
        return bytes.contentEquals(other.bytes) &&
            quality == other.quality &&
            frameCount == other.frameCount &&
            frameDurationsMs == other.frameDurationsMs &&
            requestedDurationMs == other.requestedDurationMs
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + quality
        result = 31 * result + frameCount
        result = 31 * result + frameDurationsMs.hashCode()
        result = 31 * result + requestedDurationMs.hashCode()
        return result
    }
}
