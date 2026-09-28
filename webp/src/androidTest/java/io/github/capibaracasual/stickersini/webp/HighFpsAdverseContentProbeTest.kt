package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter
import kotlin.random.Random

private const val TAG = "StickersiniAdverseFpsProbe"

/**
 * Investigación de producto (ver conversación de la fase, no aprobada
 * todavía): antes de fijar el fps de prefiltro sobre un RF-06 de 5 s,
 * confirma que el caso más adverso (ruido puro, el mismo patrón que
 * ADR-0006/ADR-0007/ADR-0016 siempre usan como peor caso) sigue
 * cumpliendo RF-12 en el frame count exacto que produciría un `targetFps`
 * dado sobre el tramo completo de 5 s — el video real usado en el resto
 * de esta investigación no es necesariamente el peor caso posible.
 * `targetFps` se pasa por `-e targetFps N` a `am instrument`.
 *
 * El contenido es determinístico (misma semilla siempre), así que el
 * resultado (quality/size/shortenedByMs) no varía entre corridas — solo
 * el tiempo lo haría. Quien mida el tiempo con precisión corre esta clase
 * varias veces por separado; lo primero que importa acá es si RF-12 se
 * cumple en absoluto.
 */
@RunWith(AndroidJUnit4::class)
class HighFpsAdverseContentProbeTest {

    private val size = 512

    private fun noisyFrames(count: Int, durationMs: Int): List<WebpFrame> {
        val random = Random(42)
        return (0 until count).map {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size) {
                Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            WebpFrame(bitmap, durationMs)
        }
    }

    private fun probe(trace: FileWriter, label: String, frameCount: Int, durationMsPerFrame: Int) {
        val frames = noisyFrames(frameCount, durationMsPerFrame)
        val start = System.nanoTime()
        val line = try {
            val result = WebpAnimEncoder(singleShotEncoder = NativeWebpEncoder, targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES).encode(frames)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            "$label: frameCountIn=$frameCount elapsedMs=$elapsed sizeBytes=${result.bytes.size} " +
                "quality=${result.quality} frameCountOut=${result.frameCount} shortenedByMs=${result.shortenedByMs}"
        } catch (e: WebpEncodeException) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            "$label: FALLO RF-12 tras ${elapsed}ms: ${e.message}"
        }
        Log.i(TAG, line)
        trace.write("$line\n")
        trace.flush()
    }

    @Test
    fun ruidoPuroSobreTramoDe5sAFpsVariable() {
        val targetFps = InstrumentationRegistry.getArguments().getString("targetFps")?.toIntOrNull() ?: 15
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val traceFile = File(context.getExternalFilesDir(null), "adverse_fps_probe_trace.txt")
        val frameCount = targetFps * 5 // tramo de 5s (RF-06)
        val durationMsPerFrame = 1000 / targetFps
        FileWriter(traceFile, /* append = */ true).use { trace ->
            probe(trace, "${targetFps}fps/5s", frameCount = frameCount, durationMsPerFrame = durationMsPerFrame)
        }
        println("StickersiniAdverseFpsProbe: traza completa en ${traceFile.absolutePath}")
    }
}
