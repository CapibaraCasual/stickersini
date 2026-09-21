package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Prueba la estrategia de ADR-0006 y ADR-0007 (una pasada primero, reducir
 * fotogramas por estimación si no basta —nunca por debajo del piso de fps
 * de ADR-0007—, bisecar calidad como último recurso, `minimize_size` solo
 * cerca del límite, tope duro de tiempo) con un [SingleShotWebpEncoder]
 * falso, sin tocar la librería nativa. El [Bitmap] de cada [WebpFrame] es
 * un mock: la orquestación nunca lee sus píxeles, solo cuenta fotogramas.
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
        // número de fotogramas): con 10 fotogramas (100 ms cada uno, 1 s
        // total, piso de ADR-0007 = 5) no cabe (1_200_000); la proporción
        // por sí sola estimaría 4, pero el piso la sube a 5, que tampoco
        // cabe (750_000) — recién ahí entra la bisección de calidad, ya
        // sobre esos 5 fotogramas. Al estar ya en el piso, prueba
        // quality=0 primero (ver KDoc de QualitySearch); en este fake
        // todas las calidades caben, así que igual converge a 74, con un
        // intento más que si hubiera empezado por el medio.
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

        assertEquals(5, result.frameCount)
        assertEquals(74, result.quality)
        assertEquals(79_000, result.bytes.size)
        // 79_000 es 15.8% del límite: no está "cerca", no debió minimizarse.
        assertEquals(0, minimizeSizeCalls)
    }

    @Test
    fun `el piso de fps evita que la reduccion de fotogramas baje demasiado, aunque haga falta bajar mucho la calidad`() {
        // 20 fotogramas de 100 ms (2 s totales): el piso de ADR-0007 a 5
        // fps es 10 fotogramas. A calidad 75 cada fotograma "cuesta" 1_000_000,
        // así que la proporción por sí sola estimaría 0 (500_000 / 20_000_000),
        // muy por debajo del piso: debe clamparse a 10, no a 2.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                val count = candidateFrames.size
                if (quality == 75) ByteArray(count * 1_000_000) else ByteArray(count * 100 + quality * 100)
            },
        )

        val result = encoder.encode(frames(20))

        assertEquals(10, result.frameCount) // el piso, no los 0-2 que daría la proporción sola
        assertEquals(74, result.quality) // tuvo que bajar mucho la calidad para caber en el piso
        assertTrue(result.bytes.size <= 500_000)
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
        assertEquals(5, result.frameCount)
        // La convergencia completa de esta bisección necesita 8 intentos en
        // la fase 3 (empieza en 0, no en 37: un intento más que antes de
        // ADR-0007) más 2 de las fases 1 y 2: con el tope de tiempo debió
        // cortar antes de los 10.
        assertTrue("debió cortar antes de agotar la bisección completa (10 intentos)", calls < 10)
    }

    @Test
    fun `en el piso de fps, si solo la calidad minima cabe, un dispositivo lento igual entrega un resultado valido`() {
        // El caso real que motivó este cambio (ver ADR-0007, corrida en
        // dispositivo): a 15 fotogramas (piso de 5 fps para 3 s), de
        // ruido adverso, SOLO quality=0 cabía — bisecar desde una calidad
        // alta encontraba eso como último intento, no como primero. Con
        // latencia simulada y un tope de tiempo que solo alcanza para 1-2
        // intentos de la fase 3 (como en un dispositivo más lento que el
        // medido), el orden importa: si el primer intento probado fuera
        // una calidad alta (el comportamiento antes de este cambio), el
        // tope podría cumplirse sin haber llegado nunca a probar 0, y
        // quien usa la app se quedaría sin sticker (RF-12). Con quality=0
        // primero, ya hay un resultado válido garantizado tras el primer
        // intento de la fase 3, sin importar cuánto tiempo quede después.
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                calls++
                Thread.sleep(15)
                val count = candidateFrames.size
                when (quality) {
                    75 -> ByteArray(count * 100_000) // fase 1: no cabe
                    0 -> ByteArray(count * 6_000) // única calidad que cabe
                    else -> ByteArray(999_999) // cualquier otra calidad: tampoco cabe
                }
            },
            hardTimeLimitMs = 40, // alcanza para ~2 intentos de fase 3, no para bisecar entero
        )

        // 15 fotogramas de 200 ms = 3 s: ya está en el piso de ADR-0007 (15).
        val result = encoder.encode(frames(15, durationMs = 200))

        assertEquals(0, result.quality)
        assertTrue("el resultado entregado debe cumplir RF-10", result.bytes.size <= 500_000)
        // Con 7 valores más por explorar entre 1 y 74 (todos fallan en este
        // fake), la bisección completa necesitaría más intentos que los
        // que el tope de 40 ms permite: confirma que sí se cortó antes de
        // converger, no que coincidió con la respuesta por casualidad.
        assertTrue("debió cortar antes de terminar de bisecar", calls < 9)
    }
}
