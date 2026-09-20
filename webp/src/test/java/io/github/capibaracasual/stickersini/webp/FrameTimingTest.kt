package io.github.capibaracasual.stickersini.webp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameTimingTest {

    @Test
    fun `acepta duraciones validas`() {
        FrameTiming.validate(listOf(8, 100, 9_892)) // suma 10000, justo en el límite
    }

    @Test
    fun `rechaza RF-13 una lista vacia`() {
        assertThrows(WebpEncodeException::class.java) {
            FrameTiming.validate(emptyList())
        }
    }

    @Test
    fun `rechaza RF-13 un fotograma por debajo de 8ms`() {
        assertThrows(WebpEncodeException::class.java) {
            FrameTiming.validate(listOf(100, 7, 100))
        }
    }

    @Test
    fun `rechaza RF-13 una animacion de mas de 10s`() {
        assertThrows(WebpEncodeException::class.java) {
            FrameTiming.validate(listOf(5_000, 5_001))
        }
    }

    @Test
    fun `halve combina fotogramas de dos en dos conservando la duracion total`() {
        val halved = FrameTiming.halve(listOf(100, 200, 300, 400))
        checkNotNull(halved)
        assertEquals(listOf(IndexedValue(0, 300), IndexedValue(2, 700)), halved)
        assertEquals(1000, halved.sumOf { it.value })
    }

    @Test
    fun `halve con numero impar de fotogramas deja el ultimo solo`() {
        val halved = FrameTiming.halve(listOf(100, 200, 300))
        checkNotNull(halved)
        assertEquals(listOf(IndexedValue(0, 300), IndexedValue(2, 300)), halved)
    }

    @Test
    fun `halve con un solo fotograma no reduce mas`() {
        assertNull(FrameTiming.halve(listOf(100)))
    }

    @Test
    fun `halve con lista vacia no reduce mas`() {
        assertNull(FrameTiming.halve(emptyList()))
    }
}
