package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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

private const val TAG = "StickersiniReducedResDecode"
private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val IMAGE_AVAILABLE_TIMEOUT_MS = 200L
private const val RUNS_PER_CASE = 5

/** Mismo patrón que `SweepTrace`/`Trace` en los otros tests de este paquete: nombre distinto por archivo, mismo propósito. */
private class FeasibilityTrace(val file: File) {
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
 * Investiga (sin cambiar `VideoFrameDecoder`, ADR-0012 lo deja como está)
 * si este dispositivo puede hacer que `MediaCodec` entregue menos píxeles
 * por fotograma **desde el decode mismo**, no recién al escalar después
 * (que es lo que midió `ResolutionFpsSweepTest` y no atacaba el costo
 * real: `YuvFrameConverter` siempre convierte el cuadrado nativo completo,
 * y recién `Bitmap.createScaledBitmap` lo achica).
 *
 * Método: crear el `ImageReader` con un tamaño menor que el nativo del
 * video (`format.KEY_WIDTH`/`KEY_HEIGHT`), configurar el decoder igual
 * (el `MediaFormat` de origen sin tocar — el tamaño del stream no cambia,
 * lo que cambia es el tamaño del buffer de salida pedido) y declarar
 * `VIDEO_SCALING_MODE_SCALE_TO_FIT`. Lo que este test mide, sin asumir la
 * respuesta:
 *
 * 1. **¿El decoder acepta la configuración?** (`configure`/`start` pueden
 *    fallar o el bucle puede colgarse si el tamaño pedido no es válido
 *    para este `ImageReader`).
 * 2. **¿La imagen entregada tiene de verdad el tamaño pedido?**
 *    (`Image.getWidth/getHeight`) — si el dispositivo ignora el tamaño del
 *    `ImageReader` y entrega igual el nativo, esta vía no sirve acá y hay
 *    que decirlo, no asumir que "probablemente funciona".
 * 3. **Si lo acepta y lo respeta, ¿baja el tiempo de decode?** (el bucle
 *    de acquire, sin conversión — esta clase no llama a
 *    `YuvFrameConverter`, mide únicamente el costo de `MediaCodec`+
 *    `ImageReader`).
 *
 * No trae video embebido: usa `stickersini_test_video.mp4` en el
 * `externalFilesDir`, igual que los demás tests de este paquete.
 */
@RunWith(AndroidJUnit4::class)
class ReducedResolutionDecodeFeasibilityTest {

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

    private data class ProbeResult(
        val requestedWidth: Int,
        val requestedHeight: Int,
        val actualWidth: Int?,
        val actualHeight: Int?,
        val decodeMs: Long,
        val framesAcquired: Int,
        val outcome: String,
    )

    /**
     * Decodifica `durationMs` del video pidiendo que el `ImageReader` sea de
     * `readerWidth`×`readerHeight` en vez del nativo. No convierte a RGB
     * (eso es un costo aparte, ya medido en otro lado): solo mide el bucle
     * `MediaCodec`+`ImageReader`, para aislar si achicar el tamaño de
     * salida del decode en sí mismo cambia algo.
     */
    private fun probeReaderSize(
        context: Context,
        uri: Uri,
        readerWidth: Int,
        readerHeight: Int,
        durationMs: Long,
    ): ProbeResult {
        val extractor = MediaExtractor()
        var actualWidth: Int? = null
        var actualHeight: Int? = null
        var framesAcquired = 0
        var outcome = "exito"
        val start = System.nanoTime()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                extractor.setDataSource(checkNotNull(pfd).fileDescriptor)
            }
            val trackIndex = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(trackIndex)
            val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(trackIndex)

            val imageReader = ImageReader.newInstance(readerWidth, readerHeight, ImageFormat.YUV_420_888, /* maxImages = */ 2)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, imageReader.surface, null, 0)
                codec.start()
                codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val endUs = durationMs * 1_000L

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex))
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0 || extractor.sampleTime > endUs) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    if (outputIndex >= 0) {
                        codec.releaseOutputBuffer(outputIndex, true)
                        val image: Image? = acquireImageOrNull(imageReader)
                        if (image != null) {
                            if (framesAcquired == 0) {
                                actualWidth = image.width
                                actualHeight = image.height
                            }
                            framesAcquired++
                            image.close()
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                imageReader.close()
            }
        } catch (error: Exception) {
            outcome = "excepcion: ${error.javaClass.simpleName}: ${error.message}"
        } finally {
            extractor.release()
        }
        val decodeMs = (System.nanoTime() - start) / 1_000_000
        return ProbeResult(readerWidth, readerHeight, actualWidth, actualHeight, decodeMs, framesAcquired, outcome)
    }

    private fun acquireImageOrNull(reader: ImageReader): Image? {
        val deadlineNanos = System.nanoTime() + IMAGE_AVAILABLE_TIMEOUT_MS * 1_000_000L
        while (true) {
            val image = reader.acquireNextImage()
            if (image != null) return image
            if (System.nanoTime() >= deadlineNanos) return null
            Thread.sleep(1)
        }
    }

    @Test
    fun probarTamanosDeImageReaderMenoresAlNativo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val videoFile = testVideoFile()
        val uri = Uri.fromFile(videoFile)

        val trace = FeasibilityTrace(File(context.getExternalFilesDir(null), "reduced_resolution_decode_trace.txt"))
        trace.line("=== factibilidad de ImageReader reducido, sobre ${videoFile.absolutePath} ===")

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(trackIndex)
            val nativeWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val nativeHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            extractor.release()
            trace.line("nativo: ${nativeWidth}x$nativeHeight")

            // Candidatos: nativo (control), mitad y cuarto en cada eje,
            // redondeados a par (formatos YUV necesitan dimensiones pares).
            val candidates = listOf(
                nativeWidth to nativeHeight,
                (nativeWidth / 2).let { it - it % 2 } to (nativeHeight / 2).let { it - it % 2 },
                (nativeWidth / 4).let { it - it % 2 } to (nativeHeight / 4).let { it - it % 2 },
            )

            for ((readerWidth, readerHeight) in candidates) {
                val results = (1..RUNS_PER_CASE).map { run ->
                    val r = probeReaderSize(context, uri, readerWidth, readerHeight, durationMs = 3_000L)
                    trace.line(
                        "pedido=${readerWidth}x$readerHeight corrida=$run " +
                            "real=${r.actualWidth}x${r.actualHeight} decodeMs=${r.decodeMs} " +
                            "framesAcquired=${r.framesAcquired} outcome=${r.outcome}",
                    )
                    r
                }
                val times = results.map { it.decodeMs }.sorted()
                val honored = results.all { it.actualWidth == readerWidth && it.actualHeight == readerHeight }
                trace.line(
                    "RESUMEN pedido=${readerWidth}x$readerHeight " +
                        "medianaMs=${times[times.size / 2]} rangoMs=${times.first()}-${times.last()} " +
                        "respetadoPorElDispositivo=$honored",
                )
            }
        } finally {
            trace.line("=== fin ===")
            trace.close()
        }

        println("StickersiniReducedResDecode: traza completa en ${trace.path}")
    }
}
