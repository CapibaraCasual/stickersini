package io.github.capibaracasual.stickersini.media

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.measuringWebpEncoderFloat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter

private const val TAG = "StickersiniFloatQualitySweep"
private const val TARGET_SIZE_BYTES = 500_000
private const val TARGET_OCCUPANCY_FRACTION = 0.95f
private const val EPSILON = 0.05f

/**
 * Investigación (no aprobada como cambio de producto, ADR-0022): bisecta
 * calidad como `Float`, no `Int` (`WebPConfig.quality` es un `float` de
 * verdad), para ver si acerca el resultado al ~95% de RF-10 cuando hay un
 * salto grande entre dos calidades enteras consecutivas (confirmado con
 * el video de referencia, `QualityCurveProbeTest`: 90→91 salta 2.5×).
 *
 * Args: `videoFile`, `startMs`, `durationMs`, `zoom`, `method`,
 * `sharpYuv`, `label` (mismo significado que en
 * `MethodSharpYuvSweepTest`, más `method`/`sharpYuv`). Corre el escalado
 * (bilineal o bicúbico) que tenga compilado el APK instalado en ese
 * momento — no es un parámetro de este test.
 */
@RunWith(AndroidJUnit4::class)
class FloatQualitySharpYuvSweepTest {

    private fun testVideoFile(fileName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), fileName)
        assumeTrue("No hay video de prueba en ${videoFile.absolutePath}", videoFile.exists())
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

        val trace = File(context.getExternalFilesDir(null), "float_quality_sweep_trace.txt")
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

            val encodeFn = measuringWebpEncoderFloat(method, sharpYuv)
            var attempts = 0
            val encodeStart = System.nanoTime()

            var low = 0f
            var high = 100f
            var bestQuality: Float? = null
            var bestBytes: ByteArray? = null

            val zeroBytes = encodeFn(importResult.frames, 0f, false)
            attempts++
            line("  intento#$attempts quality=0.00 sizeBytes=${zeroBytes.size}")
            if (zeroBytes.size <= TARGET_SIZE_BYTES) {
                bestQuality = 0f
                bestBytes = zeroBytes
                while (high - low > EPSILON) {
                    val mid = (low + high) / 2f
                    val bytes = encodeFn(importResult.frames, mid, false)
                    attempts++
                    line("  intento#$attempts quality=%.3f sizeBytes=%d".format(mid, bytes.size))
                    if (bytes.size <= TARGET_SIZE_BYTES) {
                        bestQuality = mid
                        bestBytes = bytes
                        low = mid
                        if (bytes.size >= TARGET_SIZE_BYTES * TARGET_OCCUPANCY_FRACTION) break
                    } else {
                        high = mid
                    }
                }
            }
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000

            if (bestBytes == null) {
                line("FALLÓ label=$label ni siquiera calidad 0 entró en $TARGET_SIZE_BYTES bytes (${zeroBytes.size})")
            } else {
                line(
                    "TOTAL label=$label decodeMs=$decodeMs encodeMs=$encodeMs totalMs=${decodeMs + encodeMs} " +
                        "attempts=$attempts quality=%.3f sizeBytes=${bestBytes.size} occupancy=%.1f%%".format(
                            bestQuality,
                            bestBytes.size * 100.0 / TARGET_SIZE_BYTES,
                        ),
                )
                val outFile = File(context.getExternalFilesDir(null), "sticker_$label.webp")
                outFile.writeBytes(bestBytes)
            }
        } finally {
            writer.close()
        }

        println("StickersiniFloatQualitySweep: traza completa en ${trace.absolutePath}")
    }
}
