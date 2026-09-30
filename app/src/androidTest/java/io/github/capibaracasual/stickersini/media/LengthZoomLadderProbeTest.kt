package io.github.capibaracasual.stickersini.media

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.ProductionWebpEncoder
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import io.github.capibaracasual.stickersini.webp.WebpFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter
import kotlin.random.Random

private const val TAG = "StickersiniLengthZoomProbe"

/**
 * Investigación (no aprobada como cambio de producto todavía): reportado
 * desde uso real, con videos largos/complejos el sticker sale con menos
 * fotogramas y recortado a 3 s, aunque se haya elegido un tramo más largo
 * (hasta 5 s, RF-06/ADR-0019). Este harness corre el pipeline de
 * PRODUCCIÓN sin cambiar nada ([VideoFrameDecoder] + [WebpAnimEncoder])
 * para separar dos preguntas antes de proponer un reemplazo de ADR-0016:
 *
 * 1. ¿Influye el largo del video de origen, más allá del tramo elegido?
 *    Por diseño no debería: [VideoFrameDecoder.decode] solo decodifica
 *    `[startMs, startMs+durationMs)` y [FrameSampler] muestrea en
 *    timestamps relativos al tramo, no al video completo.
 * 2. ¿Cuánto degrada la escalera de ADR-0016 (fotogramas/calidad/
 *    resolución/duración) con contenido adverso real, con y sin zoom
 *    (RF-07)? Datos para diseñar la escalera de reemplazo.
 *
 * Una corrida por invocación de `am instrument` (mismo criterio que
 * [HighFpsPipelineProbeTest]): quien mida corre esta clase varias veces
 * por separado para cada configuración, pasando argumentos con `-e`:
 * `videoFile`, `startMs`, `durationMs`, `zoom` (sizeFraction de
 * [NormalizedCrop], `1` = sin zoom), `label` (nombre para la traza y el
 * .webp de salida).
 *
 * No decide nada de producción: es una herramienta de medición, mismo
 * espíritu que el resto de los harnesses conservados en el repo.
 */
@RunWith(AndroidJUnit4::class)
class LengthZoomLadderProbeTest {

