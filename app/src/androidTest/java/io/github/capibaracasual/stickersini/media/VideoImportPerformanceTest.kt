package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.ProductionWebpEncoder
import io.github.capibaracasual.stickersini.webp.SingleShotWebpEncoder
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import io.github.capibaracasual.stickersini.webp.WebpEncodeException
import io.github.capibaracasual.stickersini.webp.WebpFrame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "StickersiniVideoImport"
private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

/** Escribe cada línea a logcat y a un archivo, con flush inmediato (mismo motivo que `TraceWriter` en `:webp`). */
private class Trace(val file: File) {
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
 * Envuelve [delegate] contando y cronometrando cada llamada a `encode`, y
 * registrando cada una en [trace] además de logcat — mismo propósito que
 * `MeasuringEncoder` en `WebpAnimEncoderPerformanceTest` (`:webp`), copiado
 * acá en vez de reutilizado porque esa clase es `private` a ese módulo. Sin
 * el límite de tiempo por intento que sí tiene la de `:webp`
 * (`TimedAttemptRunner`): esa protección se agregó ahí para desarrollar la
 * estrategia de ADR-0006 sin arriesgar un cuelgue; acá ya hay evidencia de
 * que la ruta nativa no se cuelga, solo tarda.
 */
private class MeasuringEncoder(
    private val delegate: SingleShotWebpEncoder,
    private val trace: Trace,
) : SingleShotWebpEncoder {
    var callCount = 0
        private set

    override fun encode(frames: List<WebpFrame>, quality: Int, minimizeSize: Boolean): ByteArray {
        callCount++
        val attemptNumber = callCount
        val start = System.nanoTime()
        val bytes = delegate.encode(frames, quality, minimizeSize)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        trace.line(
            "intento #$attemptNumber: frameCount=${frames.size} quality=$quality " +
                "minimizeSize=$minimizeSize sizeBytes=${bytes.size} elapsedMs=$elapsedMs",
        )
        return bytes
    }
}

/**
 * Objetivo declarado de la Fase 2 (ADR-0008, ADR-0009): medir en
 * dispositivo real cuánto tarda decodificar y seleccionar fotogramas de una
 * grabación de pantalla real, sumarlo al tiempo ya medido del codificador
 * (`docs/desarrollo/pruebas.md`), y confirmar si el total sigue dentro de
 * RNF-08 con contenido real, no sintético — para varias duraciones de clip,
 * no solo el máximo de 10 s de RF-06: la gente arma stickers de 2-3 s, no
 * del máximo permitido.
 *
 * No trae un video de prueba embebido: usa uno real que quien corre el test
 * pone en el dispositivo. Si no está, el test se salta (no falla) con
 * [assumeTrue] y un mensaje con la ruta exacta y el comando `adb push`. Ver
 * `docs/desarrollo/pruebas.md` para el procedimiento completo.
 */
@RunWith(AndroidJUnit4::class)
class VideoImportPerformanceTest {

