package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

private const val TAG = "StickersiniPerfBaseline"

/**
 * Línea base de rendimiento real en dispositivo para RNF-08, con el
 * algoritmo tal como está hoy — sin acotar iteraciones, sin separar
 * codificación de búsqueda de codificación final. No es una prueba de
 * aprobado/reprobado contra el presupuesto de 5 segundos: es la medición
 * que decide si ADR-0006 (propuesto, no implementado) hace falta y con qué
 * alcance. Ver docs/desarrollo/pruebas.md.
 *
 * Envuelve [NativeWebpEncoder] con [MeasuringEncoder] en vez de tocar
 * producción: WebpAnimEncoder ya acepta un [SingleShotWebpEncoder]
 * inyectado para esto exactamente.
 */
@RunWith(AndroidJUnit4::class)
class WebpAnimEncoderPerformanceTest {

    private class MeasuringEncoder(private val delegate: SingleShotWebpEncoder) : SingleShotWebpEncoder {
        data class Attempt(val quality: Int, val sizeBytes: Int, val elapsedMs: Long)

        val attempts = mutableListOf<Attempt>()

        override fun encode(frames: List<WebpFrame>, quality: Int): ByteArray {
            val start = System.nanoTime()
            val bytes = delegate.encode(frames, quality)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            attempts += Attempt(quality, bytes.size, elapsedMs)
            return bytes
        }
    }

    private val size = 512

    /**
     * Ruido pseudoaleatorio independiente por fotograma: nada que la
     * codificación entre fotogramas de `minimize_size` pueda aprovechar, y
     * poco que la compresión intra-fotograma pueda aprovechar tampoco. Es
     * el contenido realista más adverso para la búsqueda de calidad, sin
     * forzar artificialmente un número de intentos concreto: el número real
     * de intentos es parte de lo que esta prueba mide, no algo que se fije
     * de antemano.
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

        val measuring = MeasuringEncoder(NativeWebpEncoder)
        val encoder = WebpAnimEncoder(singleShotEncoder = measuring)

        val wallClockStart = System.nanoTime()
        val result = try {
            encoder.encode(frames)
        } catch (error: WebpEncodeException) {
            Log.w(TAG, "encode() lanzó WebpEncodeException, se registra igual: ${error.message}")
            null
        }
        val totalMs = (System.nanoTime() - wallClockStart) / 1_000_000

        Log.i(TAG, "=== Línea base RNF-08: $frameCount fotogramas, contenido adverso ===")
        measuring.attempts.forEachIndexed { index, attempt ->
            Log.i(
                TAG,
                "intento #${index + 1}: quality=${attempt.quality} sizeBytes=${attempt.sizeBytes} " +
                    "elapsedMs=${attempt.elapsedMs}",
            )
        }
        Log.i(
            TAG,
            "TOTAL: intentos=${measuring.attempts.size} tiempoTotalMs=$totalMs " +
                "resultadoQuality=${result?.quality} resultadoBytes=${result?.bytes?.size} " +
                "resultadoFrameCount=${result?.frameCount} (entrada: $frameCount fotogramas) " +
                "exito=${result != null}",
        )
        println(
            "StickersiniPerfBaseline: intentos=${measuring.attempts.size} tiempoTotalMs=$totalMs " +
                "resultadoQuality=${result?.quality} resultadoFrameCount=${result?.frameCount} " +
                "exito=${result != null}",
        )

        // Sanidad mínima, no presupuesto de tiempo: esto es una línea base,
        // no todavía una validación de RNF-08.
        assertTrue("debería haber al menos 1 intento de codificación", measuring.attempts.isNotEmpty())
    }
}