    private fun testVideoFile(fileName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), fileName)
        assumeTrue(
            "No hay video de prueba en ${videoFile.absolutePath}. Antes de correr este test: " +
                "adb push <tu_video.mp4> ${videoFile.absolutePath}",
            videoFile.exists(),
        )
        return videoFile
    }

    private fun traceWriter(context: android.content.Context): Pair<File, FileWriter> {
        val trace = File(context.getExternalFilesDir(null), "length_zoom_probe_trace.txt")
        return trace to FileWriter(trace, /* append = */ true)
    }

    @Test
    fun corridaVideo() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoFileName = args.getString("videoFile") ?: "stickersini_test_video.mp4"
        val startMs = args.getString("startMs")?.toLongOrNull() ?: 0L
        val durationMs = args.getString("durationMs")?.toLongOrNull() ?: MAX_CLIP_DURATION_MS
        val zoomSizeFraction = args.getString("zoom")?.toFloatOrNull() ?: 1f
        val label = args.getString("label") ?: videoFileName.removeSuffix(".mp4")
        val hardTimeLimitMs = args.getString("hardTimeLimitMs")?.toLongOrNull() ?: 20_000L

        val videoFile = testVideoFile(videoFileName)
        val uri = Uri.fromFile(videoFile)
        val crop = NormalizedCrop(xFraction = 0.5f, yFraction = 0.5f, sizeFraction = zoomSizeFraction)

        val (trace, writer) = traceWriter(context)
        fun line(text: String) {
            Log.i(TAG, text)
            writer.write("$text\n")
            writer.flush()
        }

        line(
            "=== label=$label videoFile=$videoFileName startMs=$startMs durationMs=$durationMs " +
                "zoom=$zoomSizeFraction hardTimeLimitMs=$hardTimeLimitMs ===",
        )
        try {
            val decodeStart = System.nanoTime()
            val importResult = VideoFrameDecoder().decode(context, uri, startMs, durationMs, crop)
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

            val encodeStart = System.nanoTime()
            val result = WebpAnimEncoder(
                singleShotEncoder = ProductionWebpEncoder,
                targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES,
                hardTimeLimitMs = hardTimeLimitMs,
            ).encode(importResult.frames) { progress ->
                line("  intento#${progress.attemptNumber} elapsedMs=${progress.elapsedMs} de ${progress.hardTimeLimitMs}")
            }
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000

            line(
                "TOTAL label=$label decodedFrameCount=${importResult.decodedFrameCount} " +
                    "sourceDurationMs=${importResult.sourceDurationMs} truncated=${importResult.truncated} " +
                    "decodeMs=$decodeMs encodeMs=$encodeMs totalMs=${decodeMs + encodeMs} " +
                    "sizeBytes=${result.bytes.size} quality=${result.quality} frameCount=${result.frameCount} " +
                    "requestedDurationMs=${result.requestedDurationMs} shortenedByMs=${result.shortenedByMs}",
            )

            val outFile = File(context.getExternalFilesDir(null), "sticker_$label.webp")
            outFile.writeBytes(result.bytes)
        } catch (e: Exception) {
            line("FALLÓ label=$label error=${e.message}")
            throw e
        } finally {
            writer.close()
        }

        println("StickersiniLengthZoomProbe: traza completa en ${trace.absolutePath}")
    }

    @Test
    fun corridaRuido() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zoomSizeFraction = args.getString("zoom")?.toFloatOrNull() ?: 1f
        val label = args.getString("label") ?: "ruido"
        val fps = VIDEO_PREFILTER_TARGET_FPS
        val durationMs = MAX_CLIP_DURATION_MS
        val frameCount = (durationMs * fps / 1000L).toInt()
        val frameDurationMs = (1000 / fps)

        val frames = (0 until frameCount).map { i ->
            WebpFrame(zoomedNoisyBitmap(seed = i, zoomSizeFraction = zoomSizeFraction), frameDurationMs)
        }

        val (trace, writer) = traceWriter(context)
        fun line(text: String) {
            Log.i(TAG, text)
            writer.write("$text\n")
            writer.flush()
        }

        line("=== label=$label ruido frameCount=$frameCount zoom=$zoomSizeFraction ===")
        try {
            val encodeStart = System.nanoTime()
            val result = WebpAnimEncoder(
                singleShotEncoder = ProductionWebpEncoder,
                targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES,
            ).encode(frames)
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000

            line(
                "TOTAL label=$label encodeMs=$encodeMs sizeBytes=${result.bytes.size} " +
                    "quality=${result.quality} frameCount=${result.frameCount} " +
                    "requestedDurationMs=${result.requestedDurationMs} shortenedByMs=${result.shortenedByMs}",
            )

            val outFile = File(context.getExternalFilesDir(null), "sticker_$label.webp")
            outFile.writeBytes(result.bytes)
        } finally {
            writer.close()
        }

        println("StickersiniLengthZoomProbe: traza completa en ${trace.absolutePath}")
    }

    /** Ruido pseudoaleatorio independiente por píxel (peor caso posible, mismo patrón que WebpAnimEncoderPerformanceTest), con zoom (RF-07) simulado como recorte central + reescalado. */
    private fun zoomedNoisyBitmap(seed: Int, zoomSizeFraction: Float): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        if (zoomSizeFraction >= 1f) return bitmap
        val cropSize = (zoomSizeFraction * size).toInt().coerceIn(1, size)
        val offset = (size - cropSize) / 2
        val cropped = Bitmap.createBitmap(bitmap, offset, offset, cropSize, cropSize)
        return Bitmap.createScaledBitmap(cropped, size, size, /* filter = */ true)
    }
}
