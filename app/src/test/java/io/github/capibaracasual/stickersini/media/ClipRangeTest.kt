package io.github.capibaracasual.stickersini.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRangeTest {

    @Test
    fun porDefectoEsDesdeElInicioHasta10Segundos() {
        val clip = ClipRange.of(startMs = 0, durationMs = MAX_CLIP_DURATION_MS, sourceDurationMs = 0)

        assertEquals(0L, clip.startUs)
        assertEquals(10_000_000L, clip.endUs)
        assertFalse(clip.truncated)
    }

    @Test
    fun unInicioArbitrarioDesplazaElTramoSinCambiarSuDuracion() {
        val clip = ClipRange.of(startMs = 5_000, durationMs = 3_000, sourceDurationMs = 0)

        assertEquals(5_000_000L, clip.startUs)
        assertEquals(8_000_000L, clip.endUs)
        assertFalse(clip.truncated)
    }

    @Test
    fun recortaLaDuracionPedidaAlTopeDeRf06() {
        val clip = ClipRange.of(startMs = 0, durationMs = 15_000, sourceDurationMs = 0)

        assertEquals(10_000_000L, clip.endUs)
        assertTrue("una duración pedida por encima de 10 s debe marcarse truncada", clip.truncated)
    }

    @Test
    fun seCortaSiElVideoNoLlegaAlFinDelTramoPedido() {
        // Video de 4 s, tramo pedido desde el segundo 2 por 10 s (recortado
        // a 10 s por RF-06, pero el video ya se acaba antes de eso).
        val clip = ClipRange.of(startMs = 2_000, durationMs = MAX_CLIP_DURATION_MS, sourceDurationMs = 4_000)

        assertEquals(2_000_000L, clip.startUs)
        assertEquals(4_000_000L, clip.endUs)
        assertTrue(clip.truncated)
    }

    @Test
    fun seMarcaTruncadaSiElVideoSigueDespuesDelTramoProcesado() {
        // Bug real, medido el 2026-09-25 con una grabación de pantalla de
        // 37 687 ms: con los valores por defecto (startMs=0,
        // durationMs=10000), ni durationCapped ni cutBySource se activaban,
        // así que un video mucho más largo que el tramo procesado se
        // reportaba como truncated=false. Este es el caso que lo prueba.
        val clip = ClipRange.of(startMs = 0, durationMs = MAX_CLIP_DURATION_MS, sourceDurationMs = 37_687)

        assertEquals(10_000_000L, clip.endUs)
        assertTrue(
            "un video de 37687ms recortado a 10000ms debe marcarse truncado, aunque " +
                "lo PEDIDO (10000ms) se haya cumplido exacto",
            clip.truncated,
        )
    }

    @Test
    fun noSeMarcaTruncadaSiElVideoAlcanzaJustoParaElTramoPedido() {
        val clip = ClipRange.of(startMs = 0, durationMs = 3_000, sourceDurationMs = 3_000)

        assertEquals(3_000_000L, clip.endUs)
        assertFalse(clip.truncated)
    }

    @Test
    fun containsRespetaAmbosExtremosDelTramo() {
        val clip = ClipRange.of(startMs = 1_000, durationMs = 2_000, sourceDurationMs = 0)

        assertFalse(clip.contains(999_999))
        assertTrue(clip.contains(1_000_000))
        assertTrue(clip.contains(3_000_000))
        assertFalse(clip.contains(3_000_001))
    }

    @Test
    fun relativeToStartDesplazaAlOrigenDelTramo() {
        val clip = ClipRange.of(startMs = 5_000, durationMs = 2_000, sourceDurationMs = 0)

        assertEquals(0L, clip.relativeToStart(5_000_000))
        assertEquals(12_000L, clip.relativeToStart(5_012_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rechazaUnInicioNegativo() {
        ClipRange.of(startMs = -1, durationMs = 1_000, sourceDurationMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rechazaUnaDuracionNoPositiva() {
        ClipRange.of(startMs = 0, durationMs = 0, sourceDurationMs = 0)
    }
}
