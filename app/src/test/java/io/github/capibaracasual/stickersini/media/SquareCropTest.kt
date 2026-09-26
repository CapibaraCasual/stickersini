package io.github.capibaracasual.stickersini.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SquareCropTest {

    @Test
    fun devuelveElRectanguloCompletoSiYaEsCuadrado() {
        val crop = SquareCrop.of(width = 512, height = 512)
        assertEquals(SquareCrop(size = 512, xOffset = 0, yOffset = 0), crop)
    }

    @Test
    fun recortaLosCostadosEnUnaImagenApaisada() {
        // 1600x720: el ancho sobra, se recorta simétrico a los costados.
        val crop = SquareCrop.of(width = 1600, height = 720)
        assertEquals(SquareCrop(size = 720, xOffset = 440, yOffset = 0), crop)
    }

    @Test
    fun recortaArribaYAbajoEnUnaImagenEnRetrato() {
        // 720x1600: mismo caso que el video real medido en Fase 2, pero en vertical.
        val crop = SquareCrop.of(width = 720, height = 1600)
        assertEquals(SquareCrop(size = 720, xOffset = 0, yOffset = 440), crop)
    }

    @Test
    fun redondeaHaciaAbajoElOffsetSiLaDiferenciaEsImpar() {
        val crop = SquareCrop.of(width = 101, height = 100)
        assertEquals(SquareCrop(size = 100, xOffset = 0, yOffset = 0), crop)
    }

    @Test
    fun unNormalizedCropDistintoDelCentradoDesplazaElRecorte() {
        // xFraction=0/yFraction=0: pegado a la esquina superior izquierda.
        val crop = SquareCrop.of(width = 1600, height = 720, normalized = NormalizedCrop(0f, 0f, 0.5f))
        assertEquals(SquareCrop(size = 360, xOffset = 0, yOffset = 0), crop)
    }

    @Test
    fun unNormalizedCropConFraccionesExtremasQuedaDentroDeLosLimites() {
        // xFraction=1/yFraction=1: pegado a la esquina inferior derecha, no se sale del rectángulo.
        val crop = SquareCrop.of(width = 1600, height = 720, normalized = NormalizedCrop(1f, 1f, 0.5f))
        assertEquals(SquareCrop(size = 360, xOffset = 1240, yOffset = 360), crop)
    }
}
