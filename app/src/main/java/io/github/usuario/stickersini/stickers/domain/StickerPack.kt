package io.github.usuario.stickersini.stickers.domain

/**
 * Pack de stickers válido para WhatsApp. Solo se puede construir mediante
 * [StickerPack.create], que aplica las validaciones de RF-16 y RF-18 antes de
 * dejar existir un pack inconsistente.
 */
class StickerPack private constructor(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFileName: String,
    val stickers: List<Sticker>,
    val publisherEmail: String,
    val publisherWebsite: String,
    val privacyPolicyWebsite: String,
    val licenseAgreementWebsite: String,
) {
    val isAnimatedPack: Boolean
        get() = stickers.first().isAnimated

    companion object {
        const val STICKERS_MIN_PER_PACK = 3
        const val STICKERS_MAX_PER_PACK = 30

        fun create(
            identifier: String,
            name: String,
            publisher: String,
            trayImageFileName: String,
            stickers: List<Sticker>,
            publisherEmail: String = "",
            publisherWebsite: String = "",
            privacyPolicyWebsite: String = "",
            licenseAgreementWebsite: String = "",
        ): StickerPack {
            require(stickers.size in STICKERS_MIN_PER_PACK..STICKERS_MAX_PER_PACK) {
                "RF-16: \"$identifier\" tiene ${stickers.size} stickers, debe tener entre " +
                    "$STICKERS_MIN_PER_PACK y $STICKERS_MAX_PER_PACK."
            }
            val animatedCount = stickers.count { it.isAnimated }
            require(animatedCount == 0 || animatedCount == stickers.size) {
                "RF-18: \"$identifier\" mezcla stickers animados y estáticos, no está permitido."
            }
            return StickerPack(
                identifier = identifier,
                name = name,
                publisher = publisher,
                trayImageFileName = trayImageFileName,
                stickers = stickers,
                publisherEmail = publisherEmail,
                publisherWebsite = publisherWebsite,
                privacyPolicyWebsite = privacyPolicyWebsite,
                licenseAgreementWebsite = licenseAgreementWebsite,
            )
        }
    }
}
