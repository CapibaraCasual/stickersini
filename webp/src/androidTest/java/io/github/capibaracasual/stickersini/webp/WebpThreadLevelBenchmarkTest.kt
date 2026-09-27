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

private const val TAG = "StickersiniThreadLevelBenchmark"
private const val PER_ATTEMPT_TIMEOUT_MS = 60_000L
private const val QUALITY = 75
private const val RUNS_PER_CELL = 5

/** 24 ≈ fps=8/3 s, 80 ≈ fps=8/10 s (el máximo de RF-06): los dos extremos que importan. */
private val FRAME_COUNTS = listOf(24, 80)

/**
 * Vía pedida: si paralelizar la codificación de fotogramas da algo
 * parecido al 1.75×-2.75× que dio paralelizar el decode. `WebPAnimEncoder`
 * no ofrece eso — es una API de streaming, `WebPAnimEncoderAdd` necesita
 * el fotograma anterior para decidir si el actual sale como diferencia o
 * como cuadro clave, así que solo puede llamarse en orden, un fotograma a
 * la vez (ver el comentario en `webp_jni.c`). Lo único real que
 * `WebPConfig` ofrece en ese sentido es `thread_level`, que paraleliza dos
 * cosas DENTRO de un fotograma, no entre fotogramas: el canal alfa (no
 * aplica, los fotogramas de video son opacos — libwebp detecta eso y ni
 * siquiera codifica un canal alfa) y, solo para `method<=1` (el caso de
 * producción, ADR-0006), la fase de análisis/segmentación
 * (`VP8EncAnalyze`), partida en dos mitades entre el hilo principal y uno
 * nuevo. Esta clase mide si esa única vía real vale la pena.
 *
 * `minimizeSize=false` (mismo motivo que `WebpEncodeMethodBenchmarkTest`:
 * aísla el costo de una codificación, no el de la búsqueda de
 * `WebpAnimEncoder`), quality=75 fija, method=0 fijo (producción,
 * ADR-0006) — la única variable es `threadLevel`. 5 corridas por celda,
 * dos tipos de contenido (adverso y "realista", mismos generadores que
 * `WebpEncodeMethodBenchmarkTest`) y dos tamaños de animación (24 y 80
 * fotogramas). Compara también `sizeBytes` entre threadLevel 0 y 1: si de
 * verdad solo cambia cómputo interno (no la decisión de compresión en sí),
 * el tamaño de salida no debería moverse.
 */
@RunWith(AndroidJUnit4::class)
class WebpThreadLevelBenchmarkTest {

    private val size = 512
    private val frameDurationMs = 100

    private fun noisyBitmap(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    private fun realisticBitmap(index: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val gradient = LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            Color.rgb(30, 30, 40), Color.rgb(60, 60, 90),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { shader = gradient })
        canvas.drawRect(40f, 40f, size - 40f, 140f, Paint().apply { color = Color.rgb(245, 245, 245) })
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

    @Test
    fun threadLevelCeroContraUno_porContenidoYCantidadDeFotogramas() {
        val trace = TraceWriter("webp_thread_level_trace.txt")
        val runner = TimedAttemptRunner("webp-thread-level")

        try {
            for (frameCount in FRAME_COUNTS) {
                val contenidos = linkedMapOf(
                    "adverso" to (0 until frameCount).map { i -> WebpFrame(noisyBitmap(i), frameDurationMs) },
                    "realista" to (0 until frameCount).map { i -> WebpFrame(realisticBitmap(i), frameDurationMs) },
                )
                for ((nombreContenido, frames) in contenidos) {
                    for (threadLevel in listOf(0, 1)) {
                        val tiempos = mutableListOf<Long>()
                        var sizeBytes: Int? = null
                        repeat(RUNS_PER_CELL) {
                            val result = runner.run(PER_ATTEMPT_TIMEOUT_MS) {
                                NativeWebpEncoder.encode(frames, quality = QUALITY, minimizeSize = false, method = 0, threadLevel = threadLevel)
                            }
                            val line = if (result.timedOut) {
                                "frameCount=$frameCount contenido=$nombreContenido threadLevel=$threadLevel TIMEOUT tras ${PER_ATTEMPT_TIMEOUT_MS}ms"
                            } else {
                                sizeBytes = result.bytes!!.size
                                tiempos += result.elapsedMs
                                "frameCount=$frameCount contenido=$nombreContenido threadLevel=$threadLevel " +
                                    "sizeBytes=${result.bytes.size} elapsedMs=${result.elapsedMs}"
                            }
                            Log.i(TAG, line)
                            trace.line(line)
                        }
                        if (tiempos.isNotEmpty()) {
                            val sorted = tiempos.sorted()
                            val resumen = "RESUMEN frameCount=$frameCount contenido=$nombreContenido threadLevel=$threadLevel " +
                                "medianaMs=${sorted[sorted.size / 2]} rangoMs=${sorted.first()}-${sorted.last()} sizeBytes=$sizeBytes"
                            Log.i(TAG, resumen)
                            trace.line(resumen)
                        }
                    }
                }
            }
        } finally {
            runner.shutdown()
            trace.close()
        }

        val resumen = "StickersiniThreadLevelBenchmark: traza completa en ${trace.file.absolutePath}"
        Log.i(TAG, resumen)
        println(resumen)
        assertTrue("la corrida debe dejar traza pase lo que pase con los tiempos", trace.file.exists())
    }
}