    private fun testVideoFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fileName = InstrumentationRegistry.getArguments().getString("videoFileName")
            ?: "stickersini_test_video.mp4"
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), fileName)
        assumeTrue(
            "No hay video de prueba en ${videoFile.absolutePath}. Antes de correr este test: " +
                "adb push <tu_grabacion.mp4> ${videoFile.absolutePath}",
            videoFile.exists(),
        )
        return videoFile
    }

    /**
     * A diferencia de un test normal, un `outcome` distinto de `exito` acá
     * no es un fallo del test (mismo criterio que
     * `WebpAnimEncoderPerformanceTest` en `:webp`): es el dato que se estaba
     * midiendo. Por eso el `WebpEncodeException` de RF-12 se captura y se
     * registra en la traza en vez de dejar que tumbe el test — así la
     * corrida completa (decode + todos los intentos de encode) queda
     * siempre en el archivo, se haya podido producir un WebP o no.
     */
    private fun measureClip(context: Context, videoFile: File, durationMs: Long, traceFileName: String) {
        val trace = Trace(File(context.getExternalFilesDir(null), traceFileName))
        trace.line("=== inicio: durationMs=$durationMs sobre ${videoFile.absolutePath} (${videoFile.length()} bytes) ===")
        try {
            val decodeStart = System.nanoTime()
            val importResult = VideoFrameDecoder().decode(context, Uri.fromFile(videoFile), durationMs = durationMs)
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

            val restoMs = decodeMs - importResult.acquireImageMs - importResult.conversionMs
            trace.line(
                "decode: elapsedMs=$decodeMs sourceDurationMs=${importResult.sourceDurationMs} " +
                    "truncated=${importResult.truncated} decodedFrameCount=${importResult.decodedFrameCount} " +
                    "framesParaEncoder=${importResult.frames.size} " +
                    "acquireImageMs=${importResult.acquireImageMs} conversionMs=${importResult.conversionMs} restoMs=$restoMs",
            )

            val measuring = MeasuringEncoder(ProductionWebpEncoder, trace)
            val encodeStart = System.nanoTime()
            var outcome: String
            var sizeBytes: Int? = null
            var quality: Int? = null
            var frameCount: Int? = null
            try {
                val encodeResult = WebpAnimEncoder(singleShotEncoder = measuring).encode(importResult.frames)
                sizeBytes = encodeResult.bytes.size
                quality = encodeResult.quality
                frameCount = encodeResult.frameCount
                outcome = "exito"
            } catch (error: WebpEncodeException) {
                outcome = "excepcion: ${error.message}"
            }
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000

            trace.line(
                "encode: elapsedMs=$encodeMs outcome=$outcome intentos=${measuring.callCount} " +
                    "sizeBytes=$sizeBytes quality=$quality frameCount=$frameCount",
            )

            val totalMs = decodeMs + encodeMs
            trace.line(
                "TOTAL: decodeMs=$decodeMs encodeMs=$encodeMs totalMs=$totalMs outcome=$outcome " +
                    "(comparar contra RNF-08: 5000ms contenido representativo, 20000ms alta complejidad visual)",
            )

            if (sizeBytes != null) {
                assertTrue("el WebP resultante debe respetar RF-10 (500 000 bytes)", sizeBytes <= 500_000)
            }
        } finally {
            trace.line("=== fin ===")
            trace.close()
        }

        println("StickersiniVideoImport: traza completa en ${trace.path}")
        assertTrue("la corrida debe dejar traza pase lo que pase con los tiempos", trace.file.exists())
    }

    @Test
    fun decodificaYCodificaUnVideoReal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        measureClip(instrumentation.targetContext, testVideoFile(), MAX_CLIP_DURATION_MS, "video_import_trace.txt")
    }

    /** Clip de 2 s: la duración típica de un sticker, no el máximo de RF-06. */
    @Test
    fun decodificaYCodificaClipDe2Segundos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        measureClip(instrumentation.targetContext, testVideoFile(), 2_000, "video_import_trace_2s.txt")
    }

    /** Clip de 3 s: el mismo caso de referencia que usan las pruebas sintéticas de Fase 1. */
    @Test
    fun decodificaYCodificaClipDe3Segundos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        measureClip(instrumentation.targetContext, testVideoFile(), 3_000, "video_import_trace_3s.txt")
    }

    /** Clip de 5 s: el punto medio entre lo típico y el máximo de RF-06. */
    @Test
    fun decodificaYCodificaClipDe5Segundos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        measureClip(instrumentation.targetContext, testVideoFile(), 5_000, "video_import_trace_5s.txt")
    }

    /**
     * Chequeo de humo del posicionamiento arbitrario (ADR-0008): decodificar
     * desde un `startMs > 0` con el mismo video real no debe fallar ni
     * devolver el clip completo. No valida contenido pixel a pixel (el
     * video es arbitrario, provisto por quien corre el test) — eso ya lo
     * cubre `ClipRangeTest` en aislamiento; esto sí ejercita el
     * `MediaExtractor.seekTo` real, que `ClipRangeTest` no puede.
     */
    @Test
    fun decodificaDesdeUnInicioArbitrario() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = testVideoFile()

        val retriever = MediaMetadataRetriever()
        val sourceDurationMs = try {
            retriever.setDataSource(videoFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            // No usar Closeable.use/close(): MediaMetadataRetriever solo lo
            // implementa desde API 29, y minSdk es 26.
            retriever.release()
        }
        assumeTrue(
            "el video de prueba dura ${sourceDurationMs}ms; hacen falta al menos 3000ms " +
                "para probar un inicio distinto de 0",
            sourceDurationMs >= 3_000,
        )

        val trace = Trace(
            File(instrumentation.targetContext.getExternalFilesDir(null), "video_import_seek_trace.txt"),
        )
        trace.line("=== inicio: startMs=1000 durationMs=2000 sobre ${videoFile.absolutePath} ===")
        try {
            val decodeStart = System.nanoTime()
            val importResult = VideoFrameDecoder().decode(
                instrumentation.targetContext,
                Uri.fromFile(videoFile),
                startMs = 1_000,
                durationMs = 2_000,
            )
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

            trace.line(
                "decode: elapsedMs=$decodeMs truncated=${importResult.truncated} " +
                    "decodedFrameCount=${importResult.decodedFrameCount} framesParaEncoder=${importResult.frames.size} " +
                    "duracionTotalFramesMs=${importResult.frames.sumOf { it.durationMs }}",
            )

            assertTrue("el tramo pedido debe producir al menos un fotograma", importResult.frames.isNotEmpty())
            assertTrue(
                "un tramo de 2000ms pedido no debería devolver bastante más que eso " +
                    "(${importResult.frames.sumOf { it.durationMs }}ms) salvo que el video sea más corto",
                importResult.frames.sumOf { it.durationMs } <= 2_500,
            )
        } finally {
            trace.line("=== fin ===")
            trace.close()
        }

        println("StickersiniVideoImport: traza completa en ${trace.path}")
    }
}
