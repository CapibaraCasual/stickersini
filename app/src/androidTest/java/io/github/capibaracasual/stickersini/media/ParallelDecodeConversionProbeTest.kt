package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "StickersiniParallelDecodeProbe"
private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
private const val RUNS_PER_CELL = 5

private class ProbeTrace(val file: File) {
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
 * Vía 2 pedida: ¿superponer la conversión YUV→RGB con el decode del
 * siguiente fotograma (`ParallelVideoFrameDecoder`) baja el tiempo de
 * decode+conversión frente al bucle secuencial de `VideoFrameDecoder`?
 * Esta clase mide SOLO decode+conversión (sin `WebpAnimEncoder`): la
 * pregunta de esta fase es si la vía abre margen antes de gastar tiempo en
 * medir la codificación también. Si abre margen, `ResolutionFpsSweepTest`
 * ya tiene el patrón para remedir la tubería completa a 12/15 fps contra
 * RNF-08.
 *
 * Mismo video real de siempre, método de 5 corridas por celda (mediana y
 * rango, peor caso decide). Compara, para cada fps y duración: el
 * secuencial (`VideoFrameDecoder`, línea base) contra el paralelo con dos
 * tamaños de pool (2 y 3 hilos) y `imageReaderCapacity=4` (deja hasta 3
 * fotogramas decodificados esperando conversión).
 */
@RunWith(AndroidJUnit4::class)
class ParallelDecodeConversionProbeTest {

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

    private fun timeSequential(context: Context, uri: Uri, fps: Int, durationMs: Long): Long {
        val start = System.nanoTime()
        VideoFrameDecoder(targetFps = fps).decode(context, uri, durationMs = durationMs)
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun timeParallel(
        context: Context,
        uri: Uri,
        fps: Int,
        durationMs: Long,
        converterThreads: Int,
        imageReaderCapacity: Int,
    ): Long {
        val start = System.nanoTime()
        ParallelVideoFrameDecoder(
            targetFps = fps,
            converterThreads = converterThreads,
            imageReaderCapacity = imageReaderCapacity,
        ).decode(context, uri, durationMs = durationMs)
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun runCell(trace: ProbeTrace, label: String, timesMs: List<Long>) {
        val sorted = timesMs.sorted()
        trace.line(
            "$label: corridas=$timesMs medianaMs=${sorted[sorted.size / 2]} " +
                "rangoMs=${sorted.first()}-${sorted.last()}",
        )
    }

    @Test
    fun compararSecuencialContraParaleloEnDecodeMasConversion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val videoFile = testVideoFile()
        val uri = Uri.fromFile(videoFile)

        val trace = ProbeTrace(File(context.getExternalFilesDir(null), "parallel_decode_probe_trace.txt"))
        trace.line("=== secuencial vs. paralelo, decode+conversión, sobre ${videoFile.absolutePath} ===")

        val fpsList = listOf(8, 12, 15)
        val durations = listOf(3_000L, 5_000L, 10_000L)

        try {
            for (fps in fpsList) {
                for (durationMs in durations) {
                    val seq = (1..RUNS_PER_CELL).map { timeSequential(context, uri, fps, durationMs) }
                    runCell(trace, "secuencial fps=$fps durationMs=$durationMs", seq)

                    val par2 = (1..RUNS_PER_CELL).map {
                        timeParallel(context, uri, fps, durationMs, converterThreads = 2, imageReaderCapacity = 4)
                    }
                    runCell(trace, "paralelo(hilos=2,capacidad=4) fps=$fps durationMs=$durationMs", par2)

                    val par3 = (1..RUNS_PER_CELL).map {
                        timeParallel(context, uri, fps, durationMs, converterThreads = 3, imageReaderCapacity = 5)
                    }
                    runCell(trace, "paralelo(hilos=3,capacidad=5) fps=$fps durationMs=$durationMs", par3)
                }
            }
        } finally {
            trace.line("=== fin ===")
            trace.close()
        }

        println("StickersiniParallelDecodeProbe: traza completa en ${trace.path}")
    }
}
