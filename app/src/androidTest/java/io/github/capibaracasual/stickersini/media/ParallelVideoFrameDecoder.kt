package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import io.github.capibaracasual.stickersini.webp.FrameTiming
import io.github.capibaracasual.stickersini.webp.WebpFrame
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore

private const val PARALLEL_DEQUEUE_TIMEOUT_US = 10_000L
private const val PARALLEL_IMAGE_AVAILABLE_TIMEOUT_MS = 200L
private const val PARALLEL_STICKER_SIZE = 512

/**
 * Prototipo que investigó la vía 2 pedida (decodificar y convertir en
 * paralelo) antes de adoptarla. **ADR-0015 la adoptó a producción**
 * (`VideoFrameDecoder` ya decodifica así, con `CONVERTER_THREADS=3`/
 * `IMAGE_READER_CAPACITY=5` fijos): esta clase queda como herramienta de
 * medición para comparar *otras* configuraciones de hilos/capacidad contra
 * ese valor adoptado, no como la ruta de producción. Mismo bucle de
 * `MediaCodec`/`ImageReader`, la conversión YUV→RGB de cada fotograma
 * (nativa, `:yuv`, ADR-0011) se manda a un `ExecutorService` en vez de
 * esperarla antes de seguir decodificando: la idea es que el bucle pueda
 * seguir alimentando/vaciando `MediaCodec` mientras el fotograma anterior
 * todavía se está convirtiendo en otro hilo.
 *
 * `imageReaderCapacity` (el `maxImages` del `ImageReader`) limita cuántos
 * fotogramas pueden estar decodificados-pero-sin-convertir a la vez: el
 * bucle de decode se frena (`inFlight.acquire()`) si la conversión no da
 * abasto, así que no puede haber más conversiones pendientes que buffers
 * libres en el `ImageReader`. `converterThreads` es el tamaño del pool.
 * Ambos son parámetros de medición: el propósito de esta clase es
 * comparar valores, no fijar uno.
 *
 * `acquireImageMs`/`conversionMs` de [VideoImportResult] quedan en 0 acá:
 * con la conversión superpuesta al decode, medir cada una por separado ya
 * no aísla nada (se solapan a propósito) — lo que importa es el tiempo
 * total de punta a punta, que sí mide quien llama a [decode].
 */
class ParallelVideoFrameDecoder(
    private val targetFps: Int,
    private val converterThreads: Int,
    private val imageReaderCapacity: Int,
) {
    fun decode(
        context: Context,
        uri: Uri,
        startMs: Long = 0L,
        durationMs: Long,
        normalizedCrop: NormalizedCrop = NormalizedCrop.CENTERED,
    ): VideoImportResult {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                extractor.setDataSource(checkNotNull(pfd).fileDescriptor)
            }
            return decodeSelectedTrack(extractor, startMs, durationMs, normalizedCrop)
        } finally {
            extractor.release()
        }
    }

    private fun decodeSelectedTrack(
        extractor: MediaExtractor,
        startMs: Long,
        durationMs: Long,
        normalizedCrop: NormalizedCrop,
    ): VideoImportResult {
        val trackIndex = (0 until extractor.trackCount).first { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        val format = extractor.getTrackFormat(trackIndex)
        val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val rotationDegrees = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            format.getInteger(MediaFormat.KEY_ROTATION)
        } else {
            0
        }
        val sourceDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        val clip = ClipRange.of(startMs, durationMs, sourceDurationMs = sourceDurationUs / 1_000)

        extractor.selectTrack(trackIndex)
        if (clip.startUs > 0) {
            extractor.seekTo(clip.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        val imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, imageReaderCapacity)
        val codec = MediaCodec.createDecoderByType(mime)
        val executor: ExecutorService = Executors.newFixedThreadPool(converterThreads)
        // Como mucho (imageReaderCapacity - 1) fotogramas decodificados
        // esperando conversión: si se piden más de los que el ImageReader
        // puede retener, acquireNextImage empieza a devolver null.
        val inFlight = Semaphore(imageReaderCapacity - 1)
        try {
            codec.configure(format, imageReader.surface, null, 0)
            codec.start()

            val sampler = FrameSampler(targetFps)
            val futures = mutableListOf<Future<Bitmap>>()
            val keptPtsUs = mutableListOf<Long>()
            var decodedFrameCount = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(PARALLEL_DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex))
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0 || extractor.sampleTime > clip.endUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, PARALLEL_DEQUEUE_TIMEOUT_US)
                if (outputIndex >= 0) {
                    val presentationTimeUs = bufferInfo.presentationTimeUs
                    val keep = clip.contains(presentationTimeUs) &&
                        sampler.shouldKeep(clip.relativeToStart(presentationTimeUs))
                    codec.releaseOutputBuffer(outputIndex, keep)
                    if (keep) {
                        decodedFrameCount++
                        inFlight.acquire()
                        val image = acquireImageWithRetry(imageReader, inFlight)
                        keptPtsUs += presentationTimeUs
                        futures += executor.submit(
                            Callable {
                                try {
                                    YuvFrameConverter.toSquareBitmap(image, rotationDegrees, PARALLEL_STICKER_SIZE, normalizedCrop)
                                } finally {
                                    image.close()
                                    inFlight.release()
                                }
                            },
                        )
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            val keptBitmaps = futures.map { it.get() }
            return VideoImportResult(
                frames = buildWebpFrames(keptBitmaps, keptPtsUs, clip.endUs),
                decodedFrameCount = decodedFrameCount,
                sourceDurationMs = sourceDurationUs / 1_000,
                truncated = clip.truncated,
            )
        } finally {
            executor.shutdown()
            runCatching { codec.stop() }
            codec.release()
            imageReader.close()
        }
    }

    private fun buildWebpFrames(bitmaps: List<Bitmap>, ptsUs: List<Long>, endUs: Long): List<WebpFrame> {
        if (bitmaps.isEmpty()) {
            throw VideoDecodeException("No se conservó ningún fotograma del video dentro del tramo decodificado")
        }
        return bitmaps.indices.map { i ->
            val startUs = ptsUs[i]
            val nextUs = if (i + 1 < ptsUs.size) ptsUs[i + 1] else endUs
            val durationMs = ((nextUs - startUs) / 1_000).toInt().coerceAtLeast(FrameTiming.MIN_FRAME_DURATION_MS)
            WebpFrame(bitmaps[i], durationMs)
        }
    }

    /** Si el timeout se agota, libera el permiso antes de fallar para no dejar el semáforo inconsistente. */
    private fun acquireImageWithRetry(reader: ImageReader, inFlight: Semaphore): Image {
        val deadlineNanos = System.nanoTime() + PARALLEL_IMAGE_AVAILABLE_TIMEOUT_MS * 1_000_000L
        while (true) {
            val image = reader.acquireNextImage()
            if (image != null) return image
            if (System.nanoTime() >= deadlineNanos) {
                inFlight.release()
                throw VideoDecodeException("ImageReader no entregó el fotograma decodificado a tiempo")
            }
            Thread.sleep(1)
        }
    }
}
