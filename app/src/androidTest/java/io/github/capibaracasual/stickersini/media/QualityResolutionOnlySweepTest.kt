package io.github.capibaracasual.stickersini.media

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.ProductionWebpEncoder
import io.github.capibaracasual.stickersini.webp.WebpFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter
import kotlin.random.Random

private const val TAG = "StickersiniQualityResolutionOnlySweep"
private const val ENCODE_SIZE = 512

/**
 * Investigación (no aprobada como cambio de producto todavía): pedido de
 * producto, siguiente al modelo Sticker.ly — nunca sacrificar fps ni
 * duración, solo calidad y luego resolución (reemplazo propuesto de
 * ADR-0016). Antes de proponer el ADR, mide si esa escalera de dos
 * escalones sola (sin tocar fotogramas ni duración) alcanza a meter en
 * 500 KB (RF-10) el peor contenido disponible, sobre el número de
 * fotogramas COMPLETO que produce el prefiltro de producción (20 fps ×
 * hasta 5 s, ADR-0019 — no el piso reducido de ADR-0016).
 *
 * Args: `videoFile`, `startMs`, `durationMs`, `zoom`, `label` (mismo
 * significado que en [LengthZoomLadderProbeTest]); si `videoFile` no se
 * pasa, genera ruido puro sintético (peor caso) en vez de decodificar un
 * video. Una sola pasada por combinación (calidad × resolución, 8 en
 * total): mide tamaño, no tiempo contra un presupuesto — mismo criterio
 * que `DegradationLadderSweepTest`.
 */
@RunWith(AndroidJUnit4::class)
class QualityResolutionOnlySweepTest {

    private fun testVideoFile(fileName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), fileName)
        assumeTrue(
            "No hay video de prueba en ${videoFile.absolutePath}",
            videoFile.exists(),
        )
        return videoFile
    }

    private fun noisyBitmap(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(ENCODE_SIZE, ENCODE_SIZE, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        val pixels = IntArray(ENCODE_SIZE * ENCODE_SIZE) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, ENCODE_SIZE, 0, 0, ENCODE_SIZE, ENCODE_SIZE)
        return bitmap
    }

    private fun degradeResolution(frames: List<WebpFrame>, resolution: Int): List<WebpFrame> {
        if (resolution == ENCODE_SIZE) return frames
        return frames.map { frame ->
            val downscaled = Bitmap.createScaledBitmap(frame.bitmap, resolution, resolution, /* filter = */ true)
            val upscaled = Bitmap.createScaledBitmap(downscaled, ENCODE_SIZE, ENCODE_SIZE, /* filter = */ true)
            frame.copy(bitmap = upscaled)
        }
    }

    @Test
    fun barreCalidadXResolucion_sinTocarFotogramasNiDuracion() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoFileName = args.getString("videoFile")
        val startMs = args.getString("startMs")?.toLongOrNull() ?: 0L
        val durationMs = args.getString("durationMs")?.toLongOrNull() ?: MAX_CLIP_DURATION_MS
        val zoomSizeFraction = args.getString("zoom")?.toFloatOrNull() ?: 1f
        val label = args.getString("label") ?: (videoFileName?.removeSuffix(".mp4") ?: "ruido")

        val frames: List<WebpFrame> = if (videoFileName != null) {
            val videoFile = testVideoFile(videoFileName)
            val crop = NormalizedCrop(xFraction = 0.5f, yFraction = 0.5f, sizeFraction = zoomSizeFraction)
            VideoFrameDecoder().decode(context, Uri.fromFile(videoFile), startMs, durationMs, crop).frames
        } else {
            val fps = VIDEO_PREFILTER_TARGET_FPS
            val frameCount = (durationMs * fps / 1000L).toInt()
            val frameDurationMs = (1000 / fps)
            val full = (0 until frameCount).map { i -> noisyBitmap(i) }
            val zoomed = if (zoomSizeFraction >= 1f) {
                full
            } else {
                full.map { bmp ->
                    val cropSize = (zoomSizeFraction * ENCODE_SIZE).toInt().coerceIn(1, ENCODE_SIZE)
                    val offset = (ENCODE_SIZE - cropSize) / 2
                    val cropped = Bitmap.createBitmap(bmp, offset, offset, cropSize, cropSize)
                    Bitmap.createScaledBitmap(cropped, ENCODE_SIZE, ENCODE_SIZE, /* filter = */ true)
                }
            }
            zoomed.map { WebpFrame(it, frameDurationMs) }
        }

        val trace = File(context.getExternalFilesDir(null), "quality_resolution_only_sweep_trace.txt")
        val writer = FileWriter(trace, /* append = */ true)
        fun line(text: String) {
            Log.i(TAG, text)
            writer.write("$text\n")
            writer.flush()
        }

        line("=== label=$label frameCount=${frames.size} totalDurationMs=${frames.sumOf { it.durationMs }} zoom=$zoomSizeFraction ===")
        try {
            val qualities = listOf(75, 50, 25, 0)
            val resolutions = listOf(512, 384, 320)
            var foundFit = false
            for (resolution in resolutions) {
                val encodeFrames = degradeResolution(frames, resolution)
                for (quality in qualities) {
                    val start = System.nanoTime()
                    val bytes = ProductionWebpEncoder.encode(encodeFrames, quality.toFloat(), minimizeSize = false)
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    val fits = bytes.size <= ANIMATED_WEBP_TARGET_SIZE_BYTES
                    if (fits) foundFit = true
                    line(
                        "label=$label resolution=$resolution quality=$quality sizeBytes=${bytes.size} " +
                            "elapsedMs=$elapsedMs cabe500KB=$fits",
                    )
                    if (quality == 0) {
                        File(context.getExternalFilesDir(null), "sticker_${label}_${resolution}_q0.webp").writeBytes(bytes)
                    }
                }
            }
            line("RESUMEN label=$label algunaCombinacionCabe=$foundFit")
        } finally {
            writer.close()
        }

        println("StickersiniQualityResolutionOnlySweep: traza completa en ${trace.absolutePath}")
    }
}
