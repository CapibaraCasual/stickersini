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

private const val TAG = "StickersiniMinimizeSizeCost"
private const val PER_ATTEMPT_TIMEOUT_MS = 60_000L
private const val QUALITY = 75

/**
 * Mide el costo de `minimizeSize=true` a `method=0` (el que fija ADR-0006
 * para producción), para justificar con datos el umbral de "cerca del
 * límite" que decide cuándo pagar ese costo. Comparar contra las filas
 * `method=0, minimizeSize=false` de [WebpEncodeMethodBenchmarkTest]
 * (1330 ms / 59672 bytes en realista, 5178 ms / 4838184 bytes en adverso).
 */
@RunWith(AndroidJUnit4::class)
class WebpMinimizeSizeCostTest {

    private val size = 512
    private val frameCount = 30
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

    @Test
    fun costoDeMinimizeSize_method0_quality75() {
        val trace = TraceWriter("webp_minimize_size_trace.txt")
        val runner = TimedAttemptRunner("webp-minimize-size")

        val contenidos = linkedMapOf(
            "adverso" to (0 until frameCount).map { i -> WebpFrame(noisyBitmap(i), frameDurationMs) },
            "realista" to (0 until frameCount).map { i -> WebpFrame(realisticBitmap(i), frameDurationMs) },
        )
        var medidas = 0

        try {
            for ((nombreContenido, frames) in contenidos) {
                val result = runner.run(PER_ATTEMPT_TIMEOUT_MS) {
                    NativeWebpEncoder.encode(frames, quality = QUALITY, minimizeSize = true, method = 0)
                }
                medidas++

                val line = if (result.timedOut) {
                    "contenido=$nombreContenido method=0 minimizeSize=true quality=$QUALITY " +
                        "TIMEOUT tras ${PER_ATTEMPT_TIMEOUT_MS}ms"
                } else {
                    "contenido=$nombreContenido method=0 minimizeSize=true quality=$QUALITY " +
                        "sizeBytes=${result.bytes!!.size} elapsedMs=${result.elapsedMs}"
                }
                Log.i(TAG, line)
                trace.line(line)
            }
        } finally {
            runner.shutdown()
            trace.close()
        }

        val resumen = "StickersiniMinimizeSizeCost: $medidas mediciones, traza completa en ${trace.file.absolutePath}"
        Log.i(TAG, resumen)
        println(resumen)

        assertTrue("deberían quedar registradas las ${contenidos.size} mediciones", medidas == contenidos.size)
    }
}
