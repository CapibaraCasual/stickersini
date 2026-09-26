package io.github.capibaracasual.stickersini.stickers.data

import android.content.Context
import android.net.Uri
import io.github.capibaracasual.stickersini.provider.WaStickerContract
import io.github.capibaracasual.stickersini.stickers.domain.ManagedStickerPack
import io.github.capibaracasual.stickersini.stickers.domain.SeedPacks
import io.github.capibaracasual.stickersini.stickers.domain.StickerPack

private const val USER_PACK_PUBLISHER = "Stickersini"
private const val TRAY_FILE_NAME = "tray.png"

/** Un pack elegible como destino de un sticker nuevo del mismo tipo (RF-18), con cuántos tiene ya (para saber si le queda lugar, RF-16). */
data class PackChoice(val identifier: String, val name: String, val stickerCount: Int)

/**
 * Combina la definición base de cada pack (siempre en `assets/`, de solo
 * lectura, sin cambios frente a como ya la leía
 * [StickerPackAssetRepository]) con los stickers que el usuario agregó
 * después (en almacenamiento interno, ver [UserPackStickerRepository] y
 * ADR-0010), más los packs enteramente propios del usuario (RF-15, sin
 * ninguna base en `assets/` — ver [UserPackManifestRepository]). Es el único
 * repositorio que debe usar
 * [io.github.capibaracasual.stickersini.provider.StickerContentProvider]: no
 * le importa de qué fuente viene cada sticker, salvo al momento de abrir sus
 * bytes ([isUserAddedFile]/[userStickerFile]).
 *
 * Construye dos formas del mismo dato (ADR-0014): [ManagedStickerPack]
 * (todos los packs, válidos para WhatsApp o no — [getAllManagedPacks], para
 * la pantalla de gestión) y [StickerPack] (solo los que ya llegan al mínimo
 * de RF-16 — [getAllPacks], lo único que ve
 * [io.github.capibaracasual.stickersini.provider.StickerContentProvider]).
 */
class StickerPackRepository(private val context: Context) {

    private val assetRepository = StickerPackAssetRepository(context)
    private val userStickerRepository = UserPackStickerRepository(context)
    private val userPackManifest = UserPackManifestRepository(context)

    /** RF-15: todos los packs, válidos para WhatsApp o no todavía (ADR-0014) — para la pantalla de gestión. */
    fun getAllManagedPacks(): List<ManagedStickerPack> {
        val seedPacks = assetRepository.getAllPacks().map { base ->
            val extras = userStickerRepository.getExtraStickers(base.identifier, base.isAnimatedPack)
            ManagedStickerPack(base.identifier, base.name, base.isAnimatedPack, base.stickers + extras, isSeedPack = true)
        }
        val userPacks = userPackManifest.getAll().map { entry ->
            val stickers = userStickerRepository.getExtraStickers(entry.identifier, entry.isAnimated)
            ManagedStickerPack(entry.identifier, entry.name, entry.isAnimated, stickers, isSeedPack = false)
        }
        return seedPacks + userPacks
    }

    /** Solo los que ya llegan al mínimo de RF-16: lo que expone el `ContentProvider` a WhatsApp (ADR-0014). */
    fun getAllPacks(): List<StickerPack> = getAllManagedPacks().mapNotNull(::toValidStickerPackOrNull)

    fun getPack(identifier: String): StickerPack? = getAllPacks().find { it.identifier == identifier }

    fun isUserAddedFile(identifier: String, fileName: String): Boolean =
        userStickerRepository.stickerFile(identifier, fileName).exists()

    fun userStickerFile(identifier: String, fileName: String) = userStickerRepository.stickerFile(identifier, fileName)

    /** Para mostrar miniaturas en la pantalla de gestión (RF-15): mismo criterio que ya usa `StickerContentProvider.openAssetFile`. */
    fun readStickerBytes(identifier: String, fileName: String): ByteArray =
        if (isUserAddedFile(identifier, fileName)) {
            userStickerFile(identifier, fileName).readBytes()
        } else {
            context.assets.open("$identifier/$fileName").use { it.readBytes() }
        }

    /** Packs donde puede ir un sticker nuevo de tipo [isAnimated] (RF-18): el semilla correspondiente primero, luego los propios que todavía tengan lugar (RF-16). */
    fun getEligiblePacksForNewSticker(isAnimated: Boolean): List<PackChoice> =
        getAllManagedPacks()
            .filter { it.isAnimated == isAnimated && it.stickers.size < StickerPack.STICKERS_MAX_PER_PACK }
            .map { PackChoice(it.identifier, it.name, it.stickers.size) }

