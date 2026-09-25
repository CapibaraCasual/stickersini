package io.github.capibaracasual.stickersini.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CenterSquareCropTest {

    @Test
    fun devuelveElRectanguloCompletoSiYaEsCuadrado() {
        val crop = CenterSquareCrop.of(width = 512, height = 512)
        assertEquals(CenterSquareCrop(size = 512, xOffset = 0, yOffset = 0), crop)
    }

    @Test
    fun recortaLosCostadosEnUnaImagenApaisada() {
        // 1600x720: el ancho sobra, se recorta simétrico a los costados.
        val crop = CenterSquareCrop.of(width = 1600, height = 720)
        assertEquals(CenterSquareCrop(size = 720, xOffset = 440, yOffset = 0), crop)
    }

    @Test
    fun recortaArribaYAbajoEnUnaImagenEnRetrato() {
        // 720x1600: mismo caso que el video real medido en Fase 2, pero en vertical.
        val crop = CenterSquareCrop.of(width = 720, height = 1600)
        assertEquals(CenterSquareCrop(size = 720, xOffset = 0, yOffset = 440), crop)
    }

    @Test
    fun redondeaHaciaAbajoElOffsetSiLaDiferenciaEsImpar() {
        val crop = CenterSquareCrop.of(width = 101, height = 100)
        assertEquals(CenterSquareCrop(size = 100, xOffset = 0, yOffset = 0), crop)
    }
}
