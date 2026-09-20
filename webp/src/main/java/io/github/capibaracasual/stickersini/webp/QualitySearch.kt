package io.github.capibaracasual.stickersini.webp

/**
 * Busca por bisección la mayor calidad de codificación (0-100) cuyo
 * resultado no supere [targetSizeBytes]. Asume que el tamaño de salida
 * crece de forma monótona con la calidad, cierto en la práctica para el
 * codificador lossy de libwebp: a más calidad, igual o más bytes, nunca
 * menos.
 *
 * Uso: pedir [firstQuality], codificar a esa calidad, pasar el resultado a
 * [next] para obtener la siguiente calidad a probar. Repetir hasta que
 * [next] devuelva null. En ese punto, [bestFittingQuality] tiene la mejor
 * calidad encontrada que cupo en el límite, o null si ninguna cupo.
 */
class QualitySearch(private val targetSizeBytes: Int) {

    private var low = MIN_QUALITY
    private var high = MAX_QUALITY
    private var bestFitting: Int? = null
    private var exhausted = false

    fun firstQuality(): Int = high

    fun next(quality: Int, sizeBytes: Int): Int? {
        if (exhausted) return null

        if (sizeBytes <= targetSizeBytes) {
            bestFitting = quality
            low = quality + 1
        } else {
            high = quality - 1
        }

        if (low > high) {
            exhausted = true
            return null
        }
        return (low + high) / 2
    }

    fun bestFittingQuality(): Int? = bestFitting

    companion object {
        const val MIN_QUALITY = 0
        const val MAX_QUALITY = 100
    }
}
