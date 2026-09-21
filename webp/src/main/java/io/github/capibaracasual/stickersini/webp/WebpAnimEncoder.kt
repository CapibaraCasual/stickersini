package io.github.capibaracasual.stickersini.webp

/** RF-10: un WebP animado no debe pesar más de 500 KB. */
const val ANIMATED_WEBP_TARGET_SIZE_BYTES = 500_000

/** Por debajo de esto ya no tiene sentido seguir combinando fotogramas. */
private const val MIN_FRAMES_AFTER_REDUCTION = 2

/**
 * Codifica una animación WebP ajustando calidad y, si no basta, número de
 * fotogramas, hasta caber en [targetSizeBytes] (RF-10), respetando siempre
 * los tiempos de RF-13 (comprobados por [FrameTiming]). No decide de dónde
 * salen los fotogramas ni cuántos hay: eso es responsabilidad de quien
 * llame (editor, captura de pantalla), fuera del alcance de esta fase.
 *
 * [singleShotEncoder] es sustituible en los tests para probar el ajuste sin
 * tocar la librería nativa; en la app real es [NativeWebpEncoder].
 */
class WebpAnimEncoder(
    private val singleShotEncoder: SingleShotWebpEncoder = NativeWebpEncoder,
    private val targetSizeBytes: Int = ANIMATED_WEBP_TARGET_SIZE_BYTES,
) {

    /**
     * @throws WebpEncodeException si [frames] no cumple RF-13, o si no se
     * pudo producir un WebP dentro de [targetSizeBytes] ni reduciendo
     * calidad ni fotogramas (RF-12).
     */
    fun encode(frames: List<WebpFrame>): WebpEncodeResult {
        if (frames.isEmpty()) {
            throw WebpEncodeException("Se necesita al menos 1 fotograma")
        }
        FrameTiming.validate(frames.map { it.durationMs })

        var currentFrames = frames
        while (true) {
            val result = encodeWithQualitySearch(currentFrames)
            if (result != null) return result

            val halved = FrameTiming.halve(currentFrames.map { it.durationMs })
            if (halved == null || halved.size < MIN_FRAMES_AFTER_REDUCTION) {
                throw WebpEncodeException(
                    "RF-12: no se pudo producir un WebP de $targetSizeBytes bytes o menos " +
                        "ni reduciendo calidad ni fotogramas (quedarían ${halved?.size ?: currentFrames.size})",
                )
            }
            currentFrames = halved.map { (originalIndex, duration) ->
                currentFrames[originalIndex].copy(durationMs = duration)
            }
        }
    }

    private fun encodeWithQualitySearch(frames: List<WebpFrame>): WebpEncodeResult? {
        val search = QualitySearch(targetSizeBytes)
        var quality: Int? = search.firstQuality()
        var bestSearchBytes: ByteArray? = null

        // minimizeSize=false: la búsqueda solo necesita saber si una
        // calidad cabe o no, no el archivo más pequeño posible a esa
        // calidad. Antes se pagaba el costo lento de minimize_size en cada
        // intento de la bisección — un descuido corregido aparte de
        // cualquier cambio al propio algoritmo de búsqueda (ver ADR-0006).
        while (quality != null) {
            val bytes = singleShotEncoder.encode(frames, quality, minimizeSize = false)
            if (bytes.size <= targetSizeBytes) {
                bestSearchBytes = bytes
            }
            quality = search.next(quality, bytes.size)
        }

        val finalQuality = search.bestFittingQuality() ?: return null

        // Una sola pasada final con minimizeSize=true, ya sobre la calidad
        // ganadora: nunca puede producir un archivo más grande que la
        // pasada de búsqueda a la misma calidad (prueba keyframe y
        // diferencia, se queda con el más chico), así que sigue cabiendo.
        // Red de seguridad por si acaso: si no cupiera, usar el resultado
        // de búsqueda ya validado en vez de fallar.
        val finalBytes = singleShotEncoder.encode(frames, finalQuality, minimizeSize = true)
        val bytes = if (finalBytes.size <= targetSizeBytes) finalBytes else bestSearchBytes!!

        return WebpEncodeResult(
            bytes = bytes,
            quality = finalQuality,
            frameCount = frames.size,
            frameDurationsMs = frames.map { it.durationMs },
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
