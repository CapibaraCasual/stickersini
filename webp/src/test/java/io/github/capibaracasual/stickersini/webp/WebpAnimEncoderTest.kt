package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Prueba la estrategia de ADR-0020/ADR-0021/ADR-0022: calidad y
 * resolución se agotan SIEMPRE sobre el fotograma completo antes de tocar
 * fps o duración — fps (piso de 12) y duración (mínimo 3 s) son el último
 * y el último-último recurso, no un escalón intermedio —; una vez que
 * algo cabe, se sigue subiendo la calidad (bisección `Float`, ADR-0022)
 * hasta usar ~95% del límite de tamaño (ADR-0021), no solo la primera que
 * entra. Con un [SingleShotWebpEncoder] falso, sin tocar la librería
 * nativa. El [Bitmap] de cada [WebpFrame] es un mock: la orquestación
 * nunca lee sus píxeles, solo cuenta fotogramas.
 */
class WebpAnimEncoderTest {

    private fun frames(count: Int, durationMs: Int = 100) =
        List(count) { WebpFrame(bitmap = mock(Bitmap::class.java), durationMs = durationMs) }

    /** Tolerancia para calidades `Float`: converge dentro de [QualitySearch.CONVERGENCE_EPSILON], no a un valor exacto. */
    private val qualityTolerance = 2 * QualitySearch.CONVERGENCE_EPSILON

