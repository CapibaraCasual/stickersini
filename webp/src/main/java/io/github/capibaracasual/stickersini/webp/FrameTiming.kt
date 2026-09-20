package io.github.capibaracasual.stickersini.webp

/**
 * Valida y ajusta la duración de los fotogramas de una animación según
 * RF-13: cada fotograma dura al menos [MIN_FRAME_DURATION_MS] y la
 * animación completa no supera [MAX_TOTAL_DURATION_MS].
 */
object FrameTiming {
    const val MIN_FRAME_DURATION_MS = 8
    const val MAX_TOTAL_DURATION_MS = 10_000

    /**
     * @throws WebpEncodeException si [durationsMs] no cumple RF-13.
     */
    fun validate(durationsMs: List<Int>) {
        if (durationsMs.isEmpty()) {
            throw WebpEncodeException("RF-13: la animación necesita al menos 1 fotograma")
        }
        val tooShort = durationsMs.withIndex().firstOrNull { (_, duration) -> duration < MIN_FRAME_DURATION_MS }
        if (tooShort != null) {
            throw WebpEncodeException(
                "RF-13: el fotograma ${tooShort.index} dura ${tooShort.value} ms, " +
                    "el mínimo es $MIN_FRAME_DURATION_MS ms",
            )
        }
        val total = durationsMs.sum()
        if (total > MAX_TOTAL_DURATION_MS) {
            throw WebpEncodeException(
                "RF-13: la animación dura $total ms, el máximo es $MAX_TOTAL_DURATION_MS ms",
            )
        }
    }

    /**
     * Combina los fotogramas de dos en dos sumando su duración, para reducir
     * el número de fotogramas a codificar sin acortar la animación. Cada
     * elemento del resultado es el índice del fotograma original que
     * sobrevive junto con su nueva duración (la suya más la del que le
     * seguía). Devuelve null cuando ya no se puede reducir más: quedaría un
     * solo fotograma.
     */
    fun halve(durationsMs: List<Int>): List<IndexedValue<Int>>? {
        if (durationsMs.size < 2) return null
        val result = mutableListOf<IndexedValue<Int>>()
        var i = 0
        while (i < durationsMs.size) {
            val duration = if (i + 1 < durationsMs.size) {
                durationsMs[i] + durationsMs[i + 1]
            } else {
                durationsMs[i]
            }
            result += IndexedValue(i, duration)
            i += 2
        }
        return result
    }
}
