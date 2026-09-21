package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `si la maxima calidad ya cabe, hace 1 pasada de busqueda y 1 final`() {
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, _ ->
                calls++
                ByteArray(if (quality == 100) 1_000 else 999_999)
            },
            targetSizeBytes = 500_000,
        )

        val result = encoder.encode(frames(3))

        assertEquals(100, result.quality)
        // 1 intento de búsqueda (quality=100, cabe de inmediato) + 1 pasada
        // final a esa misma calidad con minimizeSize=true. No 1: la pasada
        // final es intencional, no un intento de bisección de más.
        assertEquals(2, calls)
    }

    @Test
    fun `minimizeSize es false durante toda la busqueda y true solo en la pasada final`() {
        val minimizeSizeByCall = mutableListOf<Boolean>()
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, minimizeSize ->
                minimizeSizeByCall += minimizeSize
                ByteArray(quality * 1_000)
            },
            targetSizeBytes = 55_000,
        )

        encoder.encode(frames(3))

        // Todas las pasadas de búsqueda (todas menos la última) van con
        // minimizeSize=false; la última, la final, va con true.
        assertTrue(minimizeSizeByCall.size >= 2)
        assertTrue(
            "ninguna pasada de búsqueda debería usar minimizeSize=true",
            minimizeSizeByCall.dropLast(1).none { it },
        )
        assertTrue("la pasada final debería usar minimizeSize=true", minimizeSizeByCall.last())
    }

    @Test
    fun `reduce la calidad hasta caber en el limite`() {
        // tamaño simulado = calidad * 1000; con límite 55_000 la mejor calidad es 55
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, _ -> ByteArray(quality * 1_000) },
            targetSizeBytes = 55_000,
        )

        val result = encoder.encode(frames(3))

        assertEquals(55, result.quality)
        assertEquals(55_000, result.bytes.size)
    }

    @Test
    fun `si la pasada final no cupiera, usa el resultado ya validado de la busqueda`() {
        // minimizeSize=true "falla" a propósito (devuelve más grande que la
        // búsqueda) para probar la red de seguridad: no debería pasar nunca
        // en la realidad (minimize_size solo puede achicar o igualar), pero
        // si pasara, no debe romper el límite de RF-10.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, minimizeSize ->
                if (minimizeSize) ByteArray(999_999) else ByteArray(quality * 1_000)
            },
            targetSizeBytes = 55_000,
        )

        val result = encoder.encode(frames(3))

        assertEquals(55, result.quality)
        assertTrue(result.bytes.size <= 55_000)
    }

    @Test
    fun `si ninguna calidad cabe, reduce fotogramas y reintenta`() {
        // Con 4 fotogramas nunca cabe (tamaño mínimo 400_000 a calidad 0);
        // con 2 fotogramas (tras halve) sí cabe incluso a calidad alta.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { currentFrames, quality, _ ->
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
            singleShotEncoder = SingleShotWebpEncoder { _, _, _ -> ByteArray(999_999) },
            targetSizeBytes = 500_000,
        )

        assertThrows(WebpEncodeException::class.java) {
            encoder.encode(frames(4))
        }
    }

    @Test
    fun `rechaza RF-13 antes de intentar codificar`() {
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _, _ ->
                throw AssertionError("no debería llegar a codificar")
            },
        )

        assertThrows(WebpEncodeException::class.java) {
            encoder.encode(frames(2, durationMs = 5)) // por debajo de los 8 ms mínimos
        }
    }
}
