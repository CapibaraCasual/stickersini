package io.github.capibaracasual.stickersini.webp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualitySearchTest {

    /** Simula un codificador donde el tamaño crece 1:1 con la calidad. */
    private fun sizeAt(quality: Float): Int = (quality * 1000).toInt()

    private fun runSearch(search: QualitySearch): Float? {
        var quality: Float? = search.firstQuality()
        var iterations = 0
        while (quality != null) {
            quality = search.next(quality, sizeAt(quality))
            iterations++
            check(iterations < 20) { "la búsqueda no debería tardar más que log2(100/epsilon) pasos" }
        }
        return search.bestFittingQuality()
    }

    @Test
    fun `si la maxima calidad ya cabe, no busca mas`() {
        val search = QualitySearch(targetSizeBytes = 200_000)
        val first = search.firstQuality()
        assertEquals(100f, first)

        val next = search.next(first, sizeAt(first))
        assertNull(next)
        assertEquals(100f, search.bestFittingQuality()!!, 0f)
    }

    @Test
    fun `converge a la mayor calidad que cabe en el limite`() {
        val result = runSearch(QualitySearch(targetSizeBytes = 55_000))
        assertEquals(55f, result!!, 2 * QualitySearch.CONVERGENCE_EPSILON)
    }

    @Test
    fun `si ni la calidad minima cabe, no hay resultado`() {
        assertNull(runSearch(QualitySearch(targetSizeBytes = -1)))
    }

    @Test
    fun `calidad baja cabe cuando el limite es muy bajo pero no negativo`() {
        // 500 en vez de 0 exacto a propósito: con un dominio continuo y
        // convergencia por épsilon (no por igualdad entera), un límite
        // pegado al borde absoluto (0) puede no llegar a probarse nunca si
        // la semilla es alta y la única franja que cabe es más chica que
        // el épsilon de convergencia — responsabilidad de quien llama
        // (WebpAnimEncoder siembra por el piso a propósito en ese caso, ver
        // su KDoc), no una garantía de esta clase en aislamiento.
        val result = runSearch(QualitySearch(targetSizeBytes = 500))
        assertEquals(0.5f, result!!, 2 * QualitySearch.CONVERGENCE_EPSILON)
    }
}
