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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val IMAGE_AVAILABLE_TIMEOUT_MS = 200L

/** RF-10/RF-11: los fotogramas que produce esta fase ya salen al tamaño exacto de un sticker. */
private const val STICKER_SIZE = 512

/**
 * ADR-0015: cuántos fotogramas decodificados-pero-sin-convertir puede
 * retener el `ImageReader` a la vez. El bucle de decode se frena
 * (`inFlight.acquire()`) si la conversión no da abasto: no puede haber más
 * conversiones pendientes que buffers libres. Valor medido, no elegido a
 * ojo — ver `docs/desarrollo/pruebas.md`.
 */
private const val IMAGE_READER_CAPACITY = 5

/** ADR-0015: tamaño del pool que convierte fotogramas en paralelo al decode del siguiente. Valor medido. */
private const val CONVERTER_THREADS = 3

/**
 * Fase 2 (RF-02, ADR-0008): decodifica un video existente en una lista de
 * [WebpFrame] lista para
 * [io.github.capibaracasual.stickersini.webp.WebpAnimEncoder]. No conoce ni
 * le importa la estrategia de ajuste de calidad/fotogramas de ese encoder
 * (ADR-0006, ADR-0007): esta clase solo decide, vía [FrameSampler], qué
 * fotogramas decodificados sobreviven al prefiltro, y los convierte a
 * bitmaps cuadrados de [STICKER_SIZE]×[STICKER_SIZE] con [YuvFrameConverter].
 *
 * [decode] acepta un tramo arbitrario (`startMs`, `durationMs`, RF-06,
 * elegido en `ui/TrimScreen.kt`) y un recorte de área (`normalizedCrop`,
 * RF-07, elegido en `ui/CropScreen.kt`); por defecto el tramo es el mismo
 * de siempre (desde el segundo 0, hasta 10 s) y el recorte el cuadrado
 * centrado más grande. `durationMs` se recorta a
 * [MAX_CLIP_DURATION_MS] si lo supera, sin decodificar el resto
 * (ADR-0008). [VideoImportResult.truncated] expone si el tramo procesado
 * quedó más corto que lo pedido, para que la capa que llame pueda
 * informarlo cuando exista una pantalla para hacerlo.
 *
 * **La conversión de cada fotograma corre en paralelo al decode del
 * siguiente (ADR-0015), no en el mismo hilo que lo espera.** El fotograma
 * decodificado se manda a un pool de [CONVERTER_THREADS] hilos apenas
 * `ImageReader` lo entrega, y el bucle sigue alimentando/vaciando
 * `MediaCodec` sin esperar a que esa conversión termine — un semáforo
 * ([IMAGE_READER_CAPACITY] `- 1` permisos) frena el bucle si la conversión
 * se atrasa, para no pedirle a `ImageReader` más buffers de los que puede
 * retener. Medido 1.75×-2.75× más rápido que esperar cada conversión antes
 * de seguir decodificando, con el mismo resultado (mismos bitmaps, mismo
 * orden): ver `docs/desarrollo/pruebas.md`.
 */
