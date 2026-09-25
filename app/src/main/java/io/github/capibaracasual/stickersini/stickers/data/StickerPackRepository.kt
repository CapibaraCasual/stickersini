package io.github.capibaracasual.stickersini.stickers.data

import android.content.Context
import android.net.Uri
import io.github.capibaracasual.stickersini.provider.WaStickerContract
import io.github.capibaracasual.stickersini.stickers.domain.SeedPacks
import io.github.capibaracasual.stickersini.stickers.domain.StickerPack

/**
 * Combina la definición base de cada pack (siempre en `assets/`, de solo
 * lectura, sin cambios frente a como ya la leía
 * [StickerPackAssetRepository]) con los stickers que el usuario agregó
 * después (en almacenamiento interno, ver [UserPackStickerRepository] y
 * ADR-0010). Es el único repositorio que debe usar
 * [io.github.capibaracasual.stickersini.provider.StickerContentProvider]:
 * no le importa de qué fuente viene cada sticker, salvo al momento de abrir
 * sus bytes ([isUserAddedFile]/[userStickerFile]).
 */
class StickerPackRepository(private val context: Context) {

    private val assetRepository = StickerPackAssetRepository(context)
    private val userStickerRepository = UserPackStickerRepository(context)

    fun getAllPacks(): List<StickerPack> = assetRepository.getAllPacks().map(::withUserExtras)

    fun getPack(identifier: String): StickerPack? = assetRepository.getPack(identifier)?.let(::withUserExtras)

    fun isUserAddedFile(identifier: String, fileName: String): Boolean =
        userStickerRepository.stickerFile(identifier, fileName).exists()

    fun userStickerFile(identifier: String, fileName: String) = userStickerRepository.stickerFile(identifier, fileName)

    /**
     * Agrega [webpBytes] al pack semilla que corresponda según [isAnimated]
     * (RF-18: un sticker animado no puede entrar al pack estático, ni al
     * revés). Ver [SeedPacks] para por qué el destino es siempre uno de los
     * dos packs semilla en vez de un pack nuevo.
     *
     * @throws IllegalStateException si ese pack ya llegó al máximo de RF-16.
     */
    fun addStickerToSeedPack(
        isAnimated: Boolean,
        webpBytes: ByteArray,
        emojis: List<String>,
        accessibilityText: String,
    ): StickerPack {
        val identifier = if (isAnimated) SeedPacks.ANIMATED_IDENTIFIER else SeedPacks.STATIC_IDENTIFIER
        val current = checkNotNull(getPack(identifier)) { "Pack semilla no encontrado: $identifier" }
        check(current.stickers.size < StickerPack.STICKERS_MAX_PER_PACK) {
            "RF-16: \"$identifier\" ya tiene el máximo de ${StickerPack.STICKERS_MAX_PER_PACK} stickers"
        }
        userStickerRepository.addSticker(identifier, webpBytes, isAnimated, emojis, accessibilityText)
        notifyPackChanged(identifier)
        return checkNotNull(getPack(identifier))
    }

    /**
     * RF-22: WhatsApp ya cachea el contenido de un pack que el usuario
     * confirmó agregar; esto es lo que le avisa que cambió (junto con el
     * `image_data_version` que ahora refleja la cantidad real de stickers,
     * ver [io.github.capibaracasual.stickersini.provider.StickerContentProvider]).
     */
    private fun notifyPackChanged(identifier: String) {
        val authority = WaStickerContract.authority(context)
        val uri = Uri.parse("content://$authority/${WaStickerContract.Path.METADATA}/$identifier")
        context.contentResolver.notifyChange(uri, null)
    }

    private fun withUserExtras(basePack: StickerPack): StickerPack {
        val extras = userStickerRepository.getExtraStickers(basePack.identifier, basePack.isAnimatedPack)
        if (extras.isEmpty()) return basePack
        return StickerPack.create(
            identifier = basePack.identifier,
            name = basePack.name,
            publisher = basePack.publisher,
            trayImageFileName = basePack.trayImageFileName,
            stickers = basePack.stickers + extras,
            publisherEmail = basePack.publisherEmail,
            publisherWebsite = basePack.publisherWebsite,
            privacyPolicyWebsite = basePack.privacyPolicyWebsite,
            licenseAgreementWebsite = basePack.licenseAgreementWebsite,
        )
    }
}
