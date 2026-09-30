package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
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
 * es un valor a ajustar sin repetir esa medición. `Float` desde ADR-0022
 * (`WebPConfig.quality` es un `float` de verdad — bisecar solo enteros
 * deja resultados lejos del objetivo de [TARGET_UPPER_OCCUPANCY_FRACTION]
 * cuando dos calidades enteras consecutivas difieren mucho en tamaño,
 * medido en dispositivo real: 2.5×-2.6× de salto entre 90 y 91 en el
 * video de referencia, ver `docs/desarrollo/pruebas.md`).
 */
private const val FIRST_QUALITY = 75f

/**
 * Piso de fps (ADR-0020, reemplaza el piso de cantidad fija de fotogramas
 * de ADR-0016): decisión de producto — fps y duración solo ceden como
 * último recurso, después de agotar calidad y resolución sobre el
 * fotograma completo que pidió el usuario. Cuando ese último recurso hace
 * falta, no se baja de 12 fps: es una tasa, no una cantidad fija, a
 * propósito — a diferencia del piso de ADR-0007 (5 fps, que se rompía al
 * estirarse sobre duraciones distintas a la medida), acá la duración ya
 * está acotada por RF-06 (5 s desde ADR-0019) antes de llegar a este
 * escalón, así que la tasa nunca se aplica sobre una duración sin medir.
 */
private const val MIN_FPS_FLOOR = 12

/** Mínimo absoluto de última instancia (ADR-0016, Fase F, sin cambios de ADR-0020): garantiza que siempre haya un resultado, aunque sea muy poco fluido. */
private const val ABSOLUTE_MIN_FRAME_COUNT = 2

/**
 * Duración mínima a la que se acorta un clip en el último-último recurso
 * (ADR-0020, después de agotar calidad, resolución y el piso de fps):
 * mismo valor que fijó ADR-0016, ahora reinterpretado como el mínimo que
 * sigue leyéndose como animación (coincide con lo que las implementaciones
 * equivalentes revisadas usan por defecto), no como una consecuencia
 * aritmética de un piso de fotogramas.
 */
private const val MIN_DURATION_MS_BEFORE_LAST_RESORT = 3_000

/** Resolución de codificación de producción: 512×512 exactos (RF-10/RF-11). */
private const val PRODUCTION_RESOLUTION = 512

/**
 * Único escalón de resolución degradada (ADR-0016, sin cambios de
 * ADR-0020). El barrido de `docs/desarrollo/pruebas.md` midió que 320 no
 * resuelve ningún caso que 384 no resolviera ya, y que a calidad 75/50/25
 * 320 pesa *más* que 384 (no monotónico, sin explicar) — por eso la
 * escalera tiene un solo escalón de resolución, no dos.
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
 * Ocupación objetivo del límite de tamaño (ADR-0021): decisión de
 * producto — no alcanza con que un resultado quepa, hay que usar el
 * tamaño disponible para dar la mayor calidad posible. Cualquier calidad
 * que ya cupo se acepta como definitiva en cuanto llega a esta ocupación
 * (o más), en vez de seguir bisecando para acercarse más al límite exacto
 * — subir de ~95% a, por ejemplo, 99% cuesta intentos adicionales por una
 * mejora marginal de calidad, y arriesga más quedar del lado equivocado
 * si el contenido varía apenas entre corridas.
 *
 * Reemplaza el criterio de ADR-0016/ADR-0020 (`0.5`, "no seguir bisecando
 * sin margen real"): aquel criterio paraba de buscar apenas se superaba el
 * 50% de ocupación, dejando calidad sin usar en casos con contenido
 * liviano (el video de referencia, por ejemplo, cabía con calidad 75 a
 * 11-24% del límite — mucho margen sin aprovechar). Con ADR-0021 (fps de
 * prefiltro bajado a 15, que deja más margen de tamaño) se decidió usar
 * ese margen para calidad en vez de dejarlo sin tocar.
 */
private const val TARGET_UPPER_OCCUPANCY_FRACTION = 0.95f

/**
 * Cuántos fotogramas hacen falta para no bajar de [MIN_FPS_FLOOR] sobre
 * [durationMs] de clip. Es una tasa, no una cantidad fija (ADR-0020): a
 * diferencia del piso fijo de ADR-0016 (que se rompía al estirarse sobre
 * una duración distinta a la única medida), acá la duración de entrada ya
 * está acotada por RF-06/ADR-0019 (5 s) antes de llegar a este escalón, así
 * que la tasa nunca se aplica fuera del rango medido.
 */
