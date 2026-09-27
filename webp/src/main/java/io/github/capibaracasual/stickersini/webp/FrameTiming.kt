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
     * Reduce [durationsMs] a como mucho [targetCount] fotogramas, agrupando
     * en bloques consecutivos de tamaño lo más parejo posible y sumando sus
     * duraciones bajo el primer fotograma de cada bloque, sin acortar la
     * animación. Cada elemento del resultado es el índice del fotograma
     * original que sobrevive junto con su nueva duración. Si [durationsMs]
     * ya tiene [targetCount] fotogramas o menos, lo devuelve sin tocar.
     */
    fun reduceTo(durationsMs: List<Int>, targetCount: Int): List<IndexedValue<Int>> {
        if (targetCount >= durationsMs.size) {
            return durationsMs.mapIndexed { index, duration -> IndexedValue(index, duration) }
        }
        val result = mutableListOf<IndexedValue<Int>>()
        var index = 0
        var itemsLeft = durationsMs.size
        var bucketsLeft = targetCount.coerceAtLeast(1)
        while (index < durationsMs.size) {
            val bucketSize = (itemsLeft + bucketsLeft - 1) / bucketsLeft
            val end = (index + bucketSize).coerceAtMost(durationsMs.size)
            result += IndexedValue(index, durationsMs.subList(index, end).sum())
            index = end
            itemsLeft -= bucketSize
            bucketsLeft -= 1
        }
        return result
    }

    /**
     * Recorta [durationsMs] a como mucho [targetTotalMs] de duración total,
     * quedándose con un prefijo contiguo desde el principio — a diferencia
     * de [reduceTo], que reparte fotogramas por proporción sin acortar la
     * animación, esto la acorta de verdad (ADR-0016, escalón de duración de
     * la escalera de degradación: solo se usa cuando ni calidad ni
     * resolución alcanzaron a la duración completa). Devuelve los índices
     * originales que sobreviven, sin tocar sus duraciones. Recortar desde
     * el principio es la opción más simple, no una medida frente a
     * recortar del final o del centro — queda como posible ajuste futuro
     * si hiciera falta.
     *
     * Si [durationsMs] ya dura [targetTotalMs] o menos, devuelve todos los
     * índices sin tocar. Garantiza al menos 1 índice en el resultado
     * (el primer fotograma), incluso si su sola duración ya supera
     * [targetTotalMs].
     */
    fun trimToDuration(durationsMs: List<Int>, targetTotalMs: Int): List<Int> {
        if (durationsMs.sum() <= targetTotalMs) return durationsMs.indices.toList()
        val kept = mutableListOf<Int>()
        var accumulated = 0
        for ((index, duration) in durationsMs.withIndex()) {
            if (accumulated >= targetTotalMs) break
            kept += index
            accumulated += duration
        }
        return kept.ifEmpty { listOf(0) }
    }
}
