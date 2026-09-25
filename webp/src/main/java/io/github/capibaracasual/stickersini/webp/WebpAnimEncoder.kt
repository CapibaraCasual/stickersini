package io.github.capibaracasual.stickersini.webp

import kotlin.math.ceil

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
 * Piso de fotogramas por segundo bajo el cual la reducción de fotogramas
 * (ADR-0007) no debe bajar: sin él, contenido adverso podía terminar en 1
 * fps (3 de 30 fotogramas), técnicamente dentro de RF-10 pero ya no una
 * animación. Por debajo del piso, lo que se ajusta es la calidad, no el
 * número de fotogramas.
 */
private const val MIN_FRAMES_PER_SECOND = 5

/** Mínimo absoluto para clips tan cortos que el piso de fps daría menos. */
private const val MIN_FRAMES_AFTER_REDUCTION = 2

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
 * `minimize_size` solo se prueba si el mejor resultado ya válido ocupa al
 * menos esta fracción de [ANIMATED_WEBP_TARGET_SIZE_BYTES]. ADR-0006 lo
 * justifica con una medición: el beneficio de `minimize_size` fue nulo o
 * marginal (≤0.1%) en los dos contenidos medidos, a 1.7×-2× el costo en
 * tiempo — no vale la pena pagarlo cuando ya hay margen de sobra.
 */
private const val CLOSE_TO_LIMIT_FRACTION = 0.8

/**
 * En el piso de fotogramas (ADR-0007), tras asegurar que la calidad mínima
 * cabe, solo tiene sentido seguir bisecando hacia arriba si ese resultado
 * deja margen real. Medido en dispositivo: calidad 0 a 15 fotogramas ocupó
 * el 70% del límite (348 516 de 500 000 bytes) y ninguna calidad superior
 * cupo — los intentos que lo intentaron (5 codificaciones más) no
 * encontraron nada mejor. No hay medición de dónde exactamente deja de
 * valer la pena entre 50% y 70%, así que se elige 50%, por debajo del
 * único dato medido, como margen conservador en vez de arriesgar seguir
 * gastando tiempo cerca del punto ya confirmado inútil.
 */
private const val UPWARD_SEARCH_MAX_OCCUPANCY_FRACTION = 0.5

