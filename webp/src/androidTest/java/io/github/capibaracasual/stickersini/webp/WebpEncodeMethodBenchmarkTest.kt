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

private const val TAG = "StickersiniMethodBenchmark"

/** Igual que en [WebpAnimEncoderPerformanceTest]: red de seguridad, no presupuesto. */
private const val PER_ATTEMPT_TIMEOUT_MS = 60_000L

/**
 * Calidad fija, sin bisección: no es la calidad que elegiría RF-12, es solo
 * un punto medio representativo para comparar `method` y contenido entre sí.
 */
private const val QUALITY = 75

/** 0=rápido, 6=más lento y mejor compresión (documentado por libwebp). */
private val METHODS = listOf(0, 2, 4, 6)

/**
 * Piso real de UNA sola codificación (sin bisección de calidad, sin
 * `minimize_size`: `minimizeSize=false` es la opción más barata posible),
 * a `method` y calidad fijos, con dos tipos de contenido. Aísla el costo
 * por codificación del costo de la búsqueda de [WebpAnimEncoder]: llama a
 * [NativeWebpEncoder] directamente en vez de pasar por el orquestador.
 *
 * Existe porque la línea base de [WebpAnimEncoderPerformanceTest] (30
 * fotogramas de ruido adverso, con búsqueda) midió ~13 s por codificación
 * el 2026-09-20 en un Redmi Note 14 — más del doble del presupuesto de 5 s
 * de RNF-08 — y no está claro cuánto de eso es el costo mínimo de
 * codificar contra cuánto es el número de intentos de la búsqueda. Ver
 * docs/desarrollo/pruebas.md y ADR-0006 (todavía en Propuesto).
 *
 * Cota de tiempo total conocida de antemano: 2 contenidos x 4 methods = 8
 * codificaciones, cada una acotada por [PER_ATTEMPT_TIMEOUT_MS]; en el peor
 * caso (las 8 agotan el límite) la corrida completa tarda como mucho
 * 8 x 60 s = 8 min. No hay bucle ni reintento que pueda alargarla más.
 */
@RunWith(AndroidJUnit4::class)
class WebpEncodeMethodBenchmarkTest {

    private val size = 512
    private val frameCount = 30
    private val frameDurationMs = 100

    /** Ruido puro: el contenido más adverso posible, igual que en la línea base. */
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
     * Degradado de fondo + zona plana + forma en movimiento + texto:
     * aproxima una grabación de pantalla real (tema con degradado, tarjeta
     * de UI, transición, etiqueta), a diferencia del ruido puro que solo
     * mide el peor caso posible, no un caso típico.
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

    private data class Medicion(
        val contenido: String,
        val method: Int,
        val sizeBytes: Int?,
        val elapsedMs: Long,
        val timedOut: Boolean,
    )

    @Test
    fun pisoDeCodificacion_porMethodYContenido() {
        val trace = TraceWriter("webp_benchmark_trace.txt")
        val runner = TimedAttemptRunner("webp-benchmark")

        val contenidos = linkedMapOf(
            "adverso" to (0 until frameCount).map { i -> WebpFrame(noisyBitmap(i), frameDurationMs) },
            "realista" to (0 until frameCount).map { i -> WebpFrame(realisticBitmap(i), frameDurationMs) },
        )
        val mediciones = mutableListOf<Medicion>()

        try {
            for ((nombreContenido, frames) in contenidos) {
                for (method in METHODS) {
                    val result = runner.run(PER_ATTEMPT_TIMEOUT_MS) {
                        NativeWebpEncoder.encode(frames, quality = QUALITY, minimizeSize = false, method = method)
                    }
                    mediciones += Medicion(nombreContenido, method, result.bytes?.size, result.elapsedMs, result.timedOut)

                    val line = if (result.timedOut) {
                        "contenido=$nombreContenido method=$method quality=$QUALITY TIMEOUT tras ${PER_ATTEMPT_TIMEOUT_MS}ms"
                    } else {
                        "contenido=$nombreContenido method=$method quality=$QUALITY " +
                            "sizeBytes=${result.bytes!!.size} elapsedMs=${result.elapsedMs}"
                    }
                    Log.i(TAG, line)
                    trace.line(line)
                }
            }
        } finally {
            runner.shutdown()
            trace.close()
        }

        val resumen = "StickersiniMethodBenchmark: ${mediciones.size} mediciones, traza completa en " +
            trace.file.absolutePath
        Log.i(TAG, resumen)
        println(resumen)

        assertTrue(
            "deberían quedar registradas las ${contenidos.size * METHODS.size} combinaciones de contenido x method",
            mediciones.size == contenidos.size * METHODS.size,
        )
    }
}
