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
 *
 * **Con tiempo acotado, el orden en que se siembra la búsqueda importa,**
 * aunque esta clase no lo imponga: [next] solo reacciona a la calidad que
 * se le pase, cualquiera que sea. Si quien llama corta el bucle antes de
 * que [next] devuelva null (por ejemplo, por un tope de tiempo como el de
 * [WebpAnimEncoder]), lo único garantizado es el resultado de la
 * *primera* calidad probada — todo lo demás puede quedar sin intentar.
 * Sembrar desde arriba (probar una calidad alta primero, como hace
 * [WebpAnimEncoder] en el caso general) es razonable cuando se espera que
 * calidades altas o medias quepan: encuentra rápido algo bueno y refina
 * desde ahí. Pero en el piso de fotogramas de ADR-0007 — cuando el
 * contenido ya obligó a reducir fotogramas hasta el mínimo aceptable y
 * *aun así* no cabe — la medición de ese ADR encontró que, para el peor
 * contenido probado, la única calidad que cabía era la mínima (0):
 * bisecar desde arriba en ese caso puede agotar todo el tiempo disponible
 * confirmando, una por una, que las calidades intermedias tampoco caben,
 * y llegar a la única que sí cabe justo cuando ya no queda tiempo para
 * usarla — dejando al usuario sin ningún resultado en vez de uno
 * imperfecto. Por eso, en ese escenario específico, [WebpAnimEncoder]
 * siembra con [MIN_QUALITY] primero: si no cabe, se sabe en una sola
 * codificación que no hay ninguna solución a ese número de fotogramas; si
 * cabe, queda de inmediato como resultado válido garantizado, y el resto
 * del tiempo disponible se usa para bisecar *hacia arriba* buscando algo
 * mejor — sin arriesgar quedarse sin nada si el tiempo se agota a mitad
 * de camino.
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