private fun minFrameCountForFpsFloor(durationMs: Long): Int =
    ceil(durationMs / 1000.0 * MIN_FPS_FLOOR).toInt().coerceAtLeast(1)

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
 * escalones (ADR-0020, reemplaza ADR-0016 — cambia el orden y qué se
 * sacrifica primero, no la idea de una escalera):
 *
 * 1. **Calidad**: una sola pasada a [FIRST_QUALITY] primero sobre el
 *    fotograma COMPLETO que llegó (nunca se reduce fotogramas ni duración
 *    en este escalón). Si no cabe, salta directo al piso (0, ver
 *    [QualitySearch]) en vez de bisecar desde [FIRST_QUALITY] hacia abajo
 *    — sobre el fotograma completo de producción, bisecar desde
 *    [FIRST_QUALITY] costaría hasta 7 codificaciones caras solo para
 *    confirmar que ninguna calidad alcanza (medido: >30 s en el peor caso,
 *    rompiendo RNF-08 antes de llegar siquiera al escalón de fps —
 *    corrección de ADR-0020 sobre su propio diseño inicial, ver
 *    `docs/desarrollo/pruebas.md`). Si SÍ cabe (a [FIRST_QUALITY] o al
 *    piso), sigue bisecando hacia arriba **hasta usar
 *    [TARGET_UPPER_OCCUPANCY_FRACTION] del límite de tamaño** (ADR-0021,
 *    decisión de producto: dar la mayor calidad que el tamaño disponible
 *    permita, no conformarse con la primera que quepa).
 * 2. **Resolución de codificación** ([DEGRADED_RESOLUTION], un solo
 *    escalón): si ni la calidad más baja alcanza a 512, se repite la
 *    bisección de calidad sobre los mismos fotogramas escalando a 384
 *    antes de codificar. Fps y duración siguen sin tocarse.
 * 3. **Piso de fps** ([MIN_FPS_FLOOR], último recurso): decisión de
 *    producto (ADR-0020) — solo si ni calidad ni resolución alcanzaron
 *    sobre el fotograma completo, se reduce el número de fotogramas por
 *    estimación directa, nunca por debajo de la tasa fija de
 *    [MIN_FPS_FLOOR], y se repiten los escalones 1-2 sobre ese número
 *    reducido. La duración pedida no cambia en este escalón.
 * 4. **Duración, último-último recurso**: si ni siquiera el piso de fps
 *    alcanza, se acorta el clip a [MIN_DURATION_MS_BEFORE_LAST_RESORT] y
 *    se repiten los escalones 1-3 sobre ese tramo más corto. Ninguna
 *    medición de ADR-0016 ni de ADR-0020 encontró un caso real que llegue
 *    hasta acá — el peor contenido medido (ruido puro, con o sin zoom)
 *    resuelve en el escalón 3 — pero queda como red de seguridad.
 *
 * Por qué este orden y no el de ADR-0016 (que sacrificaba fotogramas y
 * duración antes que agotar calidad/resolución sobre el fotograma
 * completo): decisión de producto, no un hallazgo de rendimiento — el
 * sticker debe verse siempre con la fluidez y duración que el usuario
 * eligió mientras sea técnicamente posible, y solo ceder fps/duración
 * cuando calidad y resolución ya se agotaron. Medido en dispositivo real
 * que esto es viable en casi todos los casos: sobre el fotograma completo
 * de producción (20 fps × hasta 5 s, ADR-0019), calidad+resolución solas
 * alcanzan para el contenido de referencia (con y sin zoom) y para ruido
 * puro sin zoom; solo 2 de 6 combinaciones adversas medidas (alto
 * movimiento sin zoom, ruido puro con zoom) necesitan el escalón de fps —
 * ver `docs/desarrollo/pruebas.md` y ADR-0020.
 *
 * No usa `minimize_size` (ADR-0018, cambia este punto de ADR-0006): medido
 * en contenido real, cuesta 1.8×-1.9× el tiempo de la codificación que ya
 * cupo por solo un 3-5% menos de tamaño — no vale la pena ese costo, y
 * aparte afecta la decisión de cuadro-clave-vs-diferencia por fotograma de
 * una forma que `sticker-convert` (proyecto de terceros) reporta que le
 * generó líneas negras en WhatsApp — no verificado en este dispositivo,
 * motivo adicional al costo medido. Un tope duro de tiempo (RNF-08) acota cuánto
 * puede tardar el caso adverso, estimando la duración de cada codificación
 * antes de lanzarla en vez de solo comprobar el reloj después — y, dentro
 * de un mismo escalón, **estimando por proporción si un intento tiene
 * alguna chance antes de gastarlo**: si la propia proporción ya indica que
 * ni el piso de fps alcanzaría, no se gasta ese intento — se entra directo
 * al escalón de duración (ver ADR-0016, punto 5, mismo principio).
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
        var bestQuality: Float? = null
        var bestFrames: List<WebpFrame>? = null
        var bestResolution: Int? = null
        var attemptNumber = 0

        // Del último intento, haya cupido o no: a diferencia de bestBytes/
        // bestQuality/bestFrames (que solo se actualizan cuando el
        // resultado cabe), esto existe para poder describir, si al final
        // no hay ningún resultado válido, con qué se quedó más cerca (RF-12),
        // y para estimar por proporción el escalón de fps (ADR-0020).
        var lastAttemptBytes: ByteArray? = null
        var lastAttemptQuality: Float? = null
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

        fun attempt(candidateFrames: List<WebpFrame>, quality: Float, resolution: Int): ByteArray {
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
                bestResolution = resolution
            }
            return bytes
        }

        /**
         * Bisección de calidad (ADR-0006/ADR-0007) sobre [candidateFrames]
         * a [resolution], probando primero [probeQuality] — [FIRST_QUALITY]
         * en el escalón de 512 (cubre en un solo intento el caso común,
         * contenido que cabe de sobra), [QualitySearch.MIN_QUALITY] en el
         * escalón de 384 (ya se sabe, por haber llegado hasta acá, que ni
         * la calidad mínima alcanzó a 512).
         *
         * Si [probeQuality] es [FIRST_QUALITY] y falla, el siguiente
         * intento salta directo al piso ([QualitySearch.MIN_QUALITY]) en
         * vez del punto medio natural de la bisección (37, para un rango
         * 0-100): confirma en un segundo intento si esta resolución tiene
         * alguna chance, en vez de hasta 7 (ADR-0020, corrección medida en
         * dispositivo real — bisecar desde arriba sin este salto tardó
         * >30 s en el peor caso, rompiendo RNF-08 antes de llegar siquiera
         * al escalón de fps, ver `docs/desarrollo/pruebas.md`). El estado
         * interno de [QualitySearch] ya registró que [FIRST_QUALITY] falló
         * antes de este salto, así que la bisección posterior sigue acotada
         * correctamente entre el piso y [FIRST_QUALITY] — el salto no
         * pierde ninguna garantía de encontrar la mejor calidad real, solo
         * reordena qué se prueba primero.
         *
         * Una vez que algo cabe (a [probeQuality], al piso, o más arriba),
         * sigue bisecando hacia arriba hasta usar
         * [TARGET_UPPER_OCCUPANCY_FRACTION] del límite de tamaño (ADR-0021)
         * — no se conforma con la primera calidad que entra.
         */
        fun bisectQuality(candidateFrames: List<WebpFrame>, resolution: Int, probeQuality: Float) {
            if (bestBytes != null) return
            if (!estimatedTimeAllows(candidateFrames.size)) return
            val search = QualitySearch(targetSizeBytes)

            fun reachedTarget(bytes: ByteArray) =
                bytes.size <= targetSizeBytes && bytes.size >= targetSizeBytes * TARGET_UPPER_OCCUPANCY_FRACTION

            val firstBytes = attempt(candidateFrames, probeQuality, resolution)
            val afterFirst = search.next(probeQuality, firstBytes.size)
            var quality: Float? = when {
                reachedTarget(firstBytes) -> null
                firstBytes.size <= targetSizeBytes -> afterFirst
                probeQuality != QualitySearch.MIN_QUALITY -> QualitySearch.MIN_QUALITY
                else -> afterFirst // ya era el piso y falló: afterFirst ya es null (nada más abajo)
            }
            while (quality != null && estimatedTimeAllows(candidateFrames.size)) {
                val bytes = attempt(candidateFrames, quality, resolution)
                val next = search.next(quality, bytes.size)
                quality = if (reachedTarget(bytes)) null else next
            }
        }

        /**
         * Escalones 1-2 (calidad + resolución, ADR-0020): nunca toca
         * fotogramas ni duración — eso es responsabilidad de
         * [runFpsFloorLadder] y del escalón de duración en [encode]. No
         * hace nada si ya hay un resultado válido de un escalón anterior
         * (ADR-0006: mínimo trabajo necesario, salvo que ADR-0021 ya
         * decidió seguir usando ese trabajo para maximizar calidad dentro
         * del mismo escalón — ver [bisectQuality]).
         *
         * @return `true` si encontró un resultado válido (en [bestBytes]).
         */
        fun runQualityAndResolutionLadder(candidateFrames: List<WebpFrame>): Boolean {
            if (bestBytes != null) return true
            if (!estimatedTimeAllows(candidateFrames.size)) return false

            bisectQuality(candidateFrames, PRODUCTION_RESOLUTION, FIRST_QUALITY)
            if (bestBytes != null) return true
            if (estimatedTimeAllows(candidateFrames.size)) {
                bisectQuality(candidateFrames, DEGRADED_RESOLUTION, QualitySearch.MIN_QUALITY)
            }
            return bestBytes != null
        }

        /**
         * Escalón 3 (piso de fps, ADR-0020, último recurso): solo se llega
         * acá si calidad+resolución agotaron el fotograma completo sin
         * encontrar nada. Estima cuántos fotogramas harían falta por
         * proporción contra el último intento (el más barato ya probado:
         * calidad mínima a 384), sin bajar de [minFrameCountForFpsFloor]
         * sobre la duración de [candidateFrames] — y, si esa misma
         * proporción ya indica que ni el piso alcanzaría, no gasta el
         * intento (mismo principio de "estimar antes de intentar" de
         * ADR-0016, punto 5): directo a que [encode] pruebe el escalón de
         * duración.
         *
         * @return `true` si encontró un resultado válido (en [bestBytes]).
         */
        fun runFpsFloorLadder(candidateFrames: List<WebpFrame>): Boolean {
            if (bestBytes != null) return true
            val totalDurationMs = candidateFrames.sumOf { it.durationMs }.toLong()
            val floor = minFrameCountForFpsFloor(totalDurationMs)
            if (candidateFrames.size <= floor) return false
            val reference = lastAttemptBytes ?: return false
            val ratio = targetSizeBytes.toDouble() / reference.size
            val rawEstimate = (candidateFrames.size * ratio).toInt()
            if (rawEstimate < floor) return false
            val estimatedCount = rawEstimate.coerceIn(floor, candidateFrames.size - 1)
            if (!estimatedTimeAllows(estimatedCount)) return false
            val reduced = FrameTiming.reduceTo(candidateFrames.map { it.durationMs }, estimatedCount)
            val working = reduced.map { (originalIndex, duration) -> candidateFrames[originalIndex].copy(durationMs = duration) }
            return runQualityAndResolutionLadder(working)
        }

        if (!runQualityAndResolutionLadder(frames) && !runFpsFloorLadder(frames)) {
            // Escalón 4, último-último recurso (ADR-0020): ninguna
            // medición encontró todavía un caso real que llegue hasta acá
            // (ver docs/desarrollo/pruebas.md) — el peor contenido medido
            // (ruido puro, con o sin zoom) resuelve en el escalón 3.
            val totalDurationMs = frames.sumOf { it.durationMs }
            if (totalDurationMs > MIN_DURATION_MS_BEFORE_LAST_RESORT) {
                val trimmedIndices = FrameTiming.trimToDuration(frames.map { it.durationMs }, MIN_DURATION_MS_BEFORE_LAST_RESORT)
                val trimmedFrames = trimmedIndices.map { frames[it] }
                if (!runQualityAndResolutionLadder(trimmedFrames)) {
                    runFpsFloorLadder(trimmedFrames)
                }
            }
        }

        // Fase F, red de seguridad final (ADR-0016, sin cambios de
        // ADR-0020): ninguna medición encontró un caso que llegue hasta
        // acá, pero RF-12 exige que siempre haya una salida. Un solo
        // intento al mínimo absoluto, a la calidad y resolución más
        // baratas medidas, sobre el tramo ya acortado: no hay datos para
        // justificar una búsqueda más fina en esta zona.
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
                        "piso de fps=$MIN_FPS_FLOOR por ADR-0020). Último intento (#$attemptNumber, " +
                        "de $attemptNumber en total): frameCount=$lastAttemptFrameCount " +
                        "quality=$lastAttemptQuality sizeBytes=$lastSize$overByText, dentro de " +
                        "${hardTimeLimitMs}ms de presupuesto.",
                )
            }
        val finalQuality = checkNotNull(bestQuality)
        val finalFrames = checkNotNull(bestFrames)
        val finalResolution = checkNotNull(bestResolution)

        return WebpEncodeResult(
            bytes = finalBytes,
            quality = finalQuality,
            resolution = finalResolution,
            frameCount = finalFrames.size,
            frameDurationsMs = finalFrames.map { it.durationMs },
            requestedDurationMs = requestedDurationMs,
        )
    }
}

data class WebpEncodeResult(
    val bytes: ByteArray,
    /** `Float` desde ADR-0022: ver KDoc de [SingleShotWebpEncoder.encode]. */
    val quality: Float,
    /**
     * Resolución de codificación con la que se produjo [bytes] (512 o 384,
     * ver [DEGRADED_RESOLUTION] — ADR-0022): el WebP final siempre mide
     * 512×512 exactos (RF-10/RF-11), esto expone en qué escalón de la
     * escalera de ADR-0020 se encontró el resultado, sin necesitar
     * instrumentación aparte para saberlo.
     */
    val resolution: Int,
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
            resolution == other.resolution &&
            frameCount == other.frameCount &&
            frameDurationsMs == other.frameDurationsMs &&
            requestedDurationMs == other.requestedDurationMs
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + quality.hashCode()
        result = 31 * result + resolution
        result = 31 * result + frameCount
        result = 31 * result + frameDurationsMs.hashCode()
        result = 31 * result + requestedDurationMs.hashCode()
        return result
    }
}
