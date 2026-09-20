package io.github.capibaracasual.stickersini.stickers.domain

/**
 * Un sticker dentro de un [StickerPack]. [imageFileName] es el nombre del
 * archivo WebP dentro de `assets/<identifier>/`, no una ruta completa.
 */
data class Sticker(
    val imageFileName: String,
    val isAnimated: Boolean,
    val emojis: List<String> = emptyList(),
    val accessibilityText: String = "",
) {
    init {
        require(emojis.size <= MAX_EMOJIS_PER_STICKER) {
            "Un sticker admite como mÃ¡ximo $MAX_EMOJIS_PER_STICKER emojis, \"$imageFileName\" tiene ${emojis.size}."
        }
    }

    companion object {
        const val MAX_EMOJIS_PER_STICKER = 3
    }
}
