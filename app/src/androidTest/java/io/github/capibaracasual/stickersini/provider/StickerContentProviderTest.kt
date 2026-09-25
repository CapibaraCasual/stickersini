package io.github.capibaracasual.stickersini.provider

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica que [StickerContentProvider] responde el contrato WAStickerApps
 * tal como lo consultarÃ­a WhatsApp: primero la lista de packs, luego los
 * stickers de un pack, y por Ãºltimo que el asset de cada sticker se puede
 * abrir de verdad.
 */
@RunWith(AndroidJUnit4::class)
class StickerContentProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = WaStickerContract.authority(context)

    /**
     * Dos packs semilla desde ADR-0004/ADR-0010 (uno estático, uno animado):
     * RF-18 prohíbe mezclar ambos tipos en un mismo pack, así que hace
     * falta uno de cada uno para que el primer sticker de cualquier tipo
     * que agregue el usuario tenga un pack ya válido a mano (ver
     * [io.github.capibaracasual.stickersini.stickers.domain.SeedPacks]).
     */
    @Test
    fun metadata_devuelveLosDosPacksSemilla() {
        val metadataUri = Uri.parse("content://$authority/${WaStickerContract.Path.METADATA}")
        context.contentResolver.query(metadataUri, null, null, null, null).use { cursor ->
            assertNotNull("La consulta a /metadata no debe devolver null", cursor)
            checkNotNull(cursor)
            assertEquals(2, cursor.count)

            val identifierIndex = cursor.getColumnIndexOrThrow(WaStickerContract.PackColumns.IDENTIFIER)
            val animatedIndex = cursor.getColumnIndexOrThrow(WaStickerContract.PackColumns.ANIMATED_PACK)

            assertTrue(cursor.moveToFirst())
            assertEquals("sticker_pack_semilla", cursor.getString(identifierIndex))
            assertEquals(0, cursor.getInt(animatedIndex))

            assertTrue(cursor.moveToNext())
            assertEquals("sticker_pack_semilla_animado", cursor.getString(identifierIndex))
            assertEquals(1, cursor.getInt(animatedIndex))
        }

        val stickersUri = Uri.parse("content://$authority/${WaStickerContract.Path.STICKERS}/sticker_pack_semilla")
        context.contentResolver.query(stickersUri, null, null, null, null).use { cursor ->
            assertNotNull("La consulta a /stickers/<id> no debe devolver null", cursor)
            checkNotNull(cursor)
            assertEquals(3, cursor.count)

            val fileNameIndex = cursor.getColumnIndexOrThrow(WaStickerContract.StickerColumns.FILE_NAME)
            val fileNames = generateSequence { if (cursor.moveToNext()) cursor.getString(fileNameIndex) else null }.toList()
            assertEquals(listOf("sticker_1.webp", "sticker_2.webp", "sticker_3.webp"), fileNames)
        }
    }

    @Test
    fun stickersAsset_abreLosBytesDeCadaStickerYDelIconoDeBandeja() {
        val fileNames = listOf("sticker_1.webp", "sticker_2.webp", "sticker_3.webp", "tray.png")
        for (identifier in listOf("sticker_pack_semilla", "sticker_pack_semilla_animado")) {
            for (fileName in fileNames) {
                val assetUri = Uri.parse("content://$authority/${WaStickerContract.Path.STICKERS_ASSET}/$identifier/$fileName")
                context.contentResolver.openAssetFileDescriptor(assetUri, "r").use { descriptor ->
                    assertNotNull("No se pudo abrir el asset $identifier/$fileName", descriptor)
                    checkNotNull(descriptor)
                    assertTrue("$identifier/$fileName debería pesar más de 0 bytes", descriptor.length > 0)
                }
            }
        }
    }
}
