package io.github.capibaracasual.stickersini.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSamplerTest {

    @Test
    fun conservaElPrimerFotogramaSiempre() {
        val sampler = FrameSampler(targetFps = 20)
        assertTrue(sampler.shouldKeep(presentationTimeUs = 0))
    }

    @Test
    fun descartaFotogramasDentroDelMismoIntervalo() {
        // 20 fps -> intervalo de 50_000 us.
        val sampler = FrameSampler(targetFps = 20)
        assertTrue(sampler.shouldKeep(0))
        assertFalse(sampler.shouldKeep(16_667)) // fotograma siguiente a 60 fps de origen
        assertFalse(sampler.shouldKeep(33_333))
        assertTrue(sampler.shouldKeep(50_000))
    }

    @Test
    fun conservaExactamenteElFpsObjetivoEnPromedioSobreOrigenA30Fps() {
        // 3 s de origen a 30 fps (90 fotogramas) muestreados a 20 fps
        // objetivo: la grilla fija da exactamente 60 conservados (2 de cada
        // 3 fotogramas de origen), verificado a mano en ADR-0008.
        val sampler = FrameSampler(targetFps = 20)
        val sourceFrameIntervalUs = 1_000_000L / 30
        var pts = 0L
        var kept = 0
        repeat(90) {
            if (sampler.shouldKeep(pts)) kept++
            pts += sourceFrameIntervalUs
        }
        assertEquals(60, kept)
    }

    @Test
    fun trasUnSaltoDeTimestampsConservaVariosSeguidosHastaPonerseAlDia() {
        // Un salto real (fotograma perdido, o el primer fotograma de un
        // clip) no debe dejar el muestreo "atrasado" para siempre: debe
        // ponerse al día conservando fotogramas seguidos hasta alcanzar de
        // nuevo la grilla fija, no arrastrar el hueco.
        val sampler = FrameSampler(targetFps = 20) // intervalo: 50_000 us
        assertTrue(sampler.shouldKeep(0))
        assertTrue(sampler.shouldKeep(500_000)) // salto grande hacia adelante
        // La grilla sigue en múltiplos de 50_000 desde el origen (100_000
        // en este punto), no desde 500_000: el siguiente fotograma, aunque
        // llegue enseguida, ya la superó y se conserva también.
        assertTrue(sampler.shouldKeep(510_000))
    }
}
