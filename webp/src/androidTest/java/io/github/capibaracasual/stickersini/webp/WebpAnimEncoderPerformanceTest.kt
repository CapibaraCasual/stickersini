package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

private const val TAG = "StickersiniPerfBaseline"

/** Ningún intento individual de codificación debería tardar más que esto. */
private const val PER_ATTEMPT_TIMEOUT_MS = 60_000L

/**
 * Red de seguridad del propio test, no la de producción: [WebpAnimEncoder]
 * ya tiene su tope duro de 20 s (RNF-08, ADR-0006). Este límite es más
 * generoso a propósito, por si ese tope tuviera un bug y no cortara.
 */
private const val TOTAL_RUN_TIMEOUT_MS = 60_000L

/**
 * Línea base de rendimiento real en dispositivo para RNF-08, con la
 * estrategia de ADR-0006 ya implementada: una sola pasada a calidad fija
 * primero, reducción de fotogramas por proporción si no basta, bisección
 * de calidad como último recurso, `minimize_size` solo cerca del límite, y
 * un tope duro de 20 s. Corre el orquestador real ([WebpAnimEncoder]) de
 * punta a punta sobre [NativeWebpEncoder], sobre los dos tipos de
 * contenido medidos en ADR-0006 (adverso y realista), para ver si la
 * estrategia resuelve el caso representativo y cómo se comporta el caso
 * adverso bajo el tope de tiempo. Ver docs/desarrollo/pruebas.md.
 *
 * Los límites de tiempo de esta clase son una red de seguridad para la
 * propia prueba, no la validación de RNF-08 en sí: existen para que, si
 * algo se descontrola, el test termine y reporte datos de todos modos.
 */
@RunWith(AndroidJUnit4::class)
class WebpAnimEncoderPerformanceTest {

    /**
     * Envuelve [delegate] contando y cronometrando cada llamada a
     * `encode`, con límite de tiempo por intento vía [TimedAttemptRunner],
     * y registrando cada una en [trace] además de logcat.
     */
    private class MeasuringEncoder(
        private val delegate: SingleShotWebpEncoder,
        private val trace: TraceWriter,
        private val contentLabel: String,
    ) : SingleShotWebpEncoder {
        var callCount = 0
            private set

        private val runner = TimedAttemptRunner("webp-attempt")

        override fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean): ByteArray {
            callCount++
            val attemptNumber = callCount
            val result = runner.run(PER_ATTEMPT_TIMEOUT_MS) { delegate.encode(frames, quality, minimizeSize) }

            if (result.timedOut) {
                val message = "contenido=$contentLabel intento #$attemptNumber: frameCount=${frames.size} " +
                    "quality=$quality minimizeSize=$minimizeSize TIMEOUT tras ${PER_ATTEMPT_TIMEOUT_MS}ms"
                Log.w(TAG, message)
                trace.line(message)
                throw WebpEncodeException(
                    "Intento #$attemptNumber (quality=$quality, minimizeSize=$minimizeSize) " +
                        "superó el límite de ${PER_ATTEMPT_TIMEOUT_MS}ms por intento.",
                )
            }

            val message = "contenido=$contentLabel intento #$attemptNumber: frameCount=${frames.size} " +
                "quality=$quality minimizeSize=$minimizeSize sizeBytes=${result.bytes!!.size} " +
                "elapsedMs=${result.elapsedMs}"
            Log.i(TAG, message)
            trace.line(message)
            return result.bytes
        }

        fun shutdown() {
            runner.shutdown()
        }
    }

    private val size = 512
    private val frameCount = 30
    private val frameDurationMs = 100

    /** Ruido pseudoaleatorio independiente por fotograma: el peor caso posible. */
    private fun noisyBitmap(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /**
     * Degradado + zona plana + forma en movimiento + texto: aproxima una
     * grabación de pantalla real, igual que en `WebpEncodeMethodBenchmarkTest`.
     */
    private fun realisticBitmap(index: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val gradient = LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            Color.rgb(30, 30, 40), Color.rgb(60, 60, 90),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { shader = gradient })

        canvas.drawRect(
            40f, 40f, size - 40f, 140f,
            Paint().apply { color = Color.rgb(245, 245, 245) },
        )

        val shapeX = 60f + (index % 20) * 18f
        canvas.drawCircle(shapeX, size / 2f, 30f, Paint().apply { color = Color.rgb(0, 150, 220) })

        canvas.drawText(
            "Stickersini frame $index",
            50f,
            100f,
            Paint().apply {
                color = Color.BLACK
                textSize = 36f
                isAntiAlias = true
            },
        )

        return bitmap
    }

    private fun runStrategy(contentLabel: String, frames: List<WebpFrame>, trace: TraceWriter, runExecutor: java.util.concurrent.ExecutorService) {
        val measuring = MeasuringEncoder(NativeWebpEncoder, trace, contentLabel)
        val encoder = WebpAnimEncoder(singleShotEncoder = measuring)

        var outcome: String
        var result: WebpEncodeResult? = null
        val wallClockStart = System.nanoTime()
        try {
            val future = runExecutor.submit<WebpEncodeResult> { encoder.encode(frames) }
            result = future.get(TOTAL_RUN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            outcome = "exito"
        } catch (timeout: java.util.concurrent.TimeoutException) {
            outcome = "timeout_corrida_completa (>${TOTAL_RUN_TIMEOUT_MS}ms)"
        } catch (error: Exception) {
            outcome = "excepcion: ${error.cause?.message ?: error.message}"
        } finally {
            measuring.shutdown()
        }
        val totalMs = (System.nanoTime() - wallClockStart) / 1_000_000

        val totalLine = "TOTAL contenido=$contentLabel: codificaciones=${measuring.callCount} " +
            "tiempoTotalMs=$totalMs outcome=$outcome resultadoQuality=${result?.quality} " +
            "resultadoBytes=${result?.bytes?.size} resultadoFrameCount=${result?.frameCount} " +
            "(entrada: ${frames.size} fotogramas)"
        Log.i(TAG, totalLine)
        trace.line(totalLine)
        println("StickersiniPerfBaseline: $totalLine")
    }

    @Test
    fun estrategiaAdr0006_30fotogramas_ambosContenidos() {
        val trace = TraceWriter("webp_strategy_trace.txt")
        val runExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "webp-run").apply { isDaemon = true }
        }

        val adverso = (0 until frameCount).map { i -> WebpFrame(noisyBitmap(i), frameDurationMs) }
        val realista = (0 until frameCount).map { i -> WebpFrame(realisticBitmap(i), frameDurationMs) }

        try {
            runStrategy("adverso", adverso, trace, runExecutor)
            runStrategy("realista", realista, trace, runExecutor)
        } finally {
            runExecutor.shutdownNow()
            trace.close()
        }

        println("StickersiniPerfBaseline: traza completa en ${trace.file.absolutePath}")
        assertTrue("el test debe terminar y dejar traza, pase lo que pase con los tiempos", trace.file.exists())
    }
}
