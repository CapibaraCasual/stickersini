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
import java.io.ByteArrayOutputStream

private const val TAG = "StickersiniStaticOverhead"
private val QUALITIES = listOf(50, 75, 90)

/**
 * Antes de decidir que RF-11 (sticker estático) se codifica con el mismo
 * [WebpAnimEncoder] que RF-10 (una animación de un solo fotograma, ver su
 * KDoc), hace falta saber cuánto cuesta el contenedor de animación
 * (`WebPAnimEncoder`, con sus chunks `ANIM`/`ANMF`) frente a un WebP
 * estático "de verdad" (`Bitmap.compress`, sin ningún chunk de animación)
 * — contra un límite de apenas 100 KB (RF-11), un sobrecosto que sería
 * despreciable en el límite de 500 KB de RF-10 podría no serlo acá.
 *
 * Un solo fotograma por medición (sin bisección, sin `minimize_size`): esta
 * prueba no mide la estrategia de ajuste, solo el costo del contenedor en
 * sí, a la misma calidad para ambos caminos.
 */
@RunWith(AndroidJUnit4::class)
class StaticWebpContainerOverheadTest {

    private val size = 512

    private fun flatColorBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(0, 150, 220))
        return bitmap
    }

    /** Mismo patrón que en `WebpEncodeMethodBenchmarkTest`: aproxima una captura real, no un caso sintético trivial. */
    private fun realisticBitmap(): Bitmap {
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
        canvas.drawCircle(150f, size / 2f, 30f, Paint().apply { color = Color.rgb(0, 150, 220) })
        canvas.drawText(
            "Stickersini sticker estático",
            50f,
            100f,
            Paint().apply {
                color = Color.BLACK
                textSize = 32f
                isAntiAlias = true
            },
        )

        return bitmap
    }

    /**
     * WebP estático "de verdad": sin ningún chunk de animación. `WEBP` (no
     * `WEBP_LOSSY`, que pide API 30) alcanza: para `quality < 100` produce
     * WebP con pérdida igual que `WEBP_LOSSY`, y esta prueba corre en
     * dispositivo real (API 34 en este caso), no depende del `minSdk` de
     * producción.
     */
    @Suppress("DEPRECATION")
    private fun staticFrameworkWebpBytes(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
            out.toByteArray()
        }

    private fun animatedSingleFrameBytes(bitmap: Bitmap, quality: Int): ByteArray =
        NativeWebpEncoder.encode(listOf(WebpFrame(bitmap, FrameTiming.MIN_FRAME_DURATION_MS)), quality, minimizeSize = false)

    @Test
    fun comparaContenedorAnimadoDeUnFotogramaContraWebpEstaticoDelFramework() {
        val trace = TraceWriter("webp_static_overhead_trace.txt")
        val contenidos = linkedMapOf(
            "colorPlano" to flatColorBitmap(),
            "realista" to realisticBitmap(),
        )

        var medidas = 0
        try {
            for ((nombreContenido, bitmap) in contenidos) {
                for (quality in QUALITIES) {
                    val animatedBytes = animatedSingleFrameBytes(bitmap, quality)
                    val staticBytes = staticFrameworkWebpBytes(bitmap, quality)
                    val overhead = animatedBytes.size - staticBytes.size
                    val overheadPercentOfRf11 = overhead * 100.0 / STATIC_WEBP_TARGET_SIZE_BYTES

                    val line = "contenido=$nombreContenido quality=$quality " +
                        "animatedBytes=${animatedBytes.size} staticBytes=${staticBytes.size} " +
                        "overheadBytes=$overhead overheadPercentOfRf11=${"%.3f".format(overheadPercentOfRf11)}"
                    Log.i(TAG, line)
                    trace.line(line)
                    medidas++
                }
            }
        } finally {
            trace.close()
        }

        val resumen = "StickersiniStaticOverhead: $medidas mediciones, traza completa en ${trace.file.absolutePath}"
        Log.i(TAG, resumen)
        println(resumen)

        assertTrue(
            "deberían quedar registradas las ${2 * QUALITIES.size} combinaciones de contenido x quality",
            medidas == 2 * QUALITIES.size,
        )
    }
}
