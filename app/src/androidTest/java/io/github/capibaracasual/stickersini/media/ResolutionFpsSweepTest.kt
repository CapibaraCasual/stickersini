package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.ProductionWebpEncoder
import io.github.capibaracasual.stickersini.webp.SingleShotWebpEncoder
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import io.github.capibaracasual.stickersini.webp.WebpEncodeException
import io.github.capibaracasual.stickersini.webp.WebpFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "StickersiniResolutionSweep"
private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
private const val STICKER_SIZE = 512
private const val RUNS_PER_CELL = 5

/** Escribe cada línea a logcat y a un archivo, con flush inmediato — mismo patrón que `VideoImportPerformanceTest.Trace` (nombre distinto: dos `private class` iguales en el mismo paquete no compilan). */
private class SweepTrace(val file: File) {
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

/** Cuenta cuántas veces `WebpAnimEncoder` tuvo que codificar de verdad (ADR-0006/0007) — mismo patrón que `VideoImportPerformanceTest.MeasuringEncoder`, sin la traza por intento. */
private class SweepMeasuringEncoder(private val delegate: SingleShotWebpEncoder) : SingleShotWebpEncoder {
    var callCount = 0
        private set

    override fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean): ByteArray {
        callCount++
        return delegate.encode(frames, quality, minimizeSize)
    }
}

/**
 * Explora la estrategia de Sticker.ly: convertir/codificar a una resolución
 * menor que los 512×512 finales (RF-10/RF-11) y escalar el resultado a 512
 * recién al final, para ver si eso deja subir el fps de prefiltro (fijo en
 * 8 desde ADR-0012) sin volver a romper RNF-08. No cambia ningún valor de
 * producción: `VideoFrameDecoder.decode` acepta `targetSize` desde este
 * mismo cambio, con 512 de valor por defecto — este test es el único que
 * lo pide distinto.
 *
 * Por cada celda (resolución × fps × duración), [runOnce] decodifica+
 * convierte a `targetSize`, escala cada fotograma a 512×512 si
 * `targetSize` != 512 (`Bitmap.createScaledBitmap`, el "escalar al final"),
 * y codifica ESE resultado ya a 512×512 — el mismo archivo que
 * `StickerContentProvider` tendría que poder servir de verdad (RF-10/11
 * son exactos, no aproximados). El tiempo reportado es decode + escalado +
 * codificación juntos: la pregunta que importa es si la estrategia
 * completa entra en el presupuesto de RNF-08, no si un paso intermedio
 * (codificar a 320×320, que nadie ve) lo hace.
 *
 * Mismo dispositivo y video real de siempre (ver `docs/desarrollo/pruebas.md`),
 * método de 5 corridas por celda (mediana y rango, peor caso el que decide
 * si cumple RNF-08 — no la mediana, mismo criterio que ADR-0012).
 *
 * No trae un video embebido: usa `stickersini_test_video.mp4` en el
 * `externalFilesDir` de la app, igual que `VideoImportPerformanceTest`. Si
 * no está, el test se salta con [assumeTrue].
 */
@RunWith(AndroidJUnit4::class)
class ResolutionFpsSweepTest {

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

    private data class RunResult(
        val decodeMs: Long,
        val upscaleMs: Long,
        val encodeMs: Long,
        val totalMs: Long,
        val sizeBytes: Int?,
        val attempts: Int,
        val frameCount: Int?,
        val quality: Int?,
        val outcome: String,
    )

    private fun runOnce(context: Context, uri: Uri, targetSize: Int, fps: Int, durationMs: Long): RunResult {
        val decodeStart = System.nanoTime()
        val importResult = VideoFrameDecoder(targetFps = fps).decode(context, uri, durationMs = durationMs, targetSize = targetSize)
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

        val upscaleStart = System.nanoTime()
        val framesToEncode = if (targetSize == STICKER_SIZE) {
            importResult.frames
        } else {
            importResult.frames.map { frame ->
                val scaled = Bitmap.createScaledBitmap(frame.bitmap, STICKER_SIZE, STICKER_SIZE, /* filter = */ true)
                WebpFrame(scaled, frame.durationMs)
            }
        }
        val upscaleMs = (System.nanoTime() - upscaleStart) / 1_000_000

        val measuring = SweepMeasuringEncoder(ProductionWebpEncoder)
        val encodeStart = System.nanoTime()
        var sizeBytes: Int? = null
        var quality: Int? = null
        var frameCount: Int? = null
        var outcome: String
        try {
            val result = WebpAnimEncoder(singleShotEncoder = measuring, targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES).encode(framesToEncode)
            sizeBytes = result.bytes.size
            quality = result.quality
            frameCount = result.frameCount
            outcome = "exito"
        } catch (error: WebpEncodeException) {
            outcome = "excepcion: ${error.message}"
        }
        val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000
        val totalMs = decodeMs + upscaleMs + encodeMs

        return RunResult(decodeMs, upscaleMs, encodeMs, totalMs, sizeBytes, measuring.callCount, frameCount, quality, outcome)
    }

    @Test
    fun barridoDeResolucionYFps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val videoFile = testVideoFile()
        val uri = Uri.fromFile(videoFile)

        val trace = SweepTrace(File(context.getExternalFilesDir(null), "resolution_fps_sweep_trace.txt"))
        trace.line("=== barrido resolución×fps×duración, método de 5 corridas, sobre ${videoFile.absolutePath} (${videoFile.length()} bytes) ===")

        val resolutions = listOf(512, 448, 384, 320)
        val fpsList = listOf(8, 12, 15)
        val durations = listOf(3_000L, 5_000L, 10_000L)

        try {
            for (targetSize in resolutions) {
                for (fps in fpsList) {
                    for (durationMs in durations) {
                        val results = (1..RUNS_PER_CELL).map { run ->
                            val r = runOnce(context, uri, targetSize, fps, durationMs)
                            trace.line(
                                "res=$targetSize fps=$fps durationMs=$durationMs corrida=$run " +
                                    "decodeMs=${r.decodeMs} upscaleMs=${r.upscaleMs} encodeMs=${r.encodeMs} totalMs=${r.totalMs} " +
                                    "sizeBytes=${r.sizeBytes} intentos=${r.attempts} frameCount=${r.frameCount} quality=${r.quality} outcome=${r.outcome}",
                            )
                            r
                        }
                        val totals = results.map { it.totalMs }.sorted()
                        val budgetMs = if (durationMs <= 5_000L) 5_000L else 20_000L
                        val worst = totals.last()
                        val median = totals[totals.size / 2]
                        trace.line(
                            "RESUMEN res=$targetSize fps=$fps durationMs=$durationMs " +
                                "medianaMs=$median rangoMs=${totals.first()}-${totals.last()} " +
                                "presupuestoMs=$budgetMs cumplePeorCaso=${worst < budgetMs}",
                        )
                    }
                }
            }
        } finally {
            trace.line("=== fin ===")
            trace.close()
        }

        println("StickersiniResolutionSweep: traza completa en ${trace.path}")
    }
}