    /**
     * Agrega [webpBytes] al pack [identifier] (semilla o propio, ver
     * [getEligiblePacksForNewSticker]). No pasa por [getPack]/[getAllPacks]
     * para el chequeo de máximo: esos filtran los packs propios que todavía
     * no llegan al mínimo de RF-16 (ADR-0014), y un pack recién creado con
     * menos de 3 stickers tiene que poder seguir recibiendo el siguiente.
     *
     * @throws IllegalStateException si el pack ya llegó al máximo de RF-16.
     */
    fun addStickerToPack(
        identifier: String,
        isAnimated: Boolean,
        webpBytes: ByteArray,
        emojis: List<String>,
        accessibilityText: String,
    ): ManagedStickerPack {
        val baseCount = assetRepository.getPack(identifier)?.stickers?.size ?: 0
        val extrasCount = userStickerRepository.getExtraStickers(identifier, isAnimated).size
        check(baseCount + extrasCount < StickerPack.STICKERS_MAX_PER_PACK) {
            "RF-16: \"$identifier\" ya tiene el máximo de ${StickerPack.STICKERS_MAX_PER_PACK} stickers"
        }
        // RF-14: solo un pack propio necesita que se le genere el ícono acá —
        // uno semilla ya trae el suyo en assets/ (ADR-0004).
        if (baseCount == 0) userStickerRepository.ensureTrayIcon(identifier, webpBytes)
        userStickerRepository.addSticker(identifier, webpBytes, isAnimated, emojis, accessibilityText)
        notifyPackChanged(identifier)
        return checkNotNull(getAllManagedPacks().find { it.identifier == identifier }) {
            "Pack no encontrado tras guardar: $identifier"
        }
    }

    /** RF-15: crea un pack propio nuevo, sin stickers todavía (invisible para WhatsApp hasta llegar al mínimo, ADR-0014). */
    fun createUserPack(name: String, isAnimated: Boolean): UserPackEntry = userPackManifest.create(name, isAnimated)

    /** @throws IllegalArgumentException si [identifier] es uno de los dos packs semilla (de solo lectura, ADR-0014). */
    fun renameUserPack(identifier: String, newName: String) {
        requireNotSeedPack(identifier)
        userPackManifest.rename(identifier, newName)
        notifyPackChanged(identifier)
    }

    /**
     * RF-15: borra el pack propio entero. No puede retirarlo de WhatsApp si
     * ya se había confirmado ahí (ADR-0014) — la UI debe avisarlo antes de
     * llamar a esto.
     *
     * @throws IllegalArgumentException si [identifier] es uno de los dos packs semilla.
     */
    fun deleteUserPack(identifier: String) {
        requireNotSeedPack(identifier)
        userPackManifest.delete(identifier)
        userStickerRepository.deletePackDirectory(identifier)
        notifyPackChanged(identifier)
    }

    /** @throws IllegalArgumentException si [identifier] es uno de los dos packs semilla (ADR-0014: ni sus propios stickers se pueden quitar desde la gestión). */
    fun removeStickerFromUserPack(identifier: String, isAnimated: Boolean, imageFileName: String) {
        requireNotSeedPack(identifier)
        userStickerRepository.removeSticker(identifier, isAnimated, imageFileName)
        notifyPackChanged(identifier)
    }

    private fun requireNotSeedPack(identifier: String) {
        require(identifier != SeedPacks.STATIC_IDENTIFIER && identifier != SeedPacks.ANIMATED_IDENTIFIER) {
            "Los packs semilla son de solo lectura (ADR-0014): $identifier"
        }
    }

    private fun toValidStickerPackOrNull(pack: ManagedStickerPack): StickerPack? {
        if (pack.stickers.size < StickerPack.STICKERS_MIN_PER_PACK) return null
        if (!pack.isSeedPack) {
            return StickerPack.create(
                identifier = pack.identifier,
                name = pack.name,
                publisher = USER_PACK_PUBLISHER,
                trayImageFileName = TRAY_FILE_NAME,
                stickers = pack.stickers,
            )
        }
        // Conserva publisher/email/sitios tal como vienen de assets/contents.json:
        // ManagedStickerPack no los carga (la gestión de packs no los necesita).
        val base = checkNotNull(assetRepository.getPack(pack.identifier))
        return StickerPack.create(
            identifier = base.identifier,
            name = base.name,
            publisher = base.publisher,
            trayImageFileName = base.trayImageFileName,
            stickers = pack.stickers,
            publisherEmail = base.publisherEmail,
            publisherWebsite = base.publisherWebsite,
            privacyPolicyWebsite = base.privacyPolicyWebsite,
            licenseAgreementWebsite = base.licenseAgreementWebsite,
        )
    }

    /**
     * RF-22: WhatsApp ya cachea el contenido de un pack que el usuario
     * confirmó agregar; esto es lo que le avisa que cambió (junto con el
     * `image_data_version` que ahora refleja la cantidad real de stickers,
     * ver [io.github.capibaracasual.stickersini.provider.StickerContentProvider]).
     * No hay un aviso equivalente para "este pack ya no existe" (ADR-0014):
     * el contrato WAStickerApps no tiene esa acción.
     */
    private fun notifyPackChanged(identifier: String) {
        val authority = WaStickerContract.authority(context)
        val uri = Uri.parse("content://$authority/${WaStickerContract.Path.METADATA}/$identifier")
        context.contentResolver.notifyChange(uri, null)
    }
}
