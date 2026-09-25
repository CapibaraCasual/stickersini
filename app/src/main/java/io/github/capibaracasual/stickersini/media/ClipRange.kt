package io.github.capibaracasual.stickersini.media

/**
 * Tramo de un video de origen que se va a decodificar, en microsegundos:
 * convierte `(startMs, durationMs)` aplicando el tope de RF-06
 * ([MAX_CLIP_DURATION_MS]) y, si se conoce la duración real del video, no
 * lo deja pasarse de ahí. Puro y testeable sin `MediaExtractor` — buscar el
 * keyframe anterior a [startUs] y descartar lo decodificado antes de él es
 * responsabilidad de [VideoFrameDecoder], esta clase solo calcula los
 * límites (ver ADR-0008).
 */
internal class ClipRange private constructor(
    val startUs: Long,
    val endUs: Long,
    /**
     * `true` si el tramo procesado dejó contenido sin usar: porque
     * `durationMs` pedido superaba el tope de 10 s, porque el video se
     * acababa antes de llegar al tramo pedido, o porque el video sigue
     * después de donde el tramo terminó (el caso más común: un video más
     * largo que los 10 s que se procesan por defecto).
     */
    val truncated: Boolean,
) {
    fun contains(presentationTimeUs: Long): Boolean = presentationTimeUs in startUs..endUs

    /** Timestamp relativo al inicio del tramo, para que [FrameSampler] no dependa de dónde arranca el video. */
    fun relativeToStart(presentationTimeUs: Long): Long = presentationTimeUs - startUs

    companion object {
        /**
         * @param startMs instante de inicio del tramo, dentro del video de origen.
         * @param durationMs duración pedida; se recorta a [MAX_CLIP_DURATION_MS] si la supera (RF-06).
         * @param sourceDurationMs duración total del video, o `0`/negativo si no se conoce.
         */
        fun of(startMs: Long, durationMs: Long, sourceDurationMs: Long): ClipRange {
            require(startMs >= 0) { "startMs no puede ser negativo: $startMs" }
            require(durationMs > 0) { "durationMs debe ser positivo: $durationMs" }

            val durationCapped = durationMs > MAX_CLIP_DURATION_MS
            val clampedDurationMs = durationMs.coerceAtMost(MAX_CLIP_DURATION_MS)

            val startUs = startMs * 1_000L
            val requestedEndUs = startUs + clampedDurationMs * 1_000L
            val sourceDurationUs = sourceDurationMs * 1_000L
            val cutBySource = sourceDurationMs > 0 && sourceDurationUs < requestedEndUs
            val endUs = if (cutBySource) sourceDurationUs else requestedEndUs

            // Bug medido el 2026-09-25 (docs/desarrollo/pruebas.md): con los
            // valores por defecto (startMs=0, durationMs=10000) sobre un
            // video de 37 687 ms, ni durationCapped (10000 no es >10000) ni
            // cutBySource (el video NO es más corto que lo pedido, es más
            // largo) se activaban, así que un video bastante más largo que
            // el tramo procesado se reportaba truncated=false. La condición
            // que faltaba: compara la duración real del video contra el
            // tramo que se terminó procesando (endUs), no el tramo pedido
            // contra su propio tope.
            val moreSourceRemains = sourceDurationMs > 0 && sourceDurationUs > endUs

            return ClipRange(startUs, endUs, durationCapped || cutBySource || moreSourceRemains)
        }
    }
}
