package io.github.capibaracasual.stickersini.media

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import io.github.capibaracasual.stickersini.webp.measuringWebpEncoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter

private const val TAG = "StickersiniMethodSharpYuvSweep"

/**
 * Investigación (no aprobada como cambio de producto todavía, ADR-0022):
 * referencia de otros conversores (ffmpeg+libwebp) que usan `method`
 * 4-6 y `use_sharp_yuv` — ADR-0006 ya midió `method` alto sobre contenido
 * sintético (adverso/realista dibujado), esto mide sobre el video REAL de
 * referencia, a través del pipeline completo de producción
 * ([VideoFrameDecoder] + [WebpAnimEncoder], con el escalado bicúbico de
 * ADR-0022 ya aplicado en el recorte), para ver calidad/tamaño/tiempo
 * finales, no solo el costo de una codificación aislada a calidad fija.
 *
 * Args: `videoFile`, `startMs`, `durationMs`, `zoom`, `label` (mismo
 * significado que en [LengthZoomLadderProbeTest]), más `method` y
 * `sharpYuv` (`true`/`false`). Una corrida por invocación de
 * `am instrument`: quien mida corre esta clase varias veces por separado
 * para cada combinación.
 */
@RunWith(AndroidJUnit4::class)
class MethodSharpYuvSweepTest {

    private fun testVideoFile(fileName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), fileName)
        assumeTrue(
            "No hay video de prueba en ${videoFile.absolutePath}",
            videoFile.exists(),
        )
        return videoFile
    }

    @Test
    fun corridaVideo() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoFileName = args.getString("videoFile") ?: "stickersini_test_video.mp4"
        val startMs = args.getString("startMs")?.toLongOrNull() ?: 0L
        val durationMs = args.getString("durationMs")?.toLongOrNull() ?: MAX_CLIP_DURATION_MS
        val zoomSizeFraction = args.getString("zoom")?.toFloatOrNull() ?: 1f
        val method = args.getString("method")?.toIntOrNull() ?: 0
        val sharpYuv = args.getString("sharpYuv")?.toBooleanStrictOrNull() ?: false
        val label = args.getString("label") ?: "${videoFileName.removeSuffix(".mp4")}_m${method}_sy$sharpYuv"

        val videoFile = testVideoFile(videoFileName)
        val uri = Uri.fromFile(videoFile)
        val crop = NormalizedCrop(xFraction = 0.5f, yFraction = 0.5f, sizeFraction = zoomSizeFraction)

        val trace = File(context.getExternalFilesDir(null), "method_sharpyuv_sweep_trace.txt")
        val writer = FileWriter(trace, /* append = */ true)
        fun line(text: String) {
            Log.i(TAG, text)
            writer.write("$text\n")
            writer.flush()
        }

        line(
            "=== label=$label videoFile=$videoFileName startMs=$startMs durationMs=$durationMs " +
                "zoom=$zoomSizeFraction method=$method sharpYuv=$sharpYuv ===",
        )
        try {
            val decodeStart = System.nanoTime()
            val importResult = VideoFrameDecoder().decode(context, uri, startMs, durationMs, crop)
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

            val encodeStart = System.nanoTime()
            val result = WebpAnimEncoder(
                singleShotEncoder = measuringWebpEncoder(method, sharpYuv),
                targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES,
            ).encode(importResult.frames)
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000

            line(
                "TOTAL label=$label decodeMs=$decodeMs encodeMs=$encodeMs totalMs=${decodeMs + encodeMs} " +
                    "sizeBytes=${result.bytes.size} quality=${result.quality} resolution=${result.resolution} " +
                    "frameCount=${result.frameCount} shortenedByMs=${result.shortenedByMs}",
            )

            val outFile = File(context.getExternalFilesDir(null), "sticker_$label.webp")
            outFile.writeBytes(result.bytes)
        } catch (e: Exception) {
            line("FALLÓ label=$label error=${e.message}")
            throw e
        } finally {
            writer.close()
        }

        println("StickersiniMethodSharpYuvSweep: traza completa en ${trace.absolutePath}")
    }
}
