package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.net.Uri
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.STATIC_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import io.github.capibaracasual.stickersini.webp.WebpEncodeResult

/**
 * Recorrido mínimo de Fase 3: junta la decodificación ([VideoFrameDecoder] o
 * [ImageFrameDecoder], según [isVideo]) con la codificación
 * ([WebpAnimEncoder]) en una sola llamada, reportando [ConversionStage] real
 * en cada paso (RNF-08). Con recorte automático al centro y sin tramo propio
 * todavía (RF-06/RF-07 quedan para después de este recorrido mínimo): un
 * video siempre se procesa desde el segundo 0 hasta el tope de
 * [MAX_CLIP_DURATION_MS].
 *
 * No decide en qué pack queda el resultado ni lo guarda: eso es
 * responsabilidad de quien llame, con el [WebpEncodeResult] que devuelve
 * esta función.
 */
object StickerConversionPipeline {

    /** @throws VideoDecodeException, ImageDecodeException o WebpEncodeException, según dónde falle. */
    fun convert(
        context: Context,
        uri: Uri,
        isVideo: Boolean,
        onStage: (ConversionStage) -> Unit = {},
    ): WebpEncodeResult {
        val frames = if (isVideo) {
            VideoFrameDecoder().decode(context, uri) { framesDecoded, estimatedTotalFrames ->
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
