package io.github.capibaracasual.stickersini.media

import io.github.capibaracasual.stickersini.webp.EncodeAttemptProgress

/**
 * Etapa concreta del recorrido "elegir archivo → sticker guardado", para que
 * la UI muestre avance real (RNF-08: nueve segundos de espera sin
 * información se sienten como una app colgada) en vez de un indicador
 * indeterminado. Cada variante trae los números que la propia etapa ya
 * conoce, sin inventar un porcentaje global que ninguna etapa puede calcular
 * por sí sola (el tiempo de decodificación y el de codificación no son
 * comparables entre sí).
 */
sealed interface ConversionStage {
    /** [framesDecoded] ya sobrevivió el prefiltro de [FrameSampler]; [estimatedTotalFrames] sale del mismo cálculo. */
    data class DecodingVideo(val framesDecoded: Int, val estimatedTotalFrames: Int) : ConversionStage

    /** La imagen se decodifica en un solo paso: no hay fracción intermedia que mostrar. */
    data object DecodingImage : ConversionStage

    /** [attempt] envuelve lo que [io.github.capibaracasual.stickersini.webp.WebpAnimEncoder] ya mide de sí mismo. */
    data class Encoding(val attempt: EncodeAttemptProgress) : ConversionStage

    data object Saving : ConversionStage
}
