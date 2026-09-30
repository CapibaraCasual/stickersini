package io.github.capibaracasual.stickersini.media

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.WebpFrame
import io.github.capibaracasual.stickersini.webp.measuringWebpEncoderTuned
import io.github.capibaracasual.stickersini.yuv.NativeYuvConverter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter

private const val TAG = "StickersiniTunedEncodingSweep"
private const val TARGET_SIZE_BYTES = 500_000
private const val TARGET_OCCUPANCY_FRACTION = 0.95f
private const val EPSILON = 0.05f

/**
 * Investigación (no aprobada como cambio de producto, ADR-0022): bloques
 * visibles reportados en contenido adverso real, a la calidad muy baja
 * que RF-12 fuerza para entrar en RF-10 (500 KB) — prueba el filtro de
 * bloques de libwebp (`autofilter`/`filter_strength`) y un suavizado
 * liviano antes de codificar, con `method=4` (el que dio más calidad en
 * la ronda anterior, ver `docs/desarrollo/pruebas.md`).
 *
 * Args: `videoFile`, `startMs`, `durationMs`, `zoom`, `fps` (fps de
 * prefiltro para ESTA corrida, no el de producción), `filterStrength`,
 * `autofilter`, `denoise` (`true`/`false`), `label`.
 */
@RunWith(AndroidJUnit4::class)
class TunedEncodingSweepTest {

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
        val fps = args.getString("fps")?.toIntOrNull() ?: VIDEO_PREFILTER_TARGET_FPS
        val method = args.getString("method")?.toIntOrNull() ?: 4
        val filterStrength = args.getString("filterStrength")?.toIntOrNull() ?: 60
        val autofilter = args.getString("autofilter")?.toBooleanStrictOrNull() ?: false
        val filterSharpness = args.getString("filterSharpness")?.toIntOrNull() ?: 0
        val snsStrength = args.getString("snsStrength")?.toIntOrNull() ?: 50
        val denoise = args.getString("denoise")?.toBooleanStrictOrNull() ?: false
        val label = args.getString("label") ?: "tuned_${videoFileName.removeSuffix(".mp4")}"

        val videoFile = testVideoFile(videoFileName)
        val uri = Uri.fromFile(videoFile)
        val crop = NormalizedCrop(xFraction = 0.5f, yFraction = 0.5f, sizeFraction = zoomSizeFraction)

        val trace = File(context.getExternalFilesDir(null), "tuned_encoding_sweep_trace.txt")
        val writer = FileWriter(trace, /* append = */ true)
        fun line(text: String) {
            Log.i(TAG, text)
            writer.write("$text\n")
            writer.flush()
        }

        line(
            "=== label=$label videoFile=$videoFileName fps=$fps method=$method " +
                "filterStrength=$filterStrength autofilter=$autofilter filterSharpness=$filterSharpness " +
                "snsStrength=$snsStrength denoise=$denoise ===",
        )
        try {
            val decodeStart = System.nanoTime()
            val importResult = VideoFrameDecoder(targetFps = fps).decode(context, uri, startMs, durationMs, crop)
            var frames = importResult.frames
            if (denoise) {
                frames = frames.map { frame -> WebpFrame(NativeYuvConverter.lightBlur(frame.bitmap), frame.durationMs) }
            }
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

            val encodeFn = measuringWebpEncoderTuned(method, filterStrength, autofilter, filterSharpness, snsStrength)
            var attempts = 0
            val encodeStart = System.nanoTime()

            var low = 0f
            var high = 100f
            var bestQuality: Float? = null
            var bestBytes: ByteArray? = null

            val zeroBytes = encodeFn(frames, 0f)
            attempts++
            line("  intento#$attempts quality=0.00 sizeBytes=${zeroBytes.size}")
            if (zeroBytes.size <= TARGET_SIZE_BYTES) {
                bestQuality = 0f
                bestBytes = zeroBytes
                while (high - low > EPSILON) {
                    val mid = (low + high) / 2f
                    val bytes = encodeFn(frames, mid)
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
                    "TOTAL label=$label frameCount=${frames.size} decodeMs=$decodeMs encodeMs=$encodeMs " +
                        "totalMs=${decodeMs + encodeMs} attempts=$attempts quality=%.3f sizeBytes=${bestBytes.size} " +
                        "occupancy=%.1f%%".format(bestQuality, bestBytes.size * 100.0 / TARGET_SIZE_BYTES),
                )
                val outFile = File(context.getExternalFilesDir(null), "sticker_$label.webp")
                outFile.writeBytes(bestBytes)
            }
        } finally {
            writer.close()
        }

        println("StickersiniTunedEncodingSweep: traza completa en ${trace.absolutePath}")
    }
}
