package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Prueba el bucle de ajuste de [WebpAnimEncoder] (RF-12) con un
 * [SingleShotWebpEncoder] falso que simula tamaños de salida, sin tocar la
 * librería nativa. El [Bitmap] de cada [WebpFrame] es un mock: la
 * orquestación nunca lee sus píxeles, solo cuenta fotogramas.
 */
class WebpAnimEncoderTest {

    private fun frames(count: Int, durationMs: Int = 100) =
        List(count) { WebpFrame(bitmap = mock(Bitmap::class.java), durationMs = durationMs) }

    @Test
    fun `si la maxima calidad ya cabe, la usa sin mas intentos`() {
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality ->
                calls++
                ByteArray(if (quality == 100) 1_000 else 999_999)
            },
            targetSizeBytes = 500_000,
        )

        val result = encoder.encode(frames(3))

        assertEquals(100, result.quality)
        assertEquals(1, calls)
    }

    @Test
    fun `reduce la calidad hasta caber en el limite`() {
        // tamaño simulado = calidad * 1000; con límite 55_000 la mejor calidad es 55
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality -> ByteArray(quality * 1_000) },
            targetSizeBytes = 55_000,
        )

        val result = encoder.encode(frames(3))

        assertEquals(55, result.quality)
        assertEquals(55_000, result.bytes.size)
    }

    @Test
    fun `si ninguna calidad cabe, reduce fotogramas y reintenta`() {
        // Con 4 fotogramas nunca cabe (tamaño mínimo 400_000 a calidad 0);
        // con 2 fotogramas (tras halve) sí cabe incluso a calidad alta.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { currentFrames, quality ->
                val perFrame = 50_000 + quality * 1_000
                ByteArray(perFrame * currentFrames.size)
            },
            targetSizeBytes = 150_000,
        )

        val result = encoder.encode(frames(4, durationMs = 100))

        assertEquals(2, result.frameCount)
        assertEquals(listOf(200, 200), result.frameDurationsMs) // se combinaron de dos en dos
    }

    @Test
    fun `si ni reduciendo fotogramas cabe, falla informando (RF-12)`() {
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _ -> ByteArray(999_999) },
            targetSizeBytes = 500_000,
        )

        assertThrows(WebpEncodeException::class.java) {
            encoder.encode(frames(4))
        }
    }

    @Test
    fun `rechaza RF-13 antes de intentar codificar`() {
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _ ->
                throw AssertionError("no debería llegar a codificar")
            },
        )

        assertThrows(WebpEncodeException::class.java) {
            encoder.encode(frames(2, durationMs = 5)) // por debajo de los 8 ms mínimos
        }
    }
}
