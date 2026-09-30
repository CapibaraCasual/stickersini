package io.github.capibaracasual.stickersini.media

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.measuringWebpEncoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter

private const val TAG = "StickersiniQualityCurveProbe"

/**
 * Diagnóstico puntual (no aprobado como cambio de producto, investigación
 * de ADR-0021/ADR-0022): confirma si el salto de tamaño entre calidad 90
 * (cabe) y 91+ (no cabe) que encontró la bisección es real o un artefacto
 * — probando CADA calidad de 80 a 100 de forma directa (sin bisección),
 * sobre el mismo fotograma decodificado real, para ver la curva completa.
 */
@RunWith(AndroidJUnit4::class)
class QualityCurveProbeTest {

    private fun testVideoFile(fileName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), fileName)
        assumeTrue("No hay video de prueba en ${videoFile.absolutePath}", videoFile.exists())
        return videoFile
    }

    @Test
    fun curvaCalidad80a100() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoFile = testVideoFile("stickersini_test_video.mp4")
        val importResult = VideoFrameDecoder().decode(context, Uri.fromFile(videoFile), 15503L, 5000L)

        val trace = File(context.getExternalFilesDir(null), "quality_curve_trace.txt")
        val writer = FileWriter(trace, /* append = */ false)
        fun line(text: String) {
            Log.i(TAG, text)
            writer.write("$text\n")
            writer.flush()
        }

        val method = InstrumentationRegistry.getArguments().getString("method")?.toIntOrNull() ?: 0
        line("=== frameCount=${importResult.frames.size} method=$method ===")
        val encoder = measuringWebpEncoder(method = method, useSharpYuv = false)
        for (quality in 80..100) {
            val bytes = encoder.encode(importResult.frames, quality.toFloat(), minimizeSize = false)
            line("quality=$quality sizeBytes=${bytes.size} cabe500KB=${bytes.size <= 500_000}")
        }
        writer.close()

        println("StickersiniQualityCurveProbe: traza completa en ${trace.absolutePath}")
    }
}