    @Test
    fun `si la primera pasada cabe con mucho margen, sigue subiendo calidad hasta usar el 95 por ciento del limite`() {
        // Tamaño = 5_000 x calidad (monótono, sin costo fijo): a 75 cabe
        // con mucho margen (375_000, 75%), pero ADR-0021 no se conforma con
        // eso — sigue bisecando hacia arriba hasta que el resultado use al
        // menos el 95% del límite (475_000): converge a calidad 96.875
        // (484_375, 96.9%), la primera que cruza ese umbral subiendo desde
        // 75 (75→87.5→93.75→96.875).
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, minimizeSize ->
                calls++
                assertFalse(minimizeSize) // ADR-0018: WebpAnimEncoder nunca minimiza
                ByteArray((5_000 * quality).toInt())
            },
        )

        val result = encoder.encode(frames(30))

        assertEquals(4, calls) // 75, 87.5, 93.75, 96.875
        assertEquals(96.875f, result.quality, qualityTolerance)
        assertEquals(30, result.frameCount)
        assertEquals(484_375, result.bytes.size)
    }

    @Test
    fun `si la calidad 75 no basta, bisecta calidad sobre el mismo fotograma completo, sin tocar resolucion ni fotogramas`() {
        // Tamaño monótono en calidad (8_000*quality + 50_000): a 75 no cabe
        // (650_000) — salta directo al piso (0, cabe con margen: 50_000) y
        // bisecta hacia arriba hasta cruzar el 95% (475_000): converge a
        // 56.25 (500_000, exactamente el límite) — todo esto sin necesitar
        // bajar a 384 ni reducir fotogramas (ADR-0020, escalón 1 solo).
        val degradedResolutions = mutableListOf<Int>()
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, minimizeSize ->
                assertFalse(minimizeSize)
                ByteArray((8_000 * quality + 50_000).toInt())
            },
            resolutionDegrader = { candidateFrames, resolution ->
                degradedResolutions += resolution
                candidateFrames
            },
        )

        val result = encoder.encode(frames(30))

        assertEquals(30, result.frameCount) // nunca se tocan los fotogramas
        assertEquals(56.25f, result.quality, qualityTolerance)
        assertEquals(500_000, result.bytes.size)
        assertTrue("la resolución degradada no debería usarse si 512 ya alcanzó", degradedResolutions.isEmpty())
    }

    @Test
    fun `si ni calidad ni resolucion 512 alcanzan, prueba la resolucion degradada antes de tocar fotogramas o duracion`() {
        // A 512, tamaño constante (999_999, sin importar la calidad): ni
        // calidad 75 ni el piso (0) caben ahí — ADR-0020 siembra la
        // bisección por el piso en vez de bisecar desde arriba, así que 2
        // intentos alcanzan para descartar 512 entero (en vez de hasta 7).
        // A 384, tamaño = 6_000 x calidad + 50_000: calidad 0 ya cabe con
        // mucho margen (50_000, 10%), y ADR-0021 sigue subiendo hasta usar
        // ~95% del límite: 0→50→75 (500_000, 100%, cruza el umbral).
        // Ningún fotograma se reduce ni se acorta la duración.
        val degradedResolutions = mutableListOf<Int>()
        var callCount = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, _ ->
                callCount++
                if (callCount <= 2) ByteArray(999_999) else ByteArray((6_000 * quality + 50_000).toInt())
            },
            resolutionDegrader = { candidateFrames, resolution ->
                degradedResolutions += resolution
                candidateFrames
            },
        )

        val result = encoder.encode(frames(25, durationMs = 100)) // 2.5 s: por debajo del mínimo de acortar duración

        assertEquals(25, result.frameCount) // nunca se tocan los fotogramas
        assertEquals(75f, result.quality, qualityTolerance)
        assertEquals(500_000, result.bytes.size)
        // calidad 75 a 512, calidad 0 a 512 (descarta 512 entero), luego a
        // 384: calidad 0 (50_000), 50 (350_000), 75 (500_000, cruza 95%).
        assertEquals(5, callCount)
        assertEquals(listOf(384, 384, 384), degradedResolutions) // un solo escalón de resolución, nunca 320 (ADR-0016)
    }

    @Test
    fun `si ni calidad ni resolucion alcanzan sobre el fotograma completo, el piso de fps lo resuelve sin acortar la duracion`() {
        // Tamaño = fotogramas × (10_000 + calidad × 3_000): a 80 fotogramas
        // (4 s, piso de fps de 12 → 48 fotogramas) ni calidad 0 a 512 ni a
        // 384 caben (800_000 en ambas). La proporción contra ese último
        // intento (calidad 0/384) estima 50 fotogramas — por encima del
        // piso (48), así que se prueba tal cual: a 50 fotogramas, calidad
        // 0 cabe justo (500_000, cruza el 95% de inmediato).
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                ByteArray((candidateFrames.size * (10_000 + quality * 3_000)).toInt())
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(80, durationMs = 50)) // 4 s

        assertEquals(50, result.frameCount) // reducido por el piso de fps (12), no por debajo
        assertEquals(0f, result.quality, qualityTolerance)
        assertEquals(500_000, result.bytes.size)
        assertEquals(4_000L, result.requestedDurationMs)
        assertEquals(0L, result.shortenedByMs) // la duración no se tocó
    }

    @Test
    fun `si la proporcion del piso de fps ya indica que no alcanzaria, no gasta ese intento y acorta la duracion primero`() {
        // Mismo costo por fotograma que el test anterior (10_000/fotograma
        // a calidad 0, invariante frente al número de fotogramas): con 200
        // fotogramas (5 s, el máximo de RF-06/ADR-0019) la proporción
        // siempre estima 50 fotogramas — por debajo del piso de fps para
        // 5 s (60) — así que ADR-0020 no gasta ese intento: acorta la
        // duración a 3 s primero (140 fotogramas menos), y ahí el piso de
        // fps para 3 s (36) sí admite los 50 estimados, que resuelven con
        // calidad 0 exacta.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                ByteArray((candidateFrames.size * (10_000 + quality * 3_000)).toInt())
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(200, durationMs = 25)) // 5 s

        assertEquals(50, result.frameCount)
        assertEquals(0f, result.quality, qualityTolerance)
        assertEquals(500_000, result.bytes.size)
        assertEquals(5_000L, result.requestedDurationMs)
        assertEquals(2_000L, result.shortenedByMs) // acortado a 3 s (el mínimo de ADR-0020)
    }

    @Test
    fun `si ni el piso de fps ni acortar la duracion alcanzan, entrega igual un resultado por debajo del piso (garantia de RF-12)`() {
        // Encoder falso que nunca cabe salvo con 2 fotogramas o menos: ni
        // calidad+resolución, ni el piso de fps (ya por debajo de 12 fps
        // tanto en 6 s como acortado a 3 s, así que ese escalón no tiene
        // nada que reducir), ni acortar a 3 s alcanzan — cae al último
        // recurso absoluto (ADR-0016, Fase F, sin cambios de ADR-0020).
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, _, _ ->
                if (candidateFrames.size <= 2) ByteArray(50_000) else ByteArray(999_999)
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(60, durationMs = 100)) // 6 s, 10 fps: ya por debajo del piso de 12

        assertEquals(2, result.frameCount)
        assertTrue(result.bytes.size <= 500_000)
        assertEquals(6_000L, result.requestedDurationMs)
        assertEquals(3_000L, result.shortenedByMs) // acortado al mínimo de ADR-0020
    }

    @Test
    fun `si ninguna combinacion cabe, falla informando con que se quedo mas cerca`() {
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _, _ -> ByteArray(999_999) },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val error = assertThrows(WebpEncodeException::class.java) {
            encoder.encode(frames(5, durationMs = 200)) // 1 s total
        }

        // El mensaje debe alcanzar para reproducir el caso sin volver a
        // correrlo: duración del clip, frameCount/quality/tamaño del
        // último intento, y cuánto le faltaba — no solo "no se pudo" (ver
        // reproducción de RF-12 con clip de 10 s, docs/desarrollo/pruebas.md).
        assertTrue(error.message!!.contains("1000ms"))
        assertTrue(error.message!!.contains("sizeBytes=999999"))
        assertTrue(error.message!!.contains("por encima del límite"))
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
    fun `si el tiempo estimado no alcanza para converger, entrega el mejor resultado valido encontrado dentro del fotograma completo`() {
        // A calidad 75 no cabe (3_900_000), pero cualquier otra calidad sí
        // (40_000-140_000, siempre por debajo de 500_000): con 15 ms de
        // latencia simulada y un tope de 70 ms, no alcanza para converger al
        // 95% de ocupación, pero sí para devolver algo válido sin reducir
        // fotogramas (ADR-0020: la calidad se bisecta sobre el fotograma
        // completo, no sobre un piso reducido).
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                calls++
                Thread.sleep(15)
                val count = candidateFrames.size
                if (quality == 75f) {
                    ByteArray(count * 90_000 + 300_000)
                } else {
                    ByteArray((count * 1_000 + quality * 1_000).toInt())
                }
            },
            hardTimeLimitMs = 70,
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(40, durationMs = 50)) // 2 s total

        assertTrue("el resultado entregado debe cumplir RF-10 igual", result.bytes.size <= 500_000)
        assertEquals(40, result.frameCount) // el fotograma completo, sin reducir
        assertTrue("no debió alcanzar a converger al 95% de ocupación con tan poco tiempo", result.quality < 74f)
        assertTrue("debió cortar bastante antes de agotar la bisección completa", calls < 8)
    }

    @Test
    fun `nunca lanza una codificacion sin tiempo estimado para terminar`() {
        // 15 fotogramas (3 s, ya por debajo del piso de fps de 12 — no se
        // reduce más), con una duración simulada uniforme por intento:
        // permite reconstruir, después de la corrida, cuánto tiempo
        // quedaba disponible cuando arrancó cada codificación y compararlo
        // contra lo que tardó la anterior con el mismo número de
        // fotogramas — exactamente el estimador que usa WebpAnimEncoder.
        // Ninguna, salvo la primera de un número de fotogramas nuevo (sin
        // historial todavía), debería haber arrancado con menos tiempo
        // restante del que la anterior tardó.
        data class Call(val frameCount: Int, val startElapsedMs: Long, val durationMs: Long)

        val calls = mutableListOf<Call>()
        val encoderStart = System.nanoTime()
        val hardTimeLimitMs = 90L

        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                val startElapsedMs = (System.nanoTime() - encoderStart) / 1_000_000
                val callStart = System.nanoTime()
                Thread.sleep(20)
                val durationMs = (System.nanoTime() - callStart) / 1_000_000
                calls += Call(candidateFrames.size, startElapsedMs, durationMs)

                val count = candidateFrames.size
                when (quality) {
                    75f -> ByteArray(count * 100_000)
                    0f -> ByteArray(count * 6_000)
                    else -> ByteArray(999_999)
                }
            },
            hardTimeLimitMs = hardTimeLimitMs,
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        encoder.encode(frames(15, durationMs = 200))

        assertTrue("debería haber al menos una codificación registrada", calls.isNotEmpty())
        val lastDurationByFrameCount = mutableMapOf<Int, Long>()
        for (call in calls) {
            val previousDuration = lastDurationByFrameCount[call.frameCount]
            if (previousDuration != null) {
                val remainingAtStart = hardTimeLimitMs - call.startElapsedMs
                assertTrue(
                    "se lanzó una codificación de ${call.frameCount} fotogramas con " +
                        "${remainingAtStart}ms restantes, pero la anterior con ese mismo " +
                        "número de fotogramas había tardado ${previousDuration}ms",
                    remainingAtStart >= previousDuration,
                )
            }
            lastDurationByFrameCount[call.frameCount] = call.durationMs
        }
    }
}
