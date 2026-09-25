package io.github.capibaracasual.stickersini.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.capibaracasual.stickersini.stickers.data.StickerPackRepository
import io.github.capibaracasual.stickersini.stickers.domain.StickerPack
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Cumple el contrato WAStickerApps (RF-19): expone los packs y sus stickers
 * como cursor de solo lectura y sirve los bytes de cada sticker, ya sea
 * desde `assets/` (contenido base de un pack semilla) o desde
 * almacenamiento interno (stickers que el usuario agregó después, ver
 * [StickerPackRepository] y ADR-0010). Es el único punto de contacto entre
 * esta aplicación y WhatsApp.
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var authority: String
    private lateinit var matcher: UriMatcher
    private lateinit var repository: StickerPackRepository

    override fun onCreate(): Boolean {
        val context = context ?: return false
        authority = WaStickerContract.authority(context)
        matcher = buildMatcher(authority)
        repository = StickerPackRepository(context)
        return true
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        METADATA_CODE -> "vnd.android.cursor.dir/vnd.$authority.${WaStickerContract.Path.METADATA}"
        METADATA_ITEM_CODE -> "vnd.android.cursor.item/vnd.$authority.${WaStickerContract.Path.METADATA}"
        STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$authority.${WaStickerContract.Path.STICKERS}"
        STICKERS_ASSET_CODE -> "image/webp"
        else -> throw IllegalArgumentException("Uri no soportada: $uri")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = when (matcher.match(uri)) {
        METADATA_CODE -> packsCursor(repository.getAllPacks())
        METADATA_ITEM_CODE -> packsCursor(listOfNotNull(repository.getPack(uri.lastPathSegment.orEmpty())))
        STICKERS_CODE -> stickersCursor(repository.getPack(uri.lastPathSegment.orEmpty()))
        else -> throw IllegalArgumentException("Uri no soportada para consulta: $uri")
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (matcher.match(uri) != STICKERS_ASSET_CODE) {
            throw FileNotFoundException("Uri no soportada para asset: $uri")
        }
        val segments = uri.pathSegments
        val identifier = segments[1]
        val fileName = segments[2]
        val pack = repository.getPack(identifier) ?: throw FileNotFoundException("Pack desconocido: $identifier")
        val isKnownFile = fileName == pack.trayImageFileName || pack.stickers.any { it.imageFileName == fileName }
        if (!isKnownFile) throw FileNotFoundException("Archivo no autorizado: $identifier/$fileName")

        if (repository.isUserAddedFile(identifier, fileName)) {
            val file = repository.userStickerFile(identifier, fileName)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(pfd, 0, file.length())
        }
        return try {
            contextOrThrow().assets.openFd("$identifier/$fileName")
        } catch (error: IOException) {
            throw FileNotFoundException("No se pudo abrir $identifier/$fileName: ${error.message}")
        }
    }

    // El contrato WAStickerApps es de solo lectura desde WhatsApp: nunca llama
    // a insert/update/delete de este ContentProvider. Los stickers nuevos se
    // agregan dentro de la app (StickerPackRepository.addStickerToSeedPack),
    // no a través de esta interfaz.
    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("StickerContentProvider no acepta escrituras externas")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("StickerContentProvider no acepta escrituras externas")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("StickerContentProvider no acepta escrituras externas")

    private fun contextOrThrow() =
        context ?: throw FileNotFoundException("Provider sin contexto")

    private fun packsCursor(packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(WaStickerContract.PackColumns.ALL)
        for (pack in packs) {
            cursor.addRow(
                arrayOf<Any>(
                    pack.identifier,
                    pack.name,
                    pack.publisher,
                    pack.trayImageFileName,
                    "",
                    "",
                    pack.publisherEmail,
                    pack.publisherWebsite,
                    pack.privacyPolicyWebsite,
                    pack.licenseAgreementWebsite,
                    // RF-22: cambia cada vez que se agrega un sticker, para que
                    // WhatsApp note que el contenido de un pack ya añadido
                    // cambió y vuelva a pedirlo en vez de servir su copia
                    // cacheada.
                    pack.stickers.size.toString(),
                    0,
                    if (pack.isAnimatedPack) 1 else 0,
                ),
            )
        }
        return cursor
    }

    private fun stickersCursor(pack: StickerPack?): Cursor {
        val cursor = MatrixCursor(WaStickerContract.StickerColumns.ALL)
        pack?.stickers?.forEach { sticker ->
            cursor.addRow(arrayOf(sticker.imageFileName, sticker.emojis.joinToString(","), sticker.accessibilityText))
        }
        return cursor
    }

    private fun buildMatcher(authority: String) = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(authority, WaStickerContract.Path.METADATA, METADATA_CODE)
        addURI(authority, "${WaStickerContract.Path.METADATA}/*", METADATA_ITEM_CODE)
        addURI(authority, "${WaStickerContract.Path.STICKERS}/*", STICKERS_CODE)
        addURI(authority, "${WaStickerContract.Path.STICKERS_ASSET}/*/*", STICKERS_ASSET_CODE)
    }

    private companion object {
        const val METADATA_CODE = 1
        const val METADATA_ITEM_CODE = 2
        const val STICKERS_CODE = 3
        const val STICKERS_ASSET_CODE = 4
    }
}
