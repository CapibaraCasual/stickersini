package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.net.Uri
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.STATIC_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import io.github.capibaracasual.stickersini.webp.WebpEncodeResult

/**
 * Recorrido de Fase 3: junta la decodificación ([VideoFrameDecoder] o
 * [ImageFrameDecoder], según [isVideo]) con la codificación
 * ([WebpAnimEncoder]) en una sola llamada, reportando [ConversionStage] real
 * en cada paso (RNF-08). Con recorte automático al centro todavía (RF-07
 * queda pendiente): el tramo temporal de un video (RF-06, [startMs]/
 * [durationMs]) ya lo elige quien llama — ver `ui/TrimScreen.kt` — así que
 * esta función ya no asume `startMs = 0`.
 *
 * No decide en qué pack queda el resultado ni lo guarda: eso es
 * responsabilidad de quien llame, con el [WebpEncodeResult] que devuelve
 * esta función.
 */
object StickerConversionPipeline {

    /**
     * @param startMs instante de inicio del tramo a convertir (RF-06); sin
     * efecto cuando [isVideo] es `false`.
     * @param durationMs duración del tramo pedido; se recorta a
     * [MAX_CLIP_DURATION_MS] si la supera (ver [ClipRange]); sin efecto
     * cuando [isVideo] es `false`.
     * @throws VideoDecodeException, ImageDecodeException o WebpEncodeException, según dónde falle.
     */
    fun convert(
        context: Context,
        uri: Uri,
        isVideo: Boolean,
        startMs: Long = 0L,
        durationMs: Long = MAX_CLIP_DURATION_MS,
        onStage: (ConversionStage) -> Unit = {},
    ): WebpEncodeResult {
        val frames = if (isVideo) {
            VideoFrameDecoder().decode(context, uri, startMs, durationMs) { framesDecoded, estimatedTotalFrames ->
                onStage(ConversionStage.DecodingVideo(framesDecoded, estimatedTotalFrames))
            }.frames
        } else {
            onStage(ConversionStage.DecodingImage)
            listOf(ImageFrameDecoder().decode(context, uri))
        }

        val targetSizeBytes = if (isVideo) ANIMATED_WEBP_TARGET_SIZE_BYTES else STATIC_WEBP_TARGET_SIZE_BYTES
        val encoder = WebpAnimEncoder(targetSizeBytes = targetSizeBytes)
        return encoder.encode(frames) { attempt -> onStage(ConversionStage.Encoding(attempt)) }
    }
}