/**
 * Codifica una animación WebP, gastando el mínimo trabajo que el contenido
 * de entrada exija (ADR-0006): una sola pasada a calidad fija primero: con
 * contenido representativo, ya cabe y no hace falta nada más. Solo si no
 * cabe se reduce el número de fotogramas (una vez, por estimación directa,
 * nunca por debajo del piso de fps de ADR-0007) y, si tampoco basta, se
 * bisecta la calidad sobre ese número de fotogramas ya fijado — desde la
 * calidad mínima primero si ya se está en el piso (ver el KDoc de
 * [QualitySearch]), siguiendo hacia arriba solo si esa calidad mínima deja
 * margen real, desde una calidad alta en cualquier otro caso.
 * `minimize_size` se reserva para cuando el resultado ya válido queda
 * cerca del límite de RF-10. Un tope duro de tiempo (RNF-08) acota cuánto
 * puede tardar el caso adverso, estimando la duración de cada codificación
 * antes de lanzarla en vez de solo comprobar el reloj después.
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
 * una sola vez (fases 1 a 4 de este KDoc), y duplicarlo para el caso
 * estático es duplicar una fuente de bugs, no ahorrar trabajo. Segundo,
 * RF-12 ("el sistema debe ajustar automáticamente calidad y fps hasta
 * cumplir RF-10 y RF-11") describe un solo comportamiento para ambos
 * formatos: si el estático tuviera su propio codificador, RF-12 se
 * cumpliría en un formato y no en el otro salvo que alguien se acuerde de
 * implementarlo dos veces. El costo de contenedor de `WebPAnimEncoder`
 * frente a un WebP estático "de verdad" (`Bitmap.compress`) se midió antes
 * de tomar esta decisión — ver `docs/desarrollo/pruebas.md`, Fase 2
 * (RF-03) — y resultó despreciable frente al límite de 100 KB de RF-11.
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
     * @param onAttempt se llama antes de cada codificación real (no antes de
     * la de `minimize_size` final), con cuánto del presupuesto de
     * [hardTimeLimitMs] ya se gastó — ver [EncodeAttemptProgress]. El número
     * total de intentos no se puede anticipar (depende del contenido), pero
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

        val startNanos = System.nanoTime()
        val deadlineNanos = startNanos + hardTimeLimitMs * 1_000_000L
        var bestBytes: ByteArray? = null
        var bestQuality: Int? = null
        var bestFrames: List<WebpFrame>? = null
        var attemptNumber = 0

        // Última duración medida (ms) por número de fotogramas: el mejor
        // estimador disponible para la próxima codificación con ese mismo
        // número, sin necesitar codificar de más solo para medir.
        val lastDurationMsByFrameCount = mutableMapOf<Int, Long>()

        fun remainingMs() = (deadlineNanos - System.nanoTime()) / 1_000_000L

        // Sin una duración medida para este número de fotogramas todavía,
        // no hay con qué estimar: se permite el intento (es, como mucho,
        // el primero de ese tamaño). Con una duración medida, no se lanza
        // si el tiempo restante no alcanza para ella.
        fun estimatedTimeAllows(frameCount: Int): Boolean {
            val remaining = remainingMs()
            if (remaining <= 0) return false
            val lastDuration = lastDurationMsByFrameCount[frameCount] ?: return true
            return remaining >= lastDuration
        }

        fun attempt(candidateFrames: List<WebpFrame>, quality: Int): ByteArray {
            attemptNumber++
            onAttempt(EncodeAttemptProgress(attemptNumber, (System.nanoTime() - startNanos) / 1_000_000L, hardTimeLimitMs))
            val start = System.nanoTime()
            val bytes = singleShotEncoder.encode(candidateFrames, quality, minimizeSize = false)
            lastDurationMsByFrameCount[candidateFrames.size] = (System.nanoTime() - start) / 1_000_000L
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
        // Nunca baja del piso de fps (ADR-0007): por debajo de él, lo que
        // se ajusta a continuación es la calidad (fase 3), no fotogramas.
        val totalDurationMs = currentFrames.sumOf { it.durationMs }
        val frameFloor = ceil(totalDurationMs / 1000.0 * MIN_FRAMES_PER_SECOND).toInt()
            .coerceAtLeast(MIN_FRAMES_AFTER_REDUCTION)
        if (bytes.size > targetSizeBytes && currentFrames.size > frameFloor) {
            val ratio = targetSizeBytes.toDouble() / bytes.size
            val estimatedCount = (currentFrames.size * ratio).toInt()
                .coerceIn(frameFloor, currentFrames.size - 1)
            if (estimatedTimeAllows(estimatedCount)) {
                val reduced = FrameTiming.reduceTo(currentFrames.map { it.durationMs }, estimatedCount)
                currentFrames = reduced.map { (originalIndex, duration) ->
                    currentFrames[originalIndex].copy(durationMs = duration)
                }
                bytes = attempt(currentFrames, FIRST_QUALITY)
            }
        }

        // Fase 3: solo si ni la calidad fija ni la reducción de fotogramas
        // bastaron, bisección de calidad sobre el número de fotogramas ya
        // decidido en la fase 2 (o el original, si no hubo fase 2).
        if (bytes.size > targetSizeBytes) {
            val search = QualitySearch(targetSizeBytes)
            val nextFromTop = search.next(FIRST_QUALITY, bytes.size)
            val atFloor = currentFrames.size <= frameFloor

            // En el piso de fotogramas, probar la calidad mínima primero
            // en vez del punto medio habitual: ver el KDoc de
            // QualitySearch para el razonamiento completo.
            var quality: Int? = if (atFloor) QualitySearch.MIN_QUALITY else nextFromTop

            if (atFloor) {
                quality = if (estimatedTimeAllows(currentFrames.size)) {
                    bytes = attempt(currentFrames, QualitySearch.MIN_QUALITY)
                    val fitsWithHeadroom = bytes.size <= targetSizeBytes * UPWARD_SEARCH_MAX_OCCUPANCY_FRACTION
                    val next = search.next(QualitySearch.MIN_QUALITY, bytes.size)
                    // Si la calidad mínima ya cupo pero sin margen real,
                    // no vale la pena seguir bisecando hacia arriba (ver
                    // UPWARD_SEARCH_MAX_OCCUPANCY_FRACTION): se corta acá
                    // aunque el propio QualitySearch sugiera seguir. Si no
                    // cupo, `next` ya es null por sí solo (no hay
                    // solución a este número de fotogramas).
                    if (bytes.size <= targetSizeBytes && !fitsWithHeadroom) null else next
                } else {
                    null
                }
            }

            while (quality != null && estimatedTimeAllows(currentFrames.size)) {
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
        val bytesToReturn = if (closeToLimit && estimatedTimeAllows(finalFrames.size)) {
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
