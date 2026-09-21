package io.github.capibaracasual.stickersini.webp

import org.junit.Assert.assertEquals
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
    fun `reduceTo agrupa en bloques parejos conservando la duracion total`() {
        val durations = List(10) { 100 }
        val reduced = FrameTiming.reduceTo(durations, targetCount = 3)

        assertEquals(
            listOf(IndexedValue(0, 400), IndexedValue(4, 300), IndexedValue(7, 300)),
            reduced,
        )
        assertEquals(1000, reduced.sumOf { it.value })
    }

    @Test
    fun `reduceTo no toca la lista si ya tiene targetCount fotogramas o menos`() {
        val reduced = FrameTiming.reduceTo(listOf(100, 200, 300), targetCount = 5)

        assertEquals(listOf(IndexedValue(0, 100), IndexedValue(1, 200), IndexedValue(2, 300)), reduced)
    }

    @Test
    fun `reduceTo con targetCount 0 o negativo reduce a un solo fotograma`() {
        val reduced = FrameTiming.reduceTo(listOf(100, 200, 300, 400), targetCount = 0)

        assertEquals(listOf(IndexedValue(0, 1000)), reduced)
    }
}
