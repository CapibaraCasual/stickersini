package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Prueba la estrategia de ADR-0006 (una pasada primero, reducir fotogramas
 * por estimación si no basta, bisecar calidad como último recurso,
 * `minimize_size` solo cerca del límite, tope duro de tiempo) con un
 * [SingleShotWebpEncoder] falso, sin tocar la librería nativa. El [Bitmap]
 * de cada [WebpFrame] es un mock: la orquestación nunca lee sus píxeles,
 * solo cuenta fotogramas.
 */
class WebpAnimEncoderTest {

    private fun frames(count: Int, durationMs: Int = 100) =
        List(count) { WebpFrame(bitmap = mock(Bitmap::class.java), durationMs = durationMs) }

    @Test
    fun `si la primera pasada cabe de sobra, no busca ni minimiza`() {
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, minimizeSize ->
                calls++
                assertEquals(75, quality)
                assertFalse(minimizeSize)
                ByteArray(50_000) // 10% del límite de 500_000: no está "cerca"
            },
        )

        val result = encoder.encode(frames(30))

        assertEquals(1, calls)
        assertEquals(75, result.quality)
        assertEquals(30, result.frameCount)
        assertEquals(50_000, result.bytes.size)
    }

    @Test
    fun `si el resultado ya valido queda cerca del limite, prueba minimize_size`() {
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _, minimizeSize ->
                if (minimizeSize) ByteArray(420_000) else ByteArray(450_000) // 90% del límite: "cerca"
            },
        )

        val result = encoder.encode(frames(5))

        assertEquals(420_000, result.bytes.size) // usa el resultado minimizado, más chico
    }

    @Test
    fun `si minimize_size no mejora el resultado, se queda con el de la busqueda`() {
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _, minimizeSize ->
                // No debería pasar nunca en la realidad (minimize_size solo puede
                // achicar o igualar), pero si pasara, no debe romper RF-10.
                if (minimizeSize) ByteArray(999_999) else ByteArray(450_000)
            },
        )

        val result = encoder.encode(frames(5))

        assertEquals(450_000, result.bytes.size)
        assertTrue(result.bytes.size <= 500_000)
    }

    @Test
    fun `si la calidad fija no basta, reduce fotogramas por proporcion en un solo paso`() {
        // tamaño simulado = fotogramas x 50_000, sin depender de la calidad
        // (fases 1 y 2 siempre codifican a 75): con 12 fotogramas no cabe
        // (600_000), la proporción estima 10 fotogramas (500_000, justo cabe).
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, minimizeSize ->
                assertEquals(75, quality)
                val base = candidateFrames.size * 50_000
                if (minimizeSize) ByteArray(base - 5_000) else ByteArray(base)
            },
        )

        val result = encoder.encode(frames(12))

        assertEquals(10, result.frameCount)
        assertEquals(75, result.quality)
        // 500_000 (100% del límite) dispara minimize_size, que lo deja en 495_000.
        assertEquals(495_000, result.bytes.size)
    }

    @Test
    fun `si reducir fotogramas no basta, bisecta calidad despues, sin minimizar si no hace falta`() {
        // A calidad 75 el tamaño no baja de forma proporcional al reducir
        // fotogramas (hay un costo fijo de 300_000 que no depende del
        // número de fotogramas): con 10 fotogramas no cabe (1_200_000), la
        // reducción por proporción estima 4 fotogramas, que tampoco cabe
        // (660_000) — recién ahí entra la bisección de calidad, ya sobre
        // esos 4 fotogramas.
        var minimizeSizeCalls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, minimizeSize ->
                if (minimizeSize) minimizeSizeCalls++
                val count = candidateFrames.size
                if (quality == 75) {
                    ByteArray(count * 90_000 + 300_000)
                } else {
                    ByteArray(count * 1_000 + quality * 1_000)
                }
            },
        )

        val result = encoder.encode(frames(10))

        assertEquals(4, result.frameCount)
        assertEquals(74, result.quality)
        assertEquals(78_000, result.bytes.size)
        // 78_000 es 15.6% del límite: no está "cerca", no debió minimizarse.
        assertEquals(0, minimizeSizeCalls)
    }

    @Test
    fun `si ninguna combinacion cabe, falla informando RF-12`() {
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _, _ -> ByteArray(999_999) },
        )

        assertThrows(WebpEncodeException::class.java) {
            encoder.encode(frames(5))
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

    @Test
    fun `si se agota el tope de tiempo sin ningun resultado valido, falla informando RF-12`() {
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _, _ -> ByteArray(999_999) },
            hardTimeLimitMs = 0,
        )

        assertThrows(WebpEncodeException::class.java) {
            encoder.encode(frames(10))
        }
    }

    @Test
    fun `si se agota el tope de tiempo a mitad de la busqueda, entrega el mejor resultado valido encontrado`() {
        // Mismo escenario que "reducir no basta, bisecta calidad", pero con
        // 15 ms de latencia simulada por intento y un tope de 70 ms: no
        // alcanza a converger a la calidad óptima (74), pero sí a devolver
        // algo válido en vez de fallar o colgarse.
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                calls++
                Thread.sleep(15)
                val count = candidateFrames.size
                if (quality == 75) {
                    ByteArray(count * 90_000 + 300_000)
                } else {
                    ByteArray(count * 1_000 + quality * 1_000)
                }
            },
            hardTimeLimitMs = 70,
        )

        val result = encoder.encode(frames(10))

        assertTrue("el resultado entregado debe cumplir RF-10 igual", result.bytes.size <= 500_000)
        assertEquals(4, result.frameCount)
        // La convergencia completa de esta bisección necesita 7 intentos en
        // la fase 3 (más 2 de las fases 1 y 2): con el tope de tiempo debió
        // cortar antes.
        assertTrue("debió cortar antes de agotar la bisección completa (9 intentos)", calls < 9)
    }
}
