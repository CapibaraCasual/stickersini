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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "StickersiniParallelIsolation"
private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

private class IsolationTrace(val file: File) {
    val path: String = file.absolutePath
    private val writer = FileWriter(file, /* append = */ false)

    fun line(text: String) {
        val stamped = "[${TIMESTAMP_FORMAT.format(Date())}] $text"
        Log.i(TAG, stamped)
        writer.write("$stamped\n")
        writer.flush()
    }

    fun close() = writer.close()
}

/**
 * Aísla si la dispersión que mostró `ParallelPipelineFpsRemeasureTest` (8
 * fps/10 s con rango 13.6-24.3 s, contra el 17.3-17.6 s tenso pero estable
 * de ADR-0012) viene de `ParallelVideoFrameDecoder` en sí (los hilos del
 * `ExecutorService` interfiriendo con el `WebpAnimEncoder` de un solo hilo
 * que corre justo después), o de correr 5 corridas dentro del mismo
 * proceso/`am instrument` en vez de invocaciones separadas.
 *
 * Un solo `@Test`, una sola corrida por invocación de `am instrument` —
 * quien mide corre esta clase 5 veces por separado (mismo patrón que
 * `VideoImportPerformanceTest`), no hay bucle acá adentro. Así se puede
 * comparar manzanas con manzanas contra la corrida de control que ya se
 * hizo con `VideoImportPerformanceTest` (decoder secuencial, misma
 * disciplina de invocación separada).
 */
@RunWith(AndroidJUnit4::class)
class ParallelDecoderIsolationTest {

    private fun testVideoFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), "stickersini_test_video.mp4")
        assumeTrue(
            "No hay video de prueba en ${videoFile.absolutePath}. Antes de correr este test: " +
                "adb push <tu_grabacion.mp4> ${videoFile.absolutePath}",
            videoFile.exists(),
        )
        return videoFile
    }

    @Test
    fun unaSolaCorridaDecodeParaleloMasEncode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val videoFile = testVideoFile()
        val uri = Uri.fromFile(videoFile)

        val trace = IsolationTrace(File(context.getExternalFilesDir(null), "parallel_isolation_trace.txt"))
        trace.line("=== una corrida, ParallelVideoFrameDecoder(hilos=3,capacidad=5) fps=8 durationMs=10000 ===")
        try {
            val decodeStart = System.nanoTime()
            val importResult = ParallelVideoFrameDecoder(
                targetFps = 8,
                converterThreads = 3,
                imageReaderCapacity = 5,
            ).decode(context, uri, durationMs = 10_000L)
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

            val encodeStart = System.nanoTime()
            val result = WebpAnimEncoder(
                singleShotEncoder = ProductionWebpEncoder,
                targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES,
            ).encode(importResult.frames)
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000
            val totalMs = decodeMs + encodeMs

            trace.line(
                "TOTAL: decodeMs=$decodeMs encodeMs=$encodeMs totalMs=$totalMs " +
                    "sizeBytes=${result.bytes.size} quality=${result.quality} frameCount=${result.frameCount}",
            )
        } finally {
            trace.line("=== fin ===")
            trace.close()
        }

        println("StickersiniParallelIsolation: traza completa en ${trace.path}")
    }
}
