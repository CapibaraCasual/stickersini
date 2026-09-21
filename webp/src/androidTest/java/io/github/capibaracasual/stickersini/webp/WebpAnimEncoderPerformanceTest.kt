package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

private const val TAG = "StickersiniPerfBaseline"

/** Ningún intento individual de codificación debería tardar más que esto. */
private const val PER_ATTEMPT_TIMEOUT_MS = 60_000L

/** Tope para toda la corrida (búsqueda + reducción de fotogramas si hiciera falta). */
private const val TOTAL_RUN_TIMEOUT_MS = 300_000L

/**
 * Línea base de rendimiento real en dispositivo para RNF-08, ya con el
 * arreglo de `minimize_size`/`method` de `webp_jni.c` aplicado (ver ADR-0006
 * y el commit que corrige el descuido). No es una prueba de aprobado/
 * reprobado contra el presupuesto de 5 segundos todavía: es la medición que
 * decide si ADR-0006 sigue haciendo falta y con qué alcance. Ver
 * docs/desarrollo/pruebas.md.
 *
 * Los límites de tiempo de esta clase son una red de seguridad para la
 * propia prueba, no una validación de RNF-08: existen para que, si algo se
 * descontrola, el test termine y reporte datos de todos modos en vez de
 * colgar la corrida indefinidamente (como pasó antes de este arreglo: más
 * de 100 s de CPU activa sin completar ni un intento).
 *
 * La corrida del 2026-09-20 (14 intentos, corte por timeout de corrida
 * completa) perdió los intentos 1 a 12 del búfer circular de logcat: por
 * eso, además de `Log.i`, cada línea se vuelca de inmediato a un archivo
 * (ver [TraceWriter]) que sobrevive aunque logcat se sature o el proceso
 * muera a mitad de la corrida.
 */
@RunWith(AndroidJUnit4::class)
class WebpAnimEncoderPerformanceTest {

    /**
     * Envuelve [delegate] con un límite de tiempo por intento individual,
     * usando [TimedAttemptRunner], y registra cada intento en [trace]
     * además de logcat.
     */
    private class MeasuringEncoder(
        private val delegate: SingleShotWebpEncoder,
        private val trace: TraceWriter,
    ) : SingleShotWebpEncoder {
        data class Attempt(
            val quality: Int,
            val minimizeSize: Boolean,
            val sizeBytes: Int?,
            val elapsedMs: Long,
            val timedOut: Boolean,
        )

        val attempts = mutableListOf<Attempt>()
        private val runner = TimedAttemptRunner("webp-attempt")

        override fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean): ByteArray {
            val attemptNumber = attempts.size + 1
            val result = runner.run(PER_ATTEMPT_TIMEOUT_MS) { delegate.encode(frames, quality, minimizeSize) }
            attempts += Attempt(quality, minimizeSize, result.bytes?.size, result.elapsedMs, result.timedOut)

            if (result.timedOut) {
                val message = "intento #$attemptNumber: quality=$quality minimizeSize=$minimizeSize " +
                    "TIMEOUT tras ${PER_ATTEMPT_TIMEOUT_MS}ms, abortando la corrida"
                Log.w(TAG, message)
                trace.line(message)
                throw WebpEncodeException(
                    "Intento #$attemptNumber (quality=$quality, minimizeSize=$minimizeSize) " +
                        "superó el límite de ${PER_ATTEMPT_TIMEOUT_MS}ms por intento.",
                )
            }

            val message = "intento #$attemptNumber: quality=$quality minimizeSize=$minimizeSize " +
                "sizeBytes=${result.bytes!!.size} elapsedMs=${result.elapsedMs}"
            Log.i(TAG, message)
            trace.line(message)
            return result.bytes
        }

        fun shutdown() {
            runner.shutdown()
        }
    }

    private val size = 512

    /**
     * Ruido pseudoaleatorio independiente por fotograma: nada que la
     * codificación entre fotogramas de `minimize_size` pueda aprovechar, y
     * poco que la compresión intra-fotograma pueda aprovechar tampoco. Es
     * el contenido realista más adverso para la búsqueda de calidad, sin
     * forzar artificialmente un número de intentos concreto.
     */
    private fun noisyBitmap(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    @Test
    fun lineaBaseDeRendimiento_30fotogramas_contenidoAdverso() {
        // 30 fotogramas a 100 ms = 3 s, la referencia literal de RNF-08.
        val frameCount = 30
        val frameDurationMs = 100
        val frames = (0 until frameCount).map { i -> WebpFrame(noisyBitmap(seed = i), frameDurationMs) }

        val trace = TraceWriter("webp_perf_trace.txt")
        val measuring = MeasuringEncoder(NativeWebpEncoder, trace)
        val encoder = WebpAnimEncoder(singleShotEncoder = measuring)
        val runExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "webp-run").apply { isDaemon = true }
        }

        var outcome: String
        var result: WebpEncodeResult? = null
        val wallClockStart = System.nanoTime()
        try {
            val future = runExecutor.submit<WebpEncodeResult> { encoder.encode(frames) }
            result = future.get(TOTAL_RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            outcome = "exito"
        } catch (timeout: TimeoutException) {
            outcome = "timeout_corrida_completa (>${TOTAL_RUN_TIMEOUT_MS}ms)"
        } catch (error: Exception) {
            // Cubre ExecutionException (WebpEncodeException real de RF-12,
            // o la que lanza MeasuringEncoder por timeout de un intento).
            outcome = "excepcion: ${error.cause?.message ?: error.message}"
        } finally {
            runExecutor.shutdownNow()
            measuring.shutdown()
        }
        val totalMs = (System.nanoTime() - wallClockStart) / 1_000_000

        val totalLine = "TOTAL: intentos=${measuring.attempts.size} tiempoTotalMs=$totalMs outcome=$outcome " +
            "resultadoQuality=${result?.quality} resultadoBytes=${result?.bytes?.size} " +
            "resultadoFrameCount=${result?.frameCount} (entrada: $frameCount fotogramas)"
        Log.i(TAG, "=== Línea base RNF-08: $frameCount fotogramas, contenido adverso ===")
        Log.i(TAG, totalLine)
        trace.line(totalLine)
        trace.close()
        println("StickersiniPerfBaseline: $totalLine (traza completa en ${trace.file.absolutePath})")

        // Sanidad mínima, no presupuesto de tiempo: esto es una línea base,
        // no todavía una validación de RNF-08. Debe haber datos siempre,
        // incluso si son datos de fracaso (por eso no se afirma "exito").
        assertTrue("debería haber al menos 1 intento registrado, incluso si fue timeout", measuring.attempts.isNotEmpty())
    }
}
