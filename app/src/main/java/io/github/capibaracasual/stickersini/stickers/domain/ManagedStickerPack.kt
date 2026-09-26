package io.github.capibaracasual.stickersini.stickers.domain

/**
 * Un pack tal como está, válido para WhatsApp o no todavía — a diferencia de
 * [StickerPack], que solo puede existir si ya cumple RF-16 (3 a 30
 * stickers). Es lo que ve la pantalla de gestión de packs (RF-15): un pack
 * propio recién creado, o uno al que se le quitó un sticker y quedó por
 * debajo del mínimo, sigue siendo un [ManagedStickerPack] visible ahí,
 * aunque no llegue a ser un [StickerPack] válido para
 * [io.github.capibaracasual.stickersini.provider.StickerContentProvider]
 * (ver ADR-0014).
 */
data class ManagedStickerPack(
    val identifier: String,
    val name: String,
    val isAnimated: Boolean,
    val stickers: List<Sticker>,
    /** `true` para los dos packs semilla (ADR-0004): de solo lectura en la pantalla de gestión (ADR-0014). */
    val isSeedPack: Boolean,
) {
    /** Cuántos stickers faltan para llegar al mínimo de RF-16; `0` si ya lo cumple. */
    val missingForMinimum: Int
        get() = (StickerPack.STICKERS_MIN_PER_PACK - stickers.size).coerceAtLeast(0)
}
