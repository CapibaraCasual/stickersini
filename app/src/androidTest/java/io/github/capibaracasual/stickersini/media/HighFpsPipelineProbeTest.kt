package io.github.capibaracasual.stickersini.media

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.ProductionWebpEncoder
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter

private const val TAG = "StickersiniHighFpsProbe"

/**
 * Investigación de producto (meta: 12-15 fps de fluidez, no aprobada
 * todavía — ver conversación de la fase): mide el pipeline de PRODUCCIÓN
 * sin cambiar nada — el mismo [VideoFrameDecoder] (decode+conversión en
 * paralelo, ADR-0015) y el mismo [WebpAnimEncoder] (`method=0` fijo,
 * ADR-0006) — a un `targetFps` mayor que el de producción (8, ADR-0012),
 * pasado por `-e targetFps N` a `am instrument`. Separa dos preguntas
 * antes de considerar un cambio de ruta (decode por GPU) o de parámetro
 * (`method`, ya en su piso — no hay margen para "bajarlo" más):
 *
 * 1. ¿El decode+conversión en CPU ya aguanta 12-15 fps dentro de RNF-08?
 * 2. ¿El encoder aguanta el frame count resultante (hasta ~150 en un
 *    clip de 10 s) dentro de RNF-08 y RF-10 (500 KB)?
 *
 * Un solo `@Test`, una sola corrida por invocación de `am instrument`
 * (mismo patrón que [ParallelDecoderIsolationTest] y
 * `docs/desarrollo/pruebas.md`/ADR-0015 sobre por qué no un bucle de
 * varias corridas en un solo proceso): quien mide corre esta clase 5
 * veces por separado por cada `targetFps` que quiera comparar.
 */
@RunWith(AndroidJUnit4::class)
class HighFpsPipelineProbeTest {

    private fun testVideoFile(fileName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), fileName)
        assumeTrue(
            "No hay video de prueba en ${videoFile.absolutePath}. Antes de correr este test: " +
                "adb push <tu_grabacion.mp4> ${videoFile.absolutePath}",
            videoFile.exists(),
        )
        return videoFile
    }

    @Test
    fun unaSolaCorridaAFpsVariable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val targetFps = InstrumentationRegistry.getArguments().getString("targetFps")?.toIntOrNull()
            ?: VIDEO_PREFILTER_TARGET_FPS
        val durationMs = InstrumentationRegistry.getArguments().getString("durationMs")?.toLongOrNull()
            ?: MAX_CLIP_DURATION_MS
        val videoFileName = InstrumentationRegistry.getArguments().getString("videoFile")
            ?: "stickersini_test_video.mp4"
        val videoFile = testVideoFile(videoFileName)
        val uri = Uri.fromFile(videoFile)

        val trace = File(context.getExternalFilesDir(null), "high_fps_probe_trace.txt")
        val writer = FileWriter(trace, /* append = */ true)
        fun line(text: String) {
            Log.i(TAG, text)
            writer.write("$text\n")
            writer.flush()
        }

        line("=== una corrida, VideoFrameDecoder(targetFps=$targetFps) durationMs=$durationMs ===")
        try {
            val decodeStart = System.nanoTime()
            val importResult = VideoFrameDecoder(targetFps = targetFps).decode(context, uri, durationMs = durationMs)
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

            val encodeStart = System.nanoTime()
            val result = WebpAnimEncoder(
                singleShotEncoder = ProductionWebpEncoder,
                targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES,
            ).encode(importResult.frames)
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000
            val totalMs = decodeMs + encodeMs

            line(
                "TOTAL: targetFps=$targetFps framesFromDecode=${importResult.frames.size} " +
                    "decodeMs=$decodeMs encodeMs=$encodeMs totalMs=$totalMs " +
                    "sizeBytes=${result.bytes.size} quality=${result.quality} frameCount=${result.frameCount} " +
                    "shortenedByMs=${result.shortenedByMs}",
            )

            // Para poder comparar a simple vista si la calidad varía mucho
            // entre fps candidatos (ver conversación de la fase): un .webp
            // por config, nombrado por video+fps, sobrescrito en cada
            // corrida (basta con la última para mirar el resultado).
            val outputFile = File(
                context.getExternalFilesDir(null),
                "sticker_${videoFileName.removeSuffix(".mp4")}_${targetFps}fps.webp",
            )
            outputFile.writeBytes(result.bytes)
        } catch (e: Exception) {
            line("FALLÓ: targetFps=$targetFps error=${e.message}")
            throw e
        } finally {
            writer.close()
        }

        println("StickersiniHighFpsProbe: traza completa en ${trace.absolutePath}")
    }
}