class VideoFrameDecoder(
    private val targetFps: Int = VIDEO_PREFILTER_TARGET_FPS,
) {

    /**
     * @param startMs instante de inicio del tramo dentro del video de origen.
     * @param durationMs duración pedida; se recorta a [MAX_CLIP_DURATION_MS] si la supera (RF-06).
     * @param normalizedCrop recorte de área (RF-07); por defecto el cuadrado
     * centrado más grande ([NormalizedCrop.CENTERED]). Se aplica igual a
     * todos los fotogramas del tramo: el usuario elige un solo recorte para
     * todo el clip, no uno por fotograma.
     * @param targetSize lado del cuadrado que produce cada fotograma; por
     * defecto [STICKER_SIZE] (RF-10/RF-11 exigen exactamente eso en el
     * resultado final). Parámetro de medición, no de producto: existe para
     * `ResolutionFpsSweepTest` (ver `docs/desarrollo/pruebas.md`), que
     * necesita comparar el costo de codificar a una resolución menor y
     * escalar a 512 después — ningún llamador de producto lo cambia del
     * valor por defecto.
     * @param onFrameDecoded se llama cada vez que un fotograma sobrevive el
     * prefiltro de [FrameSampler] y ya se convirtió a bitmap, con cuántos
     * lleva y una estimación del total (RNF-08: avance real, no un
     * indicador indeterminado).
     * @throws VideoDecodeException si el archivo no tiene pista de video, si
     * `MediaCodec` no entrega ningún fotograma dentro del tramo decodificado,
     * o si la decodificación nativa falla.
     */
    fun decode(
        context: Context,
        uri: Uri,
        startMs: Long = 0L,
        durationMs: Long = MAX_CLIP_DURATION_MS,
        normalizedCrop: NormalizedCrop = NormalizedCrop.CENTERED,
        targetSize: Int = STICKER_SIZE,
        onFrameDecoded: (framesDecoded: Int, estimatedTotalFrames: Int) -> Unit = { _, _ -> },
    ): VideoImportResult {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                val descriptor = checkNotNull(pfd) { "No se pudo abrir $uri" }
                extractor.setDataSource(descriptor.fileDescriptor)
            }
            return decodeSelectedTrack(extractor, startMs, durationMs, normalizedCrop, targetSize, onFrameDecoded)
        } finally {
            extractor.release()
        }
    }

    private fun decodeSelectedTrack(
        extractor: MediaExtractor,
        startMs: Long,
        durationMs: Long,
        normalizedCrop: NormalizedCrop,
        targetSize: Int,
        onFrameDecoded: (framesDecoded: Int, estimatedTotalFrames: Int) -> Unit,
    ): VideoImportResult {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw VideoDecodeException("El archivo no tiene ninguna pista de video")

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
        val estimatedTotalFrames = ceil((clip.endUs - clip.startUs) / 1_000_000.0 * targetFps).toInt().coerceAtLeast(1)

        extractor.selectTrack(trackIndex)
        // Posicionarse en el keyframe anterior o igual a startUs: MediaCodec
        // no puede decodificar correctamente a partir de un fotograma P/B
        // suelto, necesita arrancar de un keyframe. Los fotogramas entre ese
        // keyframe y clip.startUs igual se decodifican (referencia
        // obligatoria) pero no se conservan: ver el filtro de clip.contains
        // más abajo.
        if (clip.startUs > 0) {
            extractor.seekTo(clip.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        val imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, IMAGE_READER_CAPACITY)
        val codec = MediaCodec.createDecoderByType(mime)
        val converters: ExecutorService = Executors.newFixedThreadPool(CONVERTER_THREADS)
        // Como mucho (IMAGE_READER_CAPACITY - 1) fotogramas
        // decodificados esperando conversión: si se piden más de los que
        // el ImageReader puede retener, acquireNextImage empieza a
        // devolver null.
        val inFlight = Semaphore(IMAGE_READER_CAPACITY - 1)
        try {
            codec.configure(format, imageReader.surface, null, 0)
            codec.start()

            val sampler = FrameSampler(targetFps)
            val conversions = mutableListOf<Future<Bitmap>>()
            val keptPtsUs = mutableListOf<Long>()
            var decodedFrameCount = 0
            val framesConverted = AtomicInteger(0)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex))
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        // Ni bien la muestra cruza el fin del tramo se manda
                        // EOS en vez de encolarla: el resto del video no
                        // llega a decodificarse (ADR-0008).
                        if (sampleSize < 0 || extractor.sampleTime > clip.endUs) {
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
                    val presentationTimeUs = bufferInfo.presentationTimeUs
                    // clip.contains descarta tanto lo anterior a startUs
                    // (el tramo entre el keyframe y el inicio pedido) como
                    // lo posterior a endUs. shouldKeep tiene efecto de lado
                    // (avanza el umbral del muestreo) y debe evaluarse una
                    // sola vez, y solo cuando el fotograma sigue dentro del
                    // tramo: el orden de esta condición no es intercambiable.
                    // El timestamp se pasa relativo al inicio del tramo para
                    // que el muestreo no dependa de dónde arranca el video.
                    val keep = clip.contains(presentationTimeUs) &&
                        sampler.shouldKeep(clip.relativeToStart(presentationTimeUs))
                    codec.releaseOutputBuffer(outputIndex, keep)
                    if (keep) {
                        decodedFrameCount++
                        inFlight.acquire()
                        val image = acquireImageWithRetry(imageReader, inFlight)
                        keptPtsUs += presentationTimeUs
                        conversions += converters.submit(
                            Callable {
                                try {
                                    val bitmap = YuvFrameConverter.toSquareBitmap(image, rotationDegrees, targetSize, normalizedCrop)
                                    onFrameDecoded(framesConverted.incrementAndGet(), estimatedTotalFrames)
                                    bitmap
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

            // .get() conserva el orden de sumisión (uno por Future), aunque
            // las conversiones terminen en otro orden entre sí: el orden de
            // los fotogramas en el resultado no depende del orden en que el
            // pool los va terminando.
            val keptBitmaps = conversions.map { it.get() }

            return VideoImportResult(
                frames = buildWebpFrames(keptBitmaps, keptPtsUs, clip.endUs),
                decodedFrameCount = decodedFrameCount,
                sourceDurationMs = sourceDurationUs / 1_000,
                truncated = clip.truncated,
            )
        } finally {
            converters.shutdown()
            runCatching { codec.stop() }
            codec.release()
            imageReader.close()
        }
    }

    /**
     * La duración de cada fotograma es la distancia hasta el siguiente
     * conservado (o hasta [endUs] para el último), no un valor fijo: el
     * muestreo de [FrameSampler] no garantiza intervalos exactos entre
     * fotogramas reales. RF-13 exige al menos [FrameTiming.MIN_FRAME_DURATION_MS].
     */
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

    /**
     * `releaseOutputBuffer(index, render = true)` programa el renderizado a
     * la superficie del `ImageReader`, pero no garantiza que la imagen esté
     * disponible de inmediato. Sondea con una espera corta en vez de asumir
     * que ya está ahí. Si se agota el plazo, libera el permiso de
     * [inFlight] antes de fallar para no dejar el semáforo inconsistente
     * (ADR-0015).
     */
    private fun acquireImageWithRetry(reader: ImageReader, inFlight: Semaphore): Image {
        val deadlineNanos = System.nanoTime() + IMAGE_AVAILABLE_TIMEOUT_MS * 1_000_000L
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

data class VideoImportResult(
    val frames: List<WebpFrame>,
    /** Fotogramas que sobrevivieron el prefiltro de [FrameSampler], no los que decodificó `MediaCodec` en total. */
    val decodedFrameCount: Int,
    val sourceDurationMs: Long,
    /** RF-06: `true` si el tramo procesado quedó más corto que lo pedido (tope de 10 s, o el video no llegaba). */
    val truncated: Boolean,
)
