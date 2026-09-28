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
    fun `si la primera pasada cabe de sobra, no busca mas`() {
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, quality, minimizeSize ->
                calls++
                assertEquals(75, quality)
                assertFalse(minimizeSize) // ADR-0018: WebpAnimEncoder nunca minimiza
                ByteArray(50_000) // 10% del límite de 500_000
            },
        )

        val result = encoder.encode(frames(30))

        assertEquals(1, calls)
        assertEquals(75, result.quality)
        assertEquals(30, result.frameCount)
        assertEquals(50_000, result.bytes.size)
    }

    @Test
    fun `si la calidad fija no basta, reduce fotogramas por proporcion en un solo paso`() {
        // tamaño simulado = fotogramas x 20_000, sin depender de la calidad
        // (fases 1 y 2 siempre codifican a 75): con 40 fotogramas no cabe
        // (800_000), la proporción estima 25 fotogramas (500_000, justo
        // cabe) — por encima del piso fijo de 15 (ADR-0016), así que la
        // proporción se usa tal cual, sin clampar.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, minimizeSize ->
                assertEquals(75, quality)
                assertFalse(minimizeSize) // ADR-0018: WebpAnimEncoder nunca minimiza
                ByteArray(candidateFrames.size * 20_000)
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(40))

        assertEquals(25, result.frameCount)
        assertEquals(75, result.quality)
        assertEquals(500_000, result.bytes.size) // 100% del límite, justo cabe
    }

    @Test
    fun `si reducir fotogramas no basta, bisecta calidad despues`() {
        // A calidad 75 el tamaño no baja de forma proporcional al reducir
        // fotogramas (hay un costo fijo de 300_000 que no depende del
        // número de fotogramas): con 40 fotogramas no cabe (3_900_000); la
        // proporción por sí sola estimaría 5, muy por debajo del piso fijo
        // de 15 (ADR-0016) — como queda clampado, ADR-0016 no gasta un
        // intento repitiendo calidad 75 a 15 fotogramas (ya se sabe, por la
        // propia proporción, que no alcanzaría) y entra directo a la
        // bisección de calidad sobre esos 15, probando quality=0 primero
        // (piso, ver KDoc de QualitySearch). En este fake todas las
        // calidades caben, así que igual converge a 74, con un intento más
        // que si hubiera empezado por el medio.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, minimizeSize ->
                assertFalse(minimizeSize) // ADR-0018: WebpAnimEncoder nunca minimiza
                val count = candidateFrames.size
                if (quality == 75) {
                    ByteArray(count * 90_000 + 300_000)
                } else {
                    ByteArray(count * 1_000 + quality * 1_000)
                }
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(40, durationMs = 50)) // 2 s total: por debajo del mínimo de acortar duración

        assertEquals(15, result.frameCount)
        assertEquals(74, result.quality)
        assertEquals(89_000, result.bytes.size)
    }

    @Test
    fun `el piso fijo de fotogramas evita que la reduccion baje de 15, sin importar la duracion del clip`() {
        // 20 fotogramas de 100 ms (2 s totales, por debajo del mínimo de
        // acortar duración): a calidad 75 cada fotograma "cuesta" 1_000_000,
        // así que la proporción por sí sola estimaría 0 (500_000 /
        // 20_000_000), muy por debajo del piso: debe clamparse a 15
        // (ADR-0016 — antes de este cambio, el piso salía de la duración
        // del clip; ahora es siempre 15, sin importar cuánto dure).
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                val count = candidateFrames.size
                if (quality == 75) ByteArray(count * 1_000_000) else ByteArray(count * 100 + quality * 100)
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(20))

        assertEquals(15, result.frameCount) // el piso fijo, no los 0-2 que daría la proporción sola
        assertEquals(74, result.quality) // tuvo que bajar mucho la calidad para caber en el piso
        assertTrue(result.bytes.size <= 500_000)
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
        // correrlo: duración del clip, piso de fotogramas (ADR-0007),
        // frameCount/quality/tamaño del último intento, y cuánto le
        // faltaba — no solo "no se pudo" (ver reproducción de RF-12 con
        // clip de 10 s, docs/desarrollo/pruebas.md).
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
    fun `si el tiempo estimado no alcanza para otra codificacion, entrega el mejor resultado valido encontrado`() {
        // Mismo escenario que "reducir no basta, bisecta calidad", pero con
        // 15 ms de latencia simulada por intento y un tope de 70 ms: no
        // alcanza para converger a la calidad óptima (74), pero sí para
        // devolver algo válido en vez de fallar o colgarse. Con la
        // estimación de duración por número de fotogramas, el corte ahora
        // es proactivo (no se lanza la codificación que no iba a alcanzar
        // a terminar), no solo reactivo como antes de este cambio.
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
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(40, durationMs = 50)) // 2 s total

        assertTrue("el resultado entregado debe cumplir RF-10 igual", result.bytes.size <= 500_000)
        assertEquals(15, result.frameCount) // el piso, fijado en la fase 2 antes de que el tiempo apriete
        assertTrue("no debió alcanzar a converger a la calidad óptima (74) con tan poco tiempo", result.quality < 74)
        assertTrue("debió cortar bastante antes de agotar la bisección completa", calls < 8)
    }

    @Test
    fun `no repite un intento de calidad 75 que la propia proporcion ya dice que no alcanzaria en el piso`() {
        // Corrección de diseño de ADR-0016, motivada por la reproducción
        // real del fallo de RF-12 (docs/desarrollo/pruebas.md): con 80
        // fotogramas y un contenido tan adverso que la proporción estima
        // menos fotogramas de los que el piso permite, repetir calidad 75
        // ya en el piso es un intento que la propia matemática de la
        // proporción ya dice que no va a caber — no debe gastarse. Calidad
        // 0 cabe de entrada sin margen amplio (no dispara la búsqueda hacia
        // arriba), así que un solo intento alcanza en el piso.
        var calls = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                calls++
                val count = candidateFrames.size
                if (quality == 75) ByteArray(count * 200_000) else ByteArray(300_000)
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(80, durationMs = 30)) // 2.4 s: por debajo del mínimo de acortar duración, aísla esta optimización de esa otra

        assertEquals(15, result.frameCount)
        assertEquals(0, result.quality)
        // Fase 1 (80 fotogramas, calidad 75) + un solo intento en el piso
        // (15 fotogramas, calidad 0): sin el intento redundante de calidad
        // 75 a 15 fotogramas que la proporción ya descartaba.
        assertEquals(2, calls)
    }

    @Test
    fun `si la proporcion clampa contra el piso en un clip largo, acorta la duracion en vez de aceptar el piso estirado`() {
        // Corrección de diseño encontrada al confirmar ADR-0016 en
        // dispositivo real (docs/desarrollo/pruebas.md): el piso fijo de
        // 15 fotogramas, estirado sobre los 10 s completos del clip que
        // motivó este ADR, SÍ llegaba a caber (15 fotogramas repartidos en
        // 10 s son 1.5 fps) — pero eso invierte el orden de sacrificio que
        // pide ADR-0016 (duración antes que fluidez): con este fake, el
        // piso estirado sobre los 8 s completos cabría igual si se
        // aceptara sin más (calidad 0 siempre cabe, sin importar cuántos
        // fotogramas ni qué duración representen), así que la única forma
        // de confirmar que se prefirió acortar es mirar cuánto se acortó.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, quality, _ ->
                if (quality == 75) ByteArray(candidateFrames.size * 200_000) else ByteArray(300_000)
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(80, durationMs = 100)) // 8 s: por encima del mínimo de acortar duración

        assertEquals(15, result.frameCount)
        assertEquals(8_000L, result.requestedDurationMs)
        assertTrue("debió acortar la duración en vez de estirar el piso sobre los 8 s completos", result.shortenedByMs > 0)
        assertEquals(3_000L, result.frameDurationsMs.sumOf { it.toLong() }) // acortado al mínimo de ADR-0016
    }

    @Test
    fun `si ni calidad ni piso alcanzan a 512, prueba la resolucion degradada antes de tocar la duracion`() {
        // ADR-0016, escalón 2: agotada la calidad a 512 (ni siquiera
        // calidad 0 cupo), el siguiente escalón es 384, no acortar la
        // duración todavía. `resolutionDegrader` es el punto de inyección
        // (`Bitmap.createScaledBitmap` real no funciona en un test JVM
        // puro): acá se espía qué resolución pide en vez de escalar de
        // verdad, y el encoder falso hace caber recién el tercer intento
        // (calidad 0 a 384), simulando que ni Fase 1 ni el piso a 512
        // encontraron nada.
        val degradedResolutions = mutableListOf<Int>()
        var callCount = 0
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { _, _, _ ->
                callCount++
                if (callCount < 3) ByteArray(999_999) else ByteArray(300_000)
            },
            resolutionDegrader = { candidateFrames, resolution ->
                degradedResolutions += resolution
                candidateFrames
            },
        )

        val result = encoder.encode(frames(15, durationMs = 100)) // 1.5 s: por debajo del mínimo de acortar duración

        assertEquals(15, result.frameCount)
        assertEquals(0, result.quality)
        assertEquals(300_000, result.bytes.size)
        assertEquals(3, callCount) // calidad 75 a 512, calidad 0 a 512, calidad 0 a 384
        assertEquals(listOf(384), degradedResolutions) // un solo escalón de resolución, nunca 320 (ADR-0016)
    }

    @Test
    fun `si ni calidad ni resolucion alcanzan a la duracion completa, acorta el clip antes de bajar del piso`() {
        // ADR-0016, escalón 3: un clip de 6 s (por encima del mínimo de
        // 3 s antes del último recurso) donde ni 512 ni 384 alcanzan a
        // ningún número de fotogramas. Antes de estirar el piso de 15
        // fotogramas sobre los 6 s completos (2.5 fps, choppy), se acorta
        // el clip a 3 s y se repite calidad+resolución sobre ese tramo más
        // corto — acá con un encoder falso que hace caber cualquier cosa
        // codificada sobre fotogramas ya acortados (identificados por
        // `durationMs`, que `FrameTiming.trimToDuration` no toca).
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, _, _ ->
                val isTrimmed = candidateFrames.sumOf { it.durationMs } <= 3_000
                if (isTrimmed) ByteArray(300_000) else ByteArray(999_999)
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(60, durationMs = 100)) // 6 s

        assertEquals(3_000L, result.frameDurationsMs.sumOf { it.toLong() })
        assertEquals(6_000L, result.requestedDurationMs)
        assertEquals(3_000L, result.shortenedByMs) // la interfaz usa esto para avisar cuánto se acortó
        assertTrue(result.bytes.size <= 500_000)
    }

    @Test
    fun `si ni acortar la duracion alcanza, entrega igual un resultado por debajo del piso (garantia de RF-12)`() {
        // ADR-0016, escalón 4 (red de seguridad final): ninguna medición
        // encontró un caso que llegue hasta acá, pero RF-12 exige que
        // siempre haya una salida. Encoder falso que nunca cabe salvo con
        // 2 fotogramas o menos: ni acortar a 3 s con calidad+resolución
        // alcanza, así que cae al último recurso.
        val encoder = WebpAnimEncoder(
            singleShotEncoder = SingleShotWebpEncoder { candidateFrames, _, _ ->
                if (candidateFrames.size <= 2) ByteArray(50_000) else ByteArray(999_999)
            },
            resolutionDegrader = { candidateFrames, _ -> candidateFrames },
        )

        val result = encoder.encode(frames(60, durationMs = 100)) // 6 s

        assertEquals(2, result.frameCount) // por debajo del piso normal de 15: el último recurso
        assertTrue(result.bytes.size <= 500_000)
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

    @Test
    fun `nunca lanza una codificacion sin tiempo estimado para terminar`() {
        // 15 fotogramas ya en el piso (200 ms x 15 = 3 s), con una
        // duración simulada uniforme por intento: permite reconstruir,
        // después de la corrida, cuánto tiempo quedaba disponible cuando
        // arrancó cada codificación y compararlo contra lo que tardó la
        // anterior con el mismo número de fotogramas — exactamente el
        // estimador que usa WebpAnimEncoder. Ninguna, salvo la primera de
        // un número de fotogramas nuevo (sin historial todavía), debería
        // haber arrancado con menos tiempo restante del que la anterior
        // tardó.
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
                    75 -> ByteArray(count * 100_000)
                    0 -> ByteArray(count * 6_000)
                    else -> ByteArray(999_999)
                }
            },
            hardTimeLimitMs = hardTimeLimitMs,
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
