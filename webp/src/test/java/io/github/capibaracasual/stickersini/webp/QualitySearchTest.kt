package io.github.capibaracasual.stickersini.webp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualitySearchTest {

    /** Simula un codificador donde el tamaño crece 1:1 con la calidad. */
    private fun sizeAt(quality: Int): Int = quality * 1000

    private fun runSearch(search: QualitySearch): Int? {
        var quality: Int? = search.firstQuality()
        var iterations = 0
        while (quality != null) {
            quality = search.next(quality, sizeAt(quality))
            iterations++
            check(iterations < 20) { "la búsqueda no debería tardar más que log2(100) pasos" }
        }
        return search.bestFittingQuality()
    }

    @Test
    fun `si la maxima calidad ya cabe, no busca mas`() {
        val search = QualitySearch(targetSizeBytes = 200_000)
        val first = search.firstQuality()
        assertEquals(100, first)

        val next = search.next(first, sizeAt(first))
        assertNull(next)
        assertEquals(100, search.bestFittingQuality())
    }

    @Test
    fun `converge a la mayor calidad que cabe en el limite`() {
        assertEquals(55, runSearch(QualitySearch(targetSizeBytes = 55_000)))
    }

    @Test
    fun `si ni la calidad minima cabe, no hay resultado`() {
        assertNull(runSearch(QualitySearch(targetSizeBytes = -1)))
    }

    @Test
    fun `calidad 0 cabe cuando el limite es muy bajo pero no negativo`() {
        assertEquals(0, runSearch(QualitySearch(targetSizeBytes = 0)))
    }
}
