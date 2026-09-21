package io.github.capibaracasual.stickersini.webp

/** RF-10: un WebP animado no debe pesar más de 500 KB. */
const val ANIMATED_WEBP_TARGET_SIZE_BYTES = 500_000

/**
 * Calidad de la primera pasada, sin bisección: medida en ADR-0006 como
 * suficiente para contenido representativo (59 672 de 500 000 bytes). No
 * es un valor a ajustar sin repetir esa medición.
 */
private const val FIRST_QUALITY = 75

/** Por debajo de esto ya no tiene sentido seguir quitando fotogramas. */
private const val MIN_FRAMES_AFTER_REDUCTION = 2

/**
 * RNF-08 (contenido de alta complejidad visual): tope dentro del cual esta
 * clase debe entregar el mejor resultado válido que encuentre, aunque no
 * sea óptimo. Una llamada JNI bloqueada no se puede interrumpir de verdad
 * desde Kotlin, así que el tope se comprueba entre codificaciones, no
 * dentro de una: si la que está en curso cuando se cumple el tope es en sí
 * misma larga, corre hasta terminar de todas formas.
 */
private const val HARD_TIME_LIMIT_MS = 20_000L

/**
 * `minimize_size` solo se prueba si el mejor resultado ya válido ocupa al
 * menos esta fracción de [ANIMATED_WEBP_TARGET_SIZE_BYTES]. ADR-0006 lo
 * justifica con una medición: el beneficio de `minimize_size` fue nulo o
 * marginal (≤0.1%) en los dos contenidos medidos, a 1.7×-2× el costo en
 * tiempo — no vale la pena pagarlo cuando ya hay margen de sobra.
 */
private const val CLOSE_TO_LIMIT_FRACTION = 0.8

/**
 * Codifica una animación WebP, gastando el mínimo trabajo que el contenido
 * de entrada exija (ADR-0006): una sola pasada a calidad fija primero: con
 * contenido representativo, ya cabe y no hace falta nada más. Solo si no
 * cabe se reduce el número de fotogramas (una vez, por estimación directa)
 * y, si tampoco basta, se bisecta la calidad. `minimize_size` se reserva
 * para cuando el resultado ya válido queda cerca del límite de RF-10. Un
 * tope duro de tiempo (RNF-08) acota cuánto puede tardar el caso adverso.
 *
 * No decide de dónde salen los fotogramas ni cuántos hay: eso es
 * responsabilidad de quien llame (editor, captura de pantalla), fuera del
 * alcance de esta fase.
 *
 * [singleShotEncoder] es sustituible en los tests para probar el ajuste sin
 * tocar la librería nativa; en la app real es [NativeWebpEncoder].
 * [hardTimeLimitMs] y [closeToLimitFraction] son parámetros, no constantes
 * fijas, para poder probar ambos límites en JUnit sin esperar segundos
 * reales ni depender del valor de producción exacto.
 */
class WebpAnimEncoder(
    private val singleShotEncoder: SingleShotWebpEncoder = NativeWebpEncoder,
    private val targetSizeBytes: Int = ANIMATED_WEBP_TARGET_SIZE_BYTES,
    private val hardTimeLimitMs: Long = HARD_TIME_LIMIT_MS,
    private val closeToLimitFraction: Double = CLOSE_TO_LIMIT_FRACTION,
) {

    /**
     * @throws WebpEncodeException si [frames] no cumple RF-13, o si no se
     * encontró ningún resultado dentro de [targetSizeBytes] antes de que se
     * cumpliera [hardTimeLimitMs] (RF-12).
     */
    fun encode(frames: List<WebpFrame>): WebpEncodeResult {
        if (frames.isEmpty()) {
            throw WebpEncodeException("Se necesita al menos 1 fotograma")
        }
        FrameTiming.validate(frames.map { it.durationMs })

        val deadlineNanos = System.nanoTime() + hardTimeLimitMs * 1_000_000L
        var bestBytes: ByteArray? = null
        var bestQuality: Int? = null
        var bestFrames: List<WebpFrame>? = null

        fun stillHaveTime() = System.nanoTime() < deadlineNanos

        fun attempt(candidateFrames: List<WebpFrame>, quality: Int): ByteArray {
            val bytes = singleShotEncoder.encode(candidateFrames, quality, minimizeSize = false)
            if (bytes.size <= targetSizeBytes) {
                bestBytes = bytes
                bestQuality = quality
                bestFrames = candidateFrames
            }
            return bytes
        }

        // Fase 1: una sola pasada a calidad fija. Con contenido
        // representativo esto ya cabe (ver ADR-0006) y no hay razón para
        // pagar el costo de una búsqueda que no hace falta.
        var currentFrames = frames
        var bytes = attempt(currentFrames, FIRST_QUALITY)

        // Fase 2: si no cupo, un único ajuste de fotogramas por
        // proporción (no bisección): la relación entre el tamaño obtenido
        // y el límite estima directamente cuántos fotogramas hacen falta.
        if (bytes.size > targetSizeBytes && currentFrames.size > MIN_FRAMES_AFTER_REDUCTION && stillHaveTime()) {
            val ratio = targetSizeBytes.toDouble() / bytes.size
            val estimatedCount = (currentFrames.size * ratio).toInt()
                .coerceIn(MIN_FRAMES_AFTER_REDUCTION, currentFrames.size - 1)
            val reduced = FrameTiming.reduceTo(currentFrames.map { it.durationMs }, estimatedCount)
            currentFrames = reduced.map { (originalIndex, duration) ->
                currentFrames[originalIndex].copy(durationMs = duration)
            }
            bytes = attempt(currentFrames, FIRST_QUALITY)
        }

        // Fase 3: solo si ni la calidad fija ni la reducción de fotogramas
        // bastaron, bisección de calidad sobre el número de fotogramas ya
        // decidido en la fase 2 (o el original, si no hubo fase 2).
        if (bytes.size > targetSizeBytes) {
            val search = QualitySearch(targetSizeBytes)
            var quality = search.next(FIRST_QUALITY, bytes.size)
            while (quality != null && stillHaveTime()) {
                bytes = attempt(currentFrames, quality)
                quality = search.next(quality, bytes.size)
            }
        }

        val finalBytes = bestBytes
            ?: throw WebpEncodeException(
                "RF-12: no se pudo producir un WebP de $targetSizeBytes bytes o menos " +
                    "ni bajando calidad ni reduciendo fotogramas dentro de ${hardTimeLimitMs}ms",
            )
        val finalQuality = checkNotNull(bestQuality)
        val finalFrames = checkNotNull(bestFrames)

        // Fase 4: minimize_size solo si el resultado ya válido queda cerca
        // del límite (ADR-0006): es la única pasada que puede pagar su
        // costo extra sin desviarse de RNF-08 cuando no hace falta.
        val closeToLimit = finalBytes.size >= targetSizeBytes * closeToLimitFraction
        val bytesToReturn = if (closeToLimit && stillHaveTime()) {
            val minimized = singleShotEncoder.encode(finalFrames, finalQuality, minimizeSize = true)
            if (minimized.size <= targetSizeBytes) minimized else finalBytes
        } else {
            finalBytes
        }

        return WebpEncodeResult(
            bytes = bytesToReturn,
            quality = finalQuality,
            frameCount = finalFrames.size,
            frameDurationsMs = finalFrames.map { it.durationMs },
        )
    }
}

data class WebpEncodeResult(
    val bytes: ByteArray,
    val quality: Int,
    val frameCount: Int,
    val frameDurationsMs: List<Int>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebpEncodeResult) return false
        return bytes.contentEquals(other.bytes) &&
            quality == other.quality &&
            frameCount == other.frameCount &&
            frameDurationsMs == other.frameDurationsMs
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + quality
        result = 31 * result + frameCount
        result = 31 * result + frameDurationsMs.hashCode()
        return result
    }
}
